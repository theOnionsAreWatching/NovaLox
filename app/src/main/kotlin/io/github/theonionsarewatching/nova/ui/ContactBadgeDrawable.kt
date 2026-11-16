package io.github.theonionsarewatching.nova.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint

/** Plain circle contact mark: subtle diagonal gradient in the stable per-chat
 *  colour, a thin pale ring, and crisp bold initials. No binder tabs or
 *  person silhouette. The [group] flag is kept so call sites stay unchanged;
 *  identity still comes from the monogram ("G" when a group has no names). */
class ContactBadgeDrawable(color: Int, monogram: String, @Suppress("UNUSED_PARAMETER") group: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var opacity = 255
    private val fill = LinearGradient(
        22f, 14f, 58f, 66f,
        androidx.core.graphics.ColorUtils.blendARGB(color, Color.WHITE, 0.28f),
        androidx.core.graphics.ColorUtils.blendARGB(color, Color.BLACK, 0.20f),
        Shader.TileMode.CLAMP
    )
    private val ring = androidx.core.graphics.ColorUtils.blendARGB(color, Color.WHITE, 0.48f)
    private val label = StaticLayout.Builder.obtain(
        monogram, 0, monogram.length,
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = if (monogram.length <= 1) 32f else 27f
            typeface = Typeface.DEFAULT_BOLD
        },
        64
    ).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).setMaxLines(1).build()

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || opacity == 0) return
        val saved = if (opacity < 255) canvas.saveLayerAlpha(
            bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat(), opacity
        ) else canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val scale = minOf(bounds.width(), bounds.height()) / 80f
        canvas.translate((bounds.width() - 80 * scale) / 2f, (bounds.height() - 80 * scale) / 2f)
        canvas.scale(scale, scale)
        paint.style = Paint.Style.FILL
        paint.shader = fill
        canvas.drawCircle(40f, 40f, 36f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = ring
        paint.strokeWidth = 2.25f
        canvas.drawCircle(40f, 40f, 34.9f, paint)
        canvas.save()
        canvas.translate(8f, 40f - label.height / 2f)
        label.draw(canvas)
        canvas.restore()
        canvas.restoreToCount(saved)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; label.paint.colorFilter = filter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
