package io.github.theonionsarewatching.nova.data

import android.content.Context
import io.github.theonionsarewatching.nova.util.PhoneUtils
import android.net.Uri
import android.provider.Telephony
import android.util.JsonReader
import android.util.JsonWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup of the Nova database into a single zip:
 *   data.json      — conversations, messages, parts (metadata), elements, keywords
 *   parts/<file>   — every stored attachment file
 * Restore is replace-all: existing data is wiped, ids are remapped on insert,
 * and attachment files are copied back into app storage.
 */
object BackupHelper {

    const val VERSION = 1

    data class LocalBackup(val displayName: String, val uri: Uri)

    private data class RestorePart(
        val oldMessageId: Long, val mime: String, val name: String,
        val storedName: String, val size: Long
    )

    /** percent 0..100 for determinate progress, -1 for "working, size unknown". */
    fun interface Progress {
        fun report(percent: Int, detail: String?)
    }

    suspend fun export(context: Context, uri: Uri, progress: Progress = Progress { _, _ -> }): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                exportToStream(context, out, progress)
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Fallback for devices without a system file picker: write straight to Downloads/D-SMS.
     *  Returns the file's display name, or null on failure. */
    suspend fun exportToDownloads(context: Context, progress: Progress = Progress { _, _ -> }): String? {
        val name = "nova-backup-" + java.text.SimpleDateFormat(
            "yyyyMMdd-HHmm", java.util.Locale.US
        ).format(java.util.Date()) + ".zip"
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/D-SMS")
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                val ok = context.contentResolver.openOutputStream(uri)?.use { out ->
                    exportToStream(context, out, progress)
                } ?: false
                if (ok) name else null
            } else {
                @Suppress("DEPRECATION")
                val downloads = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val dir = File(downloads, "D-SMS").apply { mkdirs() }
                val f = File(dir, name)
                val ok = f.outputStream().use { out -> exportToStream(context, out, progress) }
                if (ok) name else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Backups reachable without a file picker: Downloads/D-SMS (this app's own
     *  MediaStore entries on Android 10+, plain files on 9 and below). */
    fun findLocalBackups(context: Context): List<LocalBackup> {
        val out = ArrayList<LocalBackup>()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.Downloads._ID,
                        android.provider.MediaStore.Downloads.DISPLAY_NAME),
                    android.provider.MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                    arrayOf("nova-backup-%.zip"),
                    android.provider.MediaStore.Downloads.DATE_ADDED + " DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        out.add(LocalBackup(name, android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)))
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val downloads = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                for (dir in listOf(File(downloads, "D-SMS"), downloads)) {
                    dir.listFiles { f -> f.name.startsWith("nova-backup-") && f.name.endsWith(".zip") }
                        ?.sortedByDescending { it.lastModified() }
                        ?.forEach { out.add(LocalBackup(it.name, Uri.fromFile(it))) }
                }
            }
        } catch (_: Exception) {}
        return out.distinctBy { it.displayName }
    }

    private suspend fun exportToStream(
        context: Context, rawOut: java.io.OutputStream, progress: Progress = Progress { _, _ -> }
    ): Boolean {
        val repo = Repo.get(context)
        return try {
            progress.report(-1, null)
            val convos = repo.db.conversations().all()
            val messages = repo.db.messages().allMessages()
            val parts = repo.db.parts().allParts()
            val elements = repo.db.elements().allElements()
            val keywords = repo.db.keywords().all()
            val total = (convos.size + messages.size + parts.size * 2 + elements.size).coerceAtLeast(1)
            var done = 0
            fun tick(n: Int = 1) {
                done += n
                if (done % 50 == 0 || done >= total) progress.report(done * 100 / total, null)
            }

            ZipOutputStream(rawOut.buffered()).let { zip ->
                run {
                    // ---- data.json ----
                    zip.putNextEntry(ZipEntry("data.json"))
                    val w = JsonWriter(OutputStreamWriter(NonClosingOutputStream(zip), Charsets.UTF_8))
                    w.beginObject()
                    w.name("version").value(VERSION)

                    w.name("conversations").beginArray()
                    for (c in convos) {
                        w.beginObject()
                        w.name("id").value(c.id)
                        w.name("convoKey").value(c.convoKey)
                        w.name("addresses").value(c.addresses)
                        w.name("cachedNames").value(c.cachedNames)
                        w.name("cachedPhotoUri").value(c.cachedPhotoUri)
                        w.name("isGroup").value(c.isGroup)
                        w.name("groupMode").value(c.groupMode)
                        w.name("snippet").value(c.snippet)
                        w.name("snippetDate").value(c.snippetDate)
                        w.name("snippetIsMine").value(c.snippetIsMine)
                        w.name("unreadCount").value(c.unreadCount)
                        w.name("pinned").value(c.pinned)
                        w.name("archived").value(c.archived)
                        w.name("muted").value(c.muted)
                        w.name("notifBlocked").value(c.notifBlocked)
                        w.name("hidden").value(c.hidden)
                        w.name("draft").value(c.draft)
                        w.name("customTone").value(c.customTone)
                        w.name("vibrateMode").value(c.vibrateMode)
                        w.endObject()
                        tick()
                    }
                    w.endArray()

                    w.name("messages").beginArray()
                    for (m in messages) {
                        w.beginObject()
                        w.name("id").value(m.id)
                        w.name("convoId").value(m.convoId)
                        w.name("address").value(m.address)
                        w.name("body").value(m.body)
                        w.name("date").value(m.date)
                        w.name("isMine").value(m.isMine)
                        w.name("status").value(m.status)
                        w.name("read").value(m.read)
                        w.name("locked").value(m.locked)
                        if (m.deletedAt != null) w.name("deletedAt").value(m.deletedAt)
                        w.name("isMms").value(m.isMms)
                        w.name("subId").value(m.subId)
                        if (m.scheduledAt != null) w.name("scheduledAt").value(m.scheduledAt)
                        w.name("blockedByKeyword").value(m.blockedByKeyword)
                        w.name("recipientStatuses").value(m.recipientStatuses)
                        w.name("elementsExtracted").value(m.elementsExtracted)
                        if (m.telephonyId != null) w.name("telephonyId").value(m.telephonyId)
                        w.name("telephonyIsMms").value(m.telephonyIsMms)
                        w.endObject()
                        tick()
                    }
                    w.endArray()

                    w.name("parts").beginArray()
                    for (p in parts) {
                        w.beginObject()
                        w.name("messageId").value(p.messageId)
                        w.name("mimeType").value(p.mimeType)
                        w.name("fileName").value(p.fileName)
                        w.name("storedName").value(File(p.filePath).name)
                        w.name("size").value(p.size)
                        w.endObject()
                        tick()
                    }
                    w.endArray()

                    w.name("elements").beginArray()
                    for (e in elements) {
                        w.beginObject()
                        w.name("messageId").value(e.messageId)
                        w.name("type").value(e.type)
                        w.name("value").value(e.value)
                        w.endObject()
                        tick()
                    }
                    w.endArray()

                    w.name("keywords").beginArray()
                    for (k in keywords) w.value(k.keyword)
                    w.endArray()

                    w.endObject()
                    w.flush()
                    zip.closeEntry()

                    // ---- attachment files ----
                    val seen = HashSet<String>()
                    for (p in parts) {
                        tick()
                        val f = File(p.filePath)
                        if (!f.exists() || !seen.add(f.name)) continue
                        progress.report(done * 100 / total, f.name)
                        zip.putNextEntry(ZipEntry("parts/${f.name}"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                zip.finish()
                zip.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }
    suspend fun restore(context: Context, uri: Uri, progress: Progress = Progress { _, _ -> }): Boolean {
        val repo = Repo.get(context)
        return try {
            progress.report(-1, null)
            data class RConvo(val oldId: Long, val entity: ConversationEntity)
            data class RMsg(val oldId: Long, val oldConvoId: Long, val entity: MessageEntity)

            val convos = ArrayList<RConvo>()
            val messages = ArrayList<RMsg>()
            val parts = ArrayList<RestorePart>()
            val keywords = ArrayList<String>()

            // part files land in a TEMP dir first: they get written into the system
            // store, and the import then copies them back into app storage itself
            val tempDir = File(context.cacheDir, "restore_parts").apply {
                deleteRecursively(); mkdirs()
            }
            var sawData = false

            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == "data.json") {
                            sawData = true
                            val r = JsonReader(InputStreamReader(NonClosingInputStream(zip), Charsets.UTF_8))
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "conversations" -> {
                                        r.beginArray()
                                        while (r.hasNext()) {
                                            var id = 0L; var key = ""; var addrs = ""; var names = ""
                                            var photo = ""; var isGroup = false; var mode = 0
                                            var snippet = ""; var sDate = 0L; var sMine = false; var unread = 0
                                            var pinned = false; var archived = false; var muted = false
                                            var nBlocked = false; var hidden = false; var draft = ""
                                            var tone = ""; var vibMode = 0
                                            r.beginObject()
                                            while (r.hasNext()) when (r.nextName()) {
                                                "id" -> id = r.nextLong()
                                                "convoKey" -> key = r.nextString()
                                                "addresses" -> addrs = r.nextString()
                                                "cachedNames" -> names = r.nextString()
                                                "cachedPhotoUri" -> photo = r.nextString()
                                                "isGroup" -> isGroup = r.nextBoolean()
                                                "groupMode" -> mode = r.nextInt()
                                                "snippet" -> snippet = r.nextString()
                                                "snippetDate" -> sDate = r.nextLong()
                                                "snippetIsMine" -> sMine = r.nextBoolean()
                                                "unreadCount" -> unread = r.nextInt()
                                                "pinned" -> pinned = r.nextBoolean()
                                                "archived" -> archived = r.nextBoolean()
                                                "muted" -> muted = r.nextBoolean()
                                                "notifBlocked" -> nBlocked = r.nextBoolean()
                                                "hidden" -> hidden = r.nextBoolean()
                                                "draft" -> draft = r.nextString()
                                                "customTone" -> tone = r.nextString()
                                                "vibrateMode" -> vibMode = r.nextInt()
                                                else -> r.skipValue()
                                            }
                                            r.endObject()
                                            convos.add(RConvo(id, ConversationEntity(
                                                convoKey = key, addresses = addrs, cachedNames = names,
                                                cachedPhotoUri = photo, isGroup = isGroup, groupMode = mode,
                                                snippet = snippet, snippetDate = sDate, snippetIsMine = sMine,
                                                unreadCount = unread, pinned = pinned, archived = archived,
                                                muted = muted, notifBlocked = nBlocked, hidden = hidden, draft = draft,
                                                customTone = tone, vibrateMode = vibMode
                                            )))
                                        }
                                        r.endArray()
                                    }
                                    "messages" -> {
                                        r.beginArray()
                                        while (r.hasNext()) {
                                            var id = 0L; var convoId = 0L; var address = ""; var body = ""
                                            var date = 0L; var isMine = false; var status = 0; var read = true
                                            var locked = false; var deletedAt: Long? = null; var isMms = false
                                            var subId = -1; var scheduledAt: Long? = null; var blocked = false
                                            var rStatuses = ""; var extracted = true
                                            var tId: Long? = null; var tMms = false
                                            r.beginObject()
                                            while (r.hasNext()) when (r.nextName()) {
                                                "id" -> id = r.nextLong()
                                                "convoId" -> convoId = r.nextLong()
                                                "address" -> address = r.nextString()
                                                "body" -> body = r.nextString()
                                                "date" -> date = r.nextLong()
                                                "isMine" -> isMine = r.nextBoolean()
                                                "status" -> status = r.nextInt()
                                                "read" -> read = r.nextBoolean()
                                                "locked" -> locked = r.nextBoolean()
                                                "deletedAt" -> deletedAt = r.nextLong()
                                                "isMms" -> isMms = r.nextBoolean()
                                                "subId" -> subId = r.nextInt()
                                                "scheduledAt" -> scheduledAt = r.nextLong()
                                                "blockedByKeyword" -> blocked = r.nextBoolean()
                                                "recipientStatuses" -> rStatuses = r.nextString()
                                                "elementsExtracted" -> extracted = r.nextBoolean()
                                                "telephonyId" -> tId = r.nextLong()
                                                "telephonyIsMms" -> tMms = r.nextBoolean()
                                                else -> r.skipValue()
                                            }
                                            r.endObject()
                                            messages.add(RMsg(id, convoId, MessageEntity(
                                                convoId = 0, address = address, body = body, date = date,
                                                isMine = isMine, status = status, read = read, locked = locked,
                                                deletedAt = deletedAt, isMms = isMms, subId = subId,
                                                scheduledAt = scheduledAt, blockedByKeyword = blocked,
                                                recipientStatuses = rStatuses, elementsExtracted = extracted,
                                                telephonyId = tId, telephonyIsMms = tMms
                                            )))
                                        }
                                        r.endArray()
                                    }
                                    "parts" -> {
                                        r.beginArray()
                                        while (r.hasNext()) {
                                            var mid = 0L; var mime = ""; var name = ""; var stored = ""; var size = 0L
                                            r.beginObject()
                                            while (r.hasNext()) when (r.nextName()) {
                                                "messageId" -> mid = r.nextLong()
                                                "mimeType" -> mime = r.nextString()
                                                "fileName" -> name = r.nextString()
                                                "storedName" -> stored = r.nextString()
                                                "size" -> size = r.nextLong()
                                                else -> r.skipValue()
                                            }
                                            r.endObject()
                                            parts.add(RestorePart(mid, mime, name, stored, size))
                                        }
                                        r.endArray()
                                    }
                                    "keywords" -> {
                                        r.beginArray()
                                        while (r.hasNext()) keywords.add(r.nextString())
                                        r.endArray()
                                    }
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                        } else if (name.startsWith("parts/") && !entry.isDirectory) {
                            val safe = File(name).name
                            File(tempDir, safe).outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return false

            if (!sawData || convos.isEmpty() && messages.isEmpty()) return false

            val resolver = context.contentResolver
            val convoById = convos.associateBy { it.oldId }
            val partsByMsg = parts.groupBy { it.oldMessageId }

            // ---- identify this phone's own number(s), including from the backup
            //      itself: in a legacy 2-address conversation with received messages,
            //      the address that is never a sender anywhere is our own number ----
            val receivedSenders = messages.filter { !it.entity.isMine }
                .map { PhoneUtils.normalize(it.entity.address) }.toHashSet()
            val ownGuesses = HashSet<String>()
            for (c in convos) {
                val addrs = c.entity.addresses.split("|").filter { it.isNotBlank() }
                if (addrs.size != 2) continue
                val hasReceived = messages.any { it.oldConvoId == c.oldId && !it.entity.isMine }
                if (!hasReceived) continue
                for (a in addrs) {
                    val n = PhoneUtils.normalize(a)
                    if (n !in receivedSenders) ownGuesses.add(n)
                }
            }
            val own = repo.ownNumbersForRestore() + ownGuesses

            // ---- Phase 1: write real messages into the SYSTEM store ----
            val placeholderBody = context.getString(
                io.github.theonionsarewatching.nova.R.string.mms_not_downloaded)

            // preloaded duplicate indexes: one query each instead of one PER MESSAGE
            val smsSeen = HashSet<Long>()
            try {
                resolver.query(Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.DATE, Telephony.Sms.BODY), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        smsSeen.add(c.getLong(0) * 31 + (c.getString(1) ?: "").hashCode())
                    }
                }
            } catch (_: Exception) {}
            val mmsSeen = HashSet<Long>()
            try {
                resolver.query(Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) mmsSeen.add(c.getLong(0) * 10 + c.getInt(1))
                }
            } catch (_: Exception) {}

            // remove zero-part content MMS rows already in the system store (a
            // previous restore may have written them; strict stock apps crash on
            // them). Notification-ind rows (130) legitimately have no parts — kept.
            try {
                val partMids = HashSet<Long>()
                resolver.query(Uri.parse("content://mms/part"), arrayOf("mid"),
                    null, null, null)?.use { c ->
                    while (c.moveToNext()) partMids.add(c.getLong(0))
                }
                val empty = ArrayList<Long>()
                resolver.query(Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms._ID, Telephony.Mms.MESSAGE_TYPE), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val t = c.getInt(1)
                        if ((t == 128 || t == 132) && id !in partMids) empty.add(id)
                    }
                }
                for (id in empty) runCatching {
                    resolver.delete(Uri.parse("content://mms/$id"), null, null)
                }
            } catch (_: Exception) {}

            val threadIdCache = HashMap<String, Long>()
            fun threadIdFor(addrs: Collection<String>): Long {
                val key = addrs.map { PhoneUtils.normalize(it) }.sorted().joinToString(",")
                return threadIdCache.getOrPut(key) {
                    try {
                        if (addrs.size == 1) Telephony.Threads.getOrCreateThreadId(context, addrs.first())
                        else Telephony.Threads.getOrCreateThreadId(context, addrs.toSet())
                    } catch (_: Exception) { 0L }
                }
            }

            // live progress with an estimate
            var lastReport = 0L
            var currentFile = ""
            fun reportWriting(done: Int, total: Int, force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastReport < 150) return
                lastReport = now
                var detail = context.getString(
                    io.github.theonionsarewatching.nova.R.string.restore_writing_detail, done, total)
                if (currentFile.isNotBlank()) detail += " \u00B7 " + currentFile
                progress.report(done * 50 / total, detail)
            }

            repo.syncSuspended = true
            val flagged = ArrayList<Triple<Long, Boolean, Boolean>>() // sysId, isMms, locked
            val scheduledLater = ArrayList<RMsg>()
            val total = messages.size.coerceAtLeast(1)
            var done = 0
            val smsBatch = ArrayList<android.content.ContentValues>(128)
            fun flushSmsBatch() {
                if (smsBatch.isEmpty()) return
                try {
                    resolver.bulkInsert(Telephony.Sms.CONTENT_URI, smsBatch.toTypedArray())
                } catch (_: Exception) {
                    // fall back to singles if a provider dislikes bulk
                    for (v in smsBatch) runCatching { resolver.insert(Telephony.Sms.CONTENT_URI, v) }
                }
                smsBatch.clear()
            }
            fun smsValues(t: String, e: MessageEntity) = android.content.ContentValues().apply {
                put(Telephony.Sms.ADDRESS, t)
                put(Telephony.Sms.BODY, e.body)
                put(Telephony.Sms.DATE, e.date)
                put(Telephony.Sms.DATE_SENT, e.date)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
                put(Telephony.Sms.TYPE,
                    if (e.isMine) Telephony.Sms.MESSAGE_TYPE_SENT
                    else Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.THREAD_ID, threadIdFor(listOf(t)))
            }
            for (m in messages) {
                done++
                reportWriting(done, total)
                val e = m.entity
                if (e.deletedAt != null) continue                 // bin stays out of the system
                if (e.status == 6 /* SCHEDULED */) { scheduledLater.add(m); continue }
                val msgParts = partsByMsg[m.oldId].orEmpty()
                // placeholder/blank MMS with no parts must NOT be written: a system
                // MMS row with zero parts crashes some stock apps (Sonim and other
                // old-AOSP messengers choke loading it)
                if (e.isMms && msgParts.isEmpty() &&
                    (e.body.isBlank() || e.body == placeholderBody)) continue
                val convoAddrs = convoById[m.oldConvoId]?.entity?.addresses
                    ?.split("|")?.filter { it.isNotBlank() }.orEmpty()

                try {
                    if (!e.isMms) {
                        val targets = if (e.isMine)
                            e.address.split("|").filter { it.isNotBlank() }.ifEmpty { convoAddrs }
                        else listOf(e.address)
                        for (t in targets) {
                            val dupKey = e.date * 31 + e.body.hashCode()
                            if (dupKey in smsSeen) continue
                            smsSeen.add(dupKey)
                            if (e.locked) {
                                // locked rows are inserted alone: we need their id back
                                val u = resolver.insert(Telephony.Sms.CONTENT_URI, smsValues(t, e))
                                val sysId = u?.lastPathSegment?.toLongOrNull()
                                if (sysId != null) flagged.add(Triple(sysId, false, true))
                            } else {
                                smsBatch.add(smsValues(t, e))
                                if (smsBatch.size >= 100) flushSmsBatch()
                            }
                        }
                    } else {
                        val dupKey = (e.date / 1000) * 10 +
                            (if (e.isMine) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_INBOX)
                        if (dupKey in mmsSeen) continue
                        mmsSeen.add(dupKey)
                        val sysId = insertSystemMms(context, e, convoAddrs, msgParts, tempDir, own,
                            ::threadIdFor) { fileName ->
                            currentFile = fileName
                            reportWriting(done, total)
                        }
                        currentFile = ""
                        if (sysId != null && e.locked) flagged.add(Triple(sysId, true, true))
                    }
                } catch (_: Exception) {
                    // one bad message must not sink the restore
                }
            }
            flushSmsBatch()
            reportWriting(total, total, force = true)

            // ---- Phase 2: wipe the app database and re-derive it with the FIXED
            //      import — blanks filtered, groups computed correctly, subjects read ----
            progress.report(50, null)
            repo.db.elements().deleteAll()
            repo.db.parts().deleteAll()
            repo.db.messages().deleteAll()
            repo.db.conversations().deleteAll()
            repo.db.keywords().deleteAll()
            try {
                File(context.filesDir, "parts").listFiles()?.forEach { runCatching { it.delete() } }
            } catch (_: Exception) {}
            repo.syncSuspended = false
            val rebuilding = context.getString(
                io.github.theonionsarewatching.nova.R.string.restore_rebuilding)
            repo.importFromTelephony { pct -> progress.report(50 + pct * 45 / 100, rebuilding) }

            // ---- Phase 3: re-apply what only the app knows ----
            progress.report(95, context.getString(
                io.github.theonionsarewatching.nova.R.string.restore_finishing))
            for ((sysId, isMms, locked) in flagged) {
                if (!locked) continue
                repo.db.messages().byTelephonyId(sysId, isMms)?.let {
                    repo.db.messages().setLocked(it.id, true)
                }
            }
            for (m in scheduledLater) {
                try {
                    val addrs = m.entity.address.split("|").filter { it.isNotBlank() }
                        .ifEmpty { convoById[m.oldConvoId]?.entity?.addresses?.split("|")
                            ?.filter { a -> a.isNotBlank() }.orEmpty() }
                    if (addrs.isEmpty()) continue
                    val convo = repo.getOrCreateConversation(addrs)
                    repo.db.messages().insert(m.entity.copy(convoId = convo.id))
                } catch (_: Exception) {}
            }
            for (c in convos) {
                val addrs = c.entity.addresses.split("|").filter { it.isNotBlank() }
                    .filter { PhoneUtils.normalize(it) !in own }
                    .ifEmpty { c.entity.addresses.split("|").filter { it.isNotBlank() } }
                if (addrs.isEmpty()) continue
                val key = PhoneUtils.convoKey(addrs)
                val target = repo.db.conversations().byKey(key) ?: continue
                val e = c.entity
                if (e.pinned || e.archived || e.muted || e.notifBlocked || e.hidden ||
                    e.draft.isNotBlank() || e.customTone.isNotBlank() || e.vibrateMode != 0 ||
                    e.groupMode != target.groupMode
                ) {
                    repo.db.conversations().applyRestoredSettings(
                        target.id, e.pinned, e.archived, e.muted, e.notifBlocked,
                        e.hidden, e.draft, e.customTone, e.vibrateMode, e.groupMode
                    )
                }
            }
            for (k in keywords) repo.db.keywords().insert(KeywordEntity(keyword = k))

            tempDir.deleteRecursively()
            repo.rescheduleAllAlarms()
            ChangeBus.ping()
            progress.report(100, null)
            true
        } catch (_: Exception) {
            Repo.get(context).syncSuspended = false
            false
        }
    }

    /** Insert an MMS (pdu + addr rows + parts) into the system store.
     *  The addr rows are written the way our importer expects, so re-import
     *  groups everything correctly. Returns the new system id. */
    private fun insertSystemMms(
        context: Context,
        e: MessageEntity,
        convoAddrs: List<String>,
        msgParts: List<RestorePart>,
        tempDir: File,
        own: Set<String>,
        threadIdFor: (Collection<String>) -> Long,
        onFile: (String) -> Unit
    ): Long? {
        val resolver = context.contentResolver
        val dateSec = e.date / 1000

        val participants = if (e.isMine)
            e.address.split("|").filter { it.isNotBlank() }.ifEmpty { convoAddrs }
        else convoAddrs.ifEmpty { listOf(e.address) }
        val threadAddrs = participants.filter { PhoneUtils.normalize(it) !in own }
            .ifEmpty { participants }

        // column set mirrors what long-standing restore tools write, so strict
        // stock messengers (old-AOSP derivatives like Sonim's) accept the rows
        val v = android.content.ContentValues().apply {
            put(Telephony.Mms.DATE, dateSec)
            put(Telephony.Mms.DATE_SENT, dateSec)
            put(Telephony.Mms.MESSAGE_BOX,
                if (e.isMine) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_TYPE, if (e.isMine) 128 else 132)
            put(Telephony.Mms.MMS_VERSION, 18)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            put(Telephony.Mms.MESSAGE_CLASS, "personal")
            put(Telephony.Mms.PRIORITY, 129)
            put(Telephony.Mms.READ_REPORT, 129)
            put(Telephony.Mms.DELIVERY_REPORT, 129)
            put(Telephony.Mms.MESSAGE_ID, "R$dateSec${(1000..9999).random()}")
            put(Telephony.Mms.TRANSACTION_ID, "T$dateSec${(1000..9999).random()}")
            put(Telephony.Mms.TEXT_ONLY, if (msgParts.isEmpty()) 1 else 0)
            put(Telephony.Mms.THREAD_ID, threadIdFor(threadAddrs))
        }
        val mmsUri = resolver.insert(Telephony.Mms.CONTENT_URI, v) ?: return null
        val sysId = mmsUri.lastPathSegment?.toLongOrNull() ?: return null

        fun addr(address: String, type: Int) {
            try {
                resolver.insert(Uri.parse("content://mms/$sysId/addr"),
                    android.content.ContentValues().apply {
                        put("address", address)
                        put("type", type)
                        put("charset", 106)
                    })
            } catch (_: Exception) {}
        }
        if (e.isMine) {
            addr(own.firstOrNull() ?: "insert-address-token", 137)
            threadAddrs.forEach { addr(it, 151) }
        } else {
            addr(e.address, 137)
            threadAddrs.filter { PhoneUtils.normalize(it) != PhoneUtils.normalize(e.address) }
                .forEach { addr(it, 151) }
        }

        val placeholder = context.getString(io.github.theonionsarewatching.nova.R.string.mms_not_downloaded)
        if (e.body.isNotBlank() && e.body != placeholder) {
            try {
                resolver.insert(Uri.parse("content://mms/$sysId/part"),
                    android.content.ContentValues().apply {
                        put("mid", sysId)
                        put("ct", "text/plain")
                        put("text", e.body)
                        put("chset", 106)
                    })
            } catch (_: Exception) {}
        }
        for (p in msgParts) {
            try {
                onFile(p.name)
                val src = File(tempDir, p.storedName)
                if (!src.exists()) continue
                val partUri = resolver.insert(Uri.parse("content://mms/$sysId/part"),
                    android.content.ContentValues().apply {
                        put("mid", sysId)
                        put("ct", p.mime)
                        put("name", p.name)
                        put("cl", p.name)
                    }) ?: continue
                resolver.openOutputStream(partUri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
            } catch (_: Exception) {}
        }
        return sysId
    }


    /** JsonWriter/JsonReader close their stream; these keep the zip stream open across entries. */
    private class NonClosingOutputStream(private val inner: java.io.OutputStream) : java.io.OutputStream() {
        override fun write(b: Int) = inner.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = inner.write(b, off, len)
        override fun flush() = inner.flush()
        override fun close() { inner.flush() }
    }

    private class NonClosingInputStream(private val inner: java.io.InputStream) : java.io.InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() {}
    }
}
