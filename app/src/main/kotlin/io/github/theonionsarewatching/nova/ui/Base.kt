package io.github.theonionsarewatching.nova.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import io.github.theonionsarewatching.nova.R
import io.github.theonionsarewatching.nova.databinding.ViewSoftkeyBarBinding
import io.github.theonionsarewatching.nova.util.Prefs

object ThemeUtils {

    fun applyNightMode(context: Context) {
        when (Prefs.get(context).theme) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun accentOverlay(context: Context): Int = when (Prefs.get(context).accent) {
        "teal" -> R.style.Accent_Teal
        "green" -> R.style.Accent_Green
        "orange" -> R.style.Accent_Orange
        "red" -> R.style.Accent_Red
        "purple" -> R.style.Accent_Purple
        "pink" -> R.style.Accent_Pink
        "gray" -> R.style.Accent_Gray
        else -> R.style.Accent_Blue
    }

    fun accentColor(context: Context): Int {
        if (Prefs.get(context).accent == "system") {
            val tv = android.util.TypedValue()
            if (context.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorPrimary, tv, true
                )
            ) return tv.data
        }
        return ContextCompat.getColor(
        context, when (Prefs.get(context).accent) {
            "teal" -> R.color.accent_teal
            "green" -> R.color.accent_green
            "orange" -> R.color.accent_orange
            "red" -> R.color.accent_red
            "purple" -> R.color.accent_purple
            "pink" -> R.color.accent_pink
            "gray" -> R.color.accent_gray
            else -> R.color.accent_blue
        }
    )
    }

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    /** Stroke-only focus ring (drawn OVER the item, sits at the perimeter). */
    fun focusStroke(context: Context, radiusDp: Int = 8, oval: Boolean = false): StateListDrawable {
        val stroke = dp(context, Prefs.get(context).focusStrokeDp)
        val accent = accentColor(context)
        val ring = GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(stroke, accent)
            if (!oval) cornerRadius = dp(context, radiusDp).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ring)
            addState(intArrayOf(android.R.attr.state_selected), ring)
            addState(intArrayOf(android.R.attr.state_pressed), ring)
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
    }

    /** Fill-only focus shade (used as BACKGROUND so it shows around the content —
     *  message bubbles, photos and avatars sit on top of it, untinted). */
    fun focusFill(context: Context, radiusDp: Int = 8, alpha: Int = 70): StateListDrawable {
        val accent = accentColor(context)
        val fill = GradientDrawable().apply {
            setColor(Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent)))
            cornerRadius = dp(context, radiusDp).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), fill)
            addState(intArrayOf(android.R.attr.state_selected), fill)
            addState(intArrayOf(android.R.attr.state_pressed), fill)
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
    }

    /** Combined stroke + fill overlay, for controls without imagery (inputs, icons). */
    fun focusForeground(context: Context, radiusDp: Int = 8, oval: Boolean = false): StateListDrawable {
        val stroke = dp(context, Prefs.get(context).focusStrokeDp)
        val accent = accentColor(context)
        val fill = Color.argb(56, Color.red(accent), Color.green(accent), Color.blue(accent))
        val focused = GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(stroke, accent)
            if (!oval) cornerRadius = dp(context, radiusDp).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_selected), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
    }

    /** List rows and boxes that CONTAIN content (photos, message bubbles):
     *  shade behind the content, ring around it — content itself stays clean. */
    fun applyRowFocus(vararg views: android.view.View) {
        for (v in views) {
            v.background = focusFill(v.context)
            v.foreground = focusStroke(v.context)
        }
    }

    /** Rounded-square highlight for plain fields. */
    fun applyFocusHighlight(vararg views: android.view.View) {
        for (v in views) v.foreground = focusForeground(v.context)
    }

    /** Circular highlight for round icon buttons. The platform ripple's own
     *  focus-darkening is removed so the shape can't mismatch our ring. */
    fun applyFocusHighlightRound(vararg views: android.view.View) {
        for (v in views) {
            v.background = null
            v.foreground = focusForeground(v.context, oval = true)
        }
    }

    /** Pill highlight for pill-shaped inputs (bounds == the pill). */
    fun applyFocusHighlightPill(vararg views: android.view.View) {
        for (v in views) v.foreground = focusForeground(v.context, radiusDp = 20)
    }

    /** MaterialButtons draw their pill INSET 6dp from the view bounds — match it,
     *  so the ring and shade hug the actual button instead of floating around it. */
    fun applyButtonFocus(vararg views: android.view.View) {
        for (v in views) {
            val inner = focusForeground(v.context, radiusDp = 20)
            v.foreground = android.graphics.drawable.InsetDrawable(inner, 0, dp(v.context, 6), 0, dp(v.context, 6))
            // stop the button's own ripple from ALSO darkening on focus — keep press only
            (v as? com.google.android.material.button.MaterialButton)?.rippleColor =
                android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                    intArrayOf(Color.argb(48, 255, 255, 255), Color.TRANSPARENT)
                )
        }
    }
    fun densityPad(context: Context): Int = when (Prefs.get(context).listDensity) {
        "compact" -> dp(context, 4)
        "spacious" -> dp(context, 14)
        else -> dp(context, 8)
    }
}

abstract class BaseActivity : AppCompatActivity() {

    protected lateinit var prefs: Prefs
    var softkeys: Softkeys? = null

    override fun attachBaseContext(newBase: Context) {
        val p = Prefs.get(newBase)
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = config.fontScale * p.fontScale
        // whole-app resize: scaling the density scales EVERYTHING — buttons,
        // spacing, icons, avatars — not just text
        if (p.appZoom != 1.0f) {
            // floor low enough that 50% works even on 120dpi keypad screens
            // (the old floor of 120 silently cancelled every reduction there)
            config.densityDpi = (config.densityDpi * p.appZoom).toInt().coerceAtLeast(60)
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs.get(this)
        ThemeUtils.applyNightMode(this)
        val accent = prefs.accent
        if (accent != "system") {
            theme.applyStyle(ThemeUtils.accentOverlay(this), true)
        }
        super.onCreate(savedInstanceState)
        if (accent == "system" && android.os.Build.VERSION.SDK_INT >= 31) {
            // Material You: wallpaper-derived palette
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applyLayoutDirection()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        applyLayoutDirection()
    }

    protected fun applyLayoutDirection() {
        when (prefs.layoutDirection) {
            "ltr" -> window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
            "rtl" -> window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
            else -> window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
    }

    override fun onResume() {
        super.onResume()
        softkeys?.refreshVisibility()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (softkeys?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
}

/** Softkey bar controller: labels + actions for left / center / right, device-profile aware. */
class Softkeys(private val activity: BaseActivity, private val binding: ViewSoftkeyBarBinding) {

    private var leftAction: (() -> Unit)? = null
    private var centerAction: (() -> Unit)? = null
    private var rightAction: (() -> Unit)? = null
    private var menuAction: (() -> Unit)? = null
    private val prefs = Prefs.get(activity)

    init {
        binding.root.visibility = if (shouldShow()) View.VISIBLE else View.GONE
        binding.softLeft.setOnClickListener { leftAction?.invoke() }
        binding.softCenter.setOnClickListener { centerAction?.invoke() }
        binding.softRight.setOnClickListener { rightAction?.invoke() }
    }

    fun shouldShow(): Boolean = when (prefs.softkeyMode) {
        "always" -> true
        "never" -> false
        else -> {
            // Auto: show once the user has actually saved softkey mappings, or on obvious
            // keypad hardware. Saved keys are the reliable signal on real devices, since
            // many keypad phones still report a touchscreen feature.
            val cfg = activity.resources.configuration
            prefs.softkeysMapped ||
                cfg.keyboard == Configuration.KEYBOARD_12KEY ||
                !activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        }
    }

    /** Re-evaluate whether the bar should be visible (call from onResume so a setting or
     *  a just-finished setup takes effect without restarting the app). */
    fun refreshVisibility() {
        binding.root.visibility = if (shouldShow()) View.VISIBLE else View.GONE
        // optional: let the D-pad land on the on-screen softkeys (off by default;
        // it inserts three extra focus stops into every screen's navigation)
        val focusable = io.github.theonionsarewatching.nova.util.Prefs
            .get(activity).softkeysFocusable
        listOf(binding.softLeft, binding.softCenter, binding.softRight).forEach { v ->
            v.isFocusable = focusable
            v.isClickable = focusable
        }
        if (focusable && binding.softLeft.getTag(R.id.softkey_focus_wired) == null) {
            binding.softLeft.setTag(R.id.softkey_focus_wired, true)
            ThemeUtils.applyFocusHighlight(binding.softLeft, binding.softCenter, binding.softRight)
            binding.softLeft.setOnClickListener { leftAction?.invoke() }
            binding.softCenter.setOnClickListener { centerAction?.invoke() }
            binding.softRight.setOnClickListener { rightAction?.invoke() }
        }
    }

    fun set(left: String?, center: String?, right: String?,
            onLeft: (() -> Unit)? = null, onCenter: (() -> Unit)? = null, onRight: (() -> Unit)? = null,
            onMenu: (() -> Unit)? = null) {
        leftAction = onLeft
        centerAction = onCenter
        rightAction = onRight
        menuAction = onMenu ?: onLeft
        binding.softLeft.text = left ?: ""
        binding.softCenter.text = center ?: ""
        binding.softRight.text = right ?: ""
    }

    /** Returns true when the event was consumed as a softkey. MENU maps to the menu action
     *  (defaults to the left action when no separate menu action is set). */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            // consume matching UP events too so they don't leak
            return event.action == KeyEvent.ACTION_UP && codeMatches(event.keyCode)
        }
        if (event.repeatCount > 0) return codeMatches(event.keyCode)
        return when (event.keyCode) {
            prefs.softkeyLeftCode -> { leftAction?.invoke(); leftAction != null }
            prefs.softkeyRightCode -> { rightAction?.invoke(); rightAction != null }
            KeyEvent.KEYCODE_MENU -> { menuAction?.invoke(); menuAction != null }
            else -> false
        }
    }

    private fun codeMatches(code: Int): Boolean =
        (code == prefs.softkeyLeftCode && leftAction != null) ||
            (code == prefs.softkeyRightCode && rightAction != null) ||
            (code == KeyEvent.KEYCODE_MENU && menuAction != null)
}
