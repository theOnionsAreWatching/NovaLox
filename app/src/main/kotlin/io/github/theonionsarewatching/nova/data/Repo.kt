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

    suspend fun refreshConversation(convoId: Long) {
        val convo = db.conversations().byId(convoId) ?: return
        val newest = db.messages().newest(convoId)
        val unread = db.messages().unreadCount(convoId)
        val snippet = newest?.let { snippetFor(it) } ?: ""
        db.conversations().updateSummary(
            convoId, snippet, newest?.date ?: convo.snippetDate, newest?.isMine ?: false, unread
        )
    }

    private suspend fun snippetFor(m: MessageEntity): String {
        if (m.body.isNotBlank()) return m.body.take(120)
        if (m.isMms) {
            val parts = db.parts().byMessage(m.id)
            val p = parts.firstOrNull()
            return when {
                p == null -> "[MMS]"
                p.isImage() -> "[Photo]"
                p.isVideo() -> "[Video]"
                p.isAudio() -> "[Audio]"
                p.isVCard() -> "[Contact card]"
                else -> "[Attachment]"
            }
        }
        return ""
    }

    // ============================== Incoming ==============================

    /** Store an incoming SMS. Also writes it to the system provider (default-app duty).
     *  Returns the stored message + its conversation, or null when the sender is number-blocked. */
    suspend fun receiveSms(address: String, body: String, date: Long, subId: Int): Pair<MessageEntity, ConversationEntity>? {
        if (isNumberBlocked(address)) return null

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
        val blocked = matchesKeyword(body)
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
        return Pair(msg.copy(id = id), convo)
    }

    /** Ingest the newest MMS that the engine persisted into the telephony provider. */
    @SuppressLint("Range")
    suspend fun ingestLatestMmsFromTelephony(): Pair<MessageEntity, ConversationEntity>? {
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
        if (mmsId < 0) return null
        if (db.messages().existsByTelephonyId(mmsId, true)) return null
        return ingestMms(mmsId, date, msgBox)
    }

    @SuppressLint("Range")
    suspend fun ingestMms(mmsId: Long, dateMs: Long, msgBox: Int): Pair<MessageEntity, ConversationEntity>? {
        val resolver = context.contentResolver
        val isMine = msgBox != Telephony.Mms.MESSAGE_BOX_INBOX

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

        val participants = if (isMine) {
            to.ifEmpty { from }
        } else {
            // group MMS: sender + other recipients (own number is usually among "to"; we can't
            // reliably know it, so keep everyone; 1:1 MMS simply has a single sender)
            val others = to.filter { PhoneUtils.normalize(it) !in from.map { f -> PhoneUtils.normalize(f) } }
            if (others.size >= 2) from + others.take(others.size - 1) else from
        }.ifEmpty { listOf("Unknown") }

        if (!isMine && from.firstOrNull()?.let { isNumberBlocked(it) } == true) return null

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
                        ct == "text/plain" -> if (!text.isNullOrBlank()) bodyText =
                            if (bodyText.isBlank()) text else bodyText + "\n" + text
                        ct == "application/smil" -> { /* layout description, skip */ }
                        else -> binaryParts.add(RawPart(pid, ct, name, null))
                    }
                }
            }
        } catch (_: Exception) {}

        val convo = getOrCreateConversation(participants)
        val blocked = !isMine && matchesKeyword(bodyText)
        val senderAddress = if (isMine) participants.joinToString("|") else (from.firstOrNull() ?: "Unknown")
        val status = if (isMine) MsgStatus.SENT else MsgStatus.RECEIVED
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
                    db.parts().insert(
                        PartEntity(
                            messageId = id, mimeType = p.ct,
                            filePath = outFile.absolutePath, fileName = p.name, size = outFile.length()
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        extractElements(id, bodyText)
        refreshConversation(convo.id)
        ChangeBus.ping()
        return Pair(fixed.copy(id = id), convo)
    }

    private fun extensionFor(mime: String, name: String): String {
        val fromName = name.substringAfterLast('.', "")
        if (fromName.isNotBlank() && fromName.length <= 4) return ".$fromName"
        return when {
            mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
            mime.contains("png") -> ".png"
            mime.contains("gif") -> ".gif"
            mime.contains("mp4") -> ".mp4"
            mime.contains("3gpp") -> ".3gp"
            mime.contains("amr") -> ".amr"
            mime.contains("vcard") -> ".vcf"
            else -> ".bin"
        }
    }

    // ============================== Keywords / blocking ==============================

    private suspend fun matchesKeyword(body: String): Boolean {
        if (body.isBlank()) return false
        val lower = body.lowercase()
        return db.keywords().all().any { it.keyword.isNotBlank() && lower.contains(it.keyword.lowercase()) }
    }

    fun isNumberBlocked(address: String): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        return try {
            android.provider.BlockedNumberContract.isBlocked(context, address)
        } catch (_: Exception) { false }
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

        val asGroupMms = convo.isGroup && convo.groupMode == GroupMode.GROUP_MMS
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

    /** Send an MMS with an attachment (single recipient or group-MMS). */
    suspend fun sendAttachment(convoId: Long, text: String, filePath: String, mimeType: String, fileName: String): Long? {
        val convo = db.conversations().byId(convoId) ?: return null
        val addresses = convo.addressList()
        if (addresses.isEmpty()) return null
        val now = System.currentTimeMillis()
        val msg = MessageEntity(
            convoId = convoId, address = addresses.joinToString("|"), body = text, date = now,
            isMine = true, status = MsgStatus.SENDING, isMms = true
        )
        val id = db.messages().insert(msg)
        db.parts().insert(
            PartEntity(messageId = id, mimeType = mimeType, filePath = filePath,
                fileName = fileName, size = File(filePath).length())
        )
        if (text.isNotBlank()) extractElements(id, text)
        refreshConversation(convoId)
        ChangeBus.ping()
        Sender.sendMms(context, id, text, addresses, listOf(Triple(filePath, mimeType, fileName)))
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

    suspend fun onSmsDelivered(messageId: Long, recipient: String, ok: Boolean) {
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

    suspend fun onMmsSent(messageId: Long, ok: Boolean) {
        setStatusRespectingCancel(messageId, if (ok) MsgStatus.SENT else MsgStatus.FAILED)
        db.messages().byId(messageId)?.let { refreshAndPing(it.convoId) }
    }

    /** Reality wins: a sent confirmation flips CANCELED to SENT; a failure keeps CANCELED. */
    private suspend fun setStatusRespectingCancel(messageId: Long, newStatus: Int) {
        val m = db.messages().byId(messageId) ?: return
        if (m.status == MsgStatus.CANCELED && newStatus == MsgStatus.FAILED) return
        db.messages().setStatus(messageId, newStatus)
    }

    private suspend fun applyAggregate(messageId: Long, map: Map<String, Int>) {
        val values = map.values
        val agg = when {
            values.any { it == MsgStatus.FAILED } -> MsgStatus.FAILED
            values.all { it == MsgStatus.DELIVERED } -> MsgStatus.DELIVERED
            values.all { it >= MsgStatus.SENT } -> MsgStatus.SENT
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

    suspend fun retry(messageId: Long) {
        val m = db.messages().byId(messageId) ?: return
        val convo = db.conversations().byId(m.convoId) ?: return
        val addresses = convo.addressList()
        db.messages().setStatus(messageId, MsgStatus.SENDING)
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

    suspend fun deleteMessage(messageId: Long) {
        val m = db.messages().byId(messageId) ?: return
        db.messages().softDelete(messageId, System.currentTimeMillis())
        refreshAndPing(m.convoId)
    }

    suspend fun deleteThread(convoId: Long, includeLocked: Boolean) {
        db.messages().softDeleteThread(convoId, System.currentTimeMillis(), if (includeLocked) 1 else 0)
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
    suspend fun importFromTelephony(onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

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
                    val address = c.getString(1) ?: continue
                    val body = c.getString(2) ?: ""
                    val date = c.getLong(3)
                    val type = c.getInt(4)
                    val read = c.getInt(5) == 1
                    val isMine = type != Telephony.Sms.MESSAGE_TYPE_INBOX
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
    fun syncRecentFromTelephony() {
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
                        val address = c.getString(1) ?: continue
                        val body = c.getString(2) ?: ""
                        val date = c.getLong(3)
                        val type = c.getInt(4)
                        val isMine = type != Telephony.Sms.MESSAGE_TYPE_INBOX
                        val convo = getOrCreateConversation(listOf(address))
                        if (db.messages().existsSimilar(convo.id, isMine, body, date - 5000, date + 5000)) continue
                        val status = if (isMine) MsgStatus.SENT else MsgStatus.RECEIVED
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
                        if (db.messages().existsByTelephonyId(id, true)) continue
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
