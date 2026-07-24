package io.github.theonionsarewatching.nova.ui

import android.graphics.Color
import kotlin.math.abs

/**
 * Bubble colors for "per-member colors in groups".
 *
 * The first version derived a hue straight from the sender's hash, which meant
 * two members could land a few degrees apart and look identical. This uses a
 * fixed palette instead, built from eight well-separated hues crossed with
 * three shades, and — crucially — ORDERED so that consecutive indices change
 * hue rather than shade. A group therefore exhausts all eight hues before it
 * ever reuses one at a different shade, so near-duplicates only appear in
 * groups larger than eight participants.
 *
 * Shades vary by saturation while keeping brightness in a narrow band, so
 * every entry stays readable with the theme's normal message text.
 */
object MemberPalette {

    private val HUES = floatArrayOf(215f, 0f, 122f, 275f, 30f, 174f, 330f, 52f)

    /** saturation per shade — light / medium / deep tint of the same hue */
    private val LIGHT_SAT = floatArrayOf(0.16f, 0.30f, 0.46f)
    private val LIGHT_VAL = floatArrayOf(0.99f, 0.96f, 0.92f)
    private val DARK_SAT = floatArrayOf(0.28f, 0.42f, 0.55f)
    private val DARK_VAL = floatArrayOf(0.30f, 0.27f, 0.24f)

    /** total distinct colors available before any repeat */
    val size: Int get() = HUES.size * LIGHT_SAT.size

    fun colorAt(index: Int, night: Boolean): Int {
        val i = ((index % size) + size) % size
        // hue cycles fastest so neighbouring members never share a hue
        val hue = HUES[i % HUES.size]
        val shade = i / HUES.size
        return if (night)
            Color.HSVToColor(floatArrayOf(hue, DARK_SAT[shade], DARK_VAL[shade]))
        else
            Color.HSVToColor(floatArrayOf(hue, LIGHT_SAT[shade], LIGHT_VAL[shade]))
    }

    /** Stable color for a sender when no member ordering is available. */
    fun colorFor(senderKey: String, night: Boolean): Int =
        colorAt(abs(senderKey.hashCode()), night)
}
