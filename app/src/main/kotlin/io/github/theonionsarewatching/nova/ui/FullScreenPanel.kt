package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDialog
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.databinding.ViewSoftkeyBarBinding
import io.github.theonionsarewatching.nova.util.Prefs

/**
 * Full-screen in-app panel: centered accent title
 * on top, [content] filling the middle, and the normal soft-key bar at the
 * bottom. The LEFT / RIGHT soft keys act on key-UP (DOWN is swallowed),
 * like the screens' own soft keys. BACK cancels (the dialog's cancel
 * listener). D-pad only, no touch-only controls (the labels can still be
 * tapped on touch phones).
 */
class FullScreenPanel(
    private val activity: Activity,
    title: String,
    content: View,
) {
    val dialog = AppCompatDialog(activity, R.style.Theme_DSMS)
    val bar: ViewSoftkeyBarBinding
    private var onLeft: (() -> Unit)? = null
    private var onRight: (() -> Unit)? = null
    private val prefs = Prefs.get(activity)
    private val titleView: View

    init {
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeUtils.pageColor(activity))
        }
        titleView = Dialogs.title(activity, title)
        root.addView(titleView)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bar = ViewSoftkeyBarBinding.inflate(activity.layoutInflater, root, false)
        ThemeUtils.styleSoftkeyBar(bar)
        root.addView(bar.root)
        bar.softLeft.setOnClickListener { onLeft?.invoke() }
        bar.softRight.setOnClickListener { onRight?.invoke() }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(ThemeUtils.pageColor(activity)))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.setOnKeyListener { _, keyCode, event -> handleKey(keyCode, event) }
    }

    /** Change the header text (e.g. a running count). */
    fun setTitle(text: String) {
        fun first(v: View): android.widget.TextView? = when (v) {
            is android.widget.TextView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { first(v.getChildAt(it)) }
            else -> null
        }
        first(titleView)?.text = text
    }

    fun setLeft(label: String?, action: (() -> Unit)?) {
        bar.softLeft.text = label ?: ""; onLeft = action
    }

    fun setRight(label: String?, action: (() -> Unit)?) {
        bar.softRight.text = label ?: ""; onRight = action
    }

    private fun isLeft(code: Int) = code == KeyEvent.KEYCODE_SOFT_LEFT ||
        (code == prefs.softkeyLeftCode && code != KeyEvent.KEYCODE_BACK &&
            code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER)

    private fun isRight(code: Int) = code == KeyEvent.KEYCODE_SOFT_RIGHT ||
        (code == prefs.softkeyRightCode && code != KeyEvent.KEYCODE_BACK &&
            code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER)

    private fun handleKey(code: Int, event: KeyEvent): Boolean {
        val action = when {
            isLeft(code) -> onLeft
            isRight(code) -> onRight
            // a stray MENU (Sonim echo of a soft key) must not reach the
            // screen underneath
            code == KeyEvent.KEYCODE_MENU -> return true
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0 && !event.isCanceled) {
            action?.invoke()
        }
        return true
    }

    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()
}
