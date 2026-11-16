package io.github.theonionsarewatching.nova.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * text pasted inside D-SMS is wiped from the clipboard when
 * the user leaves the compose box / the app, as long as the clipboard still
 * holds exactly that text. It never wipes something copied afterwards.
 *
 * Paste paths that are noticed:
 *  - the Paste row of the Attach / Record / Paste pop-up,
 *  - the Paste row of the number picker,
 *  - the system's own paste into a watched text box (long-press / keyboard
 *    paste): seen by [watch] as an insert that equals the clipboard text.
 *
 * Clearing happens on: sending, leaving a D-SMS screen (onPause), and as a
 * last chance when no D-SMS screen is visible any more (onStop).
 *
 * Android 10+ (the Flip 2 runs 11) lets an app READ the clipboard only while
 * it has focus, but WRITING (clearing) is allowed at any time. So the
 * "is it still ours" check uses a direct read when possible, else what the
 * change listener saw while D-SMS was in front ([onClipChanged]).
 */
object ClipboardGuard {

    /** text pasted inside D-SMS that is still waiting to be cleared */
    @Volatile private var pasted: String? = null
    /** the clipboard changed to something else after the paste */
    @Volatile private var replaced = false
    private var started = 0

    /** D-SMS put [text] on the clipboard (Copy): a new copy, nothing to clear yet. */
    fun markCopied(text: String) {
        if (pasted != null && pasted != text) { pasted = null; replaced = false }
    }

    /** [text] from the clipboard was pasted inside D-SMS. */
    fun markPasted(text: String?) {
        if (text.isNullOrEmpty()) return
        pasted = text
        replaced = false
    }

    /** Clipboard change seen while D-SMS is in front (BaseActivity listener).
     *  [now] is the new text, or null when it is empty / could not be read. */
    fun onClipChanged(now: String?) {
        val p = pasted ?: return
        if (now != p) replaced = true
    }

    private fun read(context: Context): String? = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
    } catch (_: Exception) { null }

    /** Watches a text box for the system's own paste (long-press menu or
     *  keyboard paste): an insert of 2+ characters that equals the clipboard. */
    fun watch(edit: EditText) {
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s == null || count < 2 || !edit.hasFocus()) return
                if (start < 0 || start + count > s.length) return
                val clip = read(edit.context) ?: return
                if (clip.length != count) return
                if (s.subSequence(start, start + count).toString() == clip) markPasted(clip)
            }
        })
    }

    /** Clears the clipboard now if it still holds the pasted text. */
    fun clearIfStillPasted(context: Context) {
        val p = pasted ?: return
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // a direct read works while D-SMS has focus; in the background
            // Android 10+ returns nothing, then the listener's record decides
            val now = read(context)
            val stillOurs = if (now != null) now == p else !replaced
            if (stillOurs) {
                if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (_: Exception) {}
        pasted = null
        replaced = false
    }

    fun onActivityStarted() { started++ }

    /** BaseActivity.onStop: last chance once no D-SMS screen is visible. */
    fun onActivityStopped(context: Context, changingConfig: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        if (changingConfig || started > 0) return
        clearIfStillPasted(context)
    }
}
