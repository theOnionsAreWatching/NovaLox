package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import io.github.theonionsarewatching.nova.R
import java.io.File

/**
 * Plays an audio attachment in-app with MediaPlayer — no dependence on which
 * player apps the phone happens to have, or which uri schemes they accept.
 * (On Qualcomm keypad phones this can even decode carrier formats that
 * third-party apps refuse.) A fallback button still offers external apps.
 */
object AudioPlayerDialog {

    fun show(activity: Activity, path: String, title: String, onOpenExternal: () -> Unit) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        var player: MediaPlayer? = null
        var prepared = false
        val handler = Handler(Looper.getMainLooper())

        val status = TextView(activity).apply {
            text = "0:00"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        val toggle = TextView(activity).apply {
            text = activity.getString(R.string.player_play)
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

        fun fmt(ms: Int): String {
            val s = ms / 1000
            return String.format("%d:%02d", s / 60, s % 60)
        }

        lateinit var tick: Runnable
        tick = Runnable {
            val mp = player
            if (mp != null && prepared) {
                try {
                    status.text = fmt(mp.currentPosition) + " / " + fmt(mp.duration)
                } catch (_: Exception) {}
                handler.postDelayed(tick, 500)
            }
        }

        fun preparePlayer(): Boolean {
            if (prepared) return true
            return try {
                val mp = MediaPlayer()
                mp.setDataSource(path)
                mp.prepare()
                mp.setOnCompletionListener {
                    toggle.text = activity.getString(R.string.player_play)
                    try { status.text = fmt(0) + " / " + fmt(mp.duration) } catch (_: Exception) {}
                }
                player = mp
                prepared = true
                status.text = fmt(0) + " / " + fmt(mp.duration)
                true
            } catch (e: Exception) {
                status.text = activity.getString(R.string.player_cant_play)
                try { player?.release() } catch (_: Exception) {}
                player = null
                false
            }
        }

        toggle.setOnClickListener {
            if (!preparePlayer()) return@setOnClickListener
            val mp = player ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                toggle.text = activity.getString(R.string.player_play)
            } else {
                mp.start()
                toggle.text = activity.getString(R.string.player_pause)
                handler.post(tick)
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title.ifBlank { File(path).name })
            .setView(column)
            .setNeutralButton(R.string.player_open_with) { _, _ -> onOpenExternal() }
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            try { player?.stop() } catch (_: Exception) {}
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
        dialog.show()
        toggle.requestFocus()
        // prepare eagerly so the duration shows right away
        preparePlayer()
    }
}
