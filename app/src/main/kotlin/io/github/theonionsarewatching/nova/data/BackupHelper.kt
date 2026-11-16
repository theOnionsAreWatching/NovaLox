package io.github.theonionsarewatching.nova.data

import androidx.room.withTransaction
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

    // v2: + conversation customName, message deliveryDebug, keywords2 (full
    // keyword entities: mode / numbers / caseSensitive), broadcastCopies
    // registry, and restore-side re-application of per-recipient statuses.
    // Old readers still restore v2 backups: unknown names are skipped, and
    // "keywords" stays a plain string array alongside "keywords2".
    // v3: + "content" in data.json ("all" / "sms" / "mms"),
    // + manifest.json and settings.json zip entries. Old readers only look at
    // data.json and parts/ and skip unknown names, so they still restore v3
    // (an SMS-only or MMS-only backup simply has fewer messages in it).
    const val VERSION = 3

    /** What to put in a backup. */
    enum class Content(val id: String) {
        ALL("all"), SMS("sms"), MMS("mms");
        companion object {
            fun from(id: String?): Content = values().firstOrNull { it.id == id } ?: ALL
        }
    }

    /** Result shown to the user: [message] is short plain English. */
    data class Outcome(
        val ok: Boolean,
        val message: String? = null,
        val settingsApplied: Boolean = false,
        val cancelled: Boolean = false
    )

    /** A backup / settings file found on the phone. [where] is a short
     *  folder label like "D-SMS/Backups". */
    data class LocalBackup(val displayName: String, val uri: Uri, val where: String = "")

    /** Which files to look for. */
    enum class FileKind(val prefix: String, val ext: String) {
        BACKUP("nova-backup-", ".zip"), SETTINGS("nova-settings-", ".json")
    }

    /** Result of a search: what was found, plus where we looked. */
    data class Search(
        val files: List<LocalBackup>,
        val looked: List<String>,
        val unreadable: List<String>
    )

    private data class RestorePart(
        val oldMessageId: Long, val mime: String, val name: String,
        val storedName: String, val size: Long
    )

    /** percent 0..100 for determinate progress, -1 for "working, size unknown". */
    fun interface Progress {
        fun report(percent: Int, detail: String?)
    }

    private const val PAGE = 500
    const val FOLDER = "D-SMS"

    // ---------------------------------------------------------------- errors

    /** Turns an error into a short plain-English reason for the user. The
     *  technical detail goes to the diagnostic log only. */
    fun reasonFor(context: Context, t: Throwable, reading: Boolean): String {
        runCatching {
            io.github.theonionsarewatching.nova.util.DiagLog.log(
                context, "backup", "${if (reading) "restore" else "backup"} error: " +
                    "${t::class.java.simpleName}: ${t.message}"
            )
        }
        val msg = (t.message ?: "") + " " + (t.cause?.message ?: "")
        val res = when {
            t is OutOfMemoryError -> io.github.theonionsarewatching.nova.R.string.err_out_of_memory
            msg.contains("ENOSPC") || msg.contains("No space", true) ->
                io.github.theonionsarewatching.nova.R.string.err_no_space
            t is java.util.zip.ZipException || t is android.util.MalformedJsonException ||
                t is IllegalStateException && reading ->
                io.github.theonionsarewatching.nova.R.string.err_bad_file
            t is java.io.FileNotFoundException || t is SecurityException ->
                if (reading) io.github.theonionsarewatching.nova.R.string.err_cant_read
                else io.github.theonionsarewatching.nova.R.string.err_cant_write
            t is java.io.IOException ->
                if (reading) io.github.theonionsarewatching.nova.R.string.err_cant_read
                else io.github.theonionsarewatching.nova.R.string.err_cant_write
            else -> io.github.theonionsarewatching.nova.R.string.err_unknown
        }
        return context.getString(res)
    }

    // ---------------------------------------------------------------- settings file

    /** Writes the settings JSON to the D-SMS/Settings folder (Saver). */
    fun saveSettingsToDownloads(context: Context): Outcome {
        val name = io.github.theonionsarewatching.nova.util.SettingsBackup.fileName()
        var nd: io.github.theonionsarewatching.nova.ui.Saver.Target? = null
        return try {
            nd = io.github.theonionsarewatching.nova.ui.Saver.openNew(context, name, "application/json",
                io.github.theonionsarewatching.nova.ui.Saver.Kind.SETTINGS)
                ?: throw java.io.IOException("no folder")
            nd.out.use { io.github.theonionsarewatching.nova.util.SettingsBackup.write(context, it) }
            nd.publish()
            Outcome(true, context.getString(
                io.github.theonionsarewatching.nova.R.string.settings_saved_to, "${nd.label}/$name"))
        } catch (e: Exception) {
            nd?.discard?.invoke()
            Outcome(false, reasonFor(context, e, reading = false))
        } catch (e: OutOfMemoryError) {
            nd?.discard?.invoke()
            Outcome(false, reasonFor(context, e, reading = false))
        }
    }

    /** Applies a picked settings file (.json, or a backup .zip with settings inside). */
    fun restoreSettings(context: Context, uri: Uri): Outcome {
        return try {
            val parsed = io.github.theonionsarewatching.nova.util.SettingsBackup.readFromUri(context, uri)
                ?: return Outcome(false, context.getString(
                    io.github.theonionsarewatching.nova.R.string.err_not_settings_file))
            io.github.theonionsarewatching.nova.util.SettingsBackup.apply(context, parsed)
            Outcome(true, null, settingsApplied = true)
        } catch (e: Exception) {
            Outcome(false, reasonFor(context, e, reading = true))
        } catch (e: OutOfMemoryError) {
            Outcome(false, reasonFor(context, e, reading = true))
        }
    }

    // ---------------------------------------------------------------- export

    suspend fun export(
        context: Context, uri: Uri, content: Content = Content.ALL,
        progress: Progress = Progress { _, _ -> }
    ): Outcome {
        return try {
            val out = context.contentResolver.openOutputStream(uri)
                ?: return Outcome(false, context.getString(
                    io.github.theonionsarewatching.nova.R.string.err_cant_write))
            out.use { exportToStream(context, it, content, progress) }
            Outcome(true)
        } catch (e: Cancelled) {
            // Cancel: remove the half-written file the user picked
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        } catch (e: Exception) {
            Outcome(false, reasonFor(context, e, reading = false))
        } catch (e: OutOfMemoryError) {
            Outcome(false, reasonFor(context, e, reading = false))
        }
    }

    /** Thrown from a progress callback when the user pressed Cancel. */
    class Cancelled : Exception("cancelled")

    /** Writes the backup straight to D-SMS/Backups (Saver; no file picker). */
    suspend fun exportToDownloads(
        context: Context, content: Content = Content.ALL,
        progress: Progress = Progress { _, _ -> }
    ): Outcome {
        val name = "nova-backup-" + java.text.SimpleDateFormat(
            "yyyyMMdd-HHmm", java.util.Locale.US
        ).format(java.util.Date()) + ".zip"
        var nd: io.github.theonionsarewatching.nova.ui.Saver.Target? = null
        return try {
            nd = io.github.theonionsarewatching.nova.ui.Saver.openNew(context, name, "application/zip",
                io.github.theonionsarewatching.nova.ui.Saver.Kind.BACKUPS)
                ?: throw java.io.IOException("no folder")
            nd.out.use { exportToStream(context, it, content, progress) }
            nd.publish()
            Outcome(true, context.getString(
                io.github.theonionsarewatching.nova.R.string.backup_saved_to, "${nd.label}/$name"))
        } catch (e: Cancelled) {
            // Cancel: the half-written zip is removed
            nd?.discard?.invoke()
            throw e
        } catch (e: Exception) {
            nd?.discard?.invoke()
            Outcome(false, reasonFor(context, e, reading = false))
        } catch (e: OutOfMemoryError) {
            nd?.discard?.invoke()
            Outcome(false, reasonFor(context, e, reading = false))
        }
    }

    // ---------------------------------------------------------------- finding files

    /** Backups (or settings files) reachable without a file picker:
     *  - this app's own Download entries (MediaStore, Android 10+). After a
     *    phone reset these are NOT visible any more (they belong to the old
     *    install), so also:
     *  - a direct listing of D-SMS/Backups (or Settings) at the top of
     *    storage, the same under Download/D-SMS and on the SD card, the old
     *    Download/D-SMS and Download, which works when the
     *    app has all-files access (Android 11+) or on Android 9 and below. */
    fun findLocal(context: Context, kind: FileKind): Search {
        val out = ArrayList<LocalBackup>()
        @Suppress("DEPRECATION")
        val downloads = android.os.Environment
            .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        val dirs = io.github.theonionsarewatching.nova.ui.Saver.lookIn(context,
            if (kind == FileKind.BACKUP) io.github.theonionsarewatching.nova.ui.Saver.Kind.BACKUPS
            else io.github.theonionsarewatching.nova.ui.Saver.Kind.SETTINGS) + (downloads to "Download")
        // say where we looked: the folders that exist, plus today's folder
        val now = dirs.first().second
        val looked = dirs.filter { it.first.isDirectory || it.second == "Download" || it.second == now }
            .map { it.second }.distinct()
        val unreadable = ArrayList<String>()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                context.contentResolver.query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.Downloads._ID,
                        android.provider.MediaStore.Downloads.DISPLAY_NAME,
                        android.provider.MediaStore.Downloads.RELATIVE_PATH),
                    android.provider.MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                    arrayOf(kind.prefix + "%" + kind.ext),
                    android.provider.MediaStore.Downloads.DATE_ADDED + " DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        val rel = (c.getString(2) ?: "").trimEnd('/')
                        out.add(LocalBackup(name, android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                            rel.ifBlank { "Download" }))
                    }
                }
            } catch (_: Exception) {}
        }
        for ((dir, label) in dirs) {
            val files = try {
                dir.listFiles { f ->
                    f.isFile && f.name.startsWith(kind.prefix) && f.name.endsWith(kind.ext, true)
                }
            } catch (_: Exception) { null }
            if (files == null) {
                if (dir.exists() || label == "Download") unreadable.add(label)
                continue
            }
            for (f in files) {
                if (!f.canRead()) continue
                out.add(LocalBackup(f.name, Uri.fromFile(f), label))
            }
        }
        // newest first: the names carry yyyyMMdd-HHmm
        val list = out.distinctBy { it.displayName + "|" + it.where }
            .distinctBy { it.displayName }
            .sortedByDescending { it.displayName }
        return Search(list, looked, unreadable)
    }

    /**
     * Whole-phone search (restore step 2). Walks shared storage and any SD
     * card folder by folder, looking for D-SMS backups (or settings files).
     * A file counts when its NAME matches (nova-backup-*.zip /
     * nova-settings-*.json) or, cheaply, its CONTENT does: a .zip whose
     * central directory has manifest.json + data.json (backup) or
     * settings.json (settings), or a small .json that starts with the
     * settings format marker. Skips Android/data, Android/obb, hidden
     * folders and anything it cannot read. Runs on the caller's (IO) thread;
     * [cancelled] is checked between folders.
     */
    fun searchWholePhone(
        context: Context, kind: FileKind,
        cancelled: () -> Boolean,
        progress: (dirs: Int, found: Int, current: String) -> Unit
    ): List<LocalBackup> {
        val roots = LinkedHashSet<File>()
        @Suppress("DEPRECATION")
        roots.add(android.os.Environment.getExternalStorageDirectory())
        // SD cards: the app-specific dirs live under <volume>/Android/data/...
        try {
            context.getExternalFilesDirs(null).filterNotNull().forEach { d ->
                val path = d.absolutePath
                val cut = path.indexOf("/Android/data/")
                if (cut > 0) roots.add(File(path.substring(0, cut)))
            }
        } catch (_: Exception) {}
        val found = ArrayList<LocalBackup>()
        val seen = HashSet<String>()
        var dirs = 0
        for (root in roots) {
            val rootPath = root.absolutePath
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.addLast(root to 0)
            while (stack.isNotEmpty()) {
                if (cancelled()) return found
                val (dir, depth) = stack.removeLast()
                val canon = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
                if (!seen.add(canon)) continue        // symlink loops
                dirs++
                progress(dirs, found.size, dir.absolutePath.removePrefix(rootPath).ifEmpty { "/" })
                val children = try { dir.listFiles() } catch (_: Exception) { null } ?: continue
                for (f in children) {
                    val name = f.name
                    if (f.isDirectory) {
                        if (name.startsWith(".")) continue
                        if (depth == 0 && (name.equals("Android", true) || name == "LOST.DIR")) continue
                        if (depth < 14) stack.addLast(f to depth + 1)
                        continue
                    }
                    if (!f.isFile || !f.canRead()) continue
                    if (matchesKind(f, kind)) {
                        val where = (f.parentFile?.absolutePath ?: "").removePrefix(rootPath)
                            .trim('/').ifEmpty { root.name }
                        found.add(LocalBackup(name, Uri.fromFile(f), where))
                    }
                }
            }
        }
        // newest first (names carry the date); same name in two places: keep both
        return found.sortedByDescending { it.displayName }
    }

    private fun matchesKind(f: File, kind: FileKind): Boolean {
        val n = f.name.lowercase()
        if (n.startsWith(kind.prefix) && n.endsWith(kind.ext)) return true
        if (n.endsWith(".zip")) {
            if (f.length() < 64) return false
            return try {
                java.util.zip.ZipFile(f).use { z ->
                    if (kind == FileKind.BACKUP)
                        z.getEntry("manifest.json") != null && z.getEntry("data.json") != null
                    else z.getEntry(io.github.theonionsarewatching.nova.util.SettingsBackup.ZIP_ENTRY) != null
                }
            } catch (_: Exception) { false }
        }
        if (kind == FileKind.SETTINGS && n.endsWith(".json") && f.length() in 10..2_000_000) {
            return try {
                val head = f.inputStream().use { s ->
                    val b = ByteArray(256); val r = s.read(b); if (r > 0) String(b, 0, r) else ""
                }
                head.contains(io.github.theonionsarewatching.nova.util.SettingsBackup.FORMAT)
            } catch (_: Exception) { false }
        }
        return false
    }

    /** Kept for older callers: message backups only. */
    fun findLocalBackups(context: Context): List<LocalBackup> =
        findLocal(context, FileKind.BACKUP).files

    // ---------------------------------------------------------------- writing the zip

    /** Streams the backup: messages / parts / elements are read in pages of
     *  [PAGE] rows and written straight into the zip with JsonWriter, so even
     *  a huge history never sits in memory at once. Throws on failure. */
    private suspend fun exportToStream(
        context: Context, rawOut: java.io.OutputStream, content: Content,
        progress: Progress = Progress { _, _ -> }
    ) {
        val repo = Repo.get(context)
        progress.report(-1, null)
        val convos = repo.db.conversations().all()           // small: one row per chat
        val keywords = repo.db.keywords().all()
        val msgCount = when (content) {
            Content.ALL -> repo.db.messages().count()
            Content.SMS -> repo.db.messages().countOfKind(false)
            Content.MMS -> repo.db.messages().countOfKind(true)
        }
        val partCount = if (content == Content.SMS) 0 else repo.db.parts().count()
        val elemCount = repo.db.elements().count()
        val total = (convos.size + msgCount + partCount * 2 + elemCount).coerceAtLeast(1)
        var done = 0
        fun tick(n: Int = 1) {
            done += n
            if (done % 50 == 0 || done >= total) progress.report((done * 100L / total).toInt().coerceAtMost(100), null)
        }
        val kindId = when (content) { Content.ALL -> 0; Content.SMS -> 1; Content.MMS -> 2 }

        val zip = ZipOutputStream(rawOut.buffered(64 * 1024))

        // ---- manifest.json (new in v3; old readers ignore it) ----
        zip.putNextEntry(ZipEntry("manifest.json"))
        JsonWriter(OutputStreamWriter(NonClosingOutputStream(zip), Charsets.UTF_8)).apply {
            beginObject()
            name("format").value("nova-backup")
            name("version").value(VERSION.toLong())
            name("content").value(content.id)
            name("created").value(System.currentTimeMillis())
            name("app").value(try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (_: Exception) { "" })
            name("hasSettings").value(true)
            endObject()
            flush()
        }
        zip.closeEntry()

        // ---- data.json ----
        zip.putNextEntry(ZipEntry("data.json"))
        val w = JsonWriter(java.io.BufferedWriter(
            OutputStreamWriter(NonClosingOutputStream(zip), Charsets.UTF_8), 32 * 1024))
        w.beginObject()
        w.name("version").value(VERSION.toLong())
        w.name("content").value(content.id)

        w.name("conversations").beginArray()
        for (c in convos) {
            w.beginObject()
            w.name("id").value(c.id)
            w.name("convoKey").value(c.convoKey)
            w.name("addresses").value(c.addresses)
            w.name("cachedNames").value(c.cachedNames)
            w.name("cachedPhotoUri").value(c.cachedPhotoUri)
            w.name("isGroup").value(c.isGroup)
            w.name("groupMode").value(c.groupMode.toLong())
            w.name("customName").value(c.customName)
            w.name("snippet").value(c.snippet)
            w.name("snippetDate").value(c.snippetDate)
            w.name("snippetIsMine").value(c.snippetIsMine)
            w.name("unreadCount").value(c.unreadCount.toLong())
            w.name("pinned").value(c.pinned)
            w.name("archived").value(c.archived)
            w.name("muted").value(c.muted)
            w.name("notifBlocked").value(c.notifBlocked)
            w.name("hidden").value(c.hidden)
            w.name("draft").value(c.draft)
            w.name("customTone").value(c.customTone)
            w.name("vibrateMode").value(c.vibrateMode.toLong())
            w.endObject()
            tick()
        }
        w.endArray()

        w.name("messages").beginArray()
        var lastId = 0L
        while (true) {
            val page = when (content) {
                Content.ALL -> repo.db.messages().pageAfter(lastId, PAGE)
                Content.SMS -> repo.db.messages().pageAfterOfKind(lastId, false, PAGE)
                Content.MMS -> repo.db.messages().pageAfterOfKind(lastId, true, PAGE)
            }
            if (page.isEmpty()) break
            for (m in page) {
                w.beginObject()
                w.name("id").value(m.id)
                w.name("convoId").value(m.convoId)
                w.name("address").value(m.address)
                w.name("body").value(m.body)
                w.name("date").value(m.date)
                w.name("isMine").value(m.isMine)
                w.name("status").value(m.status.toLong())
                w.name("read").value(m.read)
                w.name("locked").value(m.locked)
                if (m.deletedAt != null) w.name("deletedAt").value(m.deletedAt)
                w.name("isMms").value(m.isMms)
                w.name("subId").value(m.subId.toLong())
                if (m.scheduledAt != null) w.name("scheduledAt").value(m.scheduledAt)
                w.name("blockedByKeyword").value(m.blockedByKeyword)
                w.name("recipientStatuses").value(m.recipientStatuses)
                if (m.deliveryDebug.isNotBlank()) w.name("deliveryDebug").value(m.deliveryDebug)
                w.name("elementsExtracted").value(m.elementsExtracted)
                if (m.telephonyId != null) w.name("telephonyId").value(m.telephonyId)
                w.name("telephonyIsMms").value(m.telephonyIsMms)
                w.endObject()
                tick()
            }
            lastId = page.last().id
            if (page.size < PAGE) break
        }
        w.endArray()

        w.name("parts").beginArray()
        if (content != Content.SMS) {
            lastId = 0L
            while (true) {
                val page = repo.db.parts().pageAfter(lastId, PAGE)
                if (page.isEmpty()) break
                for (p in page) {
                    w.beginObject()
                    w.name("messageId").value(p.messageId)
                    w.name("mimeType").value(p.mimeType)
                    w.name("fileName").value(p.fileName)
                    w.name("storedName").value(File(p.filePath).name)
                    w.name("size").value(p.size)
                    w.endObject()
                    tick()
                }
                lastId = page.last().id
                if (page.size < PAGE) break
            }
        }
        w.endArray()

        w.name("elements").beginArray()
        lastId = 0L
        while (true) {
            val page = repo.db.elements().pageAfter(lastId, kindId, PAGE)
            if (page.isEmpty()) break
            for (e in page) {
                w.beginObject()
                w.name("messageId").value(e.messageId)
                w.name("type").value(e.type.toLong())
                w.name("value").value(e.value)
                w.endObject()
                tick()
            }
            lastId = page.last().id
            if (page.size < PAGE) break
        }
        w.endArray()

        // legacy plain strings, so OLD app versions can still
        // restore this backup without choking on objects
        w.name("keywords").beginArray()
        for (k in keywords) w.value(k.keyword)
        w.endArray()

        // the full keyword entities: sender scope mode, listed
        // numbers, case sensitivity (lost by the legacy format)
        w.name("keywords2").beginArray()
        for (k in keywords) {
            w.beginObject()
            w.name("keyword").value(k.keyword)
            w.name("mode").value(k.mode.toLong())
            w.name("numbers").value(k.numbers)
            w.name("caseSensitive").value(k.caseSensitive)
            w.endObject()
        }
        w.endArray()

        // broadcast-copy registry: on a same-phone restore the
        // telephony rows survive, and the re-import needs these
        // entries or old fan-out copies come back as duplicates.
        // Cross-phone it's inert: SMS entries match on id AND
        // same-day date, which another phone's rows won't satisfy.
        w.name("broadcastCopies").value(
            io.github.theonionsarewatching.nova.util.Prefs.get(context).broadcastCopyMap)

        w.endObject()
        w.flush()
        zip.closeEntry()

        // ---- settings.json (new in v3) ----
        zip.putNextEntry(ZipEntry(io.github.theonionsarewatching.nova.util.SettingsBackup.ZIP_ENTRY))
        io.github.theonionsarewatching.nova.util.SettingsBackup.write(context, zip)
        zip.closeEntry()

        // ---- attachment files (second paged pass over parts) ----
        if (content != Content.SMS) {
            val seen = HashSet<String>()
            val buf = ByteArray(32 * 1024)
            lastId = 0L
            while (true) {
                val page = repo.db.parts().pageAfter(lastId, PAGE)
                if (page.isEmpty()) break
                for (p in page) {
                    tick()
                    val f = File(p.filePath)
                    if (!f.exists() || !seen.add(f.name)) continue
                    progress.report((done * 100L / total).toInt().coerceAtMost(100), f.name)
                    zip.putNextEntry(ZipEntry("parts/${f.name}"))
                    f.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            zip.write(buf, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
                lastId = page.last().id
                if (page.size < PAGE) break
            }
        }
        zip.finish()
        zip.flush()
    }

    /** Restores a backup zip. Handles all three kinds (both / SMS only /
     *  MMS only): Phase 1 only ADDS to the system store, and Phase 2 rebuilds
     *  the app database from the whole system store, so the kind that is not
     *  in the file is kept as it is on the phone. If the zip carries a
     *  settings.json, those settings are put back too. */
    /** [cancelled] is checked between messages while writing (Phase 1).
     *  On Cancel the writing stops, and the rest still runs (rebuild from
     *  the system store), so what was written shows up and nothing is
     *  half done; settings from the backup are then NOT applied. A re-run
     *  skips what is already there (duplicate checks below). */
    suspend fun restore(context: Context, uri: Uri, progress: Progress = Progress { _, _ -> },
                        cancelled: () -> Boolean = { false },
                        stopNotAllowed: () -> Unit = {}): Outcome {
        val repo = Repo.get(context)
        var stoppedAt = -1
        var settings: io.github.theonionsarewatching.nova.util.SettingsBackup.Parsed? = null
        var content = Content.ALL
        return try {
            progress.report(-1, null)
            data class RConvo(val oldId: Long, val entity: ConversationEntity)
            data class RMsg(val oldId: Long, val oldConvoId: Long, val entity: MessageEntity)

            val convos = ArrayList<RConvo>()
            val messages = ArrayList<RMsg>()
            val parts = ArrayList<RestorePart>()
            val keywords = ArrayList<String>()
            val keywords2 = ArrayList<KeywordEntity>()
            var restoredRegistry = ""

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
                                            var tone = ""; var vibMode = 0; var customName = ""
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
                                                "customName" -> customName = r.nextString()
                                                else -> r.skipValue()
                                            }
                                            r.endObject()
                                            convos.add(RConvo(id, ConversationEntity(
                                                convoKey = key, addresses = addrs, cachedNames = names,
                                                cachedPhotoUri = photo, isGroup = isGroup, groupMode = mode,
                                                customName = customName,
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
                                            var tId: Long? = null; var tMms = false; var dDebug = ""
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
                                                "deliveryDebug" -> dDebug = r.nextString()
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
                                                recipientStatuses = rStatuses, deliveryDebug = dDebug,
                                                elementsExtracted = extracted,
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
                                    "keywords2" -> {
                                        r.beginArray()
                                        while (r.hasNext()) {
                                            var kw = ""; var kMode = 0; var kNums = ""; var kCase = false
                                            r.beginObject()
                                            while (r.hasNext()) when (r.nextName()) {
                                                "keyword" -> kw = r.nextString()
                                                "mode" -> kMode = r.nextInt()
                                                "numbers" -> kNums = r.nextString()
                                                "caseSensitive" -> kCase = r.nextBoolean()
                                                else -> r.skipValue()
                                            }
                                            r.endObject()
                                            if (kw.isNotBlank()) keywords2.add(KeywordEntity(
                                                keyword = kw, mode = kMode, numbers = kNums,
                                                caseSensitive = kCase
                                            ))
                                        }
                                        r.endArray()
                                    }
                                    "broadcastCopies" -> restoredRegistry = r.nextString()
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                        } else if (name == io.github.theonionsarewatching.nova.util.SettingsBackup.ZIP_ENTRY) {
                            // small file; a bad one must not stop the message restore
                            settings = try {
                                io.github.theonionsarewatching.nova.util.SettingsBackup.read(zip)
                            } catch (_: Exception) { null }
                        } else if (name == "manifest.json") {
                            try {
                                val mr = JsonReader(InputStreamReader(NonClosingInputStream(zip), Charsets.UTF_8))
                                mr.beginObject()
                                while (mr.hasNext()) {
                                    if (mr.nextName() == "content") content = Content.from(mr.nextString())
                                    else mr.skipValue()
                                }
                                mr.endObject()
                            } catch (_: Exception) {}
                        } else if (name.startsWith("parts/") && !entry.isDirectory) {
                            val safe = File(name).name
                            File(tempDir, safe).outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return Outcome(false, context.getString(
                io.github.theonionsarewatching.nova.R.string.err_cant_read))

            runCatching {
                io.github.theonionsarewatching.nova.util.DiagLog.log(context, "backup",
                    "restore: content=${content.id} convos=${convos.size} msgs=${messages.size} " +
                        "parts=${parts.size} settings=${settings != null}")
            }
            if (!sawData) return Outcome(false, context.getString(
                io.github.theonionsarewatching.nova.R.string.err_bad_file))
            if (convos.isEmpty() && messages.isEmpty()) return Outcome(false, context.getString(
                io.github.theonionsarewatching.nova.R.string.err_empty_backup))

            val resolver = context.contentResolver
            val convoById = convos.associateBy { it.oldId }
            val partsByMsg = parts.groupBy { it.oldMessageId }

            // ---- identify this phone's own number(s), including from the backup
            //      itself: in a legacy 2-address conversation with received messages,
            //      the address that is never a sender anywhere is our own number ----
            val receivedSenders = messages.filter { !it.entity.isMine }
                .map { PhoneUtils.normalize(it.entity.address) }.toHashSet()
            val ownGuesses = HashSet<String>()
            // one pass instead of scanning every message once
            // PER conversation (was conversations x messages)
            val convosWithReceived = messages.asSequence().filter { !it.entity.isMine }
                .map { it.oldConvoId }.toHashSet()
            for (c in convos) {
                val addrs = c.entity.addresses.split("|").filter { it.isNotBlank() }
                if (addrs.size != 2) continue
                val hasReceived = c.oldId in convosWithReceived
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
            // MMS duplicates. The system store only has second-precision dates
            // for MMS, so (second, box) is NOT unique: two MMS sent in the same
            // second used to collapse into one and the second was skipped.
            // Now: count how many system rows exist per (second, box), and
            // treat a backup MMS as "already there" only while that count is
            // not used up. Inside the backup itself, exact repeats are caught
            // with a stronger key (full date, box, body, part names).
            val mmsSystemCount = HashMap<Long, Int>()
            try {
                resolver.query(Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val k = c.getLong(0) * 10 + c.getInt(1)
                        mmsSystemCount[k] = (mmsSystemCount[k] ?: 0) + 1
                    }
                }
            } catch (_: Exception) {}
            val mmsSystemUsed = HashMap<Long, Int>()
            val mmsBackupSeen = HashSet<String>()

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
                // fewer screen updates (each one is a string
                // build + a post to the main thread)
                // restore speed: at most every 300 ms
                if (!force && now - lastReport < 300) return
                lastReport = now
                var detail = context.getString(
                    io.github.theonionsarewatching.nova.R.string.restore_writing_detail, done, total)
                if (currentFile.isNotBlank()) detail += " \u00B7 " + currentFile
                // ONE continuous bar for the whole restore —
                // writing 0-50, rebuilding 50-88, chat summaries 88-94,
                // locks/settings 94-99, 100 only when everything is done
                progress.report((done * 50L / total).toInt().coerceIn(0, 50), detail)
            }

            repo.syncSuspended = true
            val tStart = System.currentTimeMillis()
            var tPhase1 = tStart
            // app-only knowledge to re-apply after the re-import, keyed by the
            // system row id each message was written to
            data class RFlag(
                val sysId: Long, val isMms: Boolean, val locked: Boolean,
                val status: Int, val rStatuses: String, val dDebug: String
            )
            val flagged = ArrayList<RFlag>()
            data class PendingFlag(val key: String, val flag: RFlag)
            val pendingFlags = ArrayList<PendingFlag>()
            fun smsKey(date: Long, address: String, body: String) = "$date|$address|${body.hashCode()}"
            fun needsTracking(e: MessageEntity): Boolean =
                e.locked || e.recipientStatuses.isNotBlank() || e.deliveryDebug.isNotBlank() ||
                    e.status == MsgStatus.DELIVERED || e.status == MsgStatus.READ_BY_RECIPIENT ||
                    e.status == MsgStatus.FAILED
            val scheduledLater = ArrayList<RMsg>()
            val total = messages.size.coerceAtLeast(1)
            var done = 0
            val smsBatch = ArrayList<android.content.ContentValues>(512)
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
            // "Note to self" notes never go into the system SMS
            // store (they were never sent); they are put back in Phase 3
            val notesOldIds = convos.filter { NotesChat.isNotes(it.entity) }.map { it.oldId }.toHashSet()
            val notesFromBackup = ArrayList<NotesChat.NoteIn>()
            for (m in messages) {
                if (cancelled()) { stoppedAt = done; break }
                done++
                reportWriting(done, total)
                val e = m.entity
                if (m.oldConvoId in notesOldIds) {
                    // its attachment files were unpacked into tempDir; they are
                    // moved (not copied) into parts/notes/ in Phase 3
                    notesFromBackup.add(NotesChat.NoteIn(e, partsByMsg[m.oldId].orEmpty().map { p ->
                        NotesChat.NotePart(p.mime, p.name, File(tempDir, File(p.storedName).name))
                    }))
                    continue
                }
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
                        var firstRow = true
                        for (t in targets) {
                            val dupKey = e.date * 31 + e.body.hashCode()
                            if (dupKey in smsSeen) continue
                            smsSeen.add(dupKey)
                            // restore speed: rows with app-only state
                            // (locked, per-recipient statuses, delivery
                            // trail, Delivered/Read/Failed) used to be
                            // inserted ONE BY ONE to learn their id. Now they
                            // go in the batch too; their ids are looked up
                            // in one query after Phase 1 (date+address+body).
                            if (firstRow && needsTracking(e)) {
                                pendingFlags.add(PendingFlag(smsKey(e.date, t, e.body), RFlag(
                                    0L, false, e.locked, e.status,
                                    e.recipientStatuses, e.deliveryDebug)))
                            } else if (e.locked) {
                                // additional fan-out copies of a locked send:
                                // each row's re-imported message keeps the lock
                                pendingFlags.add(PendingFlag(smsKey(e.date, t, e.body),
                                    RFlag(0L, false, true, 0, "", "")))
                            }
                            smsBatch.add(smsValues(t, e))
                            if (smsBatch.size >= 500) flushSmsBatch()
                            firstRow = false
                        }
                    } else {
                        val box = if (e.isMine) Telephony.Mms.MESSAGE_BOX_SENT
                            else Telephony.Mms.MESSAGE_BOX_INBOX
                        val exactKey = "${e.date}|$box|${e.address}|${e.body.hashCode()}|" +
                            msgParts.joinToString(",") { it.storedName }
                        if (!mmsBackupSeen.add(exactKey)) continue      // repeated inside the backup
                        val dupKey = (e.date / 1000) * 10 + box
                        val have = mmsSystemCount[dupKey] ?: 0
                        val used = mmsSystemUsed[dupKey] ?: 0
                        if (used < have) {                               // already on the phone
                            mmsSystemUsed[dupKey] = used + 1
                            continue
                        }
                        val sysId = insertSystemMms(context, e, convoAddrs, msgParts, tempDir, own,
                            ::threadIdFor) { fileName ->
                            currentFile = fileName
                            reportWriting(done, total)
                        }
                        currentFile = ""
                        if (sysId != null && needsTracking(e)) flagged.add(RFlag(
                            sysId, true, e.locked, e.status,
                            e.recipientStatuses, e.deliveryDebug
                        ))
                    }
                } catch (_: Exception) {
                    // one bad message must not sink the restore
                } catch (_: OutOfMemoryError) {
                    // nor one huge attachment
                }
            }
            flushSmsBatch()
            // from here on the app database is rebuilt: no stopping halfway
            stopNotAllowed()
            if (stoppedAt < 0) reportWriting(total, total, force = true)
            tPhase1 = System.currentTimeMillis()
            // the batched flagged SMS: find their system ids in ONE query
            if (pendingFlags.isNotEmpty()) {
                val minDate = pendingFlags.minOf { it.key.substringBefore('|').toLong() }
                val maxDate = pendingFlags.maxOf { it.key.substringBefore('|').toLong() }
                val idsByKey = HashMap<String, ArrayDeque<Long>>()
                try {
                    resolver.query(Telephony.Sms.CONTENT_URI,
                        arrayOf(Telephony.Sms._ID, Telephony.Sms.DATE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
                        "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?",
                        arrayOf(minDate.toString(), maxDate.toString()),
                        "${Telephony.Sms._ID} ASC")?.use { c ->
                        while (c.moveToNext()) {
                            val k = smsKey(c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "")
                            idsByKey.getOrPut(k) { ArrayDeque() }.addLast(c.getLong(0))
                        }
                    }
                } catch (_: Exception) {}
                // newest rows with that key are the ones just written
                for (pf in pendingFlags.asReversed()) {
                    val id = idsByKey[pf.key]?.removeLastOrNull() ?: continue
                    flagged.add(pf.flag.copy(sysId = id))
                }
            }

            // ---- Phase 2: wipe the app database and re-derive it with the FIXED
            //      import — blanks filtered, groups computed correctly, subjects read ----
            // own text (the old "1160 of 1160" stayed on screen here); the bar
            // stays put at 50 instead of going back to empty
            progress.report(50, context.getString(
                io.github.theonionsarewatching.nova.R.string.restore_finishing_prep))
            // notes already on this phone survive the wipe below
            val phoneNotes = NotesChat.snapshot(repo.db)
            repo.db.elements().deleteAll()
            repo.db.parts().deleteAll()
            repo.db.messages().deleteAll()
            repo.db.conversations().deleteAll()
            repo.db.keywords().deleteAll()
            try {
                // files only: notes' attachments live in parts/notes/ and stay
                File(context.filesDir, "parts").listFiles()?.forEach { if (it.isFile) runCatching { it.delete() } }
            } catch (_: Exception) {}
            repo.syncSuspended = false
            // the copy registry must be live BEFORE the re-import scans the
            // system store: on a same-phone restore the old fan-out rows are
            // still there, and without their entries every broadcast copy
            // becomes a duplicate 1:1 message
            if (restoredRegistry.isNotBlank()) {
                io.github.theonionsarewatching.nova.util.BroadcastCopies
                    .restoreMerge(context, restoredRegistry)
            }
            var lastStep = 0L
            repo.importFromTelephony(onProgress = { }) { phase, n, m ->
                val now = System.currentTimeMillis()
                if (now - lastStep < 400 && n < m) return@importFromTelephony
                lastStep = now
                val mm = m.coerceAtLeast(1)
                when (phase) {
                    0 -> progress.report(50, context.getString(
                        io.github.theonionsarewatching.nova.R.string.restore_checking_sent, n, m))
                    1 -> progress.report(50 + (n * 38L / mm).toInt().coerceIn(0, 38),
                        context.getString(
                            io.github.theonionsarewatching.nova.R.string.restore_rebuilding, n, m))
                    else -> progress.report(88 + (n * 6L / mm).toInt().coerceIn(0, 6),
                        context.getString(
                            io.github.theonionsarewatching.nova.R.string.restore_updating_chats, n, m))
                }
            }

            val tPhase2 = System.currentTimeMillis()
            // ---- Phase 3: re-apply what only the app knows ----
            val finishing = context.getString(
                io.github.theonionsarewatching.nova.R.string.restore_finishing)
            progress.report(94, finishing)
            var fDone = 0
            // every write below used to be its own database
            // transaction (one disk sync each); now they share ONE
            repo.db.withTransaction {
            for (f in flagged) {
                fDone++
                if (fDone % 200 == 0) progress.report(94 + fDone * 4 / flagged.size, finishing)
                val row = repo.db.messages().byTelephonyId(f.sysId, f.isMms) ?: continue
                if (f.locked) repo.db.messages().setLocked(row.id, true)
                if (f.rStatuses.isNotBlank()) {
                    repo.db.messages().setRecipientStatuses(row.id, f.rStatuses)
                }
                if (f.dDebug.isNotBlank() && row.deliveryDebug.isBlank()) {
                    repo.db.messages().appendDeliveryDebug(row.id, f.dDebug)
                }
                // the re-import derives Sent from the system box; lift it back
                // to the richer state the backup knew (Delivered / Read /
                // Failed) — only upward from Sent, never stomping anything
                // the re-import itself learned
                if (row.isMine && row.status == MsgStatus.SENT && f.status in intArrayOf(
                        MsgStatus.DELIVERED, MsgStatus.READ_BY_RECIPIENT, MsgStatus.FAILED
                    )
                ) {
                    repo.db.messages().setStatus(row.id, f.status)
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
            // notes: the phone's own plus the backup's, exact repeats once;
            // the chat's settings come from the backup when it has them
            val backupNotesConvo = convos.firstOrNull { NotesChat.isNotes(it.entity) }?.entity
            NotesChat.putBack(context, repo.db, backupNotesConvo ?: phoneNotes?.first,
                (phoneNotes?.second ?: emptyList()) + notesFromBackup)
            repo.db.conversations().byKey(NotesChat.KEY)?.let { repo.refreshConversation(it.id) }
            for (c in convos) {
                if (NotesChat.isNotes(c.entity)) continue
                val addrs = c.entity.addresses.split("|").filter { it.isNotBlank() }
                    .filter { PhoneUtils.normalize(it) !in own }
                    .ifEmpty { c.entity.addresses.split("|").filter { it.isNotBlank() } }
                if (addrs.isEmpty()) continue
                val key = PhoneUtils.convoKey(addrs)
                val target = repo.db.conversations().byKey(key) ?: continue
                val e = c.entity
                if (e.customName.isNotBlank()) {
                    repo.db.conversations().setCustomName(target.id, e.customName)
                }
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
            if (keywords2.isNotEmpty()) {
                // v2 backups carry the full entities (sender scope, listed
                // numbers, case sensitivity); the plain-string list is only
                // there for OLD app versions reading this backup
                for (k in keywords2) repo.db.keywords().insert(k)
            } else {
                for (k in keywords) repo.db.keywords().insert(KeywordEntity(keyword = k))
            }
            }   // withTransaction

            progress.report(98, finishing)
            runCatching {
                val tEnd = System.currentTimeMillis()
                io.github.theonionsarewatching.nova.util.DiagLog.log(context, "backup",
                    "restore timing: write ${(tPhase1 - tStart) / 1000.0}s, " +
                        "rebuild ${(tPhase2 - tPhase1) / 1000.0}s, finish ${(tEnd - tPhase2) / 1000.0}s, " +
                        "msgs=${messages.size} flaggedSms=${pendingFlags.size}")
            }
            tempDir.deleteRecursively()
            repo.rescheduleAllAlarms()
            ChangeBus.ping()

            // settings saved inside the backup (v3+)
            var applied = false
            if (stoppedAt >= 0) {
                progress.report(100, null)
                return Outcome(true, context.getString(
                    io.github.theonionsarewatching.nova.R.string.restore_cancelled, stoppedAt, messages.size),
                    cancelled = true)
            }
            settings?.let {
                applied = try {
                    io.github.theonionsarewatching.nova.util.SettingsBackup.apply(context, it); true
                } catch (_: Exception) { false }
            }
            progress.report(100, null)
            Outcome(true, null, settingsApplied = applied)
        } catch (e: Exception) {
            Repo.get(context).syncSuspended = false
            runCatching { File(context.cacheDir, "restore_parts").deleteRecursively() }
            Outcome(false, reasonFor(context, e, reading = true))
        } catch (e: OutOfMemoryError) {
            Repo.get(context).syncSuspended = false
            runCatching { File(context.cacheDir, "restore_parts").deleteRecursively() }
            Outcome(false, reasonFor(context, e, reading = true))
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
