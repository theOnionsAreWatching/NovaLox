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

    const val ALL_THREADS = -1L

    /** A broad, ordered palette — dark tones first, then light. Scrollable. */
    val COLORS = listOf(
        "#101418", "#0E1A24", "#0E2018", "#241014", "#1E1428", "#1A1A1A", "#22160E", "#0A1F1F",
        "#1A2633", "#14261A", "#2A1A1A", "#26203A", "#2B2B2B", "#33240E", "#14243A", "#331A26",
        "#2E3B47", "#28402E", "#402828", "#3A3050", "#3D3D3D", "#4A3618", "#1E3450", "#4A2836",
        "#5A6B78", "#4E6E52", "#6E4E4E", "#5E5478", "#666666", "#7A5A2E", "#2E5A8A", "#7A4E5E",
        "#F4EFE6", "#E7EEF6", "#EAF4EA", "#F6E7EA", "#FFFFFF", "#FBF3E7", "#E7F4F4", "#F0E7F6",
        "#D8CFC0", "#CBD8E6", "#CBE6CB", "#E6CBD2", "#E8E8E8", "#EAD8B8", "#B8D8E8", "#E0C8D2"
    )

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
                    1 -> colorGrid(activity, prefs, convoId, host)
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

    private fun colorGrid(activity: Activity, prefs: Prefs, convoId: Long, host: Host) {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        var dialog: AlertDialog? = null
        val perRow = 6
        var i = 0
        while (i < COLORS.size) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            var c = 0
            while (c < perRow && i < COLORS.size) {
                val hex = COLORS[i]
                val swatch = View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        setMargins(dp(5), dp(5), dp(5), dp(5))
                    }
                    // ring visible on any swatch: a light halo + dark hairline,
                    // so pale colors show a dark edge and dark colors a light one
                    background = android.graphics.drawable.LayerDrawable(arrayOf(
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor(hex))
                            setStroke(dp(2), 0x66FFFFFF.toInt())
                        },
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.TRANSPARENT)
                            setStroke(dp(1), 0x66000000)
                        }
                    ))
                    isFocusable = true
                    setOnClickListener {
                        prefs.setChatBg(convoId, hex)
                        host.applyBackgroundForCurrent()
                        dialog?.dismiss()
                    }
                }
                row.addView(swatch)
                i++; c++
            }
            grid.addView(row)
        }
        val scroll = ScrollView(activity).apply { addView(grid) }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.bg_color)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    const val REQ_BG_GALLERY = 208
    const val REQ_BG_GALLERY_ALL = 209
    const val REQ_BG_DOC_ALL = 210
}
