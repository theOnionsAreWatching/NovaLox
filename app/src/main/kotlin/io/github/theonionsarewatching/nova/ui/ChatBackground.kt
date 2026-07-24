package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.util.Prefs

/**
 * One chat-background chooser used everywhere: the thread menu, a long-press on
 * the conversation list, and the global Settings row. A [convoId] of
 * [ALL_THREADS] means "apply to every conversation" (stored as an app-wide
 * default that individual threads fall back to).
 */
object ChatBackground {

    private fun accentOf(activity: Activity): Int =
        io.github.theonionsarewatching.nova.ui.ThemeUtils.accentColor(activity)


    const val ALL_THREADS = -1L

    /** Main color families; each opens a shades submenu. Rows are full-width,
     *  so nothing clips on narrow screens. */
    private data class Family(val nameRes: Int, val hue: Float, val gray: Boolean = false)
    private val FAMILIES = listOf(
        Family(R.string.color_gray, 0f, gray = true),
        Family(R.string.color_red, 2f),
        Family(R.string.color_orange, 28f),
        Family(R.string.color_yellow, 48f),
        Family(R.string.color_green, 130f),
        Family(R.string.color_teal, 176f),
        Family(R.string.color_blue, 220f),
        Family(R.string.color_purple, 276f),
        Family(R.string.color_pink, 330f)
    )

    /** Dark -> light, generated in HSL rather than HSV.
     *
     *  HSV "value" is not perceived lightness: at V=0.42 a blue looks properly
     *  dark while a yellow or cyan still looks mid-bright, so a row of swatches
     *  built by varying V doesn't read as one family's shades and the chosen
     *  colour rarely matches the label. HSL lightness is far closer to what
     *  people mean by "a lighter/darker shade of this colour", so the same
     *  ramp now looks consistent across every family. */
    private fun shadesOf(f: Family): List<Int> {
        val steps = listOf(0.22f, 0.31f, 0.40f, 0.50f, 0.60f, 0.70f, 0.81f, 0.91f)
        if (f.gray) {
            return steps.map { l ->
                androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(0f, 0f, l))
            }
        }
        // saturation eases off at the extremes so the darkest step doesn't go
        // muddy and the lightest doesn't wash out to near-white
        return steps.map { l ->
            val sat = when {
                l <= 0.30f -> 0.72f
                l >= 0.85f -> 0.70f
                else -> 0.82f
            }
            androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(f.hue, sat, l))
        }
    }

    /** The family -> shades chooser, reusable anywhere a color is needed.
     *  topOptionRes pins a special first row (e.g. "Match accent color"). */
    fun chooseColor(
        activity: Activity, topOptionRes: Int? = null,
        topShowsAccent: Boolean = true,
        onTop: (() -> Unit)? = null, onPicked: (String) -> Unit
    ) {
        colorGrid(activity, topOptionRes, topShowsAccent, onTop, onPicked)
    }

    private fun colorGrid(
        activity: Activity, topOptionRes: Int? = null,
        topShowsAccent: Boolean = true,
        onTop: (() -> Unit)? = null, onPicked: (String) -> Unit
    ) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        var dialog: AlertDialog? = null
        if (topOptionRes != null) {
            val top = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = false
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            // the accent swatch belongs to "match accent colour" options only.
            // A background's "App default" is not the accent, so it gets a
            // neutral placeholder instead of a misleading coloured dot.
            top.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(12)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (topShowsAccent) setColor(accentOf(activity))
                    else setColor(0x00000000)
                    setStroke(dp(1), 0x66000000)
                }
            })
            top.addView(TextView(activity).apply {
                text = activity.getString(topOptionRes)
                textSize = 15f
            })
            io.github.theonionsarewatching.nova.ui.ThemeUtils.applyContrastFocusHighlight(top)
            top.setOnClickListener {
                dialog?.dismiss()
                onTop?.invoke()
            }
            column.addView(top)
        }
        for (f in FAMILIES) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = false
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            // representative swatch: the family's vivid shade
            val mid = shadesOf(f)[2]
            row.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(12)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(mid)
                    setStroke(dp(1), 0x66000000)
                }
            })
            row.addView(TextView(activity).apply {
                text = activity.getString(f.nameRes)
                textSize = 15f
            })
            io.github.theonionsarewatching.nova.ui.ThemeUtils.applyContrastFocusHighlight(row)
            row.setOnClickListener {
                dialog?.dismiss()
                shadesDialog(activity, f, onPicked)
            }
            column.addView(row)
        }
        val scroll = ScrollView(activity).apply { addView(column) }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.bg_color)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shadesDialog(activity: Activity, f: Family, onPicked: (String) -> Unit) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        var dialog: AlertDialog? = null
        for (shade in shadesOf(f)) {
            val hex = String.format("#%06X", 0xFFFFFF and shade)
            val bar = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(shade)
                    setStroke(dp(1), 0x55000000)
                }
                isFocusable = true
                isFocusableInTouchMode = false
                setOnClickListener {
                    dialog?.dismiss()
                    onPicked(hex)
                }
            }
            // bright focus ring on the D-pad-selected shade
            val normal = bar.background
            val ring = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(shade)
                setStroke(dp(3), accentOf(activity))
            }
            bar.setOnFocusChangeListener { v, has -> v.background = if (has) ring else normal }
            column.addView(bar)
        }
        val scroll = ScrollView(activity).apply { addView(column) }
        dialog = AlertDialog.Builder(activity)
            .setTitle(f.nameRes)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    interface Host {
        fun applyBackgroundForCurrent()
        fun startPicturePickerForBackground(convoId: Long)
    }

    /**
     * The background menu. Consolidated: "App default" is now the first row of
     * the colour chooser rather than a separate top-level item, and the old
     * separate "Picture" (document picker) and "Pick from gallery" rows are one
     * "Choose picture" that offers every capable app.
     */
    fun show(activity: Activity, prefs: Prefs, convoId: Long, host: Host) {
        val items = arrayOf(
            activity.getString(R.string.bg_color),
            activity.getString(R.string.bg_choose_picture),
            activity.getString(R.string.bg_dark_theme)
        )
        AlertDialog.Builder(activity)
            .setTitle(if (convoId == ALL_THREADS) R.string.bg_all_title else R.string.chat_background)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> chooseColor(
                        activity,
                        topOptionRes = R.string.bg_default,
                        topShowsAccent = false,
                        onTop = {
                            prefs.setChatBg(convoId, "")
                            host.applyBackgroundForCurrent()
                        }
                    ) { hex ->
                        prefs.setChatBg(convoId, hex)
                        host.applyBackgroundForCurrent()
                        android.widget.Toast.makeText(
                            activity, R.string.background_set,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    1 -> pickPicture(activity, convoId)
                    2 -> darkThemeDialog(activity, prefs, host)
                }
            }
            .show()
    }

    /** What the background does in dark theme. The switch stays live inside
     *  the dialog: toggling it reveals or hides the colour/picture rows rather
     *  than closing everything. */
    private fun darkThemeDialog(activity: Activity, prefs: Prefs, host: Host) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val sw = androidx.appcompat.widget.SwitchCompat(activity).apply {
            text = activity.getString(R.string.bg_dark_same)
            textSize = 15f
            isChecked = prefs.darkChatBg == "same"
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isFocusable = true
        }
        column.addView(sw)
        val options = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(options)

        var dialog: AlertDialog? = null
        fun row(labelRes: Int, onClick: () -> Unit): View =
            TextView(activity).apply {
                text = activity.getString(labelRes)
                textSize = 15f
                isFocusable = true
                setPadding(dp(14), dp(14), dp(14), dp(14))
                io.github.theonionsarewatching.nova.ui.ThemeUtils
                    .applyContrastFocusHighlight(this)
                setOnClickListener { onClick() }
            }

        fun rebuild() {
            options.removeAllViews()
            if (sw.isChecked) return
            options.addView(row(R.string.bg_color) {
                dialog?.dismiss()
                chooseColor(
                    activity, topOptionRes = R.string.bg_default,
                    topShowsAccent = false,
                    onTop = {
                        prefs.darkChatBg = "default"
                        host.applyBackgroundForCurrent()
                    }
                ) { hex ->
                    prefs.darkChatBg = hex
                    host.applyBackgroundForCurrent()
                }
            })
            options.addView(row(R.string.bg_choose_picture) {
                dialog?.dismiss()
                pickPicture(activity, DARK_THEME)
            })
        }

        sw.setOnCheckedChangeListener { _, checked ->
            prefs.darkChatBg = if (checked) "same" else "default"
            host.applyBackgroundForCurrent()
            rebuild()
        }
        rebuild()

        val scroll = ScrollView(activity).apply { addView(column) }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.bg_dark_theme)
            .setView(scroll)
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /** One picture chooser offering every gallery / photos / files app. */
    private fun pickPicture(activity: Activity, convoId: Long) {
        val req = when (convoId) {
            DARK_THEME -> REQ_BG_DARK
            ALL_THREADS -> REQ_BG_GALLERY_ALL
            else -> REQ_BG_GALLERY
        }
        try {
            // GET_CONTENT + a chooser so EVERY gallery/photos/files app is
            // offered, not just the system default
            val base = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            val chooser = Intent.createChooser(base, activity.getString(R.string.bg_pick_app))
            activity.startActivityForResult(chooser, req)
        } catch (_: Exception) {
            try {
                val i = Intent(Intent.ACTION_PICK).apply {
                    setDataAndType(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"
                    )
                }
                activity.startActivityForResult(i, req)
            } catch (_: Exception) {
                android.widget.Toast.makeText(
                    activity, R.string.no_gallery, android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** sentinel target: the picture is for the dark-theme background */
    const val DARK_THEME = -2L

    const val REQ_BG_DARK = 211
    const val REQ_BG_GALLERY = 208
    const val REQ_BG_GALLERY_ALL = 209
    const val REQ_BG_DOC_ALL = 210
}
