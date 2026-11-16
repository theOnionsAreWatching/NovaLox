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
        // short, clear name: Voice_MMDD-HHMM.ext, e.g. Voice_1030-0915.m4a.
        // No spaces (some carriers mangle them in MMS part names); a second
        // recording in the same minute becomes Voice_1030-0915_2.m4a.
        val stamp = java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        // format is user-selectable: raw AMR is the one carriers transcode
        val fmt = io.github.theonionsarewatching.nova.util.Prefs.get(activity).voiceFormat
        val ext = when (fmt) { "amr" -> "amr"; "3gp" -> "3gp"; else -> "m4a" }
        val mime = when (fmt) {
            "amr" -> "audio/amr"; "3gp" -> "audio/3gpp"; else -> "audio/mp4"
        }
        val outFile = run {
            var n = 1
            var f = File(dir, "Voice_$stamp.$ext")
            while (f.exists()) { n++; f = File(dir, "Voice_${stamp}_$n.$ext") }
            f
        }

        var dialogRef: AlertDialog? = null
        var stopRecordingRef: (() -> Unit)? = null
        var limitHit = false
        var recorder: MediaRecorder? = null
        var recording = false
        var recorded = false
        // true only once Attach handed the file over; any other
        // way out (Cancel, Back, pause) deletes the recording file
        var attached = false
        var seconds = 0
        val handler = Handler(Looper.getMainLooper())
        // the file must fit in one MMS. The recorder stops by
        // itself at the carrier limit (minus room for the text + headers).
        val maxBytes = (io.github.theonionsarewatching.nova.util.CarrierMms.limits(activity).maxBytes - 50 * 1024)
            .coerceAtLeast(100 * 1024)
        // rough bytes per second for the "time left" hint (AMR 12.2 kbps,
        // AAC voice 24 kbps + container)
        val bytesPerSec = if (fmt == "amr" || fmt == "3gp") 1_600 else 3_200
        val maxSeconds = maxBytes / bytesPerSec
        val audioManager = activity.getSystemService(android.content.Context.AUDIO_SERVICE)
            as android.media.AudioManager
        var focusRequest: Any? = null

        // compact: one fixed status line, narrow Record pill, then
        // tight Cancel / Attach rows in our own column (the stock buttons
        // looked odd and wasted height on 240px). Same order as before.
        val status = Dialogs.statusLine(activity, activity.getString(R.string.rec_ready))
        val toggle = Dialogs.pillButton(activity, activity.getString(R.string.rec_start))
        lateinit var onAttach: () -> Unit
        val cancelRow = Dialogs.compactRow(activity, activity.getString(android.R.string.cancel)) {
            dialogRef?.dismiss()
        }
        val attachRow = Dialogs.compactRow(activity, activity.getString(R.string.attach_recording)) { onAttach() }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(8))
            addView(status)
            addView(toggle)
            addView(cancelRow)
            addView(attachRow)
        }

        lateinit var tick: Runnable
        tick = Runnable {
            if (recording) {
                seconds++
                val left = (maxSeconds - seconds).coerceAtLeast(0)
                status.text = activity.getString(R.string.rec_time_left_fmt,
                    String.format("%d:%02d", seconds / 60, seconds % 60),
                    String.format("%d:%02d", left / 60, left % 60))
                handler.postDelayed(tick, 1000)
            }
        }

        val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
            // a call (or anything else taking the audio) stops and
            // keeps what was recorded so far
            if (change == android.media.AudioManager.AUDIOFOCUS_LOSS ||
                change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                handler.post { stopRecordingRef?.invoke() }
            }
        }
        fun abandonFocus() {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    (focusRequest as? android.media.AudioFocusRequest)?.let { audioManager.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
                }
            } catch (_: Exception) {}
            focusRequest = null
        }
        fun requestFocus() {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val req = android.media.AudioFocusRequest.Builder(
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setOnAudioFocusChangeListener(focusListener, handler)
                        .build()
                    focusRequest = req
                    audioManager.requestAudioFocus(req)
                } else {
                    @Suppress("DEPRECATION") audioManager.requestAudioFocus(focusListener,
                        android.media.AudioManager.STREAM_VOICE_CALL,
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }
            } catch (_: Exception) {}
        }

        fun stopRecording() {
            // recording is ending (or never ran): let the screen time out
            // normally again. Done BEFORE the early return so every exit path
            // (toggle, Attach, Cancel/dismiss) clears it.
            try {
                activity.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (_: Exception) {}
            if (!recording) return
            recording = false
            abandonFocus()
            try {
                recorder?.stop()
                recorded = outFile.exists() && outFile.length() > 0
            } catch (_: Exception) {
                // after the size-limit auto-stop the file is already complete
                // and stop() may throw; keep it then
                recorded = limitHit && outFile.exists() && outFile.length() > 0
            }
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            toggle.text = activity.getString(R.string.rec_start)
            status.text = if (recorded)
                activity.getString(R.string.rec_done_fmt, seconds / 60, seconds % 60)
            else activity.getString(R.string.rec_ready)
            // move focus to Attach so the user isn't stranded on Record
            if (recorded) attachRow.requestFocus()
        }

        fun startRecording() {
            var r: MediaRecorder? = null
            try {
                @Suppress("DEPRECATION")
                r = MediaRecorder()
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
                        // voice settings — 16 kHz mono ~24 kbps,
                        // same speech quality, files about 3x smaller than
                        // the old 44 kHz / 64 kbps
                        r.setAudioChannels(1)
                        r.setAudioSamplingRate(16000)
                        r.setAudioEncodingBitRate(24000)
                    }
                }
                r.setOutputFile(outFile.absolutePath)
                r.setMaxFileSize(maxBytes.toLong())
                r.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        handler.post {
                            limitHit = true
                            stopRecordingRef?.invoke()
                            if (recorded) {
                                status.text = activity.getString(R.string.rec_limit_fmt, seconds / 60, seconds % 60)
                                DsmsToast.makeText(activity, R.string.rec_limit_msg,
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                requestFocus()
                r.prepare()
                r.start()
                recorder = r
                limitHit = false
                recording = true
                recorded = false
                seconds = 0
                status.text = "0:00"
                toggle.text = activity.getString(R.string.rec_stop)
                handler.postDelayed(tick, 1000)
                // keep the screen on while recording — only once the recorder
                // really started, so a failed start never holds the screen
                try {
                    activity.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                status.text = activity.getString(R.string.rec_failed)
                // release the NEW recorder (the mic stayed blocked)
                try { r?.release() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
                recording = false
                abandonFocus()
                runCatching { outFile.delete() }
            }
        }

        stopRecordingRef = { stopRecording() }
        toggle.setOnClickListener { if (recording) stopRecording() else startRecording() }

        // flip closed, screen off, Home, or a call screen on top
        // pauses this screen — stop and keep what was recorded so far (he
        // can still Attach it when he comes back)
        val lifecycle = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(a: Activity) { if (a === activity && recording) stopRecording() }
            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        activity.application.registerActivityLifecycleCallbacks(lifecycle)

        val dialog = DsmsFrameworkAlertBuilder(activity)
            .setCustomTitle(Dialogs.title(activity, activity.getString(R.string.attach_menu_record)))
            .setView(column)
            .create()
        dialogRef = dialog
        onAttach = {
            stopRecording()
            if (recorded) {
                attached = true
                dialog.dismiss()
                onDone(outFile.absolutePath, mime, outFile.name)
            } else {
                status.text = activity.getString(R.string.rec_nothing)
            }
        }
        dialog.setOnDismissListener {
            if (recording) stopRecording()
            abandonFocus()
            activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
            // Cancel / Back after recording used to keep the file
            if (!attached) runCatching { outFile.delete() }
            handler.removeCallbacksAndMessages(null)
        }
        dialog.show()
        toggle.requestFocus()
    }
}
