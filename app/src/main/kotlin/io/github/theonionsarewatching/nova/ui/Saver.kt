package io.github.theonionsarewatching.nova.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Saver {

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
