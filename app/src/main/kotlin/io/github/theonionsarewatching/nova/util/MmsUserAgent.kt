package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.os.Bundle
import android.telephony.SmsManager

/**
 * Carrier MMS compatibility — the client identity presented to the MMSC.
 *
 * FIELD EVIDENCE (captured from platform MmsService logs while Verizon's own
 * Message+ app received an MMS):
 *
 *     HTTP: User-Agent=vzmmms1.0
 *     mms config: userAgent=  uaProfUrl=  uaProfTagName=Profile
 *
 * MMSCs run content adaptation: they transcode media DOWN to what they think
 * the receiving client can play, judged by its User-Agent and UAProf
 * (capability profile) headers. A client the MMSC doesn't recognize AND that
 * presents no capability profile gets the lowest common denominator — on
 * Verizon that is QCELP audio, unplayable on most handsets. This is how
 * Handcent's "profile spoofing" works: it presents a known device identity
 * with a rich capability profile, and the MMSC serves original media.
 *
 * 0.9.52: the identity is now a user-selectable profile (Settings ->
 * "MMS client identity"), because which identity a given MMSC honors is
 * empirical:
 *
 *   carrier — the carrier app's own token (vzmmms1.0 on Verizon SIMs; no-op
 *             on other carriers). The MMSC treats its own client first-class.
 *   aosp    — stock Android Messaging's classic identity with Google's
 *             capability profile URL.
 *   samsung — a current Galaxy handset identity with Samsung's published
 *             UAProf, advertising AMR/AAC/MP3 and large messages.
 *   custom  — user-entered UA and UAProf URL, Handcent-style.
 *
 * Applied on BOTH legs: SEND via SystemMmsSender's overrides, DOWNLOAD via
 * MmsPushReceiver's overrides (the platform merges caller overrides with
 * mmsConfig.putAll — verified in MmsService.java).
 */
object MmsUserAgent {

    /** Verizon and its resellers/MVNOs (Visible, Total, Straight Talk VZW…). */
    private val VERIZON = setOf(
        "311480", "310004", "311280", "311281", "311282", "311283", "311284",
        "311285", "311286", "311287", "311288", "311289", "310890", "311270",
        "311271", "311272", "311273", "311274", "311275", "311276", "311277",
        "311278", "311279", "311390", "311870", "311880", "312770"
    )

    private data class Profile(
        val userAgent: String,
        val uaProfUrl: String,
        val uaProfTagName: String
    )

    private fun simIsVerizon(context: Context): Boolean = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE)
            as android.telephony.TelephonyManager
        tm.simOperator.orEmpty() in VERIZON
    } catch (_: Exception) { false }

    /** The selected client identity, or null when off / not applicable. */
    private fun profileFor(context: Context): Profile? {
        val prefs = Prefs.get(context)
        return when (prefs.mmsClientProfile) {
            "off" -> null
            "carrier" ->
                if (simIsVerizon(context)) Profile("vzmmms1.0", "", "Profile")
                else null
            "aosp" -> Profile(
                "Android-Mms/2.0",
                "http://www.google.com/oha/rdf/ua-profile-kila.xml",
                "x-wap-profile"
            )
            "samsung" -> Profile(
                "SAMSUNG-SM-G991U",
                "http://wap.samsungmobile.com/uaprof/SM-G991U.xml",
                "x-wap-profile"
            )
            "custom" -> {
                val ua = prefs.mmsCustomUa.trim()
                if (ua.isEmpty()) null
                else Profile(ua, prefs.mmsCustomUaProf.trim(), "x-wap-profile")
            }
            else -> null
        }
    }

    /** Overrides are honored directly by the platform MmsService on both legs. */
    fun applyToOverrides(context: Context, b: Bundle) {
        val p = profileFor(context) ?: return
        b.putString(SmsManager.MMS_CONFIG_USER_AGENT, p.userAgent)
        b.putString(SmsManager.MMS_CONFIG_UA_PROF_URL, p.uaProfUrl)
        b.putString(SmsManager.MMS_CONFIG_UA_PROF_TAG_NAME, p.uaProfTagName)
        DiagLog.log(
            context, "mms-ua",
            "identity UA=${p.userAgent} uaProf=<${p.uaProfUrl}>"
        )
    }

    /** The carrier's own httpParams, so required headers survive our merge. */
    private fun carrierHttpParams(context: Context): String? = try {
        val ccm = context.getSystemService(Context.CARRIER_CONFIG_SERVICE)
            as android.telephony.CarrierConfigManager
        ccm.config?.getString("httpParams")
    } catch (_: Exception) { null }

    /**
     * Engine-path fallback (any residual download or legacy transaction the
     * engine still runs): seed its static MmsConfig so those requests carry
     * the same identity. Our own send and download paths do not need this.
     */
    fun applyToConfig(context: Context) {
        val p = profileFor(context) ?: return
        try {
            com.android.mms.MmsConfig.setUserAgent(p.userAgent)
            com.android.mms.MmsConfig.setUaProfTagName(p.uaProfTagName)
            com.android.mms.MmsConfig.setUaProfUrl(p.uaProfUrl)
        } catch (_: Exception) {}
        try {
            val carrier = carrierHttpParams(context)?.takeIf { it.isNotBlank() }
            val merged = listOfNotNull(carrier, "User-Agent:${p.userAgent}")
                .joinToString("|")
            val f = com.android.mms.MmsConfig::class.java.getDeclaredField("mHttpParams")
            f.isAccessible = true
            f.set(null, merged)
            DiagLog.log(context, "mms-ua", "engine config seeded UA=${p.userAgent}")
        } catch (e: Exception) {
            DiagLog.log(context, "mms-ua", "httpParams inject failed: ${e.message}")
        }
    }
}
