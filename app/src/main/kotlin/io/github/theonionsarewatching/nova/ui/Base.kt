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

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Neutral grey focus shade — for content rows (conversations, messages)
     *  where an accent-colored wash fought with the content's own colors. */
    fun focusFillNeutral(context: Context, radiusDp: Int = 8): StateListDrawable {
        val fill = GradientDrawable().apply {
            setColor(if (isNight(context)) 0x3DFFFFFF else 0x2E9E9E9E)
            if (radiusDp > 0) cornerRadius = dp(context, radiusDp).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), fill)
            addState(intArrayOf(android.R.attr.state_selected), fill)
            addState(intArrayOf(android.R.attr.state_pressed), fill)
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
    /** Focus ring for color tiles: thick white stroke over a dark halo, so
     *  it stands out on any tile color (never matches the color underneath). */
    fun applyContrastFocusHighlight(vararg views: android.view.View) {
        for (v in views) {
            val d = { dp: Float -> dp * v.context.resources.displayMetrics.density }
            val halo = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(6f)
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(d(5.5f).toInt(), 0x8C000000.toInt())
            }
            val ring = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(6f)
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(d(4f).toInt(), android.graphics.Color.WHITE)
            }
            val focused = android.graphics.drawable.LayerDrawable(arrayOf(halo, ring))
            val sld = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(0))
            }
            v.foreground = sld
        }
    }

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
        try {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .addPrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {}
        super.onResume()
        softkeys?.refreshVisibility()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (softkeys?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    fun clipboardText(): String? = try {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (_: Exception) { null }

    /** Screens that show clipboard-dependent labels override this. */
    open fun onClipboardChanged() {}

    private val clipListener =
        android.content.ClipboardManager.OnPrimaryClipChangedListener { onClipboardChanged() }
}

/** When the clipboard holds text and a compose box is in play, "Attach"
 *  becomes an Options menu offering Attach and Paste (with a short preview of
 *  what would be pasted). With an empty clipboard it goes straight to attach. */
object AttachOrPaste {
    fun open(
        activity: BaseActivity,
        target: android.widget.EditText,
        onAttach: () -> Unit,
        onRecord: (() -> Unit)? = null
    ) {
        val clip = activity.clipboardText()
        if (clip.isNullOrBlank() && onRecord == null) { onAttach(); return }
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        val column = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        var dialog: android.app.AlertDialog? = null
        fun row(label: String, action: () -> Unit): android.widget.TextView =
            android.widget.TextView(activity).apply {
                text = label
                textSize = 16f
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isFocusable = true
                ThemeUtils.applyFocusHighlight(this)
                setOnClickListener { dialog?.dismiss(); action() }
            }
        column.addView(row(activity.getString(R.string.softkey_attach)) { onAttach() })
        if (onRecord != null) {
            column.addView(row(activity.getString(R.string.attach_menu_record)) { onRecord() })
        }
        if (!clip.isNullOrBlank()) column.addView(row(activity.getString(R.string.paste)) {
            val start = target.selectionStart.coerceAtLeast(0)
            val end = target.selectionEnd.coerceAtLeast(0)
            target.text.replace(minOf(start, end), maxOf(start, end), clip)
            target.requestFocus()
        })
        // a glimpse of what Paste would insert — a few lines, never the whole thing
        if (!clip.isNullOrBlank()) column.addView(android.widget.TextView(activity).apply {
            text = clip
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14), 0, dp(14), dp(8))
        })
        dialog = android.app.AlertDialog.Builder(activity)
            .setView(column)
            .setNegativeButton(android.R.string.cancel, null)
            // a stray MENU event must never dismiss this menu (Sonim XP3plus)
            .setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_MENU }
            .show()
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
        val focusable = prefs.softkeysFocusable
        listOf(binding.softLeft, binding.softCenter, binding.softRight).forEach { v ->
            v.isFocusable = focusable
            v.isClickable = focusable
        }
        if (focusable && binding.softLeft.getTag(R.id.softkey_focus_wired) == null) {
            binding.softLeft.setTag(R.id.softkey_focus_wired, true)
            ThemeUtils.applyFocusHighlight(binding.softLeft, binding.softCenter, binding.softRight)
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
     *  (defaults to the left action when no separate menu action is set).
     *
     *  Actions fire on key UP, not DOWN. Firing on DOWN opened a dialog whose
     *  window then received the same keypress's UP event — and a MENU key-up
     *  dismisses dialog panels. On most phones the race is lost slowly enough
     *  to go unnoticed; on the Sonim XP3plus 5G it closed the just-opened
     *  options menu instantly (visible as a flash). Consuming DOWN silently
     *  and acting on UP means the dialog only exists after the keypress is
     *  fully over. */
    fun handleKey(event: KeyEvent): Boolean {
        if (!codeMatches(event.keyCode)) return false
        if (event.action != KeyEvent.ACTION_UP) return true
        when (event.keyCode) {
            prefs.softkeyLeftCode -> leftAction?.invoke()
            prefs.softkeyRightCode -> rightAction?.invoke()
            KeyEvent.KEYCODE_MENU -> menuAction?.invoke()
        }
        return true
    }

    private fun codeMatches(code: Int): Boolean =
        (code == prefs.softkeyLeftCode && leftAction != null) ||
            (code == prefs.softkeyRightCode && rightAction != null) ||
            (code == KeyEvent.KEYCODE_MENU && menuAction != null)
}
