package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.net.Uri
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import io.github.theonionsarewatching.nova.R
import java.io.File

/**
 * A tiny D-pad file browser, used when the phone has no system file picker.
 * A plain list popup: "Up a folder", then folders, then matching files.
 * Starts in Download; can go up to the phone storage root but no higher.
 * Picking a file calls [onPick] with a file:// Uri and the file name.
 */
object FileBrowserDialog {

    fun show(
        activity: Activity,
        suffixes: List<String>,
        onPick: (Uri, String) -> Unit,
        start: File? = null
    ) {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        @Suppress("DEPRECATION")
        val dir = start ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        open(activity, root, dir, suffixes, onPick)
    }

    private fun open(
        activity: Activity, root: File, dir: File,
        suffixes: List<String>, onPick: (Uri, String) -> Unit
    ) {
        if (activity.isFinishing) return
        val listed = try { dir.listFiles() } catch (_: Exception) { null }
        val label = labelFor(activity, root, dir)
        val canGoUp = dir.absolutePath != root.absolutePath && dir.parentFile != null

        val entries = ArrayList<Pair<String, () -> Unit>>()
        if (canGoUp) entries.add(activity.getString(R.string.browse_up) to {
            open(activity, root, dir.parentFile!!, suffixes, onPick)
        })
        if (listed != null) {
            listed.filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
                .forEach { d -> entries.add("${d.name}/" to { open(activity, root, d, suffixes, onPick) }) }
            listed.filter { f -> f.isFile && suffixes.any { f.name.endsWith(it, ignoreCase = true) } }
                .sortedByDescending { it.name }
                .forEach { f -> entries.add(f.name to { onPick(Uri.fromFile(f), f.name) }) }
        }

        val b = DsmsAlertBuilder(activity)
            .setTitle(label)
            .setNegativeButton(android.R.string.cancel, null)
        val nothing = listed == null ||
            entries.size == (if (canGoUp) 1 else 0)
        if (nothing) {
            val msg = if (listed == null) activity.getString(R.string.browse_cant_open, label)
                else activity.getString(R.string.browse_empty, label)
            if (canGoUp) {
                b.setMessage(msg)
                b.setPositiveButton(R.string.browse_up) { _, _ ->
                    open(activity, root, dir.parentFile!!, suffixes, onPick)
                }
            } else b.setMessage(msg)
        } else {
            b.setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
        }
        b.show()
    }

    private fun labelFor(activity: Activity, root: File, dir: File): String {
        val r = root.absolutePath
        val d = dir.absolutePath
        return when {
            d == r -> activity.getString(R.string.browse_phone_storage)
            d.startsWith("$r/") -> d.removePrefix("$r/")
            else -> d
        }
    }
}
