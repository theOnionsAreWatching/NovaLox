package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import io.github.theonionsarewatching.nova.R
import java.io.File

/**
 * Records a voice note in-app and hands the finished file to [onDone].
 * Exists because the fleet's recorder apps don't implement the capture
 * contract — opening them can never return a recording. AMR output: tiny
 * files, the classic MMS voice format, playable everywhere.
 */
object AudioRecorderDialog {

    fun show(activity: Activity, onDone: (path: String, mime: String, name: String) -> Unit) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val dir = File(activity.filesDir, "parts").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        // format is user-selectable: raw AMR is the one carriers transcode
        val fmt = io.github.theonionsarewatching.nova.util.Prefs.get(activity).voiceFormat
        val ext = when (fmt) { "amr" -> "amr"; "3gp" -> "3gp"; else -> "m4a" }
        val mime = when (fmt) {
            "amr" -> "audio/amr"; "3gp" -> "audio/3gpp"; else -> "audio/mp4"
        }
        val outFile = File(dir, "Recording_${stamp}_out_${System.currentTimeMillis()}.$ext")

        var dialogRef: AlertDialog? = null
        var recorder: MediaRecorder? = null
        var recording = false
        var recorded = false
        var seconds = 0
        val handler = Handler(Looper.getMainLooper())

        val status = TextView(activity).apply {
            text = activity.getString(R.string.rec_ready)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        val toggle = TextView(activity).apply {
            text = activity.getString(R.string.rec_start)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isFocusable = true
            ThemeUtils.applyFocusHighlight(this)
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(status)
            addView(toggle)
        }

        lateinit var tick: Runnable
        tick = Runnable {
            if (recording) {
                seconds++
                status.text = String.format("%d:%02d", seconds / 60, seconds % 60)
                handler.postDelayed(tick, 1000)
            }
        }

        fun stopRecording() {
            if (!recording) return
            recording = false
            try {
                recorder?.stop()
                recorded = outFile.exists() && outFile.length() > 0
            } catch (_: Exception) {
                recorded = false
            }
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            toggle.text = activity.getString(R.string.rec_start)
            status.text = if (recorded)
                activity.getString(R.string.rec_done_fmt, seconds / 60, seconds % 60)
            else activity.getString(R.string.rec_ready)
            // move focus to Attach so the user isn't stranded on Record
            if (recorded) {
                dialogRef?.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
            }
        }

        fun startRecording() {
            try {
                @Suppress("DEPRECATION")
                val r = MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                when (fmt) {
                    "amr" -> {
                        r.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                        r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    }
                    "3gp" -> {
                        r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                        r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    }
                    else -> {
                        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        r.setAudioSamplingRate(44100)
                        r.setAudioEncodingBitRate(64000)
                    }
                }
                r.setOutputFile(outFile.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                recording = true
                recorded = false
                seconds = 0
                status.text = "0:00"
                toggle.text = activity.getString(R.string.rec_stop)
                handler.postDelayed(tick, 1000)
            } catch (e: Exception) {
                status.text = activity.getString(R.string.rec_failed)
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
                recording = false
            }
        }

        toggle.setOnClickListener { if (recording) stopRecording() else startRecording() }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.attach_menu_record)
            .setView(column)
            .setPositiveButton(R.string.attach_recording, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialogRef = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                stopRecording()
                if (recorded) {
                    dialog.dismiss()
                    onDone(outFile.absolutePath, mime, outFile.name)
                } else {
                    status.text = activity.getString(R.string.rec_nothing)
                }
            }
        }
        dialog.setOnDismissListener {
            if (recording) stopRecording()
            if (!recorded) runCatching { outFile.delete() }
            handler.removeCallbacksAndMessages(null)
        }
        dialog.show()
        toggle.requestFocus()
    }
}
