package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

class Prefs(context: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    // ---- first run / import ----
    var importDone: Boolean
        get() = sp.getBoolean("import_done", false)
        set(v) = sp.edit().putBoolean("import_done", v).apply()
    var permissionsAsked: Boolean
        get() = sp.getBoolean("permissions_asked", false)
        set(v) = sp.edit().putBoolean("permissions_asked", v).apply()
    var batteryPromptShown: Boolean
        get() = sp.getBoolean("battery_prompt_shown", false)
        set(v) = sp.edit().putBoolean("battery_prompt_shown", v).apply()
    var softkeySetupDone: Boolean
        get() = sp.getBoolean("softkey_setup_done", false)
        set(v) = sp.edit().putBoolean("softkey_setup_done", v).apply()
    var defaultTone: String
        get() = sp.getString("default_tone", "") ?: ""
        set(v) = sp.edit().putString("default_tone", v).apply()
    val vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
    var softkeysMapped: Boolean
        get() = sp.getBoolean("softkeys_mapped", false)
        set(v) = sp.edit().putBoolean("softkeys_mapped", v).apply()

    // ---- appearance ----
    // system / light / dark
    val theme: String get() = sp.getString("theme", "system") ?: "system"
    val accent: String get() = sp.getString("accent", "blue") ?: "blue"
    val msgTextSp: Float get() = (sp.getString("msg_text_size", "16") ?: "16").toFloatOrNull() ?: 16f
    val timeTextSp: Float get() = (sp.getString("time_text_size", "11") ?: "11").toFloatOrNull() ?: 11f
    // compact / comfortable / spacious
    val listDensity: String get() = sp.getString("list_density", "comfortable") ?: "comfortable"
    val fontScale: Float get() = (sp.getString("ui_scale", "1.0") ?: "1.0").toFloatOrNull() ?: 1f
    val focusStrokeDp: Int get() = (sp.getString("focus_stroke", "2") ?: "2").toIntOrNull() ?: 2
    // bubble / accentbar / plain
    val messageStyle: String get() = sp.getString("message_style", "bubble") ?: "bubble"
    // auto / ltr / rtl
    val layoutDirection: String get() = sp.getString("layout_direction", "auto") ?: "auto"

    // ---- behavior ----
    val showSearchBar: Boolean get() = sp.getBoolean("show_search_bar", false)
    val deliveryReports: Boolean get() = sp.getBoolean("delivery_reports", true)
    // broadcast / group_mms : default for NEW group conversations
    val defaultGroupMode: String get() = sp.getString("default_group_mode", "broadcast") ?: "broadcast"
    // auto / ask / never
    val linkOpening: String get() = sp.getString("link_opening", "ask") ?: "ask"
    val composeMaxLines: Int get() = (sp.getString("compose_max_lines", "4") ?: "4").toIntOrNull() ?: 4
    // recent / unread / alpha / oldest
    var sortMode: String
        get() = sp.getString("sort_mode", "recent") ?: "recent"
        set(v) = sp.edit().putString("sort_mode", v).apply()
    // all / unread / unknown / groups
    var filterMode: String
        get() = sp.getString("filter_mode", "all") ?: "all"
        set(v) = sp.edit().putString("filter_mode", v).apply()

    // ---- recycle bin ----
    // days: 0 = keep until manually emptied
    val binRetentionDays: Int get() = (sp.getString("bin_retention", "30") ?: "30").toIntOrNull() ?: 30

    // ---- softkeys ----
    // auto / always / never
    val softkeyMode: String get() = sp.getString("softkey_mode", "auto") ?: "auto"
    var softkeyLeftCode: Int
        get() = sp.getInt("softkey_left_code", android.view.KeyEvent.KEYCODE_SOFT_LEFT)
        set(v) = sp.edit().putInt("softkey_left_code", v).apply()
    var softkeyRightCode: Int
        get() = sp.getInt("softkey_right_code", android.view.KeyEvent.KEYCODE_SOFT_RIGHT)
        set(v) = sp.edit().putInt("softkey_right_code", v).apply()

    // ---- contacts cache ----
    var contactsRefreshedAt: Long
        get() = sp.getLong("contacts_refreshed_at", 0)
        set(v) = sp.edit().putLong("contacts_refreshed_at", v).apply()

    companion object {
        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context.applicationContext).also { instance = it }
        }
    }
}
