package io.github.theonionsarewatching.nova.data

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import androidx.room.withTransaction
import android.telephony.SmsManager
import io.github.theonionsarewatching.nova.sms.ScheduledSendReceiver
import io.github.theonionsarewatching.nova.sms.Sender
import io.github.theonionsarewatching.nova.util.ContactsHelper
import io.github.theonionsarewatching.nova.util.ElementExtractor
import io.github.theonionsarewatching.nova.util.PhoneUtils
import io.github.theonionsarewatching.nova.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Simple in-process change bus so open screens refresh after data mutations. */
object ChangeBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun register(l: () -> Unit) { listeners.add(l) }
    fun unregister(l: () -> Unit) { listeners.remove(l) }
    fun ping() { main.post { listeners.forEach { it() } } }
}

class Repo private constructor(private val context: Context) {

    val db = AppDb.get(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val convoMutex = Mutex()

    // transaction ids we've already fired a sync-path fallback download for, so
    // repeated observer passes don't kick off the same download over and over
    // m_ids whose "no SENT row" miss has been logged once already
    private val unmatchedIndicationLogged =
        java.util.Collections.synchronizedSet(HashSet<String>())

    private val downloadFallbackAttempts =
        java.util.Collections.synchronizedSet(HashSet<String>())

    // ============================== Conversations ==============================

    suspend fun getOrCreateConversation(addresses: List<String>): ConversationEntity = convoMutex.withLock {
        val clean = addresses.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { PhoneUtils.normalize(it) }
        val key = PhoneUtils.convoKey(clean)
        db.conversations().byKey(key)?.let { return it }
        val isGroup = clean.size > 1
        val defaultMode =
            if (Prefs.get(context).defaultGroupMode == "group_mms") GroupMode.GROUP_MMS else GroupMode.BROADCAST
        val names = clean.map { ContactsHelper.lookupName(context, it) ?: "" }
        val photo = if (!isGroup) ContactsHelper.lookupPhoto(context, clean.first()) ?: "" else ""
        val c = ConversationEntity(
            convoKey = key,
            addresses = clean.joinToString("|"),
            cachedNames = names.joinToString("|"),
            cachedPhotoUri = photo,
            isGroup = isGroup,
            groupMode = defaultMode
        )
        val id = db.conversations().insert(c)
        return if (id > 0) c.copy(id = id) else db.conversations().byKey(key)!!
    }

    suspend fun refreshAllConversationSummaries() {
        db.conversations().all().forEach { refreshConversation(it.id) }
    }

    suspend fun refreshConversation(convoId: Long) {
        val convo = db.conversations().byId(convoId) ?: return
        val newest = db.messages().newest(convoId)
        val unread = db.messages().unreadCount(convoId)
        val snippet = newest?.let { snippetFor(it) } ?: ""
        db.conversations().updateSummary(
            convoId, snippet, newest?.date ?: convo.snippetDate, newest?.isMine ?: false, unread
        )
        maybeTrimConversation(convoId)
    }

    /** If auto-delete is on and this conversation is over the keep limit, drop
     *  its oldest unlocked messages. Cheap: only runs when the setting is on. */
    private suspend fun maybeTrimConversation(convoId: Long) {
        val prefs = Prefs.get(context)
        if (!prefs.autoDeleteOld) return
        val keep = prefs.autoDeleteKeep
        val victims = db.messages().messagesBeyondKeep(convoId, keep)
        if (victims.isEmpty()) return
        for (m in victims) {
            db.parts().byMessage(m.id).forEach { runCatching { File(it.filePath).delete() } }
            db.parts().deleteByMessage(m.id)
            db.elements().deleteByMessage(m.id)
            deleteFromTelephony(m)
            db.messages().hardDelete(m.id)
        }
    }

    private suspend fun snippetFor(m: MessageEntity): String {
        if (MmsStub.isStub(m.body)) return context.getString(
            io.github.theonionsarewatching.nova.R.string.snippet_mms_pending)
        if (m.body.isNotBlank()) return m.body.take(120)
        if (m.isMms) {
            val parts = db.parts().byMessage(m.id)
            val p = parts.firstOrNull()
            return when {
                p == null -> "MMS"
                p.isImage() -> "Picture"
                p.isVideo() -> "Video"
                p.isAudio() -> "Audio"
                p.isVCard() -> "Contact"
                else -> "Attachment"
            }
        }
        return ""
    }

    // ============================== Incoming ==============================

    /** Store an incoming SMS. Also writes it to the system provider (default-app duty).
     *  Returns the stored message + its conversation, or null when the sender is number-blocked. */
    suspend fun receiveSms(address: String, body: String, date: Long, subId: Int): Pair<MessageEntity, ConversationEntity>? = withContext(Dispatchers.IO) {
        if (isNumberBlocked(address)) return@withContext null

        // 1) system provider (OS compatibility)
        val telephonyId = try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
        } catch (_: Exception) { null }

        // 2) our DB
        val convo = getOrCreateConversation(listOf(address))
        val blocked = matchesKeyword(body, address)
        val msg = MessageEntity(
            convoId = convo.id, address = address, body = body, date = date,
            isMine = false, status = MsgStatus.RECEIVED, read = false,
            subId = subId, blockedByKeyword = blocked,
            telephonyId = telephonyId, telephonyIsMms = false
        )
        val id = db.messages().insert(msg)
        extractElements(id, body)
        refreshConversation(convo.id)
        ChangeBus.ping()
        return@withContext Pair(msg.copy(id = id), convo)
    }

    /** Ingest the newest MMS that the engine persisted into the telephony provider. */
    @SuppressLint("Range")
    suspend fun ingestLatestMmsFromTelephony(): Pair<MessageEntity, ConversationEntity>? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var mmsId = -1L
        var date = 0L
        var msgBox = Telephony.Mms.MESSAGE_BOX_INBOX
        resolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
            null, null, "${Telephony.Mms.DATE} DESC LIMIT 1"
        )?.use { c ->
            if (c.moveToFirst()) {
                mmsId = c.getLong(0)
                date = c.getLong(1) * 1000L // MMS dates are in seconds
                msgBox = c.getInt(2)
            }
        }
        if (mmsId < 0) return@withContext null
        // a row already linked to a real message is a duplicate — skip it.
        // EXCEPTION: a download stub is linked to this same row (tap-to-download
        // updates 130 -> 132 in place); it must pass so ingestMms can replace it.
        val linked = db.messages().byTelephonyMms(mmsId)
        if (linked != null && !MmsStub.isStub(linked.body)) return@withContext null
        return@withContext ingestMms(mmsId, date, msgBox)
    }

    /** Record a number as belonging to THIS phone (learned from sent messages). */
    private fun learnOwnNumbers(addresses: Collection<String>) {
        if (addresses.isEmpty()) return
        val prefs = Prefs.get(context)
        val current = prefs.learnedOwnNumbers
        val add = addresses.map { PhoneUtils.normalize(it) }
            .filter { it.isNotBlank() && it !in current }
        if (add.isNotEmpty()) prefs.learnedOwnNumbers = current + add
    }

    private val ownNumbers: Set<String>
        get() = simOwnNumbers + Prefs.get(context).learnedOwnNumbers

    /** Restore needs the combined own-number set to write correct system rows. */
    suspend fun ownNumbersForRestore(): Set<String> =
        withContext(Dispatchers.IO) { ownNumbers }

    /** This phone's own numbers per the SIM — best effort, empty on many SIMs. */
    private val simOwnNumbers: Set<String> by lazy {
        val out = HashSet<String>()
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            @Suppress("DEPRECATION")
            tm.line1Number?.takeIf { it.isNotBlank() }?.let { out.add(PhoneUtils.normalize(it)) }
        } catch (_: Exception) {}
        try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as android.telephony.SubscriptionManager
            @Suppress("DEPRECATION")
            sm.activeSubscriptionInfoList?.forEach { info ->
                @Suppress("DEPRECATION")
                info.number?.takeIf { it.isNotBlank() }?.let { out.add(PhoneUtils.normalize(it)) }
            }
        } catch (_: Exception) {}
        out
    }

    /** Re-read an MMS's text content (parts + subject) — used to heal blank rows. */
    private fun mmsBodyFor(mmsId: Long): String {
        var body = ""
        try {
            context.contentResolver.query(
                Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text"),
                "mid = ?", arrayOf(mmsId.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    if ((c.getString(1) ?: "") != "text/plain") continue
                    val pid = c.getLong(0)
                    val t = c.getString(2) ?: try {
                        context.contentResolver.openInputStream(Uri.parse("content://mms/part/$pid"))
                            ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    } catch (_: Exception) { null }
                    if (!t.isNullOrBlank()) body = if (body.isBlank()) t else body + "\n" + t
                }
            }
        } catch (_: Exception) {}
        val subject = mmsSubject(mmsId)
        if (subject.isNotBlank() && !body.contains(subject)) {
            body = if (body.isBlank()) subject else subject + "\n" + body
        }
        return body
    }

    /** MMS subject, charset-decoded. Many senders put the actual message text here. */
    private fun mmsSubject(mmsId: Long): String {
        return try {
            context.contentResolver.query(
                Uri.parse("content://mms"), arrayOf("sub", "sub_cs"),
                "_id = ?", arrayOf(mmsId.toString()), null
            )?.use { c ->
                if (!c.moveToFirst()) return ""
                val raw = c.getString(0) ?: return ""
                if (raw.isBlank()) return ""
                val cs = c.getInt(1)
                var decoded = try {
                    com.google.android.mms.pdu_alt.EncodedStringValue(
                        cs, raw.toByteArray(Charsets.ISO_8859_1)
                    ).string
                } catch (_: Throwable) {
                    raw
                }
                // some phones store the subject as plain readable text: if decoding
                // produced replacement garbage, the raw value was already correct
                if (decoded.contains('\uFFFD')) decoded = raw
                val cleaned = decoded.trim()
                if (cleaned.lowercase() in setOf("no subject", "(no subject)", "nosubject")) ""
                else cleaned
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    @SuppressLint("Range")
    suspend fun ingestMms(mmsId: Long, dateMs: Long, msgBox: Int): Pair<MessageEntity, ConversationEntity>? = withContext(Dispatchers.IO) {
        // broadcast copies are machinery of the broadcast illusion — the one
        // real message already lives in the broadcast conversation. EVERY
        // ingest route (sync loops, sent-confirmation, push) funnels through
        // here, so this is the single authoritative guard against the copy
        // reappearing as a "new" message in the one-to-one thread.
        if (io.github.theonionsarewatching.nova.util.BroadcastCopies.isCopy(context, mmsId)) return@withContext null
        val resolver = context.contentResolver
        val isMine = msgBox != Telephony.Mms.MESSAGE_BOX_INBOX

        // phantom-MMS-via-email diagnostics: every ingest of a row involving
        // an email address gets a line, so a ghost message can be traced to
        // the exact telephony row and box that produced it
        run {
            try {
                context.contentResolver.query(
                    android.net.Uri.parse("content://mms/$mmsId/addr"),
                    arrayOf("address", "type"), null, null, null
                )?.use { c ->
                    val sb = StringBuilder()
                    while (c.moveToNext()) {
                        val a = c.getString(0) ?: continue
                        if (a.contains("@")) sb.append("[t=${c.getInt(1)}]$a ")
                    }
                    if (sb.isNotEmpty()) {
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            context, "mms-email",
                            "ingest tid=$mmsId box=$msgBox addrs: $sb"
                        )
                    }
                    if (msgBox == Telephony.Mms.MESSAGE_BOX_INBOX &&
                        sb.contains("MAILER-DAEMON", ignoreCase = true)
                    ) {
                        // the carrier bounced an email-MMS (spam rejection):
                        // mark the most recent pending own message to an email
                        // address FAILED, so the outcome is visible where the
                        // user sent it — the bounce itself stays as evidence
                        val cutoff = System.currentTimeMillis() - 2L * 3600 * 1000
                        db.messages().ownEmailSince(cutoff)
                            .firstOrNull {
                                it.status != MsgStatus.FAILED &&
                                    it.status != MsgStatus.CANCELED
                            }?.let { orig ->
                                db.messages().setStatus(orig.id, MsgStatus.FAILED)
                                refreshConversation(orig.convoId)
                                io.github.theonionsarewatching.nova.util.DiagLog.log(
                                    context, "mms-email",
                                    "carrier bounce: marked msg=${orig.id} " +
                                        "(${orig.address}) FAILED"
                                )
                            }
                    }
                }
            } catch (_: Exception) {}
        }

        // the content observer and the MMS receiver can both try to ingest the same
        // message; the loser of that race used to create a blank duplicate. Ingest
        // each telephony row exactly once — and if the first pass caught it before
        // its text parts were written (blank body), heal it now instead.
        db.messages().byTelephonyMms(mmsId)?.let { existing ->
            // tap-to-download turns the notification row (130) into the
            // downloaded message (132) IN PLACE, so this existing row is our
            // download stub. Drop the stub and fall through to ingest the real
            // content — otherwise the "Tap to download" line never went away.
            if (MmsStub.isStub(existing.body)) {
                val convoId = existing.convoId
                db.messages().hardDelete(existing.id)
                refreshConversation(convoId)
                return@let  // continue to full ingest below
            }
            if (existing.isMine) {
                // the engine moves the box (outbox -> sent/failed) after we linked:
                // reflect reality on our row
                val boxNow = try {
                    resolver.query(Uri.parse("content://mms"), arrayOf("msg_box"),
                        "_id = ?", arrayOf(mmsId.toString()), null)?.use { c ->
                        if (c.moveToFirst()) c.getInt(0) else 0
                    } ?: 0
                } catch (_: Exception) { 0 }
                applyBoxHeal(existing, boxNow)
            }
            if (existing.body.isBlank()) {
                val healed = mmsBodyFor(mmsId)
                if (healed.isNotBlank()) {
                    db.messages().updateBody(existing.id, healed)
                    if (!existing.elementsExtracted || healed.isNotBlank()) extractElements(existing.id, healed)
                    refreshConversation(existing.convoId)
                    ChangeBus.ping()
                }
            }
            return@withContext null
        }

        // not downloaded yet (notification-ind): show a placeholder, not a blank row
        var mType = 0
        var trId: String? = null
        try {
            resolver.query(Uri.parse("content://mms"), arrayOf("m_type", "tr_id"),
                "_id = ?", arrayOf(mmsId.toString()), null)?.use { c ->
                if (c.moveToFirst()) { mType = c.getInt(0); trId = c.getString(1) }
            }
        } catch (_: Exception) {}
        // when auto-download is ON, the notification-ind is transient bookkeeping —
        // the downloaded copy (132) arrives moments later. Ingesting it created a
        // phantom "not downloaded" message next to every MMS.
        if (mType == 130) {
            val auto = Prefs.get(context).autoDownloadMms
            io.github.theonionsarewatching.nova.util.DiagLog.log(
                context, "mms-ingest",
                "notification-ind (m_type=130) mmsId=$mmsId tr_id=$trId autoDownload=$auto"
            )
            if (auto) {
                // Auto-download on: normally MmsPushReceiver downloads this and
                // we never see the bare 130 here. But on some carriers/ROMs
                // (Visible and other Verizon MVNOs seen in the field) our WAP
                // push receiver doesn't win the broadcast, so the only thing
                // that noticed this message is the content-observer sync — and
                // if we just drop it, the MMS is lost forever. Safety net:
                // kick off the download ourselves for any 130 that has a
                // content location and no downloaded (132) twin yet.
                var already132 = false
                var ctLoc: String? = null
                var subId130 = -1
                try {
                    if (!trId.isNullOrBlank()) {
                        resolver.query(Uri.parse("content://mms"), arrayOf("_id"),
                            "m_type = 132 AND tr_id = ?", arrayOf(trId), null)
                            ?.use { c -> already132 = c.count > 0 }
                    }
                    resolver.query(Uri.parse("content://mms"),
                        arrayOf("ct_l", "sub_id"), "_id = ?",
                        arrayOf(mmsId.toString()), null)?.use { c ->
                        if (c.moveToFirst()) {
                            ctLoc = c.getString(0)
                            subId130 = try { c.getInt(1) } catch (_: Exception) { -1 }
                        }
                    }
                } catch (_: Exception) {}
                if (!already132 && !ctLoc.isNullOrBlank() &&
                    !io.github.theonionsarewatching.nova.sms.MmsPushReceiver
                        .isDownloadInFlight(trId) &&
                    downloadFallbackAttempts.add(trId ?: mmsId.toString())
                ) {
                    io.github.theonionsarewatching.nova.util.DiagLog.log(
                        context, "mms-ingest",
                        "push receiver missed it — downloading from sync (mmsId=$mmsId)"
                    )
                    try {
                        io.github.theonionsarewatching.nova.sms.MmsPushReceiver.fetch(
                            context, ctLoc!!, trId ?: "", null, subId130
                        )
                    } catch (e: Exception) {
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            context, "mms-ingest", "sync download kickoff failed: $e"
                        )
                    }
                }
                return@withContext null
            }
        }
        val notDownloaded = mType == 130
        // the mms table also holds protocol rows with no content — delivery reports,
        // delivery-ind (134): the carrier's "your picture message was delivered"
        // notice. Match it to the sent message via the MMS Message-ID and mark
        // that message delivered, then skip ingesting the notice itself.
        if (mType == 134 || mType == 136) {
            handleMmsIndication(mmsId, mType)
            return@withContext null
        }

        // read reports, acknowledgements (m_type 129/131/133/135/136...). These
        // imported as BLANK messages next to the real MMS. Only actual messages pass:
        // 128 = outgoing send-request, 132 = downloaded incoming, 130 = placeholder.
        if (mType != 0 && mType != 128 && mType != 130 && mType != 132) return@withContext null

        // a downloaded copy replaces its own notification placeholder (matched by
        // transaction id) — covers users who toggled auto-download mid-stream
        if (mType == 132 && !trId.isNullOrBlank()) {
            try {
                var alreadyHave = false
                var existingMsgId = 0L
                resolver.query(Uri.parse("content://mms"), arrayOf("_id"),
                    "m_type = 132 AND tr_id = ? AND _id != ?",
                    arrayOf(trId, mmsId.toString()), null)?.use { c ->
                    while (c.moveToNext()) {
                        db.messages().byTelephonyMms(c.getLong(0))?.let { existing ->
                            alreadyHave = true
                            existingMsgId = existing.id
                        }
                    }
                }
                if (alreadyHave) {
                    // The carrier's push retry can deliver the SAME message a
                    // second time TRANSCODED DIFFERENTLY — field logs showed one
                    // copy as audio/vnd.qcelp (undecodable on most phones) and
                    // the retry as audio/amr seconds later. Blindly keeping the
                    // first copy threw the playable one away. Keep whichever
                    // copy has usable audio.
                    val newIsBetter = copyHasPlayableAudio(mmsId) &&
                        !messageHasPlayableAudio(existingMsgId)
                    if (newIsBetter) {
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            context, "mms-ingest",
                            "duplicate tr_id=$trId tid=$mmsId has PLAYABLE audio while the " +
                                "stored copy is undecodable — replacing the stored copy"
                        )
                        try { deleteMessage(existingMsgId) } catch (_: Exception) {}
                        // fall through and ingest this better copy
                    } else {
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            context, "mms-ingest",
                            "duplicate incoming copy tr_id=$trId tid=$mmsId — deleted (carrier push retry)"
                        )
                        try { resolver.delete(Uri.parse("content://mms/$mmsId"), null, null) } catch (_: Exception) {}
                        return@withContext null
                    }
                }
            } catch (_: Exception) {}
            try {
                resolver.query(Uri.parse("content://mms"), arrayOf("_id"),
                    "m_type = 130 AND tr_id = ?", arrayOf(trId), null)?.use { c ->
                    while (c.moveToNext()) {
                        val nid = c.getLong(0)
                        db.messages().byTelephonyMms(nid)?.let { ph ->
                            db.messages().hardDelete(ph.id)
                            refreshConversation(ph.convoId)
                        }
                    }
                }
            } catch (_: Exception) {}
            // tap-to-download replaces the row IN PLACE (130 -> 132, same _id),
            // so the query above finds no leftover 130 row and the stub
            // survived. Match the stub by the transaction id encoded in its
            // body and delete it directly.
            try {
                val pat = "%" + trId + "%"
                for (cand in db.messages().messagesWithBodyLike(pat)) {
                    val info = MmsStub.decode(cand.body) ?: continue
                    if (info.transactionId == trId) {
                        db.messages().hardDelete(cand.id)
                        refreshConversation(cand.convoId)
                    }
                }
            } catch (_: Exception) {}
            // AND purge any leftover m_type=130 notification row for this
            // transaction from the telephony provider itself. If the platform
            // left it behind (download landed in a NEW 132 row rather than
            // updating 130 in place), the content observer's syncRecentFrom
            // Telephony re-ingested that orphan 130 row 2.5s later and the
            // "Tap to download" stub reappeared. Remove it at the source.
            try {
                resolver.query(Uri.parse("content://mms"), arrayOf("_id"),
                    "m_type = 130 AND tr_id = ?", arrayOf(trId), null)?.use { c ->
                    while (c.moveToNext()) {
                        val nid = c.getLong(0)
                        if (nid != mmsId) {
                            resolver.delete(Uri.parse("content://mms/$nid"), null, null)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // addresses
        val from = ArrayList<String>()
        val to = ArrayList<String>()
        try {
            resolver.query(
                Uri.parse("content://mms/$mmsId/addr"),
                arrayOf("address", "type"), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val addr = c.getString(0) ?: continue
                    if (addr.isBlank() || addr == "insert-address-token") continue
                    when (c.getInt(1)) {
                        137 -> from.add(addr)
                        151 -> to.add(addr)
                    }
                }
            }
        } catch (_: Exception) {}

        // Participant rules (deterministic AND correct for 1:1):
        //   * received where the "to" side is just one number -> that one number is
        //     THIS PHONE, so the conversation is 1:1 with the sender. Keeping our own
        //     number here created a phantom 2-person "group" per contact, splitting
        //     picture messages away from the SMS thread.
        //   * received with 2+ other recipients -> a real group: sender + the others
        //     (minus our own number when the SIM reports it; when it doesn't, keeping
        //     it is still deterministic, so the group stays ONE conversation).
        if (isMine && from.isNotEmpty()) learnOwnNumbers(from)

        val participants = if (isMine) {
            to.filter { PhoneUtils.normalize(it) !in ownNumbers }.ifEmpty { to }.ifEmpty { from }
        } else {
            val fromKeys = from.map { PhoneUtils.normalize(it) }.toSet()
            val others = to.filter { PhoneUtils.normalize(it) !in fromKeys }
                .distinctBy { PhoneUtils.normalize(it) }
            val othersMinusOwn = others.filter { PhoneUtils.normalize(it) !in ownNumbers }
            when {
                others.size <= 1 -> from                       // 1:1 — the lone "to" is us
                othersMinusOwn.size == 1 -> from + othersMinusOwn // group of 2 + us, own known
                else -> from + othersMinusOwn.ifEmpty { others }
            }
        }.ifEmpty { listOf("Unknown") }

        if (!isMine && from.firstOrNull()?.let { isNumberBlocked(it) } == true) return@withContext null

        // parts
        var bodyText = ""
        data class RawPart(val id: Long, val ct: String, val name: String, val text: String?)
        val binaryParts = ArrayList<RawPart>()
        try {
            resolver.query(
                Uri.parse("content://mms/part"),
                arrayOf("_id", "ct", "text", "name", "cl"),
                "mid = ?", arrayOf(mmsId.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val pid = c.getLong(0)
                    val ct = c.getString(1) ?: "application/octet-stream"
                    val text = c.getString(2)
                    val name = c.getString(3) ?: c.getString(4) ?: "part_$pid"
                    when {
                        ct == "text/plain" -> {
                            // large text parts store their content in the part file,
                            // leaving the text column null — read the file in that case
                            val t = text ?: try {
                                resolver.openInputStream(Uri.parse("content://mms/part/$pid"))
                                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                            } catch (_: Exception) { null }
                            if (!t.isNullOrBlank()) bodyText =
                                if (bodyText.isBlank()) t else bodyText + "\n" + t
                        }
                        ct == "application/smil" -> { /* layout description, skip */ }
                        else -> binaryParts.add(RawPart(pid, ct, name, null))
                    }
                }
            }
        } catch (_: Exception) {}

        // messages sent as "subject only" (or subject + attachment) showed up blank:
        // fold the subject into the body text
        val subject = mmsSubject(mmsId)
        if (subject.isNotBlank() && !bodyText.contains(subject)) {
            bodyText = if (bodyText.isBlank()) subject else subject + "\n" + bodyText
        }
        if (notDownloaded && bodyText.isBlank()) {
            // second line of defence: the notification-ind skip above should
            // already have returned, so reaching here with auto-download ON
            // means another path ingested the stub. Log it and refuse to
            // create the phantom message.
            if (Prefs.get(context).autoDownloadMms) {
                io.github.theonionsarewatching.nova.util.DiagLog.log(
                    context, "mms-ingest",
                    "STUB SUPPRESSED: m_type=130 reached ingest with auto-download ON " +
                        "(mmsId=$mmsId tr_id=$trId) — not creating placeholder"
                )
                return@withContext null
            }
            // Build a tappable download stub. The location + tr_id + subId are
            // stashed in the body behind a marker the adapter recognizes and
            // renders as a "Download" button; on tap we fetch, and the arriving
            // m_type=132 copy replaces this row (matched by tr_id, above).
            var loc: String? = null
            try {
                context.contentResolver.query(
                    Uri.parse("content://mms"), arrayOf("ct_l"),
                    "_id = ?", arrayOf(mmsId.toString()), null
                )?.use { c -> if (c.moveToFirst()) loc = c.getString(0) }
            } catch (_: Exception) {}
            var stubSub = -1
            try {
                context.contentResolver.query(
                    Uri.parse("content://mms"), arrayOf("sub_id"),
                    "_id = ?", arrayOf(mmsId.toString()), null
                )?.use { c -> if (c.moveToFirst()) stubSub = c.getInt(0) }
            } catch (_: Exception) {}
            bodyText = MmsStub.encode(loc ?: "", trId ?: "", stubSub)
        }

        val convo = getOrCreateConversation(participants)
        val blocked = !isMine && matchesKeyword(bodyText, from.firstOrNull() ?: "")
        val senderAddress = if (isMine) participants.joinToString("|") else (from.firstOrNull() ?: "Unknown")
        // honest status from the actual message box — outgoing rows were all
        // labeled "sent" before, even ones sitting in outbox or marked failed
        if (msgBox == Telephony.Mms.MESSAGE_BOX_DRAFTS) return@withContext null
        val status = when {
            !isMine -> MsgStatus.RECEIVED
            msgBox == 5 /* MESSAGE_BOX_FAILED */ -> MsgStatus.FAILED
            msgBox == Telephony.Mms.MESSAGE_BOX_OUTBOX ->
                // a fresh outbox row is mid-send; an old one is a stuck failure
                if (System.currentTimeMillis() - dateMs < 10 * 60 * 1000) MsgStatus.SENDING
                else MsgStatus.FAILED
            else -> MsgStatus.SENT
        }

        // RECONCILIATION — the root of the outgoing-MMS duplicates: our app row is
        // created at send time with no telephony id, and the engine persists the
        // telephony row moments later. The content observer then saw a telephony
        // row it "didn't know" and ingested a second copy. If an unlinked outgoing
        // app row matches this telephony row (same conversation, close in time,
        // same text), LINK them instead of inserting a doppelgänger.
        if (isMine) {
            io.github.theonionsarewatching.nova.util.DiagLog.log(
                context, "mms-ingest", "outgoing store row tid=$mmsId box=$msgBox reached ingest"
            )
            val candidates = db.messages().unlinkedOutgoing(
                convo.id, true, dateMs - 120_000, dateMs + 120_000
            )
            val match = candidates
                .filter { it.body.trim() == bodyText.trim() || (it.body.isBlank() && bodyText.isBlank()) }
                .minByOrNull { kotlin.math.abs(it.date - dateMs) }
            if (match != null) {
                db.messages().setTelephonyId(match.id, mmsId, true)
                // upgrade status only in the honest directions
                if (status == MsgStatus.SENT && match.status in
                    listOf(MsgStatus.SENDING, MsgStatus.FAILED)
                ) {
                    setStatusRespectingCancel(match.id, MsgStatus.SENT)
                } else if (status == MsgStatus.FAILED && match.status == MsgStatus.SENDING) {
                    setStatusRespectingCancel(match.id, MsgStatus.FAILED)
                }
                refreshConversation(match.convoId)
                ChangeBus.ping()
                return@withContext null
            }
        }
        // received MMS start unread; our own sent MMS are read
        val fixed = MessageEntity(
            convoId = convo.id, address = senderAddress, body = bodyText, date = dateMs,
            isMine = isMine, status = status, read = isMine,
            isMms = true, blockedByKeyword = blocked,
            telephonyId = mmsId, telephonyIsMms = true
        )
        val id = db.messages().insert(fixed)

        // copy binary parts into app storage for instant loading
        val dir = File(context.filesDir, "parts").apply { mkdirs() }
        for (p in binaryParts) {
            try {
                val ext = extensionFor(p.ct, p.name)
                val outFile = File(dir, "m${id}_p${p.id}$ext")
                resolver.openInputStream(Uri.parse("content://mms/part/${p.id}"))?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (outFile.exists() && outFile.length() > 0) {
                    // trust the bytes over the carrier's label for audio parts
                    var finalFile = outFile
                    var finalMime = p.ct
                    val nameLc = (p.name ?: "").lowercase()
                    val looksAudio = p.ct.startsWith("audio", ignoreCase = true) ||
                        p.ct.contains("octet", ignoreCase = true) ||
                        p.ct.contains("qcelp", ignoreCase = true) ||
                        p.ct.contains("qcp", ignoreCase = true) ||
                        nameLc.endsWith(".qcp") || nameLc.endsWith(".amr") ||
                        nameLc.endsWith(".3gp") || nameLc.endsWith(".dat")
                    if (looksAudio) {
                        val sniffed = io.github.theonionsarewatching.nova.util.AudioSniff.sniff(outFile)
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            context, "mms-part",
                            "audio candidate: declared=${p.ct} name=${p.name} " +
                                "magic=${io.github.theonionsarewatching.nova.util.AudioSniff.magicHex(outFile)} " +
                                "sniff=${sniffed?.second ?: "none"}"
                        )
                        if (sniffed != null) {
                            val (sExt, sMime) = sniffed
                            io.github.theonionsarewatching.nova.util.DiagLog.log(
                                context, "mms-part",
                                "audio sniff: declared=${p.ct} actual=$sMime"
                            )
                            if (!outFile.name.endsWith(sExt, ignoreCase = true)) {
                                val renamed = File(dir, outFile.nameWithoutExtension + sExt)
                                if (outFile.renameTo(renamed)) finalFile = renamed
                            }
                            finalMime = sMime
                        }
                    }
                    val dims = imageDims(finalMime, finalFile.absolutePath)
                    db.parts().insert(
                        PartEntity(
                            messageId = id, mimeType = finalMime,
                            filePath = finalFile.absolutePath, fileName = p.name,
                            size = finalFile.length(),
                            width = dims.first, height = dims.second
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        extractElements(id, bodyText)
        refreshConversation(convo.id)
        ChangeBus.ping()
        return@withContext Pair(fixed.copy(id = id), convo)
    }

    private fun extensionFor(mime: String, name: String): String {
        // sender-supplied filename extension first, then the mime map — which
        // now covers mp3/ogg/wav/etc instead of dumping them to ".bin"
        val fromName = name.substringAfterLast('.', "")
        if (fromName.isNotBlank() && fromName.length <= 4) return ".$fromName"
        return io.github.theonionsarewatching.nova.util.MimeExt.forMime(mime)
    }

    /** Audio the phone can actually decode: anything except the carrier's
     *  legacy QCELP conversion. */
    private fun playableAudioMime(mime: String?): Boolean {
        val m = (mime ?: "").lowercase()
        if (!m.startsWith("audio")) return false
        return !m.contains("qcelp") && !m.contains("qcp") && !m.contains("evrc")
    }

    /** Does this not-yet-ingested system MMS row carry playable audio? */
    private fun copyHasPlayableAudio(mmsId: Long): Boolean {
        var sawAudio = false
        var sawPlayable = false
        try {
            context.contentResolver.query(
                Uri.parse("content://mms/part"), arrayOf("ct"),
                "mid = ?", arrayOf(mmsId.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val ct = c.getString(0)
                    if ((ct ?: "").startsWith("audio", ignoreCase = true)) {
                        sawAudio = true
                        if (playableAudioMime(ct)) sawPlayable = true
                    }
                }
            }
        } catch (_: Exception) {}
        return sawAudio && sawPlayable
    }

    /** Does the stored message have playable audio? (false when its only
     *  audio part is the undecodable carrier conversion) */
    private suspend fun messageHasPlayableAudio(messageId: Long): Boolean {
        if (messageId <= 0L) return false
        return try {
            val parts = db.parts().byMessage(messageId)
            val audio = parts.filter { (it.mimeType).startsWith("audio", ignoreCase = true) }
            audio.isNotEmpty() && audio.any { playableAudioMime(it.mimeType) }
        } catch (_: Exception) { false }
    }

    /** One-time cleanup: phantom "not downloaded" rows created by older builds
     *  survive in the database even after the code stopped making them. */
    suspend fun purgePhantomPlaceholders(): Int {
        if (!Prefs.get(context).autoDownloadMms) return 0
        val placeholder = context.getString(
            io.github.theonionsarewatching.nova.R.string.mms_not_downloaded)
        var removed = 0
        try {
            for (m in db.messages().placeholderMms(placeholder)) {
                if (db.parts().byMessage(m.id).isEmpty()) {
                    deleteMessage(m.id)
                    removed++
                }
            }
        } catch (_: Exception) {}
        if (removed > 0) {
            io.github.theonionsarewatching.nova.util.DiagLog.log(
                context, "mms-ingest", "purged $removed phantom placeholder message(s)"
            )
        }
        return removed
    }

    // ============================== Keywords / blocking ==============================

    /** Literal substring matching (never regex), so keywords with symbols —
     *  "example-web.com", "$5", "1-800" — match exactly as typed. Each keyword
     *  carries its own sender scope and case sensitivity. */
    private suspend fun matchesKeyword(body: String, senderAddress: String): Boolean {
        if (body.isBlank()) return false
        for (kw in db.keywords().all()) {
            if (kw.keyword.isBlank()) continue
            val textHit = body.contains(kw.keyword, ignoreCase = !kw.caseSensitive)
            if (!textHit) continue
            // numbers list: for mode 3 it's the BLOCK list; for every other
            // mode it's an ALLOW list layered on top (never block these
            // senders). Legacy mode 2 rows behave identically to mode 0.
            val allowListed = kw.mode != 3 && senderListed(senderAddress, kw.numbers)
            val applies = when (kw.mode) {
                1 -> !isKnownContact(senderAddress) && !allowListed
                3 -> senderListed(senderAddress, kw.numbers)
                else -> !allowListed
            }
            if (applies) return true
        }
        return false
    }

    private suspend fun isKnownContact(address: String): Boolean {
        if (address.isBlank()) return false
        return try {
            db.contactNames().byKey(PhoneUtils.normalize(address)) != null
        } catch (_: Exception) { false }
    }

    private fun senderListed(address: String, numbers: String): Boolean {
        if (address.isBlank() || numbers.isBlank()) return false
        val addrNorm = PhoneUtils.normalize(address)
        return numbers.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }
            .any { entry ->
                if (entry.contains("@") || address.contains("@")) {
                    entry.equals(address, ignoreCase = true)
                } else {
                    val eNorm = PhoneUtils.normalize(entry)
                    eNorm == addrNorm ||
                        (eNorm.length >= 7 && addrNorm.endsWith(eNorm.takeLast(7))) ||
                        (addrNorm.length >= 7 && eNorm.endsWith(addrNorm.takeLast(7)))
                }
            }
    }

    fun isNumberBlocked(address: String): Boolean {
        val norm = PhoneUtils.normalize(address)
        if (Prefs.get(context).blockedNumbers.any { PhoneUtils.normalize(it) == norm }) return true
        if (Build.VERSION.SDK_INT < 24) return false
        return try {
            android.provider.BlockedNumberContract.isBlocked(context, address)
        } catch (_: Exception) { false }
    }

    /** Block a number. Writes to the system blocked-number store when it will
     *  accept us, and always records it in our own list so blocking still
     *  works where the provider refuses (stripped ROMs, secondary users).
     *  Returns true if the system store took it. */
    suspend fun blockNumber(address: String): Boolean = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(context)
        prefs.blockedNumbers = prefs.blockedNumbers + address
        var systemOk = false
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                if (android.provider.BlockedNumberContract
                        .canCurrentUserBlockNumbers(context)
                ) {
                    val values = android.content.ContentValues().apply {
                        put(
                            android.provider.BlockedNumberContract.BlockedNumbers
                                .COLUMN_ORIGINAL_NUMBER, address
                        )
                    }
                    context.contentResolver.insert(
                        android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                        values
                    )
                    systemOk = true
                }
            } catch (e: Exception) {
                io.github.theonionsarewatching.nova.util.DiagLog.log(
                    context, "block", "system block failed for $address: ${e.message}"
                )
            }
        }
        io.github.theonionsarewatching.nova.util.DiagLog.log(
            context, "block", "blocked $address (system=$systemOk, local=yes)"
        )
        ChangeBus.ping()
        return@withContext systemOk
    }

    suspend fun unblockNumber(address: String) = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(context)
        val norm = PhoneUtils.normalize(address)
        prefs.blockedNumbers = prefs.blockedNumbers
            .filterNot { PhoneUtils.normalize(it) == norm }.toSet()
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                context.contentResolver.delete(
                    android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                    android.provider.BlockedNumberContract.BlockedNumbers
                        .COLUMN_ORIGINAL_NUMBER + " = ?",
                    arrayOf(address)
                )
            } catch (_: Exception) {}
        }
        ChangeBus.ping()
    }

    // ============================== Elements ==============================

    suspend fun extractElements(messageId: Long, body: String) {
        try {
            db.elements().deleteByMessage(messageId)
            val items = ElementExtractor.extract(messageId, body)
            if (items.isNotEmpty()) db.elements().insertAll(items)
            db.messages().markExtracted(messageId)
        } catch (_: Exception) {}
    }

    /** Batch pass for imported / legacy messages. Runs in small chunks until done. */
    fun runElementBacklog() {
        scope.launch {
            try {
                while (db.messages().extractionBacklog() > 0) {
                    val chunk = db.messages().needingExtraction(200)
                    if (chunk.isEmpty()) break
                    for (m in chunk) extractElements(m.id, m.body)
                }
            } catch (_: Exception) {}
        }
    }

    // ============================== Sending ==============================

    /** Send text to a conversation, honoring its group mode. Returns our message id. */
    suspend fun sendText(convoId: Long, text: String): Long? {
        val convo = db.conversations().byId(convoId) ?: return null
        val addresses = convo.addressList()
        if (addresses.isEmpty() || text.isBlank()) return null

        // email addresses can only be reached over MMS; SMS to them fails silently
        val hasEmail = addresses.any { it.contains("@") }
        // very long texts: concatenated SMS beyond a few segments gets dropped by
        // some carrier/handset pairs (sender still sees "sent") — convert to MMS
        // past the carrier's own threshold, like the stock app does
        val segments = try {
            @Suppress("DEPRECATION")
            android.telephony.SmsManager.getDefault().divideMessage(text)?.size ?: 1
        } catch (_: Exception) { 1 }
        val threshold = io.github.theonionsarewatching.nova.util.CarrierMms.smsToMmsThreshold(context)
        val asLongMms = segments > threshold
        if (asLongMms) {
            io.github.theonionsarewatching.nova.util.DiagLog.log(
                context, "sms-send",
                "long text: $segments segments > threshold $threshold — sending as MMS"
            )
        }
        val asGroupMms = hasEmail || asLongMms ||
            (convo.isGroup && convo.groupMode == GroupMode.GROUP_MMS)
        val now = System.currentTimeMillis()
        val initialStatuses =
            if (convo.isGroup && !asGroupMms)
                addresses.joinToString(",") { "${PhoneUtils.normalize(it)}=${MsgStatus.SENDING}" }
            else ""
        val msg = MessageEntity(
            convoId = convoId, address = addresses.joinToString("|"), body = text, date = now,
            isMine = true, status = MsgStatus.SENDING, isMms = asGroupMms,
            recipientStatuses = initialStatuses
        )
        val id = db.messages().insert(msg)
        extractElements(id, text)
        refreshConversation(convoId)
        ChangeBus.ping()

        if (asGroupMms) {
            Sender.sendMms(context, id, text, addresses, emptyList())
        } else {
            Sender.sendSmsToAll(context, id, text, addresses)
        }
        return id
    }

    /** Send an MMS with one or more attachments (single recipient or group-MMS). */
    suspend fun sendAttachment(
        convoId: Long, text: String, attachments: List<Triple<String, String, String>>
    ): Long? {
        val convo = db.conversations().byId(convoId) ?: return null
        val addresses = convo.addressList()
        if (addresses.isEmpty() || attachments.isEmpty()) return null
        val now = System.currentTimeMillis()
        val msg = MessageEntity(
            convoId = convoId, address = addresses.joinToString("|"), body = text, date = now,
            isMine = true, status = MsgStatus.SENDING, isMms = true
        )
        val id = db.messages().insert(msg)
        for ((path, mime, name) in attachments) {
            val dims = imageDims(mime, path)
            db.parts().insert(
                PartEntity(messageId = id, mimeType = mime, filePath = path,
                    fileName = name, size = File(path).length(),
                    width = dims.first, height = dims.second)
            )
        }
        if (text.isNotBlank()) extractElements(id, text)
        refreshConversation(convoId)
        ChangeBus.ping()
        Sender.sendMms(context, id, text, addresses, attachments)
        return id
    }

    // ---- status updates from receivers ----

    suspend fun onSmsPartSent(messageId: Long, recipient: String, isLastPart: Boolean, ok: Boolean) {
        val m = db.messages().byId(messageId) ?: return
        if (m.recipientStatuses.isNotBlank()) {
            // broadcast group: per-recipient tracking
            val map = parseStatuses(m.recipientStatuses).toMutableMap()
            val key = PhoneUtils.normalize(recipient)
            val cur = map[key] ?: MsgStatus.SENDING
            if (!ok) map[key] = MsgStatus.FAILED
            else if (isLastPart && cur == MsgStatus.SENDING) map[key] = MsgStatus.SENT
            db.messages().setRecipientStatuses(messageId, encodeStatuses(map))
            applyAggregate(messageId, map)
        } else {
            if (!ok) setStatusRespectingCancel(messageId, MsgStatus.FAILED)
            else if (isLastPart) setStatusRespectingCancel(messageId, MsgStatus.SENT)
        }
        refreshAndPing(m.convoId)
    }

    /** Box moved in the system store (outbox -> sent/failed): reflect it on a
     *  linked outgoing row. Upgrade-only rules. */
    private suspend fun applyBoxHeal(existing: MessageEntity, boxNow: Int) {
        val mapped = when (boxNow) {
            Telephony.Mms.MESSAGE_BOX_SENT -> MsgStatus.SENT
            5 -> MsgStatus.FAILED
            else -> null
        }
        if (mapped == MsgStatus.SENT && existing.status in
            listOf(MsgStatus.SENDING, MsgStatus.FAILED)
        ) {
            setStatusRespectingCancel(existing.id, MsgStatus.SENT)
            refreshConversation(existing.convoId); ChangeBus.ping()
        } else if (mapped == MsgStatus.FAILED && existing.status == MsgStatus.SENDING) {
            setStatusRespectingCancel(existing.id, MsgStatus.FAILED)
            refreshConversation(existing.convoId); ChangeBus.ping()
        }
    }

    /** MMS delivery-ind: look up the original sent message by MMS Message-ID
     *  and mark it delivered. */
    /** Answer read-report requests: for incoming MMS in this conversation
     *  whose sender asked for a read report (rr=128 on the telephony row),
     *  send an M-Read-Rec.ind now that the user has read them. The provider
     *  row's rr is set to 129 afterwards so each is answered exactly once. */
    /** One-shot: learn dimensions for parts from before the dims schema, so
     *  nothing is left to settle-and-shift on screen. Runs on IO at startup. */
    fun backfillPartDims() {
        scope.launch {
            try {
                val legacy = db.parts().needingDims()
                var done = 0
                legacy.forEach { part ->
                    val d = imageDims(part.mimeType, part.filePath)
                    if (d.first > 0) {
                        db.parts().setDims(part.id, d.first, d.second)
                        done++
                    }
                }
                if (legacy.isNotEmpty()) {
                    io.github.theonionsarewatching.nova.util.DiagLog.log(
                        context, "thumb", "backfilled dims for $done/${legacy.size} legacy parts"
                    )
                }
            } catch (_: Exception) {}
        }
    }

    /** Intrinsic pixel size of an image file via bounds-only decode (cheap,
     *  no bitmap allocated). (0,0) for non-images or unreadable files. */
    fun imageDims(mime: String, path: String): Pair<Int, Int> {
        if (!mime.startsWith("image/")) return 0 to 0
        return try {
            val o = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(path, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else 0 to 0
        } catch (_: Exception) { 0 to 0 }
    }

    suspend fun sendPendingReadRecs(convoId: Long) = withContext(Dispatchers.IO) {
        // ContentResolver.query is a plain blocking binder call — unlike Room
        // suspend functions it does NOT hop threads by itself. Called from a
        // Main-dispatched coroutine it froze the UI for the whole per-message
        // scan (watchdog-proven: 4.7s / 18.9s / 7.8s). Everything below runs
        // on IO, whoever calls it.
        if (!Prefs.get(context).respondReadReports) {
            io.github.theonionsarewatching.nova.util.DiagLog.log(
                context, "mms-delivery", "read-rec responder OFF by pref"
            )
            return@withContext
        }
        try {
            val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            val msgs = db.messages().threadMessagesForDelete(convoId, 1)
                .filter { !it.isMine && it.telephonyIsMms && it.date >= since }
            for (m in msgs) {
                val tid = m.telephonyId ?: continue
                var mid: String? = null
                var rr = -1
                context.contentResolver.query(
                    Uri.parse("content://mms"), arrayOf("m_id", "rr"),
                    "_id = ?", arrayOf(tid.toString()), null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        mid = c.getString(0)
                        rr = try { c.getInt(1) } catch (_: Exception) { -1 }
                    }
                }
                if (rr != 128 || mid.isNullOrBlank()) {
                    if (rr != -1) {
                        io.github.theonionsarewatching.nova.util.DiagLog.log(
                            context, "mms-delivery",
                            "read-rec scan: tid=$tid rr=$rr mid=${mid ?: "<none>"} — no response due"
                        )
                    }
                    continue
                }
                try {
                    val from = com.google.android.mms.pdu_alt.EncodedStringValue("insert-address-token")
                    val ind = com.google.android.mms.pdu_alt.ReadRecInd(
                        from, mid!!.toByteArray(), 18, /* MMS 1.2 */
                        com.google.android.mms.pdu_alt.PduHeaders.READ_STATUS_READ,
                        arrayOf(com.google.android.mms.pdu_alt.EncodedStringValue(m.address))
                    )
                    val bytes = com.google.android.mms.pdu_alt.PduComposer(context, ind).make()
                        ?: continue
                    val f = File(context.cacheDir, "read_rec_${System.currentTimeMillis()}")
                    f.writeBytes(bytes)
                    val uri = Uri.Builder()
                        .authority(context.packageName + ".MmsFileProvider")
                        .path(f.name).scheme(android.content.ContentResolver.SCHEME_CONTENT)
                        .build()
                    android.telephony.SmsManager.getDefault()
                        .sendMultimediaMessage(context, uri, null, null, null)
                    val cv = android.content.ContentValues(1).apply { put("rr", 129) }
                    context.contentResolver.update(
                        Uri.parse("content://mms/$tid"), cv, null, null
                    )
                    io.github.theonionsarewatching.nova.util.DiagLog.log(
                        context, "mms-delivery", "read-rec sent for m_id=$mid to ${m.address}"
                    )
                } catch (e: Exception) {
                    io.github.theonionsarewatching.nova.util.DiagLog.log(
                        context, "mms-delivery", "read-rec failed: ${e.message}"
                    )
                }
            }
        } catch (_: Exception) {}
    }

    /** Delete downloaded MMS media files from app storage. Messages and their
     *  part rows are kept; the file re-downloads from the carrier when the
     *  message is next opened. Frees space without losing any conversation. */
    suspend fun clearMmsCache() {
        try {
            File(context.filesDir, "parts").listFiles()?.forEach { f ->
                runCatching { f.delete() }
            }
        } catch (_: Exception) {}
        // also the transient download scratch files
        try {
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("download.")) runCatching { f.delete() }
            }
        } catch (_: Exception) {}
        ChangeBus.ping()
    }

    /** Auto-delete: keep only the newest `keep` messages per conversation,
     *  removing older unlocked ones (and their telephony rows, so they can't
     *  resurrect). Locked messages are always kept and don't count. */
    suspend fun enforceAutoDelete(keep: Int) {
        if (keep < 1) return
        for (c in db.conversations().visible()) {
            val victims = db.messages().messagesBeyondKeep(c.id, keep)
            if (victims.isEmpty()) continue
            for (m in victims) {
                db.parts().byMessage(m.id).forEach { runCatching { File(it.filePath).delete() } }
                db.parts().deleteByMessage(m.id)
                db.elements().deleteByMessage(m.id)
                deleteFromTelephony(m)
                db.messages().hardDelete(m.id)
            }
            refreshConversation(c.id)
        }
        ChangeBus.ping()
    }

    /** Auto-download off: ingest a freshly persisted notification-ind as the
     *  tappable download stub, and notify like any incoming message. */
    suspend fun ingestNotificationStub(mmsId: Long) = withContext(Dispatchers.IO) {
        var dateMs = System.currentTimeMillis()
        try {
            context.contentResolver.query(
                Uri.parse("content://mms"), arrayOf("date"),
                "_id = ?", arrayOf(mmsId.toString()), null
            )?.use { c -> if (c.moveToFirst()) dateMs = c.getLong(0) * 1000L }
        } catch (_: Exception) {}
        val result = ingestMms(mmsId, dateMs, Telephony.Mms.MESSAGE_BOX_INBOX) ?: return@withContext
        val (msg, convo) = result
        if (!msg.isMine && !msg.blockedByKeyword) {
            io.github.theonionsarewatching.nova.notify.NotificationHelper
                .notifyMessage(context, convo, msg)
        }
    }

    /** Public entry for MmsPushReceiver: apply a persisted read/delivery
     *  indication (matches the 0.9.50 behavior the engine gave us for free). */
    suspend fun applyMmsIndication(indId: Long, mType: Int) = handleMmsIndication(indId, mType)

    private suspend fun handleMmsIndication(indId: Long, mType: Int) = withContext(Dispatchers.IO) {
        try {
            var origMessageId: String? = null
            var st = -1
            // ONLY m_id and st here. These two columns exist on every provider.
            // 0.9.60 added read_status to this same projection — and on any ROM
            // whose provider lacks that column the whole query throws, which
            // took DELIVERY reports down along with read reports. read_status
            // is now read separately below so it can never do that again.
            var alreadyProcessed = false
            context.contentResolver.query(
                Uri.parse("content://mms"), arrayOf("m_id", "st", "read"),
                "_id = ?", arrayOf(indId.toString()), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    origMessageId = c.getString(0)
                    st = try { c.getInt(1) } catch (_: Exception) { -1 }
                    alreadyProcessed = try { c.getInt(2) == 1 } catch (_: Exception) { false }
                }
            }
            // the sync path revisits every unlinked indication row on every
            // pass; a row we have applied is marked read=1 and skipped, which
            // stops the endless reprocessing (and the log flood) seen in the
            // field. Unmatched rows stay unmarked so a late-stored Message-ID
            // still gets its retry.
            if (alreadyProcessed) return@withContext
            // X-Mms-Read-Status, best-effort and fully isolated.
            var readStatus = -1
            if (mType == 136) {
                try {
                    context.contentResolver.query(
                        Uri.parse("content://mms"), arrayOf(Telephony.Mms.READ_STATUS),
                        "_id = ?", arrayOf(indId.toString()), null
                    )?.use { c -> if (c.moveToFirst()) readStatus = c.getInt(0) }
                } catch (_: Exception) { readStatus = -1 }
            }
            val kind = if (mType == 136) "read-orig-ind" else "delivery-ind"
            io.github.theonionsarewatching.nova.util.DiagLog.log(
                context, "mms-delivery",
                "$kind: m_id=$origMessageId st=$st read_status=$readStatus"
            )
            val mid = origMessageId
            if (mid.isNullOrBlank()) {
                // no Message-ID on the notice: nothing to match it against
                io.github.theonionsarewatching.nova.util.DiagLog.log(
                    context, "mms-delivery", "$kind has no m_id — dropped"
                )
                return@withContext
            }
            // find the SENT telephony row carrying that Message-ID. This only
            // works if the M-Send.conf gave us a Message-ID at send time and we
            // stored it (see MmsSentReceiverImpl) — the usual reason a report
            // "does nothing" is that this lookup misses, so it is logged.
            var sentTelephonyId: Long? = null
            context.contentResolver.query(
                Uri.parse("content://mms"), arrayOf(Telephony.Mms._ID),
                "m_id = ? AND ${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_SENT}",
                arrayOf(mid), "date DESC"
            )?.use { c -> if (c.moveToFirst()) sentTelephonyId = c.getLong(0) }
            val tId = sentTelephonyId ?: run {
                if (unmatchedIndicationLogged.add(mid)) {
                    io.github.theonionsarewatching.nova.util.DiagLog.log(
                        context, "mms-delivery",
                        "$kind: no SENT row carries m_id=$mid — dropped (was the " +
                            "Message-ID stored at send time?)"
                    )
                }
                return@withContext
            }
            // broadcast copies: only the first copy is linked directly, so
            // reports for the other copies resolve through the registry to the
            // ONE broadcast message — that's what feeds the per-recipient
            // "Delivered to N · Read by N" counts
            var viaBroadcast = false
            val m = db.messages().byTelephonyMms(tId)
                ?: io.github.theonionsarewatching.nova.util.BroadcastCopies
                    .messageIdFor(context, tId)?.let { viaBroadcast = true; db.messages().byId(it) }
                ?: run {
                    io.github.theonionsarewatching.nova.util.DiagLog.log(
                        context, "mms-delivery",
                        "$kind: telephony row $tId has no linked message — dropped"
                    )
                    return@withContext
                }
            val stamp = android.text.format.DateFormat.format(
                "MM-dd HH:mm", System.currentTimeMillis())

            // THE 0.9.50 RULE, verbatim, because it is the one that worked in
            // the field: a read-orig-ind arriving IS the read signal. No
            // conditions on read_status — that column's real-world contents on
            // these devices are unknown, and gating on an unknown is how read
            // reports silently died. Its value is captured in the log above so
            // the field can tell us what it actually holds; the DECISION does
            // not depend on it.
            val isRead = mType == 136
            // A read notice implies delivery a fortiori: nothing can be read
            // that was never delivered.
            val delivered = st == 129 || isRead

            db.messages().appendDeliveryDebug(
                m.id,
                if (mType == 136)
                    "[$stamp] MMS read report (read_status=$readStatus) -> read\n"
                else
                    "[$stamp] MMS delivery report st=$st -> " +
                        "${if (delivered) "delivered" else "not delivered"}\n"
            )
            // Upgrade only, and never past a terminal state. A late delivery
            // notice must not demote an already-read message, and a report
            // arriving before our own send-confirmation (fast networks) must
            // still land — hence SENDING is an acceptable starting point.
            when {
                isRead && m.status in listOf(
                    MsgStatus.SENDING, MsgStatus.SENT, MsgStatus.DELIVERED
                ) -> setStatusRespectingCancel(m.id, MsgStatus.READ_BY_RECIPIENT)

                delivered && m.status in listOf(MsgStatus.SENDING, MsgStatus.SENT) ->
                    setStatusRespectingCancel(m.id, MsgStatus.DELIVERED)
            }
            // group message: record WHICH recipient this notice is about, from
            // the indication's TO address row, so the meta line can say
            // "Delivered to X \u00b7 Read by Y"
            try {
                if (viaBroadcast || m.address.contains(",") || m.address.contains(";")) {
                    var who: String? = null
                    context.contentResolver.query(
                        Uri.parse("content://mms/$indId/addr"),
                        arrayOf("address", "type"), null, null, null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            if (c.getInt(1) == 151) { who = c.getString(0); break }
                        }
                    }
                    if (!who.isNullOrBlank()) {
                        val key = PhoneUtils.normalize(who!!)
                        val map = m.recipientStatuses.split(",")
                            .filter { it.contains("=") }
                            .associate { it.substringBefore("=") to it.substringAfter("=") }
                            .toMutableMap()
                        val mark = if (isRead) "R" else "D"
                        if (!(map[key] == "R" && mark == "D")) map[key] = mark
                        db.messages().setRecipientStatuses(
                            m.id, map.entries.joinToString(",") { "${it.key}=${it.value}" }
                        )
                    }
                }
            } catch (_: Exception) {}
            // applied: mark the indication row so no future pass re-runs it
            try {
                val cv = android.content.ContentValues(1).apply { put("read", 1) }
                context.contentResolver.update(
                    Uri.parse("content://mms/$indId"), cv, null, null
                )
            } catch (_: Exception) {}
            refreshAndPing(m.convoId)
        } catch (e: Exception) {
            io.github.theonionsarewatching.nova.util.DiagLog.log(
                context, "mms-delivery", "indication handling failed: ${e.message}"
            )
        }
    }

    /** A status report that arrived through the SMS_DELIVER pipeline: match it
     *  to the newest outgoing text to that number and apply it. */
    suspend fun onStatusReportViaInbox(address: String, tpStatus: Int) {
        if (address.isBlank()) return
        val key = PhoneUtils.normalize(address)
        val since = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        val candidate = db.messages().recentMineSms(since)
            .firstOrNull { m ->
                m.address.split("|").any { PhoneUtils.normalize(it) == key }
            } ?: return
        onSmsDelivered(candidate.id, address, tpStatus in 0 until 32, tpStatus, resultCode = 99)
    }

    suspend fun onSmsDelivered(
        messageId: Long, recipient: String, ok: Boolean,
        tpStatus: Int = -1, resultCode: Int = 0
    ) {
        // diagnostic trail: proves whether reports ARRIVE (carrier side) and what
        // they said — shown in the message's Details
        val stamp = android.text.format.DateFormat.format("MM-dd HH:mm", System.currentTimeMillis())
        db.messages().appendDeliveryDebug(
            messageId,
            "[$stamp] report tp=$tpStatus rc=$resultCode -> ${if (ok) "delivered" else "not delivered"}\n"
        )
        val m = db.messages().byId(messageId) ?: return
        if (!ok) { refreshAndPing(m.convoId); return }
        if (m.recipientStatuses.isNotBlank()) {
            val map = parseStatuses(m.recipientStatuses).toMutableMap()
            map[PhoneUtils.normalize(recipient)] = MsgStatus.DELIVERED
            db.messages().setRecipientStatuses(messageId, encodeStatuses(map))
            applyAggregate(messageId, map)
        } else {
            db.messages().setStatus(messageId, MsgStatus.DELIVERED)
        }
        refreshAndPing(m.convoId)
    }

    suspend fun onMmsSent(messageId: Long, ok: Boolean, telephonyId: Long? = null) {
        val before = db.messages().byId(messageId)?.status
        // never move backwards: a delivery/read report can land BEFORE this
        // send-confirmation finishes (it races through the sync path), and the
        // unconditional SENT write here was stomping DELIVERED/READ back to
        // "Sent". A confirmation carries no new information for a message a
        // report has already advanced.
        val alreadyPast = ok && (before == MsgStatus.DELIVERED ||
            before == MsgStatus.READ_BY_RECIPIENT)
        if (!alreadyPast) {
            setStatusRespectingCancel(messageId, if (ok) MsgStatus.SENT else MsgStatus.FAILED)
        }
        val after = db.messages().byId(messageId)?.status
        io.github.theonionsarewatching.nova.util.DiagLog.log(
            context, "mms-status", "msg=$messageId status $before -> $after (row ${if (after == null) "MISSING" else "ok"})"
        )
        db.messages().byId(messageId)?.let { m ->
            // link the telephony row the engine persisted, so the content observer
            // recognizes it as ours (closes the duplicate window from this side too)
            if (telephonyId != null && m.telephonyId == null) {
                db.messages().setTelephonyId(m.id, telephonyId, true)
            }
            refreshAndPing(m.convoId)
        }
    }

    /** Reality wins: a sent confirmation flips CANCELED to SENT; a failure keeps CANCELED. */
    private suspend fun setStatusRespectingCancel(messageId: Long, newStatus: Int) {
        val m = db.messages().byId(messageId) ?: return
        if (m.status == MsgStatus.CANCELED && newStatus == MsgStatus.FAILED) return
        db.messages().setStatus(messageId, newStatus)
    }

    private suspend fun applyAggregate(messageId: Long, map: Map<String, Int>) {
        val values = map.values
        // READ_BY_RECIPIENT is 7, which is >= SENT, so the old chain collapsed
        // a read message back to "Sent". Rank the read state explicitly.
        val agg = when {
            values.any { it == MsgStatus.FAILED } -> MsgStatus.FAILED
            values.all { it == MsgStatus.READ_BY_RECIPIENT } -> MsgStatus.READ_BY_RECIPIENT
            values.all {
                it == MsgStatus.DELIVERED || it == MsgStatus.READ_BY_RECIPIENT
            } -> MsgStatus.DELIVERED
            values.all {
                it == MsgStatus.SENT || it == MsgStatus.DELIVERED ||
                    it == MsgStatus.READ_BY_RECIPIENT
            } -> MsgStatus.SENT
            else -> MsgStatus.SENDING
        }
        setStatusRespectingCancel(messageId, agg)
    }

    fun parseStatuses(s: String): Map<String, Int> =
        s.split(",").mapNotNull {
            val idx = it.lastIndexOf('=')
            if (idx <= 0) null else it.substring(0, idx) to (it.substring(idx + 1).toIntOrNull() ?: 0)
        }.toMap()

    private fun encodeStatuses(map: Map<String, Int>): String =
        map.entries.joinToString(",") { "${it.key}=${it.value}" }

    // ---- retry / resend / cancel ----

    suspend fun cancelSending(messageId: Long) {
        val m = db.messages().byId(messageId) ?: return
        if (m.status == MsgStatus.SENDING) {
            db.messages().setStatus(messageId, MsgStatus.CANCELED)
            refreshAndPing(m.convoId)
        }
    }

    suspend fun retry(messageId: Long) = withContext(Dispatchers.IO) {
        val m = db.messages().byId(messageId) ?: return@withContext
        val convo = db.conversations().byId(m.convoId) ?: return@withContext
        val addresses = convo.addressList()
        db.messages().setStatus(messageId, MsgStatus.SENDING)
        // A retry is a NEW send: sever the link to the failed store copy (and
        // delete that dead row), and move the message's date to now. Both are
        // required for the duplicate-guards to recognize the fresh store row
        // the engine is about to write as OURS — the stale link + old date is
        // exactly how retried messages spawned a frozen "Sending" twin.
        m.telephonyId?.let { tid ->
            try {
                val base = if (m.telephonyIsMms) "content://mms/" else "content://sms/"
                context.contentResolver.delete(Uri.parse(base + tid), null, null)
            } catch (_: Exception) {}
            db.messages().deleteOthersByTelephony(tid, m.telephonyIsMms, messageId)
            db.messages().clearTelephonyId(messageId)
        }
        // Belt beyond the link: remove any OTHER app row in this conversation
        // that is a twin of this one (same mine-ness, same body, within a few
        // minutes) — covers duplicates ingested from the failed store copy even
        // when the failed row never carried a telephonyId. This is what made the
        // retry duplicate survive earlier fixes.
        db.messages().deleteTwins(
            m.convoId, m.isMine, m.body, m.date - 300_000, m.date + 300_000, messageId
        )
        db.messages().setDate(messageId, System.currentTimeMillis())
        if (m.recipientStatuses.isNotBlank()) {
            val reset = addresses.joinToString(",") { "${PhoneUtils.normalize(it)}=${MsgStatus.SENDING}" }
            db.messages().setRecipientStatuses(messageId, reset)
        }
        refreshAndPing(m.convoId)
        val parts = db.parts().byMessage(messageId)
        if (m.isMms || parts.isNotEmpty()) {
            Sender.sendMms(context, messageId, m.body, addresses,
                parts.map { Triple(it.filePath, it.mimeType, it.fileName) })
        } else {
            Sender.sendSmsToAll(context, messageId, m.body, addresses)
        }
    }

    // ---- scheduled ----

    suspend fun scheduleMessage(convoId: Long, text: String, sendAt: Long): Long? {
        val convo = db.conversations().byId(convoId) ?: return null
        val msg = MessageEntity(
            convoId = convoId, address = convo.addresses, body = text, date = sendAt,
            isMine = true, status = MsgStatus.SCHEDULED, scheduledAt = sendAt,
            isMms = convo.isGroup && convo.groupMode == GroupMode.GROUP_MMS
        )
        val id = db.messages().insert(msg)
        setAlarm(id, sendAt)
        refreshAndPing(convoId)
        return id
    }

    suspend fun cancelScheduled(messageId: Long) {
        val m = db.messages().byId(messageId) ?: return
        cancelAlarm(messageId)
        db.messages().hardDelete(messageId)
        db.elements().deleteByMessage(messageId)
        refreshAndPing(m.convoId)
    }

    suspend fun fireScheduled(messageId: Long) {
        val m = db.messages().byId(messageId) ?: return
        if (m.status != MsgStatus.SCHEDULED) return
        val convo = db.conversations().byId(m.convoId) ?: return
        val now = System.currentTimeMillis()
        db.messages().update(m.copy(status = MsgStatus.SENDING, date = now, scheduledAt = null))
        extractElements(messageId, m.body)
        refreshAndPing(m.convoId)
        if (m.isMms) Sender.sendMms(context, messageId, m.body, convo.addressList(), emptyList())
        else Sender.sendSmsToAll(context, messageId, m.body, convo.addressList())
    }

    fun rescheduleAllAlarms() {
        scope.launch {
            val now = System.currentTimeMillis()
            db.messages().scheduled().forEach { m ->
                val at = m.scheduledAt ?: return@forEach
                if (at <= now) fireScheduled(m.id) else setAlarm(m.id, at)
            }
        }
    }

    private fun setAlarm(messageId: Long, at: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = scheduledPi(messageId)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(messageId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(scheduledPi(messageId))
    }

    private fun scheduledPi(messageId: Long): PendingIntent {
        val i = Intent(context, ScheduledSendReceiver::class.java).putExtra("message_id", messageId)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (messageId % Int.MAX_VALUE).toInt(), i, flags)
    }

    // ============================== Recycle bin ==============================

    /** Remove a message's backing row from the OS telephony provider. Without
     *  this a soft-deleted message is re-imported the next time the content
     *  observer fires or the user runs re-sync / reimport — the "deleted
     *  messages came back" bug. Locked messages are exempt (a lock means keep). */
    private fun deleteFromTelephony(m: MessageEntity) {
        val tId = m.telephonyId ?: return
        try {
            val uri = if (m.telephonyIsMms)
                android.net.Uri.parse("content://mms/$tId")
            else android.net.Uri.parse("content://sms/$tId")
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {}
    }

    suspend fun deleteMessage(messageId: Long) {
        val m = db.messages().byId(messageId) ?: return
        db.messages().softDelete(messageId, System.currentTimeMillis())
        if (!m.locked) deleteFromTelephony(m)
        refreshAndPing(m.convoId)
    }

    /** Wipe messages/conversations and re-import from the phone's message store.
     *  Fixes conversations that were mis-grouped by the old import. Keeps keywords
     *  and settings; app-only flags on messages (locks, schedules) are lost. */
    suspend fun reimportAll(onProgress: (Int) -> Unit) {
        db.elements().deleteAll()
        db.parts().deleteAll()
        db.messages().deleteAll()
        db.conversations().deleteAll()
        try {
            File(context.filesDir, "parts").listFiles()?.forEach { runCatching { it.delete() } }
        } catch (_: Exception) {}
        importFromTelephony(onProgress)
        ChangeBus.ping()
    }

    /** Mute a conversation. Muting and blocking are mutually exclusive — set
     *  one and the other clears, so the menu never shows a stale "unmute" next
     *  to an active block. */
    suspend fun setMuted(convoId: Long, muted: Boolean) {
        db.conversations().setMuted(convoId, muted)
        if (muted) db.conversations().setNotifBlocked(convoId, false)
        io.github.theonionsarewatching.nova.notify.NotificationHelper
            .refreshConvoChannels(context, convoId)
        ChangeBus.ping()
    }

    suspend fun setNotifBlocked(convoId: Long, blocked: Boolean) {
        db.conversations().setNotifBlocked(convoId, blocked)
        if (blocked) db.conversations().setMuted(convoId, false)
        io.github.theonionsarewatching.nova.notify.NotificationHelper
            .refreshConvoChannels(context, convoId)
        ChangeBus.ping()
    }

    /** Per-conversation notification tone ("" = follow app default, "silent" = no sound). */
    suspend fun setConversationTone(convoId: Long, tone: String) {
        db.conversations().setCustomTone(convoId, tone)
        io.github.theonionsarewatching.nova.notify.NotificationHelper.refreshConvoChannels(context, convoId)
        ChangeBus.ping()
    }

    /** Per-conversation vibration: 0 follow app, 1 on, 2 off. */
    suspend fun setConversationVibrate(convoId: Long, mode: Int) {
        db.conversations().setVibrateMode(convoId, mode)
        io.github.theonionsarewatching.nova.notify.NotificationHelper.refreshConvoChannels(context, convoId)
        ChangeBus.ping()
    }

    suspend fun deleteMessages(ids: Collection<Long>, includeLocked: Boolean) {
        var convoId = -1L
        val now = System.currentTimeMillis()
        for (id in ids) {
            val m = db.messages().byId(id) ?: continue
            if (m.locked && !includeLocked) continue
            db.messages().softDelete(id, now)
            deleteFromTelephony(m)
            convoId = m.convoId
        }
        if (convoId > 0) refreshAndPing(convoId)
    }

    suspend fun deleteThread(convoId: Long, includeLocked: Boolean) {
        // capture backing rows BEFORE the soft delete so we can purge telephony
        val victims = db.messages().threadMessagesForDelete(convoId, if (includeLocked) 1 else 0)
        db.messages().softDeleteThread(convoId, System.currentTimeMillis(), if (includeLocked) 1 else 0)
        for (m in victims) deleteFromTelephony(m)
        db.conversations().setDraft(convoId, "")
        refreshAndPing(convoId)
    }

    suspend fun restoreMessage(messageId: Long) {
        val m = db.messages().byId(messageId) ?: return
        db.messages().restore(messageId)
        refreshAndPing(m.convoId)
    }

    suspend fun hardDeleteMessage(messageId: Long) {
        val m = db.messages().byId(messageId)
        db.parts().byMessage(messageId).forEach { runCatching { File(it.filePath).delete() } }
        db.parts().deleteByMessage(messageId)
        db.elements().deleteByMessage(messageId)
        db.messages().hardDelete(messageId)
        m?.let { refreshAndPing(it.convoId) }
    }

    /** Auto-empty: does NOT respect locks (lock is already cleared when a message enters the bin). */
    fun cleanRecycleBin() {
        scope.launch {
            val days = Prefs.get(context).binRetentionDays
            if (days <= 0) return@launch
            val cutoff = System.currentTimeMillis() - days * 86_400_000L
            db.messages().expiredBinIds(cutoff).forEach { hardDeleteMessage(it) }
        }
    }

    // ============================== Contact name cache ==============================

    fun refreshContactNames(force: Boolean = false) {
        scope.launch {
            val prefs = Prefs.get(context)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.contactsRefreshedAt < 60_000L) return@launch
            if (!ContactsHelper.hasPermission(context)) return@launch
            prefs.contactsRefreshedAt = now
            val contacts = ContactsHelper.loadAll(context)
            val nameByKey = HashMap<String, String>()
            val photoByKey = HashMap<String, String>()
            contacts.forEach {
                val k = PhoneUtils.normalize(it.number)
                nameByKey[k] = it.name
                if (it.photoUri.isNotBlank()) photoByKey[k] = it.photoUri
            }
            var changed = false
            db.conversations().all().forEach { convo ->
                val names = convo.addressList().map { nameByKey[PhoneUtils.normalize(it)] ?: "" }
                val joined = names.joinToString("|")
                val photo = if (!convo.isGroup)
                    photoByKey[PhoneUtils.normalize(convo.addressList().firstOrNull() ?: "")] ?: ""
                else ""
                if (joined != convo.cachedNames || photo != convo.cachedPhotoUri) {
                    db.conversations().setCachedNames(convo.id, joined, photo)
                    changed = true
                }
            }
            if (changed) ChangeBus.ping()
        }
    }

    // ============================== Initial import & reconciliation ==============================

    /** One-time import of the system SMS/MMS store into our DB. Reports progress 0..100. */
    @SuppressLint("Range")
    suspend fun importFromTelephony(onProgress: (Int) -> Unit) =
        db.withTransaction { importFromTelephonyInner(onProgress) }

    private suspend fun importFromTelephonyInner(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // learn this phone's own number(s) from sent MMS senders FIRST — without
        // this, sent and received group messages computed different conversation
        // keys and sent messages landed in a parallel copy of the group
        try {
            val sentIds = ArrayList<Long>()
            resolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.MESSAGE_BOX),
                "${Telephony.Mms.MESSAGE_BOX} != ${Telephony.Mms.MESSAGE_BOX_INBOX}", null, null
            )?.use { c -> while (c.moveToNext()) sentIds.add(c.getLong(0)) }
            val learned = ArrayList<String>()
            for (id in sentIds) {
                resolver.query(
                    Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"),
                    "type = 137", null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val a = c.getString(0) ?: continue
                        if (a.isNotBlank() && a != "insert-address-token") learned.add(a)
                    }
                }
            }
            learnOwnNumbers(learned)
        } catch (_: Exception) {}

        var smsTotal = 0
        resolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, null)
            ?.use { smsTotal = it.count }
        var mmsTotal = 0
        resolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID), null, null, null)
            ?.use { mmsTotal = it.count }
        val total = (smsTotal + mmsTotal).coerceAtLeast(1)
        var done = 0

        // SMS
        try {
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ
                ),
                null, null, "${Telephony.Sms.DATE} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    done++
                    if (done % 50 == 0) onProgress(done * 100 / total)
                    val tId = c.getLong(0)
                    if (db.messages().existsByTelephonyId(tId, false)) continue
                    if (io.github.theonionsarewatching.nova.util.BroadcastCopies
                            .isSmsCopy(context, tId)) continue
                    val address = c.getString(1) ?: continue
                    val body = c.getString(2) ?: ""
                    val date = c.getLong(3)
                    val type = c.getInt(4)
                    val read = c.getInt(5) == 1
                    val isMine = type != Telephony.Sms.MESSAGE_TYPE_INBOX
                    if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                    val status = when (type) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> MsgStatus.RECEIVED
                        Telephony.Sms.MESSAGE_TYPE_FAILED -> MsgStatus.FAILED
                        Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> MsgStatus.FAILED
                        else -> MsgStatus.SENT
                    }
                    // broadcast dummy rows in some apps use "|" joined addresses
                    val addresses = address.split("|").filter { it.isNotBlank() }.ifEmpty { listOf(address) }
                    val convo = getOrCreateConversation(addresses)
                    db.messages().insert(
                        MessageEntity(
                            convoId = convo.id, address = address, body = body, date = date,
                            isMine = isMine, status = status, read = read || isMine,
                            telephonyId = tId, telephonyIsMms = false
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // MMS
        try {
            resolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
                null, null, "${Telephony.Mms.DATE} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    done++
                    if (done % 20 == 0) onProgress(done * 100 / total)
                    val id = c.getLong(0)
                    if (db.messages().existsByTelephonyId(id, true)) continue
                    ingestMms(id, c.getLong(1) * 1000L, c.getInt(2))
                }
            }
        } catch (_: Exception) {}

        // sent MMS ingested above default to unread=false handled in ingestMms; mark imported received as read
        onProgress(100)
        val all = db.conversations().all()
        all.forEach { convo ->
            db.messages().markThreadRead(convo.id) // imported history starts read
            refreshConversation(convo.id)
        }
        refreshContactNames(force = true)
        ChangeBus.ping()
    }

    /** Safety net: pull anything another app wrote to telephony in the last 48h that we don't have.
     *  Matching rule: timestamp window + address (+ body). */
    @SuppressLint("Range")
    /** Set while restore bulk-writes into telephony, so the content observer
     *  doesn't ingest half-written rows into a database we're about to wipe. */
    @Volatile
    var syncSuspended = false

    fun syncRecentFromTelephony() {
        if (syncSuspended) return
        scope.launch {
            val resolver = context.contentResolver
            val cutoff = System.currentTimeMillis() - 48 * 3600_000L
            try {
                resolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                        Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ
                    ),
                    "${Telephony.Sms.DATE} > ?", arrayOf(cutoff.toString()), null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val tId = c.getLong(0)
                        if (db.messages().existsByTelephonyId(tId, false)) continue
                        if (io.github.theonionsarewatching.nova.util.BroadcastCopies
                                .isSmsCopy(context, tId)) continue
                    if (io.github.theonionsarewatching.nova.util.BroadcastCopies
                            .isSmsCopy(context, tId)) continue
                        val address = c.getString(1) ?: continue
                        val body = c.getString(2) ?: ""
                        val date = c.getLong(3)
                        val type = c.getInt(4)
                        val isMine = type != Telephony.Sms.MESSAGE_TYPE_INBOX
                        val convo = getOrCreateConversation(listOf(address))
                        if (db.messages().existsSimilar(convo.id, isMine, body, date - 5000, date + 5000)) continue
                        if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                        val status = when (type) {
                            Telephony.Sms.MESSAGE_TYPE_INBOX -> MsgStatus.RECEIVED
                            Telephony.Sms.MESSAGE_TYPE_FAILED -> MsgStatus.FAILED
                            Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED ->
                                if (System.currentTimeMillis() - date < 10 * 60 * 1000) MsgStatus.SENDING
                                else MsgStatus.FAILED
                            else -> MsgStatus.SENT
                        }
                        val id = db.messages().insert(
                            MessageEntity(
                                convoId = convo.id, address = address, body = body, date = date,
                                isMine = isMine, status = status, read = c.getInt(5) == 1 || isMine,
                                telephonyId = tId, telephonyIsMms = false
                            )
                        )
                        extractElements(id, body)
                        refreshConversation(convo.id)
                    }
                }
            } catch (_: Exception) {}
            try {
                resolver.query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
                    "${Telephony.Mms.DATE} > ?", arrayOf((cutoff / 1000).toString()), null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (db.messages().existsByTelephonyId(id, true)) {
                            // known row: still reflect box movement (this is the
                            // path that used to skip, leaving twins frozen at
                            // "Sending" forever)
                            db.messages().byTelephonyMms(id)?.let { existing ->
                                if (existing.isMine) applyBoxHeal(existing, c.getInt(2))
                            }
                            continue
                        }
                        // a bare notification row (130) whose real message has
                        // already been downloaded and ingested under a different
                        // _id: skip it, or the "Tap to download" stub reappears
                        var trId130: String? = null
                        var mType130 = 0
                        try {
                            resolver.query(Uri.parse("content://mms"),
                                arrayOf("tr_id", "m_type"), "_id = ?",
                                arrayOf(id.toString()), null)?.use { mc ->
                                if (mc.moveToFirst()) {
                                    trId130 = mc.getString(0); mType130 = mc.getInt(1)
                                }
                            }
                        } catch (_: Exception) {}
                        if (mType130 == 130 && !trId130.isNullOrBlank()) {
                            var already = false
                            try {
                                resolver.query(Uri.parse("content://mms"), arrayOf("_id"),
                                    "m_type = 132 AND tr_id = ?", arrayOf(trId130), null)
                                    ?.use { dc -> already = dc.count > 0 }
                            } catch (_: Exception) {}
                            if (already) {
                                try { resolver.delete(Uri.parse("content://mms/$id"), null, null) }
                                catch (_: Exception) {}
                                continue
                            }
                        }
                        ingestMms(id, c.getLong(1) * 1000L, c.getInt(2))
                    }
                }
            } catch (_: Exception) {}
            ChangeBus.ping()
        }
    }

    // ============================== misc ==============================

    private suspend fun refreshAndPing(convoId: Long) {
        refreshConversation(convoId)
        ChangeBus.ping()
    }

    companion object {
        @Volatile private var instance: Repo? = null
        fun get(context: Context): Repo = instance ?: synchronized(this) {
            instance ?: Repo(context.applicationContext).also { instance = it }
        }
        fun smsManagerFor(context: Context, subId: Int): SmsManager {
            return try {
                if (subId >= 0) {
                    if (Build.VERSION.SDK_INT >= 31)
                        context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
                    else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subId)
                } else {
                    if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
                    else @Suppress("DEPRECATION") SmsManager.getDefault()
                }
            } catch (_: Exception) {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
        }
    }
}
