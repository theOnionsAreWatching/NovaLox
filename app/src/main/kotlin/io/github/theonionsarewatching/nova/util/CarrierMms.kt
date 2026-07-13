package io.github.theonionsarewatching.nova.util

import android.content.Context
import android.telephony.SmsManager

/**
 * The carrier's own MMS limits, queried per active SIM the way the AOSP
 * Messaging app does (BugleCarrierConfigValuesLoader). Falls back to the
 * ecosystem-standard 800 KB when the query fails or returns nothing.
 */
object CarrierMms {

    data class Limits(val maxBytes: Int, val maxImageWidth: Int, val maxImageHeight: Int)

    private const val FALLBACK_MAX = 800 * 1024

    /** Segment count above which a long text should be sent as MMS. Carrier
     *  value when published; the common default (4) otherwise. */
    fun smsToMmsThreshold(context: Context): Int {
        return try {
            @Suppress("DEPRECATION")
            val values = SmsManager.getDefault().carrierConfigValues
            values?.getInt(SmsManager.MMS_CONFIG_SMS_TO_MMS_TEXT_THRESHOLD, -1)
                ?.takeIf { it > 0 } ?: 4
        } catch (_: Exception) { 4 }
    }

    fun limits(context: Context): Limits {
        return try {
            @Suppress("DEPRECATION")
            val values = SmsManager.getDefault().carrierConfigValues
            val max = values?.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, FALLBACK_MAX)
                ?.takeIf { it > 0 } ?: FALLBACK_MAX
            val w = values?.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH, 0) ?: 0
            val h = values?.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT, 0) ?: 0
            Limits(max, w, h)
        } catch (_: Exception) {
            Limits(FALLBACK_MAX, 0, 0)
        }
    }
}
