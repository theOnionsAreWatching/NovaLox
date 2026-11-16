package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.theonionsarewatching.nova.util.Prefs

/**
 * full-screen reader for a long message.
 *
 * Keys (all inside the reader):
 *   UP / DOWN      one line (hold = faster)
 *   LEFT / RIGHT   one page up / down   (also 4 / 6, 2 / 8, Page Up / Down)
 *   1 / 7          top / bottom
 *   OK             close
 *   soft key/MENU  links/numbers in the message (when it has any)
 *   BACK           close
 * Page and top/bottom act on key-up; line scrolling acts while held.
 */
object LongTextReader {

    /** "Long" = more than about one screen of text, or taller than the list. */
    fun isLong(text: String, rowHeightPx: Int, listHeightPx: Int): Boolean =
        text.length >= 500 || (listHeightPx > 0 && rowHeightPx > listHeightPx)

    fun show(activity: Activity, title: String, text: CharSequence, onOk: (() -> Unit)? = null) {
        val prefs = Prefs.get(activity)
        val d = activity.resources.displayMetrics.density
        val dp = { v: Int -> (v * d).toInt() }
        val builder = DsmsAlertBuilder(activity)
        val ctx = builder.context
        val tv = TextView(ctx).apply {
            setText(text)
            textSize = prefs.msgTextSp
            setPadding(dp(16), dp(4), dp(16), dp(12))
            setTextIsSelectable(false)
        }
        val scroll = ScrollView(ctx).apply {
            isFocusable = true
            isVerticalScrollBarEnabled = true
            addView(tv)
        }
        val dialog = builder
            .setCustomTitle(Dialogs.title(activity, title))
            .setView(scroll)
            .create()

        val line = { tv.lineHeight.coerceAtLeast(dp(16)) }
        val page = { (scroll.height - line()).coerceAtLeast(line()) }

        dialog.setOnKeyListener { _, keyCode, ev ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) {
                        val accel = (1 + ev.repeatCount / 4).coerceAtMost(6)
                        val dy = line() * accel
                        scroll.smoothScrollBy(0, if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -dy else dy)
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_2,
                KeyEvent.KEYCODE_PAGE_UP -> {
                    if (ev.action == KeyEvent.ACTION_UP) scroll.smoothScrollBy(0, -page())
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_8,
                KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (ev.action == KeyEvent.ACTION_UP) scroll.smoothScrollBy(0, page())
                    true
                }
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_MOVE_HOME -> {
                    if (ev.action == KeyEvent.ACTION_UP) scroll.fullScroll(ScrollView.FOCUS_UP)
                    true
                }
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_MOVE_END -> {
                    if (ev.action == KeyEvent.ACTION_UP) scroll.fullScroll(ScrollView.FOCUS_DOWN)
                    true
                }
                // OK closes the reader (on key-up)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (ev.action == KeyEvent.ACTION_UP && ev.repeatCount == 0) dialog.dismiss()
                    true
                }
                else -> {
                    // links/numbers moved from OK to a soft key or MENU (key-up)
                    val linksKey = keyCode == KeyEvent.KEYCODE_MENU ||
                        keyCode == KeyEvent.KEYCODE_SOFT_LEFT || keyCode == KeyEvent.KEYCODE_SOFT_RIGHT ||
                        (keyCode != KeyEvent.KEYCODE_BACK &&
                            (keyCode == prefs.softkeyLeftCode || keyCode == prefs.softkeyRightCode))
                    if (linksKey && onOk != null) {
                        if (ev.action == KeyEvent.ACTION_UP && ev.repeatCount == 0) onOk.invoke()
                        true
                    } else false   // BACK closes (default)
                }
            }
        }
        dialog.show()
        // as close to full screen as the rounded dialog allows
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        scroll.requestFocus()
    }
}
