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

    /** Theme pref value ("system" / "light" / "dark") -> AppCompat mode. */
    fun nightModeFor(pref: String?): Int = when (pref) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark", "black", "midnight", "forest", "graphite", "dusk" -> AppCompatDelegate.MODE_NIGHT_YES
        "paper", "linen" -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Must run BEFORE an activity attaches its base context (App.onCreate and
     *  BaseActivity.attachBaseContext). It used to run only in
     *  onCreate, after AppCompat had already built the activity's resources
     *  with the OLD mode; AppCompat then patched those resources in place,
     *  which left the screen half old / half new. */
    fun applyNightMode(context: Context) {
        io.github.theonionsarewatching.nova.util.ThemeDebug
            .dump(context, "applyNightMode", null, null)
        applyNightModeValue(Prefs.get(context).theme)
    }

    /** Apply a theme value right away (Settings, before the pref is saved).
     *  When the mode really changes AppCompat recreates every open screen. */
    fun applyNightModeValue(pref: String?) {
        val mode = nightModeFor(pref)
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** Bumped on theme / accent change; every BaseActivity created under an
     *  older value rebuilds itself when it comes back to the front, so no
     *  screen in the back stack keeps old colors. */
    @Volatile var themeGeneration = 0
        private set

    fun bumpThemeGeneration() { themeGeneration++ }

    fun accentOverlay(context: Context): Int = when (Prefs.get(context).accent) {
        "teal" -> R.style.Accent_Teal
        "green" -> R.style.Accent_Green
        "orange" -> R.style.Accent_Orange
        "red" -> R.style.Accent_Red
        "purple" -> R.style.Accent_Purple
        "pink" -> R.style.Accent_Pink
        "gray" -> R.style.Accent_Gray
        "amber" -> R.style.Accent_Amber
        "gold" -> R.style.Accent_Gold
        "coral" -> R.style.Accent_Coral
        "crimson" -> R.style.Accent_Crimson
        "rose" -> R.style.Accent_Rose
        "violet" -> R.style.Accent_Violet
        "indigo" -> R.style.Accent_Indigo
        "sky" -> R.style.Accent_Sky
        "cyan" -> R.style.Accent_Cyan
        "mint" -> R.style.Accent_Mint
        "lime" -> R.style.Accent_Lime
        "olive" -> R.style.Accent_Olive
        "brown" -> R.style.Accent_Brown
        "slate" -> R.style.Accent_Slate
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
            "amber" -> R.color.accent_amber
            "gold" -> R.color.accent_gold
            "coral" -> R.color.accent_coral
            "crimson" -> R.color.accent_crimson
            "rose" -> R.color.accent_rose
            "violet" -> R.color.accent_violet
            "indigo" -> R.color.accent_indigo
            "sky" -> R.color.accent_sky
            "cyan" -> R.color.accent_cyan
            "mint" -> R.color.accent_mint
            "lime" -> R.color.accent_lime
            "olive" -> R.color.accent_olive
            "brown" -> R.color.accent_brown
            "slate" -> R.color.accent_slate
            else -> R.color.accent_blue
        }
    )
    }

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    /** The screen in front right now (set by BaseActivity.onResume). Pop-ups
     *  and toasts read their colors from IT, so they always follow the theme
     *  the user picked (accent, light/dark, system colors), even when a caller
     *  only had the application context. */
    @Volatile var frontActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    /** Context whose theme carries the user's current accent and light/dark
     *  setting: the front screen when there is one, else [fallback]. */
    fun themedContext(fallback: Context): Context {
        if (fallback is android.app.Activity) return fallback
        return frontActivity?.get()?.takeIf { !it.isFinishing } ?: fallback
    }

    /** Text/icon color to put ON the accent: black or white, whichever
     *  reads better against this exact accent (WCAG contrast ratio). */
    fun onAccent(context: Context): Int = onColor(accentColor(context))

    fun onColor(bg: Int): Int {
        val opaque = androidx.core.graphics.ColorUtils.setAlphaComponent(bg, 255)
        val white = androidx.core.graphics.ColorUtils.calculateContrast(
            android.graphics.Color.WHITE, opaque)
        val black = androidx.core.graphics.ColorUtils.calculateContrast(
            0xFF000000.toInt(), opaque)
        return if (white >= black) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    }

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
        return StateListDrawable().apply { setEnterFadeDuration(90); setExitFadeDuration(90) }.apply {
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
        return StateListDrawable().apply { setEnterFadeDuration(90); setExitFadeDuration(90) }.apply {
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
        return StateListDrawable().apply { setEnterFadeDuration(90); setExitFadeDuration(90) }.apply {
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
        return StateListDrawable().apply { setEnterFadeDuration(90); setExitFadeDuration(90) }.apply {
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
            val sld = android.graphics.drawable.StateListDrawable().apply { setEnterFadeDuration(90); setExitFadeDuration(90) }.apply {
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
    // ---------- main screen look ----------
    // Every color here is derived from the accent (Prefs.accent via
    // accentColor) and light/dark (isNight), so changing either in Settings
    // changes the look on the next resume. Avatar colors are not touched.

    /** Blend [a] toward [b] by [t] (0..1), alpha forced opaque. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return Color.rgb(
            (Color.red(a) * u + Color.red(b) * t).toInt(),
            (Color.green(a) * u + Color.green(b) * t).toInt(),
            (Color.blue(a) * u + Color.blue(b) * t).toInt()
        )
    }

    fun withAlpha(c: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))

    /** Card color: white (light) / dark grey (dark), same as the pop-ups. */
    fun cardColor(context: Context): Int =
        palette(context)?.surface ?: ContextCompat.getColor(context, R.color.dialog_surface)

    /** "Black (AMOLED)" theme: dark mode with a pure black page. */
    fun isBlack(context: Context): Boolean =
        Prefs.get(context).theme == "black" && isNight(context)

    /** Colours of a full theme (Midnight, Forest, Paper, Black); null for
     *  Light / Dark / System, which keep their old colours. Must match the
     *  ThemeFull_* styles in themes.xml. */
    class Palette(val page: Int, val surface: Int, val text: Int,
                  val bubbleOther: Int, val bubbleMine: Int)

    fun palette(context: Context): Palette? {
        fun c(h: String) = Color.parseColor(h)
        return when (Prefs.get(context).theme) {
        "graphite" -> Palette(c("#191F27"), c("#252D38"), c("#F0F3F7"), c("#303B48"), c("#3C4B5D"))
        "dusk" -> Palette(c("#24212C"), c("#302C3B"), c("#F4EFF7"), c("#3D354A"), c("#50445F"))
        "linen" -> Palette(c("#F6F2EA"), c("#FFFCF6"), c("#322F29"), c("#EAE3D7"), c("#DCD3C3"))
            else -> null
        }
    }

    /** ThemeFull_* overlay for the theme pref, 0 for Light/Dark/System. */
    fun fullThemeStyle(theme: String): Int = when (theme) {
        "graphite" -> R.style.ThemeFull_Graphite
        "dusk" -> R.style.ThemeFull_Dusk
        "linen" -> R.style.ThemeFull_Linen
        else -> 0
    }

    /** A colour from the current theme (?attr), [fallbackRes] if missing. */
    fun attrColor(context: Context, attr: Int, fallbackRes: Int): Int {
        val tv = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true) &&
            tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
            tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) tv.data
        else ContextCompat.getColor(context, fallbackRes)
    }

    /** Page behind the cards: a faint accent-tinted grey so white cards show. */
    fun pageColor(context: Context): Int =
        palette(context)?.page ?: if (isNight(context)) mix(Color.rgb(0x14, 0x14, 0x17), accentColor(context), 0.04f)
        else mix(Color.rgb(0xEE, 0xEE, 0xF1), accentColor(context), 0.05f)

    /** Header gradient: darker accent at the top, accent below. In dark
     *  mode both ends are darkened (night accents are light) so the white
     *  title stays readable. */
    fun headerColors(context: Context): IntArray {
        val a = accentColor(context)
        return if (isNight(context))
            intArrayOf(mix(a, Color.BLACK, 0.62f), mix(a, Color.BLACK, 0.40f))
        else intArrayOf(mix(a, Color.BLACK, 0.40f), mix(a, Color.BLACK, 0.08f))
    }

    /** Short header bar: vertical gradient, rounded bottom corners, a
     *  "N new" outlined pill. Also colors the status bar to match. */
    fun styleMainHeader(activity: android.app.Activity, header: View, pill: android.widget.TextView) {
        val ctx = header.context
        val cols = headerColors(ctx)
        val r = dp(ctx, 14).toFloat()
        header.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, cols).apply {
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        }
        // "N new" pill: light accent tint with accent text, so it
        // stands out on the accent bar in light and dark themes alike
        val acc = accentColor(ctx)
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 10).toFloat()
            setColor(mix(Color.WHITE, acc, 0.12f))
        }
        pill.setTextColor(acc)
        try {
            activity.window.statusBarColor = cols[0]
            // white status-bar icons on the dark accent
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .isAppearanceLightStatusBars = false
        } catch (_: Exception) {}
    }

    /** Conversation card: rounded 14dp; focused = light accent tint + 2dp
     *  accent outline (D-pad). */
    fun convoCardBackground(context: Context): StateListDrawable {
        val a = accentColor(context)
        val card = cardColor(context)
        val r = dp(context, 14).toFloat()
        val focused = GradientDrawable().apply {
            cornerRadius = r
            setColor(mix(card, a, if (isNight(context)) 0.24f else 0.13f))
            setStroke(dp(context, 2), a)
        }
        val normal = GradientDrawable().apply {
            cornerRadius = r
            setColor(card)
        }
        return StateListDrawable().apply { setEnterFadeDuration(90); setExitFadeDuration(90) }.apply {
            setExitFadeDuration(90)
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    /** Bottom soft-key bar, main-screen style: card color with rounded top
     *  corners, accent bold labels, thin dividers between them. */
    fun styleSoftkeyBar(bar: io.github.theonionsarewatching.nova.databinding.ViewSoftkeyBarBinding) {
        val ctx = bar.root.context
        val r = dp(ctx, 18).toFloat()
        bar.root.background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(cardColor(ctx))
        }
        bar.root.elevation = dp(ctx, 6).toFloat()
        val a = accentColor(ctx)
        for (t in listOf(bar.softLeft, bar.softCenter, bar.softRight)) {
            t.setTextColor(a)
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            t.gravity = android.view.Gravity.CENTER
        }
        bar.softDivider1.visibility = View.VISIBLE
        bar.softDivider2.visibility = View.VISIBLE
    }

    /** Pop-up row highlight: a filled pill in the accent color,
     *  matching the themed list pop-ups (drawable/bg_dialog_item). For
     *  hand-built pop-up rows. applyFocusHighlight is left unchanged. */
    fun dialogRowBackground(context: Context, insetHDp: Int = 8, insetVDp: Int = 2): StateListDrawable {
        val pill = GradientDrawable().apply {
            setColor(accentColor(context))
            cornerRadius = dp(context, 16).toFloat()
        }
        val inset = android.graphics.drawable.InsetDrawable(
            pill, dp(context, insetHDp), dp(context, insetVDp), dp(context, insetHDp), dp(context, insetVDp))
        return StateListDrawable().apply { setEnterFadeDuration(90); setExitFadeDuration(90) }.apply {
            setExitFadeDuration(80)
            addState(intArrayOf(android.R.attr.state_focused), inset)
            addState(intArrayOf(android.R.attr.state_selected), inset)
            addState(intArrayOf(android.R.attr.state_pressed), inset)
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
    }

    /** The foreground follows the actual focus fill, not a static theme grey. */
    fun dialogRowTextColors(context: Context): android.content.res.ColorStateList = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected),
            intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(onAccent(context), onAccent(context), onAccent(context),
            palette(context)?.text ?: attrColor(context, R.attr.dsmsText, R.color.dialog_text_disabled)))

    /** Hand-built pop-up rows: accent pill when focused, text readable on it. */
    fun applyDialogRowFocus(vararg views: android.widget.TextView) {
        for (v in views) {
            v.foreground = null
            v.background = dialogRowBackground(v.context)
            v.setTextColor(dialogRowTextColors(v.context))
        }
    }

    /** Pop-up row that is a container (e.g. "‹ value ›" stepper): accent
     *  pill on focus, and its text children switch color with it. */
    fun applyDialogRowFocusGroup(row: android.view.ViewGroup) {
        row.foreground = null
        row.background = dialogRowBackground(row.context)
        val colors = dialogRowTextColors(row.context)
        for (i in 0 until row.childCount) {
            (row.getChildAt(i) as? android.widget.TextView)?.let { t ->
                t.isDuplicateParentStateEnabled = true
                t.setTextColor(colors)
            }
        }
    }

    fun densityPad(context: Context): Int = when (Prefs.get(context).listDensity) {
        "compact" -> dp(context, 4)
        "spacious" -> dp(context, 14)
        else -> dp(context, 8)
    }
}

abstract class BaseActivity : AppCompatActivity() {

    // ---- audio file pick (custom notification tones) ----
    private var audioPickCallback: ((String) -> Unit)? = null

    /** SAF audio picker; grants persistable read permission so the tone keeps
     *  playing after reboot. Callback gets the uri string. */
    fun pickAudioFile(cb: (String) -> Unit) {
        audioPickCallback = cb
        try {
            startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }, 977)
        } catch (_: Exception) { audioPickCallback = null }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 977) {
            val cb = audioPickCallback; audioPickCallback = null
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null && cb != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                cb(uri.toString())
            }
        }
    }


    protected lateinit var prefs: Prefs
    var softkeys: Softkeys? = null

    override fun attachBaseContext(newBase: Context) {
        val p = Prefs.get(newBase)
        // night mode must be current BEFORE AppCompat builds this screen's
        // resources (it does so in super.attachBaseContext)
        ThemeUtils.applyNightMode(newBase)
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

    /** Theme generation this screen was built with (see ThemeUtils). */
    private var builtThemeGeneration = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs.get(this)
        ThemeUtils.applyNightMode(this)   // no-op when attachBaseContext already did it
        builtThemeGeneration = ThemeUtils.themeGeneration
        val accent = prefs.accent
        if (accent != "system") {
            theme.applyStyle(ThemeUtils.accentOverlay(this), true)
        }
        ThemeUtils.fullThemeStyle(prefs.theme).takeIf { it != 0 }?.let { theme.applyStyle(it, true) }
        super.onCreate(savedInstanceState)
        // pop-ups the app does not build itself (Settings'
        // list / text pop-ups come from androidx.preference as dialog
        // fragments) get the same finish as DsmsAlertBuilder: accent
        // buttons, no gray focus box, focus on OK when nothing else has it.
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentStarted(
                    fm: androidx.fragment.app.FragmentManager,
                    f: androidx.fragment.app.Fragment
                ) {
                    val d = (f as? androidx.fragment.app.DialogFragment)?.dialog ?: return
                    // finished before its first frame
                    DialogPolish.hook(d)
                }
            }, true)
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
        ThemeUtils.frontActivity = java.lang.ref.WeakReference(this)
        try {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).run {
                // once per screen (onResume runs many times)
                removePrimaryClipChangedListener(clipListener)
                addPrimaryClipChangedListener(clipListener)
            }
        } catch (_: Exception) {}
        super.onResume()
        softkeys?.refreshVisibility()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        InAppPickers.onPermissionResult(this, requestCode,
            grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED })
    }

    override fun onStart() {
        super.onStart()
        io.github.theonionsarewatching.nova.util.ClipboardGuard.onActivityStarted()
        // theme or accent changed while this screen was in the background:
        // rebuild it now so it can't show a mix of old and new colors
        if (builtThemeGeneration != -1 && builtThemeGeneration != ThemeUtils.themeGeneration) {
            builtThemeGeneration = ThemeUtils.themeGeneration
            recreate()
        }
    }

    /** Close the keyboard / input-mode badge for this window. */
    fun hideInputMode() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            val f = currentFocus
            if (f is android.widget.EditText) f.clearFocus()
            imm.hideSoftInputFromWindow((f ?: window.decorView).windowToken, 0)
            imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        // leaving a D-SMS screen (compose box, thread, the
        // app) clears text pasted here if the clipboard still holds it; we
        // usually still have focus now, so the clipboard can be read
        io.github.theonionsarewatching.nova.util.ClipboardGuard.clearIfStillPasted(applicationContext)
        // a screen that is closing takes the keyboard (and
        // the phone's "[ English ]" input-mode badge) with it, so it never
        // lingers over the chat list
        if (isFinishing) hideInputMode()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .removePrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        // text D-SMS copied AND pasted is cleared when the user leaves the app
        io.github.theonionsarewatching.nova.util.ClipboardGuard
            .onActivityStopped(applicationContext, isChangingConfigurations)
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
        android.content.ClipboardManager.OnPrimaryClipChangedListener {
            io.github.theonionsarewatching.nova.util.ClipboardGuard.onClipChanged(clipboardText())
            onClipboardChanged()
        }
}

/** When the clipboard holds text and a compose box is in play, "Attach"
 *  becomes an Options menu offering Attach and Paste (with a short preview of
 *  what would be pasted). With an empty clipboard it goes straight to attach. */
object AttachOrPaste {
    fun open(
        activity: BaseActivity,
        target: android.widget.EditText,
        onAttach: () -> Unit,
        onRecord: (() -> Unit)? = null,
        /** shown first (drafts: Save draft and exit / Discard draft) */
        extra: List<Pair<String, () -> Unit>> = emptyList()
    ) {
        val clip = activity.clipboardText()
        if (clip.isNullOrBlank() && onRecord == null && extra.isEmpty()) { onAttach(); return }
        val dp = { v: Int -> (v * activity.resources.displayMetrics.density).toInt() }
        // balanced, centered, no scrolling — even padding, full-
        // width pill on the focused row, text centered in it; BACK cancels
        val column = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
            isVerticalScrollBarEnabled = false
        }
        var dialog: android.app.AlertDialog? = null
        fun row(label: String, action: () -> Unit): android.widget.TextView =
            android.widget.TextView(activity).apply {
                text = label
                textSize = 16f
                minHeight = dp(44)
                gravity = android.view.Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isFocusable = true
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2); bottomMargin = dp(2) }
                // same accent pill as the themed list pop-ups, full width
                ThemeUtils.applyDialogRowFocus(this)
                background = ThemeUtils.dialogRowBackground(activity, insetHDp = 2, insetVDp = 0)
                setOnClickListener { dialog?.dismiss(); action() }
            }
        for ((l, a) in extra) column.addView(row(l, a))
        column.addView(row(activity.getString(R.string.softkey_attach)) { onAttach() })
        if (onRecord != null) {
            column.addView(row(activity.getString(R.string.attach_menu_record)) { onRecord() })
        }
        if (!clip.isNullOrBlank()) column.addView(row(activity.getString(R.string.paste)) {
            io.github.theonionsarewatching.nova.util.ClipboardGuard.markPasted(clip)
            val start = target.selectionStart.coerceAtLeast(0)
            val end = target.selectionEnd.coerceAtLeast(0)
            target.text.replace(minOf(start, end), maxOf(start, end), clip)
            target.requestFocus()
        })
        // "▼ Will paste:" in the accent, pointing down at the preview box:
        // the box below is what Paste inserts. Fixed size, not focusable, so the
        // pop-up opens at its final size and focus moves never re-lay it out.
        if (!clip.isNullOrBlank()) column.addView(android.widget.TextView(activity).apply {
            text = activity.getString(R.string.paste_will)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeUtils.accentColor(activity))
            maxLines = 1
            gravity = android.view.Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            isFocusable = false
            setPadding(0, dp(2), 0, 0)
        })
        // a glimpse of what Paste would insert — a few lines, never the whole thing
        if (!clip.isNullOrBlank()) column.addView(android.widget.TextView(activity).apply {
            text = clip
            // readable size (was 12sp, tiny on the flip)
            textSize = 15f
            // full-strength text (was faded to 80%) inside a
            // rounded frame in the theme's accent, so it reads as "this is
            // what Paste inserts"
            ContextCompat.getColorStateList(activity, R.color.dialog_item_text)
                ?.defaultColor?.let { setTextColor(it) }
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding(dp(10), dp(4), dp(10), dp(5))
            val accent = ThemeUtils.accentColor(activity)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x14))
                setStroke(maxOf(1, (1.5f * activity.resources.displayMetrics.density).toInt()), accent)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8), dp(4), dp(8), dp(2)) }
        })
        dialog = DsmsFrameworkAlertBuilder(activity)
            .setView(column)
            // no Cancel button: BACK closes (touch: tap outside)
            // a stray MENU event must never dismiss this menu (Sonim XP3plus)
            .setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_MENU }
            .create()
        // same centered middle width as the full Attach list;
        // set BEFORE showing, so it opens at its final size
        val labels = ArrayList<String>()
        for (i in 0 until column.childCount) {
            val v = column.getChildAt(i) as? android.widget.TextView ?: continue
            if (v.isFocusable) labels.add(v.text.toString())
        }
        dialog?.let { Dialogs.narrowToLabels(it, labels.toTypedArray()); it.show() }
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
        // touch phones: no bar, and (via the handleKey gate) no softkey
        // reactions either — the capture wizard reads raw keys and is exempt
        "touch" -> false
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
        // perf: the main list calls this on EVERY focus move (each D-pad
        // step); setting a label, even to the same text, re-lays-out the
        // whole screen. Only touch a label when it really changes.
        setIfChanged(binding.softLeft, left ?: "")
        setIfChanged(binding.softCenter, center ?: "")
        setIfChanged(binding.softRight, right ?: "")
    }

    /** True while the bar is on screen. */
    val isShown: Boolean get() = binding.root.visibility == View.VISIBLE

    /** The bar's view (pop-ups keep clear of it). */
    val barView: View get() = binding.root

    private class Saved(
        val left: CharSequence?, val center: CharSequence?, val right: CharSequence?,
        val onLeft: (() -> Unit)?, val onCenter: (() -> Unit)?, val onRight: (() -> Unit)?,
        val onMenu: (() -> Unit)?, val rightColors: android.content.res.ColorStateList?
    )
    private val saved = ArrayList<Saved>()

    /** Borrow the bar for a pop-up (labels + actions), e.g. the emoji
     *  picker's "Done"; [pop] gives the screen's own labels back. MENU does
     *  nothing meanwhile. */
    fun push(left: String?, center: String?, right: String?,
             onLeft: (() -> Unit)? = null, onCenter: (() -> Unit)? = null, onRight: (() -> Unit)? = null,
             accentRight: Boolean = false) {
        saved.add(Saved(binding.softLeft.text, binding.softCenter.text, binding.softRight.text,
            leftAction, centerAction, rightAction, menuAction, binding.softRight.textColors))
        set(left, center, right, onLeft, onCenter, onRight, onMenu = {})
        if (accentRight) {
            binding.softRight.setTextColor(ThemeUtils.accentColor(activity))
            binding.root.visibility = View.VISIBLE
        }
    }

    fun pop() {
        val s = saved.removeLastOrNull() ?: return
        leftAction = s.onLeft; centerAction = s.onCenter; rightAction = s.onRight; menuAction = s.onMenu
        binding.softLeft.text = s.left ?: ""
        binding.softCenter.text = s.center ?: ""
        binding.softRight.text = s.right ?: ""
        s.rightColors?.let { binding.softRight.setTextColor(it) }
        if (saved.isEmpty()) binding.root.visibility = if (shouldShow()) View.VISIBLE else View.GONE
    }

    private fun setIfChanged(t: android.widget.TextView, s: String) {
        // a styled label (icon spans, MediaViewer) is always replaced
        val cur = t.text
        if (cur is android.text.Spanned || cur?.toString() != s) t.text = s
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
    // Sonim X320 (and friends): one physical softkey press emits BOTH the
    // mapped code AND KEYCODE_MENU — firing the softkey action and the menu
    // action together (field report: attach menu + conversation options
    // opened at once). A MENU arriving within this window of another softkey
    // action is the same press's echo and gets swallowed.
    private var lastNonMenuActionAt = 0L
    private var pendingMenu: Runnable? = null

    private fun cancelPendingMenu() {
        pendingMenu?.let { binding.root.removeCallbacks(it); pendingMenu = null }
    }

    fun handleKey(event: KeyEvent): Boolean {
        // softkeys act ONLY while the bar is shown. With the bar off the
        // physical keys used to keep firing their invisible actions (right
        // softkey silently sent) — user-reported as wrong. The capture screen
        // is unaffected: it reads raw key events in its own dispatch.
        if (!shouldShow()) return false
        if (event.keyCode == KeyEvent.KEYCODE_MENU &&
            event.keyCode != prefs.softkeyLeftCode &&
            event.keyCode != prefs.softkeyRightCode &&
            System.currentTimeMillis() - lastNonMenuActionAt < 400
        ) return true  // consume the echo, fire nothing
        if (!codeMatches(event.keyCode)) return false
        if (event.action != KeyEvent.ACTION_UP) return true
        when (event.keyCode) {
            prefs.softkeyLeftCode -> {
                lastNonMenuActionAt = System.currentTimeMillis()
                cancelPendingMenu()   // Sonim MENU-first order: swallow its pair
                leftAction?.invoke()
            }
            prefs.softkeyRightCode -> {
                lastNonMenuActionAt = System.currentTimeMillis()
                cancelPendingMenu()
                rightAction?.invoke()
            }
            KeyEvent.KEYCODE_MENU -> {
                if (prefs.softkeyLeftCode == KeyEvent.KEYCODE_MENU ||
                    prefs.softkeyRightCode == KeyEvent.KEYCODE_MENU
                ) {
                    menuAction?.invoke()  // MENU IS a mapped softkey: immediate
                } else {
                    // Sonim X320: one softkey press emits its code AND MENU,
                    // in either order. MENU waits a beat; the softkey action
                    // landing inside the window cancels it. Real MENU presses
                    // just feel imperceptibly delayed.
                    val r = Runnable { pendingMenu = null; menuAction?.invoke() }
                    pendingMenu = r
                    binding.root.postDelayed(r, 280)
                }
            }
        }
        return true
    }

    private fun codeMatches(code: Int): Boolean =
        (code == prefs.softkeyLeftCode && leftAction != null) ||
            (code == prefs.softkeyRightCode && rightAction != null) ||
            (code == KeyEvent.KEYCODE_MENU && menuAction != null)
}
