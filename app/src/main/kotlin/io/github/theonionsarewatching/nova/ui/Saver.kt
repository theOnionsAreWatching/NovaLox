package io.github.theonionsarewatching.nova.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Saver {

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
