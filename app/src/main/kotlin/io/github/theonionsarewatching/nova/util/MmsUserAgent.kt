package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.os.Bundle
import android.telephony.SmsManager

/**
 * Carrier MMS compatibility.
 *
 * FIELD EVIDENCE (captured from platform MmsService logs while Verizon's own
 * Message+ app received an MMS):
 *
 *     HTTP: User-Agent=vzmmms1.0
 *     mms config: userAgent=  uaProfUrl=  uaProfTagName=Profile
 *                 httpParams=X-VzW-MDN: 1##LINE1NOCOUNTRYCODE##
 *
 * Message+ presents the CARRIER'S OWN client UA and sends no UAProf at all.
 * Verizon's MMSC serves original audio to that client, and transcodes to QCELP
 * (audio/vnd.qcelp — unplayable on most handsets) for anything else. This is
 * why incoming audio, including plain MP3s from other senders, arrives dead.
 *
 * So the fix is not "look modern" — it is to present the exact client string
 * the carrier's MMSC treats as first-class, on BOTH legs:
 *
 *   SEND     — our own sender passes config overrides directly.
 *   DOWNLOAD — the engine's download path forwards only MMS_CONFIG_HTTP_PARAMS,
 *              so the UA rides in there as an extra header (the platform's
 *              extra-header pass uses setRequestProperty, which overrides the
 *              User-Agent it set earlier). The carrier's own httpParams are
 *              preserved and merged, so required headers like X-VzW-MDN and
 *              their ##LINE1## macros keep working.
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

    /** Known-good client identities, taken from each carrier's own app. */
    private fun profileFor(context: Context): Profile? {
        val mccmnc = try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE)
                as android.telephony.TelephonyManager
            tm.simOperator.orEmpty()
        } catch (_: Exception) { "" }
        return when {
            mccmnc in VERIZON -> Profile("vzmmms1.0", "", "Profile")
            else -> null
        }
    }

    private fun enabled(context: Context) = Prefs.get(context).mmsUaSpoof

    /** SEND path: overrides are honored directly by the platform MmsService. */
    fun applyToOverrides(context: Context, b: Bundle) {
        if (!enabled(context)) return
        val p = profileFor(context) ?: return
        b.putString(SmsManager.MMS_CONFIG_USER_AGENT, p.userAgent)
        b.putString(SmsManager.MMS_CONFIG_UA_PROF_URL, p.uaProfUrl)
        b.putString(SmsManager.MMS_CONFIG_UA_PROF_TAG_NAME, p.uaProfTagName)
        DiagLog.log(context, "mms-ua", "send UA=${p.userAgent} uaProf=<${p.uaProfUrl}>")
    }

    /** The carrier's own httpParams, so required headers survive our merge. */
    private fun carrierHttpParams(context: Context): String? = try {
        val ccm = context.getSystemService(Context.CARRIER_CONFIG_SERVICE)
            as android.telephony.CarrierConfigManager
        ccm.config?.getString("httpParams")
    } catch (_: Exception) { null }

    /**
     * DOWNLOAD path: inject the UA as an extra header through httpParams.
     * MmsConfig.mHttpParams has no public setter (it is normally filled from
     * carrier XML), so it is set reflectively. MmsConfig.init() only assigns
     * keys present in its XML — httpParams is not among them — so the value
     * survives the init() call the push receiver makes before each download.
     */
    fun applyToConfig(context: Context) {
        if (!enabled(context)) return
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
            DiagLog.log(context, "mms-ua", "download httpParams=$merged")
        } catch (e: Exception) {
            DiagLog.log(context, "mms-ua", "httpParams inject failed: ${e.message}")
        }
    }
}
