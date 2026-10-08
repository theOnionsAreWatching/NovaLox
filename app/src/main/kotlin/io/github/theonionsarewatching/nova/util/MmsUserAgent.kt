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
 *   tcl_t408dl_vzw — a real Verizon handset: TCL Flip T408DL's stock
 *             Messaging identity, PROVEN on the wire (2026-10-08, same
 *             sender, same phone): Verizon's MMSC transcodes audio per the
 *             RECEIVER's UAProf — Android-Mms + Google's kila profile got the
 *             voice note as audio/vnd.qcelp (no decoder on Android), this
 *             identity got it as MP3. Values from the TCL app's MmsConfig
 *             VZW-SIM branch (getUserAgent -> "wst408dl", getUaProfUrl ->
 *             vtext wst408dl.xml; the Profile header was logged, the UA comes
 *             from the same branch), under Verizon's own tag name "Profile".
 *   tcl_4058g_vzw — TCL Flip Pro 4058G on a Verizon SIM, same MmsConfig
 *             branch ("tcl4058g" + vtext tcl4058g.xml). Not wire-tested.
 *   tcl_4058g — TCL Flip Pro 4058G on other carriers ("4058G-MMS/2.0" +
 *             TCL's own CC/PP host). Not wire-tested.
 *   vzw_samsung — Galaxy S10 on Verizon (samg970u + its uaprof.vtext.com
 *             profile): Handcent's "samsung" preset on Verizon SIMs, decoded
 *             from Handcent 11.10. Same vtext profile host as tcl_t408dl_vzw; not
 *             yet wire-tested on its own.
 *   iphone  — Handcent's / NowSMS's iPhone identity with Apple's 2 MB UAProf.
 *   custom  — user-entered UA, UAProf URL and (optional) Accept.
 *
 * Applied on BOTH legs: SEND via SystemMmsSender's overrides, DOWNLOAD via
 * MmsPushReceiver's overrides (the platform merges caller overrides with
 * mmsConfig.putAll — verified in MmsService.java).
 *
 * Accept: the platform MmsHttpClient hardcodes
 * "Accept: *&#47;*, application/vnd.wap.mms-message, application/vnd.wap.sic",
 * then applies the httpParams extra headers with setRequestProperty — which
 * REPLACES a header already set (verified in MmsHttpClient.java). So a
 * custom Accept rides in httpParams, merged after the carrier's own
 * httpParams so carrier-required headers (Verizon's MDN macros etc.) survive.
 * Presets keep the platform Accept: it was identical in the QCELP and MP3
 * captures above, so it is not what the MMSC adapts on.
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
        val uaProfTagName: String,
        /** Replaces the platform Accept header; null keeps the platform's. */
        val accept: String? = null
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
            "tcl_t408dl_vzw" -> Profile(
                "wst408dl",
                "http://uaprof.vtext.com/alcatel/wst408dl/wst408dl.xml",
                "Profile"
            )
            "tcl_4058g_vzw" -> Profile(
                "tcl4058g",
                "http://uaprof.vtext.com/tcl/tcl4058g/tcl4058g.xml",
                "Profile"
            )
            "tcl_4058g" -> Profile(
                "4058G-MMS/2.0",
                "http://www-ccpp.tcl-ta.com/files/4058g.rdf",
                "x-wap-profile"
            )
            "vzw_samsung" -> Profile(
                "samg970u",
                "http://uaprof.vtext.com/sam/samg970u/samg970u.xml",
                "Profile"
            )
            "iphone" -> Profile(
                "iPhoneOS/4.2.1 (8C148)",
                "http://iphonemms.apple.com/iphone/uaprof-2MB.rdf",
                "x-wap-profile"
            )
            "custom" -> {
                val ua = prefs.mmsCustomUa.trim()
                if (ua.isEmpty()) null
                else Profile(
                    ua, prefs.mmsCustomUaProf.trim(), "x-wap-profile",
                    prefs.mmsCustomAccept.trim().ifEmpty { null }
                )
            }
            else -> null
        }
    }

    /**
     * Overrides are honored directly by the platform MmsService on both legs.
     * [subId] picks whose carrier httpParams get preserved (dual-SIM); -1 is
     * the default SMS subscription.
     */
    fun applyToOverrides(context: Context, b: Bundle, subId: Int = -1) {
        val p = profileFor(context) ?: return
        b.putString(SmsManager.MMS_CONFIG_USER_AGENT, p.userAgent)
        b.putString(SmsManager.MMS_CONFIG_UA_PROF_URL, p.uaProfUrl)
        b.putString(SmsManager.MMS_CONFIG_UA_PROF_TAG_NAME, p.uaProfTagName)
        if (p.accept != null) {
            b.putString(
                SmsManager.MMS_CONFIG_HTTP_PARAMS,
                mergeHttpParams(carrierHttpParams(context, subId), "Accept" to p.accept)
            )
        }
        DiagLog.log(
            context, "mms-ua",
            "identity UA=${p.userAgent} uaProf=<${p.uaProfUrl}> " +
                "accept=${p.accept ?: "platform"}"
        )
    }

    /** The carrier's own httpParams, so required headers survive our merge.
     *  Same source the platform MmsService reads (its MmsConfigManager). */
    private fun carrierHttpParams(context: Context, subId: Int = -1): String? {
        val fromMmsConfig = try {
            @Suppress("DEPRECATION")
            val sm = if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId)
                else SmsManager.getDefault()
            sm.carrierConfigValues?.getString(SmsManager.MMS_CONFIG_HTTP_PARAMS)
        } catch (_: Exception) { null }
        if (fromMmsConfig != null) return fromMmsConfig
        return try {
            val ccm = context.getSystemService(Context.CARRIER_CONFIG_SERVICE)
                as android.telephony.CarrierConfigManager
            ccm.config?.getString("httpParams")
        } catch (_: Exception) { null }
    }

    /**
     * httpParams format is "Name:Value|Name:Value" (split on '|', then on the
     * first ':'). Carrier entries for a header we set are dropped so ours is
     * the only one; everything else — including ##MACRO## values the platform
     * expands — passes through untouched.
     */
    internal fun mergeHttpParams(carrier: String?, vararg ours: Pair<String, String>): String {
        val names = ours.map { it.first.lowercase() }.toSet()
        val kept = carrier.orEmpty().split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it.substringBefore(':').trim().lowercase() !in names }
        return (kept + ours.map { "${it.first}:${it.second}" }).joinToString("|")
    }

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
            val ours = listOfNotNull(
                "User-Agent" to p.userAgent,
                p.accept?.let { "Accept" to it }
            ).toTypedArray()
            val merged = mergeHttpParams(
                carrierHttpParams(context)?.takeIf { it.isNotBlank() }, *ours
            )
            val f = com.android.mms.MmsConfig::class.java.getDeclaredField("mHttpParams")
            f.isAccessible = true
            f.set(null, merged)
            DiagLog.log(context, "mms-ua", "engine config seeded UA=${p.userAgent}")
        } catch (e: Exception) {
            DiagLog.log(context, "mms-ua", "httpParams inject failed: ${e.message}")
        }
    }
}
