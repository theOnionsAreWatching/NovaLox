package io.github.theonionsarewatching.nova.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.widget.Toast

/**
 * the ONE toast for the whole app. Every short notice
 * ("Saved", "Copied", errors…) used Toast.makeText, which shows the
 * phone's plain gray system bubble. This draws the app's own: a rounded
 * pill in the user's CURRENT theme accent (whatever color / light-dark /
 * system-color theme is chosen in the app; read at show time from the
 * front screen's theme) with black or white bold text, whichever has the
 * better contrast on that accent. Short and readable on 240x320, RTL-aware.
 *
 * Same call shape as Toast.makeText, so every call site just swaps the
 * name and keeps its .show().
 *
 * Android 11+ only shows custom-view toasts while the app is in front;
 * a toast fired from the background (sending, background tasks) falls
 * back to the plain system toast so it is never lost.
 */
object DsmsToast {

    fun makeText(context: Context, resId: Int, duration: Int): Toast =
        makeText(context, context.getString(resId), duration)

    fun makeText(context: Context, text: CharSequence, duration: Int): Toast {
        if (!inForeground(context)) {
            return Toast.makeText(context.applicationContext, text, duration)
        }
        val ctx = ThemeUtils.themedContext(context)
        val dm = ctx.resources.displayMetrics
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()
        val accent = try { ThemeUtils.accentColor(ctx) } catch (_: Exception) {
            // no fixed color: the theme's own primary color
            val tv = TypedValue()
            if (ctx.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)) tv.data
            else Color.DKGRAY
        }
        val fg = ThemeUtils.onColor(accent)
        val rim = io.github.theonionsarewatching.nova.ui.ThemeUtils.attrColor(
            ctx, io.github.theonionsarewatching.nova.R.attr.dsmsRim, io.github.theonionsarewatching.nova.R.color.dialog_rim)
        val tv = TextView(ctx).apply {
            this.text = text
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            maxLines = 4
            maxWidth = (dm.widthPixels * 0.86f).toInt()
            setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18f).toFloat()
                setColor(accent)
                setStroke(dp(1f), rim)
            }
            elevation = dp(4f).toFloat()
        }
        @Suppress("DEPRECATION")
        return Toast(ctx.applicationContext).apply {
            this.duration = duration
            view = tv
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(48f))
        }
    }

    private fun inForeground(context: Context): Boolean = try {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    } catch (_: Exception) { false }
}
