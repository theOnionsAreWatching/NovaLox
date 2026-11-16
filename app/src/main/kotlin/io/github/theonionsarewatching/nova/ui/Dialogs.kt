package io.github.theonionsarewatching.nova.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.github.theonionsarewatching.nova.R

object Dialogs {
    /** Centered list rows: only for the pop-ups that keep them (emoji
     *  categories, full Attach list). Other lists use the compact
     *  left-aligned rows from select_dialog_item_material. */
    fun centeredItems(
        b: androidx.appcompat.app.AlertDialog.Builder, labels: Array<String>, onClick: (Int) -> Unit
    ): androidx.appcompat.app.AlertDialog.Builder =
        b.setAdapter(android.widget.ArrayAdapter(
            b.context, R.layout.dialog_item_centered, android.R.id.text1, labels
        )) { _, which -> onClick(which) }

    /** Centered pop-ups (Attach lists, Camera, emoji categories): one
     *  "middle" width, about 3/4 of the screen, wider only when a label
     *  needs it (the 2-item pop-up was too wide, the long
     *  Attach list too narrow). */
    fun narrowToLabels(d: android.app.Dialog, labels: Array<String>) {
        val dm = d.context.resources.displayMetrics
        val paint = android.graphics.Paint().apply {
            textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 16f, dm)
        }
        val widest = labels.maxOfOrNull { paint.measureText(it) } ?: 0f
        val w = (widest + 56 * dm.density).toInt()
            .coerceIn((dm.widthPixels * 0.76f).toInt(), (dm.widthPixels * 0.92f).toInt())
        d.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        d.window?.setGravity(android.view.Gravity.CENTER)
        // the Attach pop-ups keep this centered width; the general
        // width fitting (DialogPolish.fitWidth) leaves them alone
        d.window?.decorView?.setTag(R.id.dsms_fixed_width, true)
    }

    /** Compact pop-up action row (player / recorder): centered text, tight
     *  height, accent pill on focus; clicks (OK) act on key-up like list rows. */
    fun compactRow(activity: Activity, label: String, onClick: () -> Unit): TextView {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        return TextView(activity).apply {
            text = label
            textSize = 15f
            minHeight = dp(34)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(12), dp(3), dp(12), dp(3))
            isFocusable = true
            ThemeUtils.applyDialogRowFocus(this)
            background = ThemeUtils.dialogRowBackground(activity, insetHDp = 2, insetVDp = 1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
    }

    /** Narrow centered pill button (Play / Record): accent outline at rest,
     *  filled accent when focused. */
    fun pillButton(activity: Activity, label: String): TextView {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        return TextView(activity).apply {
            text = label
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(18), dp(5), dp(18), dp(5))
            minWidth = dp(96)
            maxLines = 1
            isFocusable = true
            val accent = ThemeUtils.accentColor(activity)
            val radius = dp(17).toFloat()
            val filled = android.graphics.drawable.GradientDrawable().apply {
                setColor(accent); cornerRadius = radius
            }
            val outline = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(2), accent); cornerRadius = radius
            }
            background = android.graphics.drawable.StateListDrawable().apply {
                setExitFadeDuration(80)
                addState(intArrayOf(android.R.attr.state_focused), filled)
                addState(intArrayOf(android.R.attr.state_pressed), filled)
                addState(intArrayOf(), outline)
            }
            ContextCompat.getColorStateList(activity, R.color.dialog_item_text)?.let { setTextColor(it) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) }
        }
    }

    /** the main action (Play): always filled with the
     *  accent, a contrasting ring when the cursor is on it, a bit larger. */
    fun primaryPill(activity: Activity, label: String): TextView {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val accent = ThemeUtils.accentColor(activity)
        val on = ThemeUtils.onColor(accent)
        val radius = dp(19).toFloat()
        return TextView(activity).apply {
            text = label
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(22), dp(6), dp(22), dp(6))
            minWidth = dp(120)
            maxLines = 1
            isFocusable = true
            setTextColor(on)
            val focused = android.graphics.drawable.GradientDrawable().apply {
                setColor(accent); cornerRadius = radius
                setStroke(dp(2), androidx.core.graphics.ColorUtils.setAlphaComponent(on, 0xCC))
            }
            val idle = android.graphics.drawable.GradientDrawable().apply {
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0xB0))
                cornerRadius = radius
            }
            background = android.graphics.drawable.StateListDrawable().apply {
                setExitFadeDuration(80)
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), idle)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) }
        }
    }

    /** Small secondary pill (Save, Open with…): accent outline, filled when
     *  the cursor is on it; [weighted] = shares a row with another pill. */
    fun smallPill(activity: Activity, label: String, weighted: Boolean, onClick: () -> Unit): TextView =
        pillButton(activity, label).apply {
            val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
            textSize = 14f
            minWidth = 0
            setPadding(dp(8), dp(3), dp(8), dp(3))
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = (if (weighted) LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                marginStart = dp(3); marginEnd = dp(3)
            }
            setOnClickListener { onClick() }   // View clicks fire on key-up
        }

    /** an on/off row — label + a drawn switch (accent track
     *  with the knob at the end when on, gray with the knob at the start
     *  when off). One press (OK, on key-up) flips it; [state] reads it. */
    fun switchRow(activity: Activity, label: String, state: () -> Boolean, onToggle: () -> Unit): TextView {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val accent = ThemeUtils.accentColor(activity)
        val gray = ThemeUtils.attrColor(activity, R.attr.dsmsTextDisabled, R.color.dialog_text_disabled)
        val track = object : android.graphics.drawable.Drawable() {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun draw(c: android.graphics.Canvas) {
                val b = bounds
                val on = state()
                val r = b.height() / 2f
                paint.color = if (on) accent else gray
                c.drawRoundRect(android.graphics.RectF(b), r, r, paint)
                paint.color = if (on) ThemeUtils.onColor(accent) else android.graphics.Color.WHITE
                val cx = if (on xor (activity.resources.configuration.layoutDirection ==
                        View.LAYOUT_DIRECTION_RTL)) b.right - r else b.left + r
                c.drawCircle(cx, b.exactCenterY(), r - dp(2), paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth() = dp(30)
            override fun getIntrinsicHeight() = dp(16)
        }
        return TextView(activity).apply {
            text = label
            textSize = 15f
            minHeight = dp(34)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(dp(12), dp(3), dp(12), dp(3))
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, track, null)
            compoundDrawablePadding = dp(10)
            isFocusable = true
            ThemeUtils.applyDialogRowFocus(this)
            background = ThemeUtils.dialogRowBackground(activity, insetHDp = 2, insetVDp = 1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onToggle(); track.invalidateSelf(); invalidate() }
        }
    }

    /** One fixed status line (timer / short state), never wraps. */
    fun statusLine(activity: Activity, initial: String): TextView {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        return TextView(activity).apply {
            text = initial
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            isSingleLine = true
            setLines(1)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Long dialog text on tiny screens: guaranteed scrollable, never cut off. */
    fun scrollableMessage(activity: Activity, textRes: Int): View =
        scrollableMessageText(activity, activity.getString(textRes))

    fun scrollableMessageText(activity: Activity, text: CharSequence): View {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val scroll = android.widget.ScrollView(activity)
        val tv = TextView(activity).apply {
            setText(text)
            textSize = 14f
            setPadding(dp(22), dp(8), dp(22), dp(8))
        }
        scroll.addView(tv)
        return scroll
    }

    /** Instructions live outside single-line hints, so they wrap and remain
     * visible while typing. No fixed height/ellipsis or post-show resizing. */
    fun inputHelp(activity: Activity, text: CharSequence): TextView = TextView(activity).apply {
        this.text = text; textSize = 13f
        includeFontPadding = true; ellipsize = null; maxLines = Int.MAX_VALUE
        setTextColor(ThemeUtils.attrColor(activity, R.attr.dsmsText, R.color.text_primary))
        setPadding(0, 0, 0, ThemeUtils.dp(activity, 6))
        isFocusable = false
    }
    fun inputPanel(activity: Activity, input: android.widget.EditText, help: CharSequence): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ThemeUtils.dp(activity,16), ThemeUtils.dp(activity,8), ThemeUtils.dp(activity,16), 0)
            addView(inputHelp(activity, help), LinearLayout.LayoutParams(-1,-2))
            addView(input, LinearLayout.LayoutParams(-1,-2))
        }
        return android.widget.ScrollView(activity).apply { addView(column); isFocusable = false }
    }

    /** Pop-up title with a short accent line underneath (soft look;
     *  same as the themed titles, see drawable/bg_dialog_title). */
    fun title(activity: Activity, text: String): View {
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        // centered title + centered short accent bar
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            // bottom: a clear 6dp gap under the accent line; the title is
            // always a fixed header outside the scrolling list
            setPadding(dp(18), dp(12), dp(18), dp(6))   // top 16->12: more rows fit
        }
        val title = TextView(activity).apply {
            setText(text)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            // capped at two lines everywhere — a big group's member list must
            // not swallow the screen (same rule as the list's options header)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = true
            background = null
            setPadding(0, 0, 0, 0)
        }
        val line = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(3)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ThemeUtils.accentColor(activity))
                cornerRadius = dp(2).toFloat()
            }
        }
        box.addView(title)
        box.addView(line)
        return box
    }
    /** Soft-key label footer for a pop-up: the labels sit
     *  where the phone's soft keys are (left / right), accent and bold like
     *  the screens' soft-key bar, so a soft-key action is always visible.
     *  The labels can also be tapped. */
    fun softKeyFooter(
        activity: android.app.Activity, left: String?, right: String?,
        onLeft: (() -> Unit)? = null, onRight: (() -> Unit)? = null,
    ): android.view.View {
        val ctx = activity
        fun dp(v: Int) = ThemeUtils.dp(ctx, v)
        val a = ThemeUtils.accentColor(ctx)
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(5), dp(14), dp(7))
        }
        fun label(t: String?, g: Int, click: (() -> Unit)?) = android.widget.TextView(ctx).apply {
            text = t ?: ""
            textSize = 14f
            setTextColor(a)
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            gravity = g or android.view.Gravity.CENTER_VERTICAL
            isFocusable = false
            maxLines = 1
            if (click != null) setOnClickListener { click() }
        }
        row.addView(label(left, android.view.Gravity.START, onLeft),
            android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(right, android.view.Gravity.END, onRight),
            android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.view.View(ctx).apply {
                setBackgroundColor(ThemeUtils.attrColor(ctx, io.github.theonionsarewatching.nova.R.attr.dsmsDivider, io.github.theonionsarewatching.nova.R.color.divider))
            }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = dp(12); marginEnd = dp(12)
            })
            addView(row)
        }
    }
}

/** "Participants (N)" for group conversations: names + numbers, with quick
 *  message-privately / call actions on each. */
object GroupParticipants {
    fun show(activity: BaseActivity, convo: io.github.theonionsarewatching.nova.data.ConversationEntity) {
        val addresses = convo.addressList()
        val names = convo.cachedNames.split("|")
        val labels = addresses.mapIndexed { i, addr ->
            val name = names.getOrNull(i)?.takeIf { it.isNotBlank() }
            if (name != null) "$name  ($addr)" else addr
        }
        DsmsAlertBuilder(activity)
            .setCustomTitle(Dialogs.title(activity,
                activity.getString(R.string.participants_title, addresses.size)))
            .setItems(labels.toTypedArray()) { _, which ->
                val addr = addresses[which]
                // email participants can't be dialed — no Call row for them
                val actions = if (addr.contains("@"))
                    arrayOf(activity.getString(R.string.message_privately))
                else arrayOf(
                    activity.getString(R.string.message_privately),
                    activity.getString(R.string.call)
                )
                DsmsAlertBuilder(activity)
                    .setTitle(labels[which])
                    .setItems(actions) { _, action ->
                        when (action) {
                            0 -> activity.lifecycleScope.launch {
                                val c = io.github.theonionsarewatching.nova.data.Repo
                                    .get(activity).getOrCreateConversation(listOf(addr))
                                activity.startActivity(
                                    android.content.Intent(activity, ThreadActivity::class.java)
                                        .putExtra(ThreadActivity.EXTRA_CONVO_ID, c.id)
                                )
                            }
                            1 -> try {
                                activity.startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_DIAL,
                                    android.net.Uri.parse("tel:$addr")
                                ))
                            } catch (_: Exception) {}
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

}
