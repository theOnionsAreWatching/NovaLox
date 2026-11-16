package io.github.theonionsarewatching.nova.util

/**
 * App-level vibration pattern for new-message notifications.
 * This is the *shape* of the buzz, separate from [Prefs.vibrateMode]
 * (whether to buzz at all: always / only in vibrate mode / never).
 *
 * "default" keeps today's behavior exactly: on Android 8+ the notification
 * channel is left with the system vibration pattern, and on older Android
 * the existing two-short waveform is used.
 *
 * Long and two-short timings match DPAD Messaging (MIT, uncleandy fork).
 */
object VibrationPattern {
    const val KEY = "vibrate_pattern"
    const val DEFAULT = "default"
    const val LONG = "long"
    const val TWO_SHORT = "two_short"
    const val THREE_SHORT = "three_short"
    const val HEARTBEAT = "heartbeat"
    const val PULSE = "pulse"
    const val GENTLE = "gentle"
    const val OFF = "off"

    val VALUES = listOf(
        DEFAULT, LONG, TWO_SHORT, THREE_SHORT, HEARTBEAT, PULSE, GENTLE, OFF
    )

    fun normalize(raw: String?): String =
        if (raw != null && raw in VALUES) raw else DEFAULT

    /** Buzz only when the existing vibrate setting allows it, and the
     *  pattern is not Off. */
    fun shouldBuzz(shouldVibrate: Boolean, pattern: String): Boolean =
        shouldVibrate && normalize(pattern) != OFF

    /**
     * Custom waveform, or null when the system default must be left alone
     * (Default) or when there is no buzz (Off). Timings are milliseconds:
     * off, on, off, on, …
     */
    fun waveform(pattern: String): LongArray? = when (normalize(pattern)) {
        LONG -> longArrayOf(0L, 1000L)
        TWO_SHORT -> longArrayOf(0L, 250L, 150L, 250L)
        THREE_SHORT -> longArrayOf(0L, 200L, 120L, 200L, 120L, 200L)
        HEARTBEAT -> longArrayOf(0L, 150L, 100L, 500L)
        PULSE -> longArrayOf(0L, 80L, 80L, 80L, 80L, 80L, 80L, 80L)
        GENTLE -> longArrayOf(0L, 120L)
        else -> null
    }

    /** Pre-Android 8 Default pattern. Must stay exactly this array. */
    val PRE_O_DEFAULT = longArrayOf(0L, 250L, 150L, 250L)
    val PRE_O_SILENT = longArrayOf(0L)

    fun preOVibrate(shouldVibrate: Boolean, pattern: String): LongArray {
        val p = normalize(pattern)
        if (!shouldBuzz(shouldVibrate, p)) return PRE_O_SILENT
        return waveform(p) ?: PRE_O_DEFAULT
    }

    /**
     * String that is hashed into the notification channel id.
     *
     * For Default, and for any case with no buzz, this is exactly
     * `tone|vibrate|L2` so existing users keep their current channels
     * (tone, importance and any system tweaks) until they pick a custom
     * pattern.
     */
    fun channelKeyMaterial(tone: String, shouldVibrate: Boolean, pattern: String): String {
        val p = normalize(pattern)
        val buzz = shouldBuzz(shouldVibrate, p)
        return if (buzz && p != DEFAULT) {
            "$tone|true|L2|$p"
        } else {
            "$tone|$buzz|L2"
        }
    }

    fun channelKeyHash(tone: String, shouldVibrate: Boolean, pattern: String): Int =
        channelKeyMaterial(tone, shouldVibrate, pattern).hashCode()
}
