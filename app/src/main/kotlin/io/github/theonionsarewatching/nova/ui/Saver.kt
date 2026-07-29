package io.github.theonionsarewatching.nova.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Saver {

    // ---- SD card support ----
    // "Save to SD card" (MMS settings): when the switch is on AND a removable
    // card is actually mounted, saves go to the card; otherwise they fall back
    // to phone storage exactly as before (the spec: card removed after the
    // setting was turned on must not break saving).
    //
    // API 29+: MediaStore exposes the card as a second volume — full public
    // Pictures/Movies/Music/Download on the card.
    // API <29: apps can only write the card's app-specific area
    // (Android/data/<pkg>/files/...); files are media-scanned so the Gallery
    // and file managers still see them.

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

    private fun wantSd(context: Context): Boolean =
        io.github.theonionsarewatching.nova.util.Prefs.get(context).saveToSd

    /** Pre-Q SD write: app-specific dir on the card + media scan. Returns the
     *  location label, or null so the caller falls back to phone storage. */
    private fun saveToSdLegacy(
        context: Context, src: File, fileName: String, mimeType: String, dir: String, label: String
    ): String? {
        val root = sdAppDir(context) ?: return null
        return try {
            val folder = File(root, "$dir/NovaLox").apply { mkdirs() }
            var dest = File(folder, fileName)
            var n = 1
            while (dest.exists()) {
                val stem = fileName.substringBeforeLast('.', fileName)
                val ext = fileName.substringAfterLast('.', "")
                dest = File(folder, if (ext.isBlank()) "${stem}_$n" else "${stem}_$n.$ext")
                n++
            }
            src.copyTo(dest, overwrite = false)
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(dest.absolutePath), arrayOf(mimeType), null
            )
            context.getString(io.github.theonionsarewatching.nova.R.string.saved_to_sd, label)
        } catch (_: Exception) { null }
    }

    /** Save an attachment where the right app finds it: pictures and videos go
     *  into the Gallery's collections (Pictures/Movies), audio into Music, and
     *  everything else into Downloads. Returns the human-readable location, or
     *  null on failure. */
    fun save(context: Context, src: File, fileName: String, mimeType: String): String? {
        if (!src.exists()) return null
        val (collection, dir, label) = when {
            mimeType.startsWith("image/") -> Triple(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES, "Pictures")
            mimeType.startsWith("video/") -> Triple(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES, "Movies")
            mimeType.startsWith("audio/") -> Triple(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MUSIC, "Music")
            else -> return if (saveToDownloads(context, src, fileName, mimeType)) "Downloads" else null
        }
        if (wantSd(context)) {
            if (Build.VERSION.SDK_INT >= 29) {
                val vol = sdVolumeName(context)
                if (vol != null) {
                    try {
                        val sdCollection = when {
                            mimeType.startsWith("image/") -> MediaStore.Images.Media.getContentUri(vol)
                            mimeType.startsWith("video/") -> MediaStore.Video.Media.getContentUri(vol)
                            else -> MediaStore.Audio.Media.getContentUri(vol)
                        }
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/NovaLox")
                        }
                        val uri = context.contentResolver.insert(sdCollection, values)
                        if (uri != null) {
                            val ok = context.contentResolver.openOutputStream(uri)?.use { out ->
                                src.inputStream().use { it.copyTo(out) }; true
                            } ?: false
                            if (ok) return context.getString(
                                io.github.theonionsarewatching.nova.R.string.saved_to_sd, label)
                        }
                    } catch (_: Exception) { /* fall through to phone storage */ }
                }
            } else {
                saveToSdLegacy(context, src, fileName, mimeType, dir, label)?.let { return it }
            }
            // switch on but no card (or the card write failed): phone storage
        }
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/NovaLox")
                }
                val uri = context.contentResolver.insert(collection, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: return null
                label
            } else {
                @Suppress("DEPRECATION")
                val base = Environment.getExternalStoragePublicDirectory(dir)
                val folder = File(base, "NovaLox").apply { mkdirs() }
                var dest = File(folder, fileName)
                var n = 1
                while (dest.exists()) {
                    val stem = fileName.substringBeforeLast('.', fileName)
                    val ext = fileName.substringAfterLast('.', "")
                    dest = File(folder, if (ext.isBlank()) "${stem}_$n" else "${stem}_$n.$ext")
                    n++
                }
                src.copyTo(dest, overwrite = false)
                // tell the media scanner so the Gallery sees it immediately
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(dest.absolutePath), arrayOf(mimeType), null
                )
                label
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Save a stored attachment to the user's Downloads/D-SMS folder. */
    fun saveToDownloads(context: Context, src: File, fileName: String, mimeType: String): Boolean {
        if (!src.exists()) return false
        if (wantSd(context)) {
            if (Build.VERSION.SDK_INT >= 29) {
                val vol = sdVolumeName(context)
                if (vol != null) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, mimeType)
                            put(MediaStore.Downloads.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS + "/D-SMS")
                        }
                        val uri = context.contentResolver.insert(
                            MediaStore.Downloads.getContentUri(vol), values
                        )
                        if (uri != null) {
                            val ok = context.contentResolver.openOutputStream(uri)?.use { out ->
                                src.inputStream().use { it.copyTo(out) }; true
                            } ?: false
                            if (ok) return true
                        }
                    } catch (_: Exception) { /* fall through to phone storage */ }
                }
            } else {
                if (saveToSdLegacy(context, src, fileName, mimeType,
                        Environment.DIRECTORY_DOWNLOADS, "Download") != null) return true
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/D-SMS")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: return false
                true
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dir = File(downloads, "D-SMS").apply { mkdirs() }
                var dest = File(dir, fileName)
                var n = 1
                while (dest.exists()) {
                    val base = fileName.substringBeforeLast('.', fileName)
                    val ext = fileName.substringAfterLast('.', "")
                    dest = File(dir, if (ext.isBlank()) "${base}_$n" else "${base}_$n.$ext")
                    n++
                }
                src.copyTo(dest, overwrite = false)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
