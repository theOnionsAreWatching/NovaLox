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

    fun accentColor(context: Context): Int = ContextCompat.getColor(
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

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    /** Focus indicator: distinct outline, not a fill, thickness user-configurable. */
    fun focusForeground(context: Context): StateListDrawable {
        val stroke = dp(context, Prefs.get(context).focusStrokeDp)
        val focused = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(stroke, accentColor(context))
            cornerRadius = dp(context, 6).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_selected), focused)
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
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
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs.get(this)
        ThemeUtils.applyNightMode(this)
        theme.applyStyle(ThemeUtils.accentOverlay(this), true)
        super.onCreate(savedInstanceState)
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
            val cfg = activity.resources.configuration
            cfg.navigation == Configuration.NAVIGATION_DPAD ||
                cfg.keyboard == Configuration.KEYBOARD_12KEY ||
                !activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
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
