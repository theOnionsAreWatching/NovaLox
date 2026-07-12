package io.github.theonionsarewatching.nova.data

import android.content.Context
import io.github.theonionsarewatching.nova.util.PhoneUtils
import android.net.Uri
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
            data class RPart(val oldMessageId: Long, val mime: String, val name: String, val storedName: String, val size: Long)
            data class REl(val oldMessageId: Long, val type: Int, val value: String)

            val convos = ArrayList<RConvo>()
            val messages = ArrayList<RMsg>()
            val parts = ArrayList<RPart>()
            val elements = ArrayList<REl>()
            val keywords = ArrayList<String>()

            val partsDir = File(context.filesDir, "parts").apply { mkdirs() }
            var sawData = false

            // pass: read the zip (data.json first entry by construction, but handle any order)
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
                                            parts.add(RPart(mid, mime, name, stored, size))
                                        }
                                        r.endArray()
                                    }
                                    "elements" -> {
                                        r.beginArray()
                                        while (r.hasNext()) {
                                            var mid = 0L; var type = 0; var value = ""
                                            r.beginObject()
                                            while (r.hasNext()) when (r.nextName()) {
                                                "messageId" -> mid = r.nextLong()
                                                "type" -> type = r.nextInt()
                                                "value" -> value = r.nextString()
                                                else -> r.skipValue()
                                            }
                                            r.endObject()
                                            elements.add(REl(mid, type, value))
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
                            val safe = File(name).name // strip any path tricks
                            progress.report(-1, safe)
                            val out = File(partsDir, safe)
                            out.outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return false

            if (!sawData || convos.isEmpty() && messages.isEmpty()) return false

            val insertTotal = (convos.size + messages.size + parts.size + elements.size).coerceAtLeast(1)
            var inserted = 0
            fun tick() {
                inserted++
                if (inserted % 50 == 0 || inserted >= insertTotal)
                    progress.report(inserted * 100 / insertTotal, null)
            }

            // wipe, then insert with id remapping
            repo.db.elements().deleteAll()
            repo.db.parts().deleteAll()
            repo.db.messages().deleteAll()
            repo.db.conversations().deleteAll()
            repo.db.keywords().deleteAll()

            val convoMap = HashMap<Long, Long>()
            for (c0 in convos) {
                // recompute the conversation key with the CURRENT normalization rules,
                // so restores also heal grouping bugs from older exports
                val c = c0.copy(entity = c0.entity.copy(
                    convoKey = PhoneUtils.convoKey(c0.entity.addresses.split("|").filter { it.isNotBlank() })
                ))
                val newId = repo.db.conversations().insert(c.entity)
                convoMap[c.oldId] = if (newId > 0) newId
                else repo.db.conversations().byKey(c.entity.convoKey)?.id ?: continue
                tick()
            }
            val msgMap = HashMap<Long, Long>()
            for (m in messages) {
                val cid = convoMap[m.oldConvoId] ?: continue
                msgMap[m.oldId] = repo.db.messages().insert(m.entity.copy(convoId = cid))
                tick()
            }
            for (p in parts) {
                val mid = msgMap[p.oldMessageId] ?: continue
                val f = File(partsDir, p.storedName)
                if (!f.exists()) continue
                repo.db.parts().insert(PartEntity(
                    messageId = mid, mimeType = p.mime, filePath = f.absolutePath,
                    fileName = p.name, size = if (p.size > 0) p.size else f.length()
                ))
                tick()
            }
            val elByMsg = elements.groupBy { it.oldMessageId }
            for ((oldMid, els) in elByMsg) {
                val mid = msgMap[oldMid] ?: continue
                repo.db.elements().insertAll(els.map { ElementEntity(messageId = mid, type = it.type, value = it.value) })
            }
            for (k in keywords) repo.db.keywords().insert(KeywordEntity(keyword = k))

            repo.rescheduleAllAlarms()
            ChangeBus.ping()
            true
        } catch (_: Exception) {
            false
        }
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
