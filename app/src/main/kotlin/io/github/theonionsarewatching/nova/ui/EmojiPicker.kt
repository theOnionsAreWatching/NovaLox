package io.github.theonionsarewatching.nova.ui

import android.widget.EditText
import android.widget.GridView
import androidx.appcompat.app.AlertDialog
import io.github.theonionsarewatching.nova.R

/**
 * D-pad friendly emoji picker: categories first, then a grid; BACK from a
 * grid returns to the categories (user-specified flow). Selecting an emoji
 * toggles a session pick; Done inserts all picks together.
 *
 * Curated set — every common emoji by category, minus the bikini (U+1F459),
 * excluded per instruction.
 */
object EmojiPicker {

    private val categories: List<Pair<String, List<String>>> = listOf(
        "Faces" to listOf(
            "\uD83D\uDE00","\uD83D\uDE01","\uD83D\uDE02","\uD83E\uDD23","\uD83D\uDE03","\uD83D\uDE04","\uD83D\uDE05","\uD83D\uDE06","\uD83D\uDE09","\uD83D\uDE0A","\uD83D\uDE0B","\uD83D\uDE0E","\uD83D\uDE0D","\uD83D\uDE18","\uD83D\uDE17","\uD83D\uDE19","\uD83D\uDE1A","\uD83D\uDE42","\uD83E\uDD17","\uD83E\uDD14","\uD83D\uDE10","\uD83D\uDE11","\uD83D\uDE36","\uD83D\uDE44","\uD83D\uDE0F","\uD83D\uDE23","\uD83D\uDE25","\uD83D\uDE2E","\uD83D\uDE10","\uD83D\uDE34","\uD83D\uDE31","\uD83D\uDE22","\uD83D\uDE2D","\uD83D\uDE24","\uD83D\uDE20","\uD83D\uDE21","\uD83E\uDD2C","\uD83E\uDD12","\uD83E\uDD15","\uD83E\uDD22","\uD83E\uDD27","\uD83D\uDE07","\uD83E\uDD20","\uD83E\uDD21","\uD83D\uDE48","\uD83D\uDE49","\uD83D\uDE4A"
        ),
        "Gestures & people" to listOf(
            "\uD83D\uDC4D","\uD83D\uDC4E","\uD83D\uDC4C","\u270C\uFE0F","\uD83E\uDD1E","\uD83E\uDD1F","\uD83E\uDD18","\uD83D\uDC4A","\u270A","\uD83D\uDC4F","\uD83D\uDE4C","\uD83D\uDC50","\uD83E\uDD32","\uD83E\uDD1D","\uD83D\uDE4F","\u270D\uFE0F","\uD83D\uDCAA","\uD83D\uDC4B","\uD83E\uDD19","\uD83D\uDD90\uFE0F","\uD83D\uDC66","\uD83D\uDC67","\uD83D\uDC68","\uD83D\uDC69","\uD83D\uDC74","\uD83D\uDC75","\uD83D\uDC76","\uD83E\uDDD1","\uD83D\uDC71","\uD83E\uDDD4","\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC66","\uD83D\uDC83","\uD83D\uDD7A","\uD83D\uDEB6","\uD83C\uDFC3"
        ),
        "Animals & nature" to listOf(
            "\uD83D\uDC36","\uD83D\uDC31","\uD83D\uDC2D","\uD83D\uDC39","\uD83D\uDC30","\uD83E\uDD8A","\uD83D\uDC3B","\uD83D\uDC3C","\uD83D\uDC28","\uD83D\uDC2F","\uD83E\uDD81","\uD83D\uDC2E","\uD83D\uDC37","\uD83D\uDC38","\uD83D\uDC35","\uD83D\uDC14","\uD83D\uDC27","\uD83D\uDC26","\uD83E\uDD86","\uD83E\uDD85","\uD83E\uDD89","\uD83E\uDD8B","\uD83D\uDC1D","\uD83D\uDC1E","\uD83D\uDC22","\uD83D\uDC0D","\uD83D\uDC19","\uD83D\uDC20","\uD83D\uDC2C","\uD83D\uDC33","\uD83D\uDC0B","\uD83D\uDC06","\uD83D\uDC18","\uD83E\uDD92","\uD83D\uDC2A","\uD83D\uDC0E","\uD83C\uDF32","\uD83C\uDF33","\uD83C\uDF34","\uD83C\uDF35","\uD83C\uDF3B","\uD83C\uDF39","\uD83C\uDF37","\uD83C\uDF41","\uD83C\uDF42","\u2600\uFE0F","\uD83C\uDF19","\u2B50","\uD83C\uDF08","\u2601\uFE0F","\u26C8\uFE0F","\u2744\uFE0F","\uD83D\uDD25","\uD83D\uDCA7","\uD83C\uDF0A"
        ),
        "Food & drink" to listOf(
            "\uD83C\uDF4E","\uD83C\uDF4C","\uD83C\uDF47","\uD83C\uDF53","\uD83C\uDF52","\uD83C\uDF51","\uD83C\uDF4D","\uD83E\uDD5D","\uD83C\uDF45","\uD83E\uDD51","\uD83C\uDF3D","\uD83E\uDD55","\uD83C\uDF5E","\uD83E\uDDC0","\uD83C\uDF57","\uD83C\uDF54","\uD83C\uDF5F","\uD83C\uDF55","\uD83C\uDF2D","\uD83C\uDF2E","\uD83C\uDF5C","\uD83C\uDF5D","\uD83C\uDF63","\uD83C\uDF66","\uD83C\uDF70","\uD83C\uDF6A","\uD83C\uDF6B","\uD83C\uDF7F","\u2615","\uD83E\uDD64","\uD83C\uDF75","\uD83E\uDDC3"
        ),
        "Activities & sports" to listOf(
            "\u26BD","\uD83C\uDFC0","\uD83C\uDFC8","\u26BE","\uD83C\uDFBE","\uD83C\uDFD0","\uD83C\uDFB3","\uD83C\uDFD3","\uD83E\uDD45","\u26F3","\uD83C\uDFA3","\uD83C\uDFBD","\uD83D\uDEB4","\uD83C\uDFCA","\uD83C\uDFC6","\uD83E\uDD47","\uD83E\uDD48","\uD83E\uDD49","\uD83C\uDFAF","\uD83C\uDFAE","\uD83C\uDFB2","\uD83C\uDFAD","\uD83C\uDFA8","\uD83C\uDFAC","\uD83C\uDFA4","\uD83C\uDFA7","\uD83C\uDFB8","\uD83C\uDFB9","\uD83C\uDFBA","\uD83E\uDD41","\uD83C\uDFC1"
        ),
        "Travel & places" to listOf(
            "\uD83D\uDE97","\uD83D\uDE95","\uD83D\uDE8C","\uD83D\uDE91","\uD83D\uDE92","\uD83D\uDE93","\uD83D\uDE9A","\uD83D\uDE9C","\uD83D\uDEB2","\uD83C\uDFCD\uFE0F","\u2708\uFE0F","\uD83D\uDE80","\uD83D\uDEA2","\u26F5","\uD83D\uDE82","\uD83D\uDE87","\uD83C\uDFE0","\uD83C\uDFE5","\uD83C\uDFEB","\uD83C\uDFE6","\u26EA","\uD83D\uDD4D","\uD83C\uDFD4\uFE0F","\uD83C\uDFD6\uFE0F","\uD83C\uDF06","\uD83C\uDF09","\uD83C\uDFAA"
        ),
        "Objects" to listOf(
            "\uD83D\uDCF1","\u260E\uFE0F","\uD83D\uDCBB","\u231A","\u23F0","\uD83D\uDCF7","\uD83D\uDCFA","\uD83D\uDCA1","\uD83D\uDD26","\uD83D\uDD0B","\uD83D\uDD0C","\uD83D\uDCB0","\uD83D\uDCB3","\uD83D\uDCE6","\u2709\uFE0F","\uD83D\uDCDD","\uD83D\uDCDA","\uD83D\uDCD6","\u2702\uFE0F","\uD83D\uDD11","\uD83D\uDD12","\uD83D\uDD28","\uD83E\uDE9B","\uD83D\uDEE0\uFE0F","\u2699\uFE0F","\uD83E\uDDF9","\uD83E\uDDFA","\uD83E\uDDFB","\uD83D\uDECD\uFE0F","\uD83C\uDF81","\uD83C\uDF88","\uD83C\uDF89"
        ),
        "Hearts & symbols" to listOf(
            "\u2764\uFE0F","\uD83E\uDDE1","\uD83D\uDC9B","\uD83D\uDC9A","\uD83D\uDC99","\uD83D\uDC9C","\uD83D\uDDA4","\uD83E\uDD0D","\uD83E\uDD0E","\uD83D\uDC94","\u2763\uFE0F","\uD83D\uDC95","\uD83D\uDC9E","\uD83D\uDC93","\uD83D\uDC97","\uD83D\uDC96","\uD83D\uDC98","\uD83D\uDC9D","\u2705","\u274C","\u2757","\u2753","\u26A0\uFE0F","\uD83D\uDEAB","\uD83D\uDCAF","\uD83D\uDD1E","\u267B\uFE0F","\u2B55","\uD83D\uDD14","\uD83C\uDF89","\uD83C\uDFB5","\uD83C\uDFB6","\uD83D\uDCA4","\uD83D\uDCA8","\uD83D\uDCA5","\uD83D\uDCAB","\uD83D\uDCA6"
        )
    )

    // ---- pick several in one go ----
    // OK on an emoji PICKS it (shown in the "Picked: …" strip); the grid
    // stays open. BACK in a grid returns to the categories and keeps the
    // picks. Right Done inserts all picks; left Back returns from the grid
    // to categories without losing them. Leaving categories also keeps picks
    // by inserting them before closing. DEL explicitly removes the last pick.

    private const val PREFS = "emoji_picker"
    private const val KEY_RECENT = "recent"
    private const val MAX_RECENT = 24

    private fun recents(activity: BaseActivity): List<String> =
        activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_RECENT, "").orEmpty().split("\n").filter { it.isNotBlank() }

    private fun saveRecent(activity: BaseActivity, e: String) {
        val list = ArrayList(recents(activity))
        list.remove(e); list.add(0, e)
        while (list.size > MAX_RECENT) list.removeAt(list.size - 1)
        activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
    }

    /** One modal window owns both pages and its bottom bar. No host-screen
     *  bar borrowing, post-show window resizing, or dismiss/reopen navigation. */
    private class Session {
        val picks = ArrayList<String>()
        var ended = false
        var categoryPosition = 0
    }

    fun show(activity: BaseActivity, target: EditText) {
        val session = Session()
        val hadIme = imeShown(activity, target)
        hideIme(activity, target)
        val dm = activity.resources.displayMetrics
        fun dp(n: Int) = ThemeUtils.dp(activity, n)
        val surface = ThemeUtils.attrColor(activity, R.attr.dsmsSurface, R.color.dialog_surface)
        val textColor = ThemeUtils.palette(activity)?.text
            ?: ThemeUtils.attrColor(activity, R.attr.dsmsText, R.color.dialog_text_disabled)
        val title = android.widget.TextView(activity).apply {
            textSize = 16f
            setTextColor(ThemeUtils.accentColor(activity))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val strip = android.widget.TextView(activity).apply {
            textSize = 13f
            setTextColor(textColor)
            gravity = android.view.Gravity.CENTER
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        val pages = android.widget.FrameLayout(activity)
        val bar = io.github.theonionsarewatching.nova.databinding.ViewSoftkeyBarBinding.inflate(activity.layoutInflater)
        ThemeUtils.styleSoftkeyBar(bar)
        bar.root.minimumHeight = dp(44)
        bar.root.isBaselineAligned = false
        bar.root.clipChildren = true
        bar.root.clipToPadding = true
        for (label in arrayOf(bar.softLeft, bar.softCenter, bar.softRight)) {
            label.gravity = android.view.Gravity.CENTER
            label.setSingleLine(true)
            label.textSize = 14f
            label.setPadding(dp(4), dp(8), dp(4), dp(8))
            label.minimumHeight = dp(44)
            label.includeFontPadding = true
            label.layoutParams = (label.layoutParams as android.widget.LinearLayout.LayoutParams).apply { height = -1 }
            label.isFocusable = false
            label.setTextColor(textColor)
        }
        bar.softLeft.text = activity.getString(R.string.emoji_back)
        bar.softCenter.text = activity.getString(R.string.emoji_select)
        bar.softRight.apply {
            text = activity.getString(R.string.emoji_done)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeUtils.onAccent(activity))
            background = android.graphics.drawable.InsetDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(ThemeUtils.accentColor(activity))
                }, dp(4), dp(3), dp(4), dp(3))
            // A background assignment may replace TextView padding with drawable
            // insets. Restore it AFTER assignment; keep the full right-third cell.
            setPadding(dp(4), dp(8), dp(4), dp(8))
            minimumHeight = dp(44)
        }
        val root = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(surface)
            addView(title)
            addView(strip)
            // Zero base height is essential: the viewport receives ONLY the
            // space left above the fixed, non-weighted bottom bar.
            addView(pages, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bar.root, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = DsmsAlertBuilder(activity).create()
        dialog.setContentView(root, android.view.ViewGroup.LayoutParams(-1, -1))
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surface))
            decorView.setPadding(0, 0, 0, 0)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            // A normal inset-fitting window: native status/navigation areas stay.
            if (android.os.Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(true)
            setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        fun updateStrip() {
            strip.text = activity.getString(R.string.emoji_added, session.picks.joinToString(""))
            // Always reserve this single line: picking cannot resize either page.
            strip.visibility = if (session.picks.isEmpty()) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
        fun finish() {
            if (session.ended) return
            session.ended = true
            insertAll(activity, target, session)
            dialog.dismiss()
        }
        var onGrid = false
        var select: () -> Unit = {}
        lateinit var categoriesPage: () -> Unit
        lateinit var gridPage: (Pair<String, List<String>>) -> Unit
        gridPage = { cat ->
            onGrid = true
            val (name, emojis) = cat
            title.text = name
            val grid = GridView(activity).apply {
                numColumns = GridView.AUTO_FIT
                val scale = io.github.theonionsarewatching.nova.util.Prefs.get(activity).fontScale.coerceIn(0.8f, 1.6f)
                columnWidth = (56 * scale * dm.density).toInt()
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                setPadding(dp(8), dp(12), dp(8), dp(10))
                verticalSpacing = dp(4); horizontalSpacing = dp(4)
                isStackFromBottom = false
                isVerticalFadingEdgeEnabled = false
                clipToPadding = true
                isVerticalScrollBarEnabled = true
                val focusRing = android.graphics.drawable.InsetDrawable(
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat(); setColor(android.graphics.Color.TRANSPARENT)
                        setStroke(dp(3), ThemeUtils.accentColor(activity))
                    }, dp(1))
                selector = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), focusRing)
                    addState(intArrayOf(android.R.attr.state_pressed), focusRing)
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                }
                setDrawSelectorOnTop(true)
                adapter = object : android.widget.ArrayAdapter<String>(activity, R.layout.item_emoji, emojis) {
                    override fun getView(position: Int, old: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                        val cell = super.getView(position, old, parent) as android.widget.TextView
                        cell.textSize = 30f
                        cell.includeFontPadding = true
                        cell.setSingleLine(true)
                        cell.setPadding(dp(4), dp(8), dp(4), dp(8))
                        cell.minHeight = (56 * scale * dm.density).toInt()
                        cell.background = if (emojis[position] in session.picks)
                            android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius = dp(8).toFloat()
                                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(ThemeUtils.accentColor(activity), 0x28))
                                setStroke(dp(1), ThemeUtils.accentColor(activity))
                            } else null
                        return cell
                    }
                }
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        view?.requestRectangleOnScreen(android.graphics.Rect(0, -dp(3), view.width, view.height + dp(3)), true)
                    }
                }
                setOnItemClickListener { _, _, pos, _ ->
                    val emoji = emojis[pos]
                    if (!session.picks.remove(emoji)) session.picks.add(emoji)
                    updateStrip()
                    (adapter as android.widget.BaseAdapter).notifyDataSetChanged()
                }
            }
            pages.removeAllViews()
            pages.addView(grid, android.widget.FrameLayout.LayoutParams(-1, -1))
            select = {
                val pos = grid.selectedItemPosition.takeIf { it >= 0 } ?: 0
                if (pos in emojis.indices) grid.performItemClick(grid.selectedView, pos, 0)
            }
            grid.requestFocus()
            grid.setSelection(0)
        }
        categoriesPage = {
            onGrid = false
            title.text = activity.getString(R.string.attach_menu_emoji)
            val cats = allCategories(activity)
            val labels = cats.map { (name, emojis) -> emojis.first() + "  " + name }
            val list = android.widget.ListView(activity).apply {
                adapter = object : android.widget.ArrayAdapter<String>(activity, R.layout.dialog_item_centered, android.R.id.text1, labels) {
                    override fun getView(position: Int, old: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                        return (super.getView(position, old, parent) as android.widget.TextView).apply {
                            // Explicit matching fill/foreground, including selected state.
                            setTextColor(ThemeUtils.dialogRowTextColors(activity))
                        }
                    }
                }
                selector = ThemeUtils.dialogRowBackground(activity)
                divider = null
                clipToPadding = true
                isVerticalScrollBarEnabled = true
                setOnItemClickListener { _, _, pos, _ ->
                    session.categoryPosition = pos
                    gridPage(cats[pos])
                }
            }
            pages.removeAllViews()
            pages.addView(list, android.widget.FrameLayout.LayoutParams(-1, -1))
            select = {
                val pos = list.selectedItemPosition.takeIf { it >= 0 } ?: 0
                if (pos in cats.indices) list.performItemClick(list.selectedView, pos, 0)
            }
            list.requestFocus()
            list.setSelection(session.categoryPosition.coerceIn(0, cats.lastIndex))
        }
        fun back() {
            if (onGrid) categoriesPage() else finish() // never silently discard picks
        }
        bar.softLeft.setOnClickListener { back() }
        bar.softCenter.setOnClickListener { select() }
        bar.softRight.setOnClickListener { finish() }
        val prefs = io.github.theonionsarewatching.nova.util.Prefs.get(activity)
        dialog.setOnKeyListener { _, code, event ->
            val action: (() -> Unit)? = when (code) {
                android.view.KeyEvent.KEYCODE_BACK -> { { back() } }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> { { select() } }
                android.view.KeyEvent.KEYCODE_DEL, android.view.KeyEvent.KEYCODE_CLEAR -> { {
                    if (session.picks.isNotEmpty()) {
                        session.picks.removeAt(session.picks.lastIndex)
                        updateStrip()
                        ((pages.getChildAt(0) as? android.widget.AbsListView)?.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                    } else back()
                } }
                else -> when {
                    code == android.view.KeyEvent.KEYCODE_SOFT_RIGHT || code == prefs.softkeyRightCode -> { { finish() } }
                    code == android.view.KeyEvent.KEYCODE_SOFT_LEFT || code == prefs.softkeyLeftCode -> { { back() } }
                    code == android.view.KeyEvent.KEYCODE_MENU -> null // swallow unassigned echo, not a second action
                    else -> return@setOnKeyListener false
                }
            }
            if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled && event.repeatCount == 0) action?.invoke()
            true
        }
        dialog.setOnDismissListener {
            if (!session.ended) { session.ended = true; insertAll(activity, target, session) }
            if (hadIme) target.postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) {
                    target.requestFocus()
                    (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(target, 0)
                }
            }, 120)
        }
        updateStrip()
        categoriesPage()
        // Content, bar and native-area policy are final BEFORE the first frame.
        dialog.show()
    }

    private fun imeShown(activity: BaseActivity, target: EditText): Boolean = try {
        target.hasFocus() && (android.os.Build.VERSION.SDK_INT < 30 || activity.window.decorView.rootWindowInsets
            ?.isVisible(android.view.WindowInsets.Type.ime()) != false)
    } catch (_: Exception) { target.hasFocus() }

    private fun hideIme(activity: BaseActivity, target: EditText) {
        val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(target.windowToken, 0)
        imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
    }

    private fun insertAll(activity: BaseActivity, target: EditText, session: Session) {
        if (session.picks.isEmpty()) return
        val all = session.picks.joinToString("")
        val text = target.text
        val at = target.selectionStart.coerceIn(0, text.length)
        val end = target.selectionEnd.coerceIn(at, text.length)
        text.replace(at, end, all)
        target.setSelection((at + all.length).coerceAtMost(target.text.length))
        session.picks.forEach { saveRecent(activity, it) }
        session.picks.clear()
    }

    private fun allCategories(activity: BaseActivity): List<Pair<String, List<String>>> {
        val recent = recents(activity)
        return if (recent.isEmpty()) categories else listOf(activity.getString(R.string.emoji_recent) to recent) + categories
    }
}
