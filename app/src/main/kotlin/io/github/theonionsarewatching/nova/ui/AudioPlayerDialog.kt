package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.databinding.ViewSoftkeyBarBinding
import java.io.File

/**
 * Plays an audio attachment in-app with MediaPlayer — no dependence on which
 * player apps the phone happens to have, or which uri schemes they accept.
 * (On Qualcomm keypad phones this can even decode carrier formats that
 * third-party apps refuse.)
 *
 * keypad layout driven by the soft keys, no buttons to walk:
 *   middle (OK)    = Play / Pause
 *   left soft key  = Earpiece on / off ("Ear: on/off"; accent badge too)
 *   right soft key = Options (Save, Open with…)
 *   D-pad Left / Right = back / forward 5 s;  BACK closes.
 * The labels go on the player's own soft-key bar (not behind the modal,
 * keys handed to the same Softkeys handler: mapped codes, MENU echo filter,
 * act on key-up). With the bar off (touch phones / "Never") the pop-up
 * shows its own bar at the bottom. Everything acts on key-up.
 */
object AudioPlayerDialog {

    private const val SEEK_MS = 5000

    /** "Voice_1102-1542.m4a" -> "Voice 11-02 15:42"; other names as they are. */
    fun shortTitle(name: String): String {
        val m = Regex("""^Voice_(\d{2})(\d{2})-(\d{2})(\d{2})(?:\.\w+)?$""").find(name)
            ?: return name
        val (mo, d, h, mi) = m.destructured
        return "Voice $mo-$d $h:$mi"
    }

    /** Rounded progress track in the accent color with a small knob. */
    private class ProgressBarView(ctx: Activity, private val accent: Int) : View(ctx) {
        var fraction = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val track = h * 0.4f
            val r = track / 2
            val knob = h / 2
            val left = knob
            val right = width - knob
            val cy = h / 2
            paint.color = ColorUtils.setAlphaComponent(accent, 0x44)
            rect.set(left, cy - r, right, cy + r)
            c.drawRoundRect(rect, r, r, paint)
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val x = if (rtl) right - (right - left) * fraction else left + (right - left) * fraction
            paint.color = accent
            if (rtl) rect.set(x, cy - r, right, cy + r) else rect.set(left, cy - r, x, cy + r)
            c.drawRoundRect(rect, r, r, paint)
            c.drawCircle(x, cy, knob, paint)
        }
    }

    fun show(
        activity: Activity,
        path: String,
        title: String,
        onOpenExternal: () -> Unit,
        onSave: (() -> Unit)? = null
    ) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        // the app Size setting scales the player too (text sp already follows it)
        val sc = io.github.theonionsarewatching.nova.util.Prefs.get(activity).fontScale.coerceIn(0.8f, 1.6f)
        val dz = { v: Int -> dp((v * sc).toInt()) }
        val accent = ThemeUtils.accentColor(activity)
        val onAccent = ThemeUtils.onColor(accent)
        val grey = ThemeUtils.attrColor(activity, R.attr.dsmsTextDisabled, R.color.dialog_text_disabled)
        var player: MediaPlayer? = null
        var prepared = false
        var failed = false
        val handler = Handler(Looper.getMainLooper())
        val prefs = io.github.theonionsarewatching.nova.util.Prefs.get(activity)
        var earpiece = prefs.voiceEarpiece
        var dialogRef: AlertDialog? = null
        var optionsRef: androidx.appcompat.app.AlertDialog? = null

        // ---- views: all fixed-size, so the pop-up opens at its final size
        val playIcon = TextView(activity).apply {
            text = "\u25B6"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(onAccent)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            isFocusable = false
        }
        val bar = ProgressBarView(activity, accent)
        val elapsed = TextView(activity).apply {
            text = "0:00"; textSize = 17f; isSingleLine = true
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        val total = TextView(activity).apply {
            text = "0:00"; textSize = 17f; isSingleLine = true
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }
        val times = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(elapsed, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(total, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val track = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dz(22)))
            addView(times)
        }
        val playerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(playIcon, LinearLayout.LayoutParams(dz(60), dz(60)).apply { marginEnd = dz(12) })
            addView(track, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val badge = TextView(activity).apply {
            textSize = 15f
            isSingleLine = true
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(2), dp(10), dp(2))
            isFocusable = false
        }
        val badgeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(badge, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dz(12), dz(4), dz(12), dz(10))
            addView(playerRow)
            addView(badgeRow)
        }

        // Real action labels belong inside the modal player, not dimmed behind it.
        val ownBar = ViewSoftkeyBarBinding.inflate(activity.layoutInflater).also { ThemeUtils.styleSoftkeyBar(it) }
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(column)
            addView(ownBar.root)
        }

        fun fmt(ms: Int): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return String.format("%d:%02d", s / 60, s % 60)
        }
        fun isPlaying() = try { player?.isPlaying == true } catch (_: Exception) { false }

        fun refreshTime() {
            val mp = player ?: return
            if (!prepared) return
            try {
                val d = mp.duration.coerceAtLeast(1)
                val p = mp.currentPosition
                elapsed.text = fmt(p); total.text = fmt(mp.duration)
                bar.fraction = p.toFloat() / d
            } catch (_: Exception) {}
        }

        lateinit var togglePlay: () -> Unit
        lateinit var toggleEar: () -> Unit
        lateinit var showOptions: () -> Unit

        fun refreshUi() {
            playIcon.text = if (isPlaying()) "\u2759\u2759" else "\u25B6"
            badge.text = activity.getString(if (earpiece) R.string.player_earpiece_on else R.string.player_speaker)
            badge.setTextColor(if (earpiece) onAccent else grey)
            badge.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                if (earpiece) setColor(accent) else setStroke(dp(1), grey)
            }
            val left = activity.getString(if (earpiece) R.string.player_ear_key_on else R.string.player_ear_key_off)
            val mid = activity.getString(if (isPlaying()) R.string.player_pause_key else R.string.player_play_key)
            val right = activity.getString(R.string.softkey_options)
            ownBar.apply {
                softLeft.text = left; softCenter.text = mid; softRight.text = right
                softLeft.setTextColor(if (earpiece) accent else grey)
            }
        }

        lateinit var tick: Runnable
        tick = Runnable {
            refreshTime()
            if (isPlaying()) handler.postDelayed(tick, 250)
        }

        // earpiece routing: call-style audio (voice communication) with the
        // speakerphone off; the phone's previous audio mode is restored after
        val am = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val oldMode = am.mode
        val oldSpeaker = am.isSpeakerphoneOn
        val oldVolumeStream = activity.volumeControlStream
        var routed = false
        fun applyRoute() {
            try {
                if (earpiece) {
                    am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                    activity.volumeControlStream = android.media.AudioManager.STREAM_VOICE_CALL
                    routed = true
                } else if (routed) {
                    am.mode = oldMode
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = oldSpeaker
                    activity.volumeControlStream = oldVolumeStream
                    routed = false
                }
            } catch (_: Exception) {}
        }

        fun preparePlayer(): Boolean {
            if (prepared) return true
            if (failed) return false
            return try {
                val mp = MediaPlayer()
                applyRoute()
                mp.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(if (earpiece) android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                // setDataSource(FileDescriptor) makes the decoder read the
                // container magic instead of keying off the .qcp extension —
                // Qualcomm devices decode QCELP in hardware this way even when
                // an extension-based open would be refused.
                java.io.FileInputStream(File(path)).use { fis -> mp.setDataSource(fis.fd) }
                mp.prepare()
                mp.setOnCompletionListener {
                    try { mp.seekTo(0) } catch (_: Exception) {}
                    refreshTime(); refreshUi()
                }
                player = mp
                prepared = true
                refreshTime()
                true
            } catch (e: Exception) {
                failed = true
                try { player?.release() } catch (_: Exception) {}
                player = null
                // a voice note that can't play closes the player
                // and says so in a themed pop-up (Open with… still offered)
                val d = dialogRef
                if (d != null && !activity.isFinishing) {
                    d.dismiss()
                    DsmsAlertBuilder(activity)
                        .setMessage(R.string.player_cant_play_msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.player_open_with) { _, _ -> onOpenExternal() }
                        .show()
                }
                false
            }
        }

        togglePlay = {
            if (preparePlayer()) {
                val mp = player
                if (mp != null) {
                    if (mp.isPlaying) mp.pause() else { mp.start(); handler.post(tick) }
                    refreshUi()
                }
            }
        }

        fun seek(deltaMs: Int) {
            if (!preparePlayer()) return
            val mp = player ?: return
            try {
                val to = (mp.currentPosition + deltaMs).coerceIn(0, mp.duration)
                mp.seekTo(to)
            } catch (_: Exception) {}
            refreshTime()
        }

        toggleEar = {
            earpiece = !earpiece
            prefs.voiceEarpiece = earpiece   // same switch as in Settings
            // the output can only change on a new player: rebuild it at the
            // same position, keep playing if it was playing
            val mp = player
            val pos = try { mp?.currentPosition ?: 0 } catch (_: Exception) { 0 }
            val wasPlaying = isPlaying()
            try { mp?.stop() } catch (_: Exception) {}
            try { mp?.release() } catch (_: Exception) {}
            player = null; prepared = false
            if (!earpiece) applyRoute()
            if (preparePlayer()) {
                try { player?.seekTo(pos) } catch (_: Exception) {}
                if (wasPlaying) { player?.start(); handler.post(tick) }
            }
            refreshUi()
        }

        showOptions = showOptions@{
            val parent = dialogRef ?: return@showOptions
            if (!parent.isShowing || optionsRef?.isShowing == true) return@showOptions
            val returnFocus = parent.currentFocus
            val labels = ArrayList<String>()
            val actions = ArrayList<() -> Unit>()
            if (onSave != null) { labels.add(activity.getString(R.string.save)); actions.add(onSave) }
            labels.add(activity.getString(R.string.player_open_with)); actions.add(onOpenExternal)
            val options = DsmsAlertBuilder(activity)
                .setCustomTitle(Dialogs.title(activity, activity.getString(R.string.softkey_options)))
                .setItems(labels.toTypedArray()) { _, which ->
                    dialogRef?.dismiss()
                    actions[which]()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            optionsRef = options
            options.setOnDismissListener {
                optionsRef = null
                // Cancel/Back closes only this window. Playback/ticks are untouched.
                if (parent.isShowing) {
                    if (returnFocus?.isAttachedToWindow == true && returnFocus.isShown) returnFocus.requestFocus()
                    else parent.window?.decorView?.requestFocus()
                }
            }
            alignOptionsBeforeFirstFrame(activity, parent, options)
            options.show()
        }

        // touch phones: the icon and the badge can be tapped too
        playIcon.setOnClickListener { togglePlay() }
        badge.setOnClickListener { toggleEar() }
        ownBar.apply {
            softLeft.setOnClickListener { toggleEar() }
            softCenter.setOnClickListener { togglePlay() }
            softRight.setOnClickListener { showOptions() }
        }

        val titleView = Dialogs.title(activity, shortTitle(title.ifBlank { File(path).name }))
        // one line; a long file name is trimmed in the middle
        (titleView as? ViewGroup)?.getChildAt(0)?.let { t ->
            (t as? TextView)?.apply {
                isSingleLine = true
                maxLines = 1
                textSize = 20f
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
        }

        val dialog = DsmsFrameworkAlertBuilder(activity)
            .setCustomTitle(titleView)
            .setView(outer)
            .create()
        dialogRef = dialog

        fun isSoft(code: Int, mapped: Int, std: Int) = code == std ||
            (code == mapped && code != KeyEvent.KEYCODE_BACK &&
                code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER)

        dialog.setOnKeyListener { _, code, event ->
            val up = event.action == KeyEvent.ACTION_UP && !event.isCanceled
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (up) togglePlay(); return@setOnKeyListener true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (up) {
                        val rtl = activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
                        val back = (code == KeyEvent.KEYCODE_DPAD_LEFT) xor rtl
                        seek(if (back) -SEEK_MS else SEEK_MS)
                    }
                    return@setOnKeyListener true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return@setOnKeyListener true
            }
            // soft keys are decided HERE, the same way playing or paused.
            // They used to go through the screen bar's shared handler first;
            // its MENU logic (a key mapped to MENU, or the delayed MENU of a
            // MENU+softkey pair) could fire the screen's own menu action,
            // i.e. Options, instead of the borrowed Ear action.
            val action: (() -> Unit)? = when {
                isSoft(code, prefs.softkeyLeftCode, KeyEvent.KEYCODE_SOFT_LEFT) -> toggleEar
                isSoft(code, prefs.softkeyRightCode, KeyEvent.KEYCODE_SOFT_RIGHT) -> showOptions
                code == KeyEvent.KEYCODE_MENU -> null   // stray echo: swallow
                else -> return@setOnKeyListener false
            }
            if (up && event.repeatCount == 0) action?.invoke()
            true
        }

        dialog.setOnDismissListener {
            optionsRef?.dismiss()
            handler.removeCallbacksAndMessages(null)
            try { player?.stop() } catch (_: Exception) {}
            try { player?.release() } catch (_: Exception) {}
            player = null
            earpiece = false; applyRoute()   // give the phone its audio mode back
        }

        // prepare before showing: the times are final when it appears
        preparePlayer()
        refreshUi()
        if (failed) {
            earpiece = false; applyRoute()
            DsmsAlertBuilder(activity)
                .setMessage(R.string.player_cant_play_msg)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.player_open_with) { _, _ -> onOpenExternal() }
                .show()
            return
        }
        // Final bounds before the first frame; bar sits above the physical keys.
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.96f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    /** Standard dialog polish runs first; suppress drawing until its fitted width
     * and our anchored position have both settled. No posted/on-show movement. */
    private fun alignOptionsBeforeFirstFrame(activity: Activity, parent: AlertDialog,
        options: androidx.appcompat.app.AlertDialog) {
        val parentDecor = parent.window?.decorView ?: return
        val win = options.window ?: return
        val decor = win.decorView
        val visible = android.graphics.Rect()
        val parentAt = IntArray(2)
        val childAt = IntArray(2)
        activity.window.decorView.getWindowVisibleDisplayFrame(visible)
        parentDecor.getLocationOnScreen(parentAt)
        win.setGravity(Gravity.CENTER)
        // Establish a useful center before attach, then use measured child bounds
        // below (including themed background insets) to clamp precisely.
        if (!visible.isEmpty) win.attributes = win.attributes.apply {
            y = parentAt[1] + parentDecor.height / 2 - visible.centerY()
        }
        win.setWindowAnimations(R.style.DSMS_AudioOptionsAnimation)
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
                    private var passes = 0
                    override fun onPreDraw(): Boolean {
                        passes++
                        // Bound startup settling; never leave an invisible modal
                        // indefinitely if a vendor keeps requesting layout.
                        if (decor.isLayoutRequested && passes < 4) return false
                        activity.window.decorView.getWindowVisibleDisplayFrame(visible)
                        parentDecor.getLocationOnScreen(parentAt)
                        decor.getLocationOnScreen(childAt)
                        if (parent.isShowing && !visible.isEmpty && decor.height > 0) {
                            val top = AudioOptionsPosition.top(parentAt[1], parentDecor.height,
                                decor.height, visible.top, visible.bottom)
                            val delta = top - childAt[1]
                            if (delta != 0 && passes < 6) {
                                win.attributes = win.attributes.apply { y += delta }
                                return false // relayout before ANY content frame
                            }
                        }
                        decor.viewTreeObserver.removeOnPreDrawListener(this)
                        return true
                    }
                }
                decor.viewTreeObserver.addOnPreDrawListener(listener)
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

}
