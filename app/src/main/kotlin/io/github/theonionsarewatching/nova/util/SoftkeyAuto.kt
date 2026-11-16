package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Soft keys without manual setup.
 *
 * The TCL Flip 2 keypad layout (matrix-keypad.kl) maps its
 * two soft keys to the standard Android codes: scan 139 -> SOFT_LEFT (1),
 * scan 48 -> SOFT_RIGHT (2). The phone's own TCL soft-key bar
 * (android.widget.MenuBar) only takes those keys when an app registers a
 * MenuBar listener; D-SMS doesn't (its AppCompat screens never get a TCL
 * MenuBar), so both keys reach D-SMS as plain key events.
 *
 * Before, D-SMS showed its soft-key bar only after the user ran the
 * "press left, press right" setup (or on phones that report no touch
 * screen / a 12-key keyboard; the Flip 2 reports a touch screen). Now:
 * when the phone's key layout says it HAS both soft keys, the standard
 * codes are saved and the bar is on from the first start, and the setup
 * screen is skipped. A mapping the user saved is never changed; the setup
 * screen stays in Settings for phones that send other codes.
 */
object SoftkeyAuto {

    /** True when the phone's keypad layout has both soft keys. */
    fun phoneHasSoftKeys(): Boolean = try {
        KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_SOFT_LEFT) &&
            KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_SOFT_RIGHT)
    } catch (_: Exception) { false }

    /** Once per install: use the standard codes when the phone has them. */
    fun applyIfNeeded(context: Context) {
        val p = Prefs.get(context)
        if (p.softkeysMapped) return
        if (p.sp.getBoolean("softkey_auto_checked", false)) return
        val has = phoneHasSoftKeys()
        val e = p.sp.edit().putBoolean("softkey_auto_checked", true)
        if (has) {
            e.putInt("softkey_left_code", KeyEvent.KEYCODE_SOFT_LEFT)
                .putInt("softkey_right_code", KeyEvent.KEYCODE_SOFT_RIGHT)
                .putBoolean("softkeys_mapped", true)
                .putBoolean("softkey_setup_done", true)
        }
        e.apply()
        DiagLog.log(context, "softkeys", "auto: phone has soft keys = $has")
    }
}
