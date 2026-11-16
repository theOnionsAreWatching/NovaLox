package io.github.theonionsarewatching.nova.ui

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.Button
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import io.github.theonionsarewatching.nova.R

/**
 * ONE place that finishes every pop-up the app builds.
 * Every AlertDialog in D-SMS is made through [DsmsAlertBuilder] (AppCompat)
 * or [DsmsFrameworkAlertBuilder] (android.app), and both run
 * [DialogPolish.onShown] when the pop-up appears:
 *
 * - focus starts on the primary button (OK / positive, else the only
 *   button) whenever nothing better has focus: plain message pop-ups such
 *   as "No archived conversations", confirmations, notices. Lists keep
 *   focus on their first row and text boxes keep the cursor.
 * - the buttons are drawn in the theme's pop-up button style (accent text,
 *   filled accent pill when focused), never Android's gray focus box; the
 *   message area doesn't get the gray box either.
 */
class DsmsAlertBuilder : androidx.appcompat.app.AlertDialog.Builder {
    constructor(context: Context) : super(context)
    constructor(context: Context, themeResId: Int) : super(context, themeResId)

    override fun create(): androidx.appcompat.app.AlertDialog =
        super.create().also { DialogPolish.install(it) }
}

class DsmsFrameworkAlertBuilder : android.app.AlertDialog.Builder {
    constructor(context: Context) : super(context)
    constructor(context: Context, themeResId: Int) : super(context, themeResId)

    override fun create(): android.app.AlertDialog =
        super.create().also { DialogPolish.install(it) }
}

object DialogPolish {

    /**
     * The pop-up used to be finished in its show
     * listener, which Android runs only AFTER the first frame is on screen:
     * the first frame was drawn at the theme's default size with the
     * default focus (the first button in the bar = Cancel), and a frame
     * later the width was fitted (the pop-up shrank and re-centered: the
     * "jump") and focus moved. Now the content is built at create() and the
     * size, button look and starting cursor are all set in a pre-draw hook,
     * so the very first frame already has its final size, place and focus.
     */
    fun install(d: Dialog) {
        // build the views now (show() then skips this); callers only set
        // listeners after create(), which still works
        runCatching { d.create() }
        hook(d)
    }

    /** Finish [d] before each first frame (also after a dismiss + show). */
    fun hook(d: Dialog) {
        val decor = d.window?.decorView ?: return
        if (decor.getTag(R.id.dsms_polish_hooked) == true) return
        decor.setTag(R.id.dsms_polish_hooked, true)
        fun arm() {
            val l = object : android.view.ViewTreeObserver.OnPreDrawListener {
                var passes = 0
                override fun onPreDraw(): Boolean {
                    passes++
                    val resized = runCatching { finish(d) }.getOrDefault(false)
                    // a new size: skip this frame, the next one is final
                    if (resized && passes < 3) return false
                    decor.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            }
            decor.viewTreeObserver.addOnPreDrawListener(l)
        }
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = arm()
            override fun onViewDetachedFromWindow(v: View) {}
        })
        if (decor.isAttachedToWindow) arm()
    }

    /** Kept for callers: same finishing, for a pop-up already on screen. */
    fun onShown(d: Dialog) { if (d.isShowing) runCatching { finish(d) } }

    private val buttonIds = intArrayOf(
        android.R.id.button1,   // positive (OK)
        android.R.id.button2,   // negative
        android.R.id.button3,   // neutral
    )

    /** Button look, width and starting cursor. Returns true when the
     *  window size changed (the caller then skips the frame). */
    private fun finish(d: Dialog): Boolean {
        val root = d.window?.decorView ?: return false
        val buttons = ArrayList<Button>()
        for (id in buttonIds) {
            val b = root.findViewById<View>(id) as? Button ?: continue
            if (b.visibility == View.VISIBLE) buttons.add(b)
        }
        // colors come from the pop-up's own theme: accent = the user's
        // current accent (any color / light-dark / system theme); text on
        // the focused pill = black or white, whichever reads better on it
        val accent = ThemeUtils.accentColor(d.context)
        val on = ThemeUtils.onColor(accent)
        val off = ThemeUtils.attrColor(d.context, R.attr.dsmsTextDisabled, R.color.dialog_text_disabled)
        val btnText = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf()
            ),
            intArrayOf(off, on, on, accent)
        )
        for (b in buttons) {
            if (b.getTag(R.id.dsms_polish_hooked) != true) {
                b.setTag(R.id.dsms_polish_hooked, true)
                b.background = ContextCompat.getDrawable(d.context, R.drawable.bg_dialog_button)
                b.isAllCaps = false
                if (Build.VERSION.SDK_INT >= 26) b.defaultFocusHighlightEnabled = false
            }
            b.setTextColor(btnText)
        }
        noGrayBox(root)
        val resized = fitWidth(d)
        startFocus(d, root)
        return resized
    }

    /**
     * The starting cursor:
     * - a list: the selected (checked) row, or the row the caller asked for
     *   (tag dsms_default_row), else the first row;
     * - a text box or other own control that already has focus: kept;
     * - otherwise OK / the positive action, else the neutral one;
     * - Cancel (negative) never gets it: with only Cancel, the message area
     *   takes the cursor and Cancel is one press away.
     */
    private fun startFocus(d: Dialog, root: View) {
        fun btn(id: Int) = (root.findViewById<View>(id) as? Button)
            ?.takeIf { it.visibility == View.VISIBLE && it.isEnabled && it.isShown }
        val list = findList(root)
        val count = list?.adapter?.count ?: 0
        if (list != null && count > 0 && list.isFocusable) {
            val asked = root.getTag(R.id.dsms_default_row) as? Int
            var pos = asked ?: list.checkedItemPosition
            if (pos < 0 && list.choiceMode == AbsListView.CHOICE_MODE_MULTIPLE) pos = 0
            if (pos < 0) pos = 0
            pos = pos.coerceIn(0, count - 1)
            if (!list.hasFocus()) list.requestFocus()
            if (list.selectedItemPosition != pos) {
                // Centering the checked row artificially scrolls a two-item dialog
                // even when both rows fit. Let short lists keep their natural origin.
                if (list is android.widget.ListView && count > 6) list.setSelectionFromTop(pos,
                    ((list.height - (list.getChildAt(0)?.height ?: 0)) / 2).coerceAtLeast(0))
                else list.setSelection(pos)
            }
            return
        }
        val cur = d.currentFocus
        val keep = cur != null && cur !is Button && cur !is ScrollView &&
            cur !is NestedScrollView && cur !== root && cur.isFocusable && cur.isShown &&
            // a plain message text is not a control: OK gets the cursor
            !(cur is android.widget.TextView && cur !is android.widget.EditText && !cur.isClickable)
        if (keep) return
        val primary = btn(android.R.id.button1) ?: btn(android.R.id.button3)
        if (primary != null) { if (!primary.isFocused) primary.requestFocus(); return }
        if (cur is Button && cur.id == android.R.id.button2) {
            val scroll = findScroll(root)
            if (scroll != null) { scroll.isFocusable = true; scroll.requestFocus() }
        }
    }

    private fun findScroll(v: View): View? {
        if ((v is ScrollView || v is NestedScrollView) && v.isShown) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    /**
     * Pop-up width (not too much empty space after each word, not
     * hugging the text either: something in between). The visible card is
     *   longest row (or title / message line / buttons) + 28dp breathing room,
     *   at least 170dp (buttons always fit), at most 85% of the screen
     *   (204dp on the 240dp-wide Flip 2).
     * Row heights are untouched; rows stay match_parent inside the card, so
     * the focus pill is exactly the card's content width. Pop-ups with
     * their own custom layout, full-screen panels and the Attach pop-ups
     * (fixed middle width, see Dialogs.narrowToLabels) are left as they are.
     */
    private fun fitWidth(d: Dialog): Boolean {
        val win = d.window ?: return false
        val root = win.decorView
        if (root.getTag(R.id.dsms_fixed_width) == true) return false
        if (win.attributes.width == ViewGroup.LayoutParams.MATCH_PARENT) return false
        val custom = root.findViewById<ViewGroup>(androidx.appcompat.R.id.custom)
            ?: root.findViewById(android.R.id.custom)
        if (custom != null && custom.isShown && custom.childCount > 0) return false
        val dm = d.context.resources.displayMetrics
        fun dp(v: Float) = (v * dm.density).toInt()
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun natural(v: View): Int { v.measure(unspec, unspec); return v.measuredWidth }

        var need = 0
        // list rows: each row's own natural width (text + its padding +
        // radio/check mark), first 80 rows are plenty to find the longest
        val list = findList(root)
        if (list != null) {
            val ad = list.adapter
            if (ad != null) for (i in 0 until minOf(ad.count, 80)) {
                try { need = maxOf(need, natural(ad.getView(i, null, list))) } catch (_: Exception) {}
            }
        }
        val msg = root.findViewById<View>(android.R.id.message)
        if (msg != null && msg.isShown) need = maxOf(need, natural(msg))
        val title = root.findViewById<View>(androidx.appcompat.R.id.alertTitle)
            ?: root.findViewById(android.R.id.title)
        if (title != null && title.isShown) {
            need = maxOf(need, natural(title) + dp(48f))   // title side padding
        } else {
            // custom title (Dialogs.title): its whole header
            root.findViewById<View>(androidx.appcompat.R.id.topPanel)
                ?.takeIf { it.isShown }?.let { need = maxOf(need, natural(it)) }
        }
        var buttons = dp(24f)   // button bar side padding
        for (id in buttonIds) {
            val b = root.findViewById<View>(id) ?: continue
            if (b.visibility == View.VISIBLE) buttons += natural(b) + dp(4f)
        }
        val card = maxOf(need + dp(28f), buttons, dp(170f))
            .coerceAtMost((dm.widthPixels * 0.85f).toInt())
        // bg_dialog insets the card by 10dp on each side of the window
        val w = card + dp(20f)
        if (win.attributes.width == w) return false
        win.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        return true
    }

    private fun findList(v: View): AbsListView? {
        if (v is AbsListView && v.isShown) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findList(v.getChildAt(i))?.let { return it }
        return null
    }

    /** The message's scroll area takes focus with Android's gray box on
     *  8.0+; turn that highlight off everywhere in the pop-up. */
    private fun noGrayBox(v: View) {
        if (Build.VERSION.SDK_INT < 26) return
        if (v is ScrollView || v is NestedScrollView) v.defaultFocusHighlightEnabled = false
        if (v is ViewGroup) for (i in 0 until v.childCount) noGrayBox(v.getChildAt(i))
    }
}
