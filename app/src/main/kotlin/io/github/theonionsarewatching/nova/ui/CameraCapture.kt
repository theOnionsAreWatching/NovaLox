package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.appcompat.app.AlertDialog
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.util.AttachLimits
import java.io.File

/**
 * Attach > Camera: a small in-app "Photo / Video" choice, then the phone's
 * own camera app (ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE) writing into
 * our staging folder through the FileProvider. No CAMERA permission is needed
 * for this intent route (and the app must NOT declare it: declaring it would
 * make the capture intents require it).
 *
 * The pending output file is kept in prefs, not in the activity, so a
 * result still lands if the phone killed D-SMS while the camera was open.
 */
object CameraCapture {
    const val REQ = 241

    private const val PREFS = "camera_capture"
    private const val K_PATH = "path"
    private const val K_MIME = "mime"

    /** Shows Photo / Video, then opens the camera. [pending] = staged items
     *  (path, mime, name), for the 10-per-message and one-video limits. */
    fun choose(activity: Activity, pending: List<Triple<String, String, String>>) {
        if (pending.size >= AttachLimits.MAX_ITEMS) {
            notice(activity, activity.getString(R.string.camera_full, AttachLimits.MAX_ITEMS)); return
        }
        val labels = arrayOf(
            activity.getString(R.string.attach_menu_photo),
            activity.getString(R.string.attach_menu_video)
        )
        // same small centered pop-up as the Attach list; list rows click on key-up
        val d = Dialogs.centeredItems(
            DsmsAlertBuilder(activity)
                .setCustomTitle(Dialogs.title(activity, activity.getString(R.string.attach_menu_camera))),
            labels
        ) { which ->
            if (which == 1 && pending.any { it.second.startsWith("video/") }) {
                notice(activity, activity.getString(R.string.camera_one_video))
            } else start(activity, video = which == 1)
        }.create()
        // final width BEFORE the first frame (no jump)
        Dialogs.narrowToLabels(d, labels)
        d.show()
    }

    private fun start(activity: Activity, video: Boolean) {
        val out: File
        val intent: Intent
        try {
            val dir = File(activity.filesDir, "parts").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            out = File(dir, (if (video) "Video_" else "Photo_") + stamp + (if (video) ".mp4" else ".jpg"))
            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity, activity.packageName + ".fileprovider", out)
            intent = Intent(if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                // some camera apps only get access through the clip data
                clipData = android.content.ClipData.newRawUri("output", uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            notice(activity, activity.getString(R.string.camera_failed)); return
        }
        prefs(activity).edit()
            .putString(K_PATH, out.absolutePath)
            .putString(K_MIME, if (video) "video/mp4" else "image/jpeg")
            .apply()
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ)
        } catch (_: Exception) {
            // ActivityNotFound (no camera app) or a camera app that refuses
            clear(activity)
            notice(activity, activity.getString(R.string.camera_none))
        }
    }

    /** Call from onActivityResult for [REQ]. [add] stages a ready file
     *  (path, mime, name); [fromUri] stages a content uri through the normal
     *  attach path (camera apps that ignore our output file and return a uri). */
    fun onResult(
        activity: Activity, resultCode: Int, data: Intent?,
        pending: List<Triple<String, String, String>>,
        add: (String, String, String) -> Unit,
        fromUri: (Uri) -> Unit
    ) {
        val p = prefs(activity)
        val path = p.getString(K_PATH, null)
        val mime = p.getString(K_MIME, null) ?: "image/jpeg"
        clear(activity)
        val file = path?.let { File(it) }
        val hasFile = file != null && file.exists() && file.length() > 0
        if (resultCode != Activity.RESULT_OK) {
            if (file != null && file.exists()) runCatching { file.delete() }
            notice(activity, activity.getString(R.string.camera_cancelled))
            return
        }
        if (hasFile) {
            if (AttachLimits.fits(activity, pending, mime, file!!.length())) {
                add(file.absolutePath, mime, file.name)
            } else {
                runCatching { file.delete() }
                notice(activity, activity.getString(
                    if (mime.startsWith("video/")) R.string.camera_one_video else R.string.camera_too_big))
            }
            return
        }
        if (file != null && file.exists()) runCatching { file.delete() }
        val uri = data?.data
        if (uri != null) fromUri(uri)   // same limits apply there
        else notice(activity, activity.getString(R.string.camera_failed))
    }

    private fun prefs(a: Activity) = a.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    private fun clear(a: Activity) { prefs(a).edit().clear().apply() }

    /** In-app styled message (title + accent line, OK). */
    private fun notice(activity: Activity, text: String) {
        if (activity.isFinishing) return
        runCatching {
            DsmsAlertBuilder(activity)
                .setCustomTitle(Dialogs.title(activity, activity.getString(R.string.attach_menu_camera)))
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
