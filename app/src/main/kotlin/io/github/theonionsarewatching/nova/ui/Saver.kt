package io.github.theonionsarewatching.nova.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Everything a user saves out of D-SMS goes into ONE folder, "D-SMS", with a
 * sub-folder per type:
 *
 *   D-SMS/Pictures     images
 *   D-SMS/Videos       videos
 *   D-SMS/Voice notes  recorded speech (amr, 3gp, aac, m4a, opus …, or a
 *                      name like Voice_… / rec… / ptt…)
 *   D-SMS/Audio        music-type audio (mp3, flac, wav, midi, ogg …)
 *   D-SMS/Contacts     vCards (.vcf), also the Settings contacts export
 *   D-SMS/Files        anything else
 *   D-SMS/Logs         the diagnostic log (Settings)
 *   D-SMS/Backups      message backups (.zip)
 *   D-SMS/Settings     settings-only files (.json)
 *
 * Where the D-SMS folder is:
 * - With "All files access" (Android 11+, MANAGE_EXTERNAL_STORAGE) or on
 *   Android 6–9 with storage permission: at the top of the phone storage,
 *   /storage/emulated/0/D-SMS/… (plain file write + media scan, so the
 *   Gallery and file managers see it at once).
 * - Otherwise (scoped storage, no all-files access): Download/D-SMS/… through
 *   MediaStore (backups too; older versions put backups in Download/D-SMS
 *   itself, Restore still looks there).
 * - "Save to SD card" on and a card present: the same layout on the card;
 *   phone storage when the card write fails.
 *
 * The functions return the folder as short plain text ("D-SMS/Pictures",
 * "Download/D-SMS/Videos", "SD card (D-SMS/Files)") for the Save toast, or
 * null when saving failed. They copy the file, so call them off the main
 * thread.
 */
object Saver {

    const val ROOT = "D-SMS"

    enum class Kind(val folder: String) {
        PICTURES("Pictures"), VIDEOS("Videos"), VOICE("Voice notes"), AUDIO("Audio"),
        CONTACTS("Contacts"), FILES("Files"), LOGS("Logs"),
        BACKUPS("Backups"), SETTINGS("Settings")
    }

    /** A new file opened for writing in a D-SMS folder (backups, settings):
     *  write to [out], then [publish]; [discard] removes it after a failure.
     *  [label] is the real folder, e.g. "D-SMS/Backups". */
    class Target(
        val out: java.io.OutputStream,
        val label: String,
        val discard: () -> Unit,
        val publish: () -> Unit
    )

    /** Where [kind] files go right now, same order as [save]/[openNew]. */
    fun currentFolder(context: Context, kind: Kind): String {
        val app = context.applicationContext
        val sub = "$ROOT/${kind.folder}"
        if (wantSd(app) && sdCardPresent(app)) {
            if (sdRoot(app) != null && canWriteDirect(app)) return app.getString(
                io.github.theonionsarewatching.nova.R.string.saved_to_sd, sub)
            if (Build.VERSION.SDK_INT < 29) return app.getString(
                io.github.theonionsarewatching.nova.R.string.saved_to_sd, sub)
            return app.getString(io.github.theonionsarewatching.nova.R.string.saved_to_sd,
                "${Environment.DIRECTORY_DOWNLOADS}/$sub")
        }
        return if (canWriteDirect(app)) sub else "${Environment.DIRECTORY_DOWNLOADS}/$sub"
    }

    private fun fileTarget(folder: File, name: String, mime: String, label: String, app: Context): Target? {
        return try {
            if (!folder.isDirectory && !folder.mkdirs()) return null
            var f = File(folder, name)
            var n = 1
            while (f.exists()) {
                f = File(folder, name.substringBeforeLast('.') + "_$n." + name.substringAfterLast('.')); n++
            }
            val dest = f
            Target(dest.outputStream(), label, discard = { runCatching { dest.delete() } }, publish = {
                android.media.MediaScannerConnection.scanFile(app, arrayOf(dest.absolutePath), arrayOf(mime), null)
            })
        } catch (_: Exception) { null }
    }

    private fun mediaStoreTarget(app: Context, name: String, mime: String, sub: String, volume: String?, label: String): Target? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = app.contentResolver
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$sub")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val coll = if (volume != null) MediaStore.Downloads.getContentUri(volume)
                else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(coll, values) ?: return null
            val out = try { resolver.openOutputStream(uri) } catch (_: Exception) { null }
            if (out == null) { runCatching { resolver.delete(uri, null, null) }; return null }
            Target(out, label, discard = { runCatching { resolver.delete(uri, null, null) } }, publish = {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            })
        } catch (_: Exception) { null }
    }

    /** Opens a new [kind] file in the D-SMS folder: SD card when that is on
     *  and works, else the top of storage (all-files access), else
     *  Download/D-SMS. Null when nothing can be written. */
    fun openNew(context: Context, fileName: String, mimeType: String, kind: Kind): Target? {
        val app = context.applicationContext
        val sub = kind.folder
        val sd = { p: String -> app.getString(io.github.theonionsarewatching.nova.R.string.saved_to_sd, p) }
        if (wantSd(app)) {
            val root = sdRoot(app)
            if (root != null && canWriteDirect(app))
                fileTarget(File(root, "$ROOT/$sub"), fileName, mimeType, sd("$ROOT/$sub"), app)?.let { return it }
            if (Build.VERSION.SDK_INT >= 29) {
                sdVolumeName(app)?.let { vol ->
                    mediaStoreTarget(app, fileName, mimeType, sub, vol,
                        sd("${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$sub"))?.let { return it }
                }
            } else sdAppDir(app)?.let { dir ->
                fileTarget(File(dir, "$ROOT/$sub"), fileName, mimeType, sd("$ROOT/$sub"), app)?.let { return it }
            }
        }
        if (canWriteDirect(app)) {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStorageDirectory()
            fileTarget(File(base, "$ROOT/$sub"), fileName, mimeType, "$ROOT/$sub", app)?.let { return it }
        }
        mediaStoreTarget(app, fileName, mimeType, sub, null,
            "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$sub")?.let { return it }
        if (Build.VERSION.SDK_INT < 29) {
            @Suppress("DEPRECATION")
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            fileTarget(File(dl, "$ROOT/$sub"), fileName, mimeType,
                "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$sub", app)?.let { return it }
        }
        return null
    }

    /** Folders that may hold [kind] files, new and old places, as (dir, label). */
    fun lookIn(context: Context, kind: Kind): List<Pair<File, String>> {
        val app = context.applicationContext
        val sub = kind.folder
        val out = ArrayList<Pair<File, String>>()
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStorageDirectory()
        @Suppress("DEPRECATION")
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        out.add(File(base, "$ROOT/$sub") to "$ROOT/$sub")
        out.add(File(dl, "$ROOT/$sub") to "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$sub")
        sdRoot(app)?.let { out.add(File(it, "$ROOT/$sub") to sdLabelOf(app, "$ROOT/$sub")) }
        sdAppDir(app)?.let { out.add(File(it, "$ROOT/$sub") to sdLabelOf(app, "$ROOT/$sub")) }
        // older versions wrote straight into Download/D-SMS
        out.add(File(dl, ROOT) to "${Environment.DIRECTORY_DOWNLOADS}/$ROOT")
        return out
    }

    private fun sdLabelOf(app: Context, p: String) =
        app.getString(io.github.theonionsarewatching.nova.R.string.saved_to_sd, p)

    private val MUSIC_MIMES = setOf(
        "audio/mpeg", "audio/mp3", "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav",
        "audio/wave", "audio/midi", "audio/x-midi", "audio/sp-midi", "audio/mid", "audio/ogg",
        "audio/x-ms-wma", "audio/imelody", "audio/x-imelody"
    )
    private val MUSIC_EXT = setOf("mp3", "flac", "wav", "mid", "midi", "ogg", "wma", "imy", "xmf", "rtttl")

    /** The type folder for a part. */
    fun kindFor(mimeType: String, fileName: String): Kind {
        val mime = mimeType.lowercase()
        val name = fileName.lowercase()
        val ext = name.substringAfterLast('.', "")
        return when {
            mime.contains("vcard") || ext == "vcf" -> Kind.CONTACTS
            mime.startsWith("image/") -> Kind.PICTURES
            mime.startsWith("video/") -> Kind.VIDEOS
            mime.startsWith("audio/") || mime.contains("qcelp") -> {
                val voiceName = name.startsWith("voice") || name.startsWith("rec") ||
                    name.startsWith("ptt") || name.contains("voice") || name.contains("memo")
                if (!voiceName && (mime in MUSIC_MIMES || ext in MUSIC_EXT)) Kind.AUDIO
                else Kind.VOICE
            }
            else -> Kind.FILES
        }
    }

    // ---- SD card support ----
    // "Save to SD card" (MMS settings): when the switch is on AND a removable
    // card is actually mounted, saves go to the card; otherwise they fall back
    // to phone storage (card removed after the setting was turned on must not
    // break saving).

    /** True when a removable, mounted SD card is available to write. */
    fun sdCardPresent(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 29) sdVolumeName(context) != null
        else sdAppDir(context) != null

    private fun sdVolumeName(context: Context): String? = try {
        MediaStore.getExternalVolumeNames(context)
            .firstOrNull { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }
    } catch (_: Exception) { null }

    private fun sdAppDir(context: Context): File? = try {
        context.getExternalFilesDirs(null).filterNotNull().firstOrNull {
            try {
                Environment.isExternalStorageRemovable(it) &&
                    Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
            } catch (_: Exception) { false }
        }
    } catch (_: Exception) { null }

    /** Android 11+: the card's root folder (needs all-files access to write). */
    private fun sdRoot(context: Context): File? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
            sm?.storageVolumes?.firstOrNull {
                it.isRemovable && it.state == Environment.MEDIA_MOUNTED
            }?.directory
        } catch (_: Exception) { null }
    }

    private fun wantSd(context: Context): Boolean =
        io.github.theonionsarewatching.nova.util.Prefs.get(context).saveToSd

    /** Plain file writes to shared storage are allowed. */
    private fun canWriteDirect(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == 29 -> false  // scoped storage, no legacy flag
        else -> context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Copy into [folder] with a free name (name_1.ext, name_2.ext …), scan it. */
    private fun copyInto(context: Context, src: File, folder: File, fileName: String, mimeType: String): Boolean {
        return try {
            if (!folder.isDirectory && !folder.mkdirs()) return false
            val clean = fileName.replace('/', '_').replace('\\', '_').ifBlank { "file" }
            var dest = File(folder, clean)
            var n = 1
            while (dest.exists()) {
                val stem = clean.substringBeforeLast('.', clean)
                val ext = clean.substringAfterLast('.', "")
                dest = File(folder, if (ext.isBlank()) "${stem}_$n" else "${stem}_$n.$ext")
                n++
            }
            src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(dest.absolutePath), arrayOf(mimeType), null
            )
            true
        } catch (_: Exception) { false }
    }

    /** Android 10+: write into Download/D-SMS/<type> on [volume] via MediaStore. */
    private fun mediaStoreDownloads(
        context: Context, src: File, fileName: String, mimeType: String, sub: String, volume: String?
    ): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val resolver = context.contentResolver
        var uri: android.net.Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$sub")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val coll = if (volume != null) MediaStore.Downloads.getContentUri(volume)
                else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            uri = resolver.insert(coll, values) ?: return false
            val ok = resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out, 64 * 1024) }; true
            } ?: false
            if (!ok) { runCatching { resolver.delete(uri, null, null) }; return false }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            true
        } catch (_: Exception) {
            uri?.let { u -> runCatching { resolver.delete(u, null, null) } }
            false
        }
    }

    /** Save [src] into its D-SMS type folder. Returns the folder for the
     *  toast, or null on failure. [kind] overrides the type from the mime. */
    fun save(context: Context, src: File, fileName: String, mimeType: String, kind: Kind? = null): String? {
        if (!src.isFile) return null
        val k = kind ?: kindFor(mimeType, fileName)
        val sub = k.folder
        val app = context.applicationContext
        val sdLabel = { path: String ->
            app.getString(io.github.theonionsarewatching.nova.R.string.saved_to_sd, path)
        }

        if (wantSd(app)) {
            val root = sdRoot(app)
            if (root != null && canWriteDirect(app) &&
                copyInto(app, src, File(root, "$ROOT/$sub"), fileName, mimeType)) {
                return sdLabel("$ROOT/$sub")
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val vol = sdVolumeName(app)
                if (vol != null && mediaStoreDownloads(app, src, fileName, mimeType, sub, vol)) {
                    return sdLabel("${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$sub")
                }
            } else {
                // Android 6–9: only the app's own area on the card is writable
                val dir = sdAppDir(app)
                if (dir != null && copyInto(app, src, File(dir, "$ROOT/$sub"), fileName, mimeType)) {
                    return sdLabel("$ROOT/$sub")
                }
            }
            // switch on but no card (or the card write failed): phone storage
        }

        if (canWriteDirect(app)) {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStorageDirectory()
            if (copyInto(app, src, File(base, "$ROOT/$sub"), fileName, mimeType)) return "$ROOT/$sub"
        }
        if (mediaStoreDownloads(app, src, fileName, mimeType, sub, null)) {
            return "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$sub"
        }
        if (Build.VERSION.SDK_INT < 29) {
            // no storage permission granted: try Download/D-SMS/<type> anyway
            @Suppress("DEPRECATION")
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (copyInto(app, src, File(dl, "$ROOT/$sub"), fileName, mimeType)) {
                return "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$sub"
            }
        }
        return null
    }
}
