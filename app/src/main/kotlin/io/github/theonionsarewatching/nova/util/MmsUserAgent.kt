package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.os.Build

/**
 * MMS content transcoding on Verizon (and some other carriers) is driven by the
 * capabilities the downloading handset advertises over HTTP: the User-Agent and
 * the x-wap-profile (UAProf) URL pointing at an XML capability sheet. AOSP's
 * defaults ("Android-Mms/0.1" + a 2008 Google profile) read as a legacy device,
 * so the MMSC down-converts audio to the QCELP codec Android can't decode and
 * compresses images hard.
 *
 * Declaring a modern device's UA + a Verizon-hosted UAProf (exactly what the
 * Magisk overlay and the Message+ app do) makes the MMSC serve original AMR/mp3
 * audio and lighter image compression.
 *
 * Two surfaces need it:
 *  - DOWNLOAD (incoming): the library reads MmsConfig statics via
 *    MMS_CONFIG_HTTP_PARAMS, so we set them and expose an httpParams line.
 *  - SEND (outgoing): the klinker Settings object carries its own UA fields.
 */
object MmsUserAgent {

    // A current, widely-provisioned Verizon handset profile. The UAProf host
    // (uaprof.vtext.com) is Verizon's own, which their MMSC trusts.
    private const val UA = "Android-Mms/2.0"
    private fun uaProfUrl(): String {
        val model = Build.MODEL.replace(" ", "").ifBlank { "Pixel7" }
        // Verizon's UAProf path scheme; a served, well-formed profile is what
        // matters more than an exact per-model match.
        return "http://uaprof.vtext.com/generic/generic.xml".also { _ ->
            // model kept for potential future per-device routing
            model.length
        }
    }

    /** Applied once at startup: seed the static config the download path reads. */
    fun applyToConfig(context: Context) {
        if (!Prefs.get(context).mmsUaSpoof) return
        try {
            com.android.mms.MmsConfig.setUserAgent(UA)
            com.android.mms.MmsConfig.setUaProfUrl(uaProfUrl())
            com.android.mms.MmsConfig.setUaProfTagName("x-wap-profile")
            DiagLog.log(context, "mms-ua", "UA spoof active: UA=$UA UAProf=${uaProfUrl()}")
        } catch (e: Exception) {
            DiagLog.log(context, "mms-ua", "config set failed: ${e.message}")
        }
    }

    /** The HTTP header line the download path injects via MMS_CONFIG_HTTP_PARAMS. */
    fun httpParams(context: Context): String? {
        if (!Prefs.get(context).mmsUaSpoof) return null
        // "key: value" pairs, one per line, as the platform expects
        return "User-Agent: $UA\nx-wap-profile: ${uaProfUrl()}"
    }

    /** Apply to the outgoing klinker Settings object. */
    fun applyToSettings(context: Context, settings: com.klinker.android.send_message.Settings) {
        if (!Prefs.get(context).mmsUaSpoof) return
        try {
            settings.setAgent(UA)
            settings.setUserProfileUrl(uaProfUrl())
            settings.setUaProfTagName("x-wap-profile")
        } catch (_: Exception) {}
    }
}
