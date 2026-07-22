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
    var learnedOwnNumbers: Set<String>
        get() = (sp.getString("learned_own_numbers", "") ?: "")
            .split(",").filter { it.isNotBlank() }.toSet()
        set(v) = sp.edit().putString("learned_own_numbers", v.joinToString(",")).apply()
    fun chatBg(convoId: Long): String {
        if (convoId == -1L) return sp.getString("chat_bg_all", "") ?: ""
        val own = sp.getString("chat_bg_$convoId", null)
        // a thread with no setting of its own inherits the app-wide default
        if (own != null) return own
        return sp.getString("chat_bg_all", "") ?: ""
    }
    fun setChatBg(convoId: Long, v: String) {
        val key = if (convoId == -1L) "chat_bg_all" else "chat_bg_$convoId"
        sp.edit().putString(key, v).apply()
    }
    val autoDownloadMms: Boolean
        get() = sp.getBoolean("auto_download_mms", true)
    val deleteApkAfterUpdate: Boolean
        get() = sp.getBoolean("delete_apk_after_update", true)
    var pendingUpdateDownloadId: Long
        get() = sp.getLong("pending_update_dl_id", -1L)
        set(v) = sp.edit().putLong("pending_update_dl_id", v).apply()
    var pendingUpdateTag: String
        get() = sp.getString("pending_update_tag", "") ?: ""
        set(v) = sp.edit().putString("pending_update_tag", v).apply()
    var lastNotifiedUpdateTag: String
        get() = sp.getString("last_notified_update_tag", "") ?: ""
        set(v) = sp.edit().putString("last_notified_update_tag", v).apply()
    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()
    val appZoom: Float
        get() = (sp.getString("app_zoom", "1.0") ?: "1.0").toFloatOrNull() ?: 1.0f
    var defaultTone: String
        get() = sp.getString("default_tone", "") ?: ""
        set(v) = sp.edit().putString("default_tone", v).apply()
    /** Tri-state vibrate: "always" / "vibrate_only" (only when the phone is in
     *  vibrate mode) / "never". Migrates the old on/off setting. */
    val vibrateMode: String
        get() = sp.getString("vibrate_mode", null)
            ?: if (sp.getBoolean("vibrate", true)) "always" else "never"

    fun shouldVibrate(context: android.content.Context): Boolean = when (vibrateMode) {
        "never" -> false
        "vibrate_only" -> {
            val am = context.getSystemService(android.content.Context.AUDIO_SERVICE)
                as android.media.AudioManager
            am.ringerMode == android.media.AudioManager.RINGER_MODE_VIBRATE
        }
        else -> true
    }
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
    val respondToDeliveryRequests: Boolean get() = sp.getBoolean("respond_delivery", true)
    /** "single" (one aggregated notification, default) or "per_convo". */
    val notifMode: String get() = sp.getString("notif_mode", "single") ?: "single"
    // migration: the old "keep until conversation opened" toggle maps to
    // clearing on conversation open
    val notifClearMode: String
        get() {
            val v = sp.getString("notif_clear_mode", null)
            if (v != null) return v
            return if (sp.getBoolean("notif_persist", false)) "conversation" else "app"
        }
    val softkeysFocusable: Boolean get() = sp.getBoolean("softkeys_focusable", false)
    /** Spoof the MMS User-Agent + UAProf so Verizon's MMSC treats us as a
     *  modern handset and stops transcoding audio to QCELP / over-compressing
     *  images. Off by default; the value is the device model token. */
    /** Container/codec for in-app voice notes: "m4a" (AAC, what modern
     *  handsets send), "3gp" (AMR in a 3GPP container) or "amr" (raw AMR).
     *  Raw AMR is what Verizon's MMSC most eagerly transcodes to QCELP. */
    val voiceFormat: String get() = sp.getString("voice_format", "m4a") ?: "m4a"
    // 0.9.52: the boolean spoof switch became a profile picker. Migration:
    // an existing explicit OFF carries over; everything else starts on the
    // carrier identity (the previous behavior).
    val mmsClientProfile: String
        get() {
            val v = sp.getString("mms_client_profile", null)
            if (v != null) return v
            return if (sp.getBoolean("mms_ua_spoof", true)) "carrier" else "off"
        }
    val mmsCustomUa: String
        get() = sp.getString("mms_custom_ua", "") ?: ""
    val mmsCustomUaProf: String
        get() = sp.getString("mms_custom_uaprof", "") ?: ""
    var phantomsPurged: Boolean
        get() = sp.getBoolean("phantoms_purged", false)
        set(v) { sp.edit().putBoolean("phantoms_purged", v).apply() }
    var snippetWordsFixed: Boolean
        get() = sp.getBoolean("snippet_words_fixed", false)
        set(v) { sp.edit().putBoolean("snippet_words_fixed", v).apply() }
    var sentColor: String
        get() = sp.getString("sent_color", "") ?: ""
        set(v) { sp.edit().putString("sent_color", v).apply() }
    var wasDefaultSms: Boolean
        get() = sp.getBoolean("was_default_sms", false)
        set(v) { sp.edit().putBoolean("was_default_sms", v).apply() }
    // broadcast / group_mms : default for NEW group conversations
    val defaultGroupMode: String get() = sp.getString("default_group_mode", "group_mms") ?: "group_mms"
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
