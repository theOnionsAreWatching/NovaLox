package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * Diagnostics for "the dark theme won't stick".
 *
 * Rather than guessing which layer is wrong, every input to the decision is
 * printed on ONE line, so the contradiction is visible at a glance:
 *
 *   pref        what the user chose in Settings ("system" / "light" / "dark")
 *   delegate    what AppCompat was actually told (-1 follow, 1 no, 2 yes)
 *   actNight    is THIS activity's configuration in night mode
 *   appNight    is the APPLICATION context's configuration in night mode
 *   sysNight    what the system itself is set to
 *
 * The classic failure is actNight != appNight: AppCompat's night override
 * applies to activity contexts only, so any code asking the application
 * context gets the SYSTEM's answer and paints the wrong palette. If the log
 * shows pref=dark delegate=2 actNight=true appNight=false, that is the bug,
 * proven, and the fix is to stop asking the application context.
 *
 * The background half prints the raw stored values and the resolved result,
 * so a "won't stick" background can be traced to the exact pref that holds a
 * stale value — and every WRITE is logged too, so a setting that silently
 * fails to persist shows up as a write with no matching read-back.
 */
object ThemeDebug {

    private fun nightOf(context: Context): Boolean =
        (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** One line with every input of the theme + background decision. */
    fun dump(context: Context, where: String, convoId: Long?, resolved: String?) {
        try {
            val prefs = Prefs.get(context)
            val app = context.applicationContext
            val sb = StringBuilder()
            sb.append(where).append(" | ")
            sb.append("pref=").append(prefs.theme)
            sb.append(" delegate=").append(AppCompatDelegate.getDefaultNightMode())
            sb.append(" actNight=").append(nightOf(context))
            sb.append(" appNight=").append(nightOf(app))
            if (convoId != null) {
                sb.append(" | convo=").append(convoId)
                sb.append(" own=").append(prefs.chatBgOwn(convoId) ?: "<none>")
            }
            sb.append(" globalBg='").append(prefs.chatBg(-1L).take(28)).append('\'')
            sb.append(" darkBg='").append(prefs.darkChatBg.take(28)).append('\'')
            if (resolved != null) sb.append(" -> '").append(resolved.take(28)).append('\'')
            DiagLog.log(context, "theme", sb.toString())
        } catch (_: Exception) {
        }
    }

    /**
     * Log a settings WRITE and immediately read the value back from storage,
     * so a preference that doesn't persist is caught at the moment it fails
     * rather than inferred later from wrong colors.
     */
    fun logWrite(context: Context, key: String, wrote: String) {
        try {
            val sp = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context.applicationContext)
            val readBack = try {
                sp.all[key]?.toString() ?: "<absent>"
            } catch (_: Exception) { "<unreadable>" }
            val ok = readBack == wrote
            DiagLog.log(
                context, "theme",
                "WRITE $key='$wrote' readBack='$readBack'${if (ok) "" else "  <-- DID NOT STICK"}"
            )
        } catch (_: Exception) {
        }
    }
}
