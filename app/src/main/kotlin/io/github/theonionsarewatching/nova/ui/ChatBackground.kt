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
        Family(R.string.color_red, 0f),
        Family(R.string.color_orange, 28f),
        Family(R.string.color_yellow, 52f),
        Family(R.string.color_green, 120f),
        Family(R.string.color_teal, 175f),
        Family(R.string.color_blue, 215f),
        Family(R.string.color_purple, 268f),
        Family(R.string.color_pink, 330f)
    )

    private fun shadesOf(f: Family): List<Int> {
        return if (f.gray) {
            listOf(0.06f, 0.15f, 0.25f, 0.40f, 0.55f, 0.72f, 0.87f, 0.97f)
                .map { v -> Color.HSVToColor(floatArrayOf(0f, 0f, v)) }
        } else {
            listOf(
                0.62f to 0.22f, 0.62f to 0.35f, 0.60f to 0.50f, 0.55f to 0.65f,
                0.48f to 0.80f, 0.36f to 0.90f, 0.22f to 0.96f, 0.10f to 0.98f
            ).map { (sat, v) -> Color.HSVToColor(floatArrayOf(f.hue, sat, v)) }
        }
    }

    interface Host {
        fun applyBackgroundForCurrent()
        fun startPicturePickerForBackground(convoId: Long)
    }

    fun show(activity: Activity, prefs: Prefs, convoId: Long, host: Host) {
        val items = arrayOf(
            activity.getString(R.string.bg_default),
            activity.getString(R.string.bg_color),
            activity.getString(R.string.bg_picture),
            activity.getString(R.string.bg_gallery)
        )
        AlertDialog.Builder(activity)
            .setTitle(if (convoId == ALL_THREADS) R.string.bg_all_title else R.string.chat_background)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { prefs.setChatBg(convoId, ""); host.applyBackgroundForCurrent() }
                    1 -> chooseColor(activity) { hex ->
                        prefs.setChatBg(convoId, hex)
                        host.applyBackgroundForCurrent()
                        android.widget.Toast.makeText(activity, R.string.background_set,
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                    2 -> host.startPicturePickerForBackground(convoId)   // ACTION_OPEN_DOCUMENT
                    3 -> pickFromGallery(activity, convoId)               // ACTION_PICK (no file picker needed)
                }
            }
            .show()
    }

    private fun pickFromGallery(activity: Activity, convoId: Long) {
        val req = if (convoId == ALL_THREADS) REQ_BG_GALLERY_ALL else REQ_BG_GALLERY
        try {
            // GET_CONTENT + a chooser so EVERY gallery/photos app is offered,
            // not just the system default (same pattern as picking attachments)
            val base = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            val chooser = Intent.createChooser(base, activity.getString(R.string.bg_pick_app))
            activity.startActivityForResult(chooser, req)
        } catch (_: Exception) {
            // fall back to a direct gallery pick if no chooser can be built
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

    /** The family -> shades chooser, reusable anywhere a color is needed. */
    fun chooseColor(activity: Activity, onPicked: (String) -> Unit) {
        colorGrid(activity, onPicked)
    }

    private fun colorGrid(activity: Activity, onPicked: (String) -> Unit) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        var dialog: AlertDialog? = null
        for (f in FAMILIES) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = false
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            // representative swatch: the family's mid shade
            val mid = shadesOf(f)[3]
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
            io.github.theonionsarewatching.nova.ui.ThemeUtils.applyFocusHighlight(row)
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

    const val REQ_BG_GALLERY = 208
    const val REQ_BG_GALLERY_ALL = 209
    const val REQ_BG_DOC_ALL = 210
}
