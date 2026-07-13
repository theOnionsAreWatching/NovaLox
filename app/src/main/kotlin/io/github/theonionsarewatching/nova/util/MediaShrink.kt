package io.github.theonionsarewatching.nova.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Shrinks images to fit the MMS byte budget. Carriers cap multimedia messages
 * (the engine enforces 800 KB); raw camera photos are several megabytes, so
 * without this every photo send fails with the system's IO error. Standard
 * messaging apps all downscale silently — now we do too.
 */
object MediaShrink {

    /** Longest edge after the first decode pass; MMS screens don't need more. */
    private const val MAX_EDGE = 1600

    /**
     * Returns JPEG bytes no larger than [budget], or null when the input can't
     * be decoded (caller then sends the original and lets the engine decide).
     */
    fun shrinkToBudget(input: ByteArray, budget: Int, maxEdge: Int = MAX_EDGE): ByteArray? {
        return try {
            // pass 1: bounds only, choose a power-of-two sample size
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) {
                sample *= 2
            }
            var bmp = BitmapFactory.decodeByteArray(
                input, 0, input.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null

            // pass 2: quality ladder, then dimension reduction if still too big
            while (true) {
                for (quality in intArrayOf(90, 80, 70, 60, 50, 40)) {
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    if (out.size() <= budget) return out.toByteArray()
                }
                val nw = (bmp.width * 3) / 4
                val nh = (bmp.height * 3) / 4
                if (maxOf(nw, nh) < 320) {
                    // give up gracefully: smallest attempt, quality 40
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 40, out)
                    return out.toByteArray()
                }
                bmp = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}
