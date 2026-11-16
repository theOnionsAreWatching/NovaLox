package io.github.theonionsarewatching.nova.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * the picture viewer's image. Drawn through a matrix from a
 * screen-sized-or-bigger decode, so zooming re-samples the real pixels
 * (sharp) instead of stretching an already-scaled view (blurry).
 *
 * Start size: the picture FILLS the screen when that cuts off only a little
 * (aspect within 25%), so there are no black side bars; very wide or very
 * tall pictures start fitted so nothing important is hidden. Zoom in up to
 * 4x the start size; zoom out goes back to the start size and then one more
 * step to "whole picture" (fit).
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    /** false for video posters: always fitted, like the video itself */
    var fillAllowed = true
        set(v) { field = v; reset() }

    /** called after every zoom / pan change */
    var onChanged: (() -> Unit)? = null

    private val m = Matrix()
    private var fit = 1f
    private var base = 1f
    var scale = 1f
        private set
    private var tx = 0f
    private var ty = 0f

    init { scaleType = ScaleType.MATRIX }

    private val dw get() = drawable?.intrinsicWidth ?: 0
    private val dh get() = drawable?.intrinsicHeight ?: 0

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    /** back to the start size, centered */
    fun reset() {
        if (width == 0 || height == 0 || dw <= 0 || dh <= 0) return
        fit = minOf(width.toFloat() / dw, height.toFloat() / dh)
        val fill = maxOf(width.toFloat() / dw, height.toFloat() / dh)
        base = if (fillAllowed && fill / fit <= 1.25f) fill else fit
        scale = base
        tx = (width - dw * scale) / 2f
        ty = (height - dh * scale) / 2f
        apply()
    }

    /** zoom relative to the start size, in % */
    val percent: Int get() = if (base <= 0f) 100 else Math.round(scale / base * 100f)

    val isZoomedIn: Boolean get() = scale > base * 1.01f
    val canZoomIn: Boolean get() = dw > 0 && scale < base * 3.99f
    val canZoomOut: Boolean get() = dw > 0 && scale > fit * 1.01f

    fun zoomIn() {
        if (!canZoomIn) return
        setScale((scale * 1.5f).coerceAtMost(base * 4f))
    }

    fun zoomOut() {
        if (!canZoomOut) return
        val next = scale / 1.5f
        setScale(when {
            next > base * 1.05f -> next
            scale > base * 1.01f -> base      // back to the start size
            else -> fit                        // then the whole picture
        })
    }

    /** OK: zoom to 2x around the center, or back to the start size */
    fun toggle() { if (isZoomedIn) setScale(base) else setScale(base * 2f) }

    /** keeps the point at the middle of the screen in place */
    fun setScale(s: Float) {
        if (dw <= 0 || width == 0) return
        val cx = width / 2f; val cy = height / 2f
        val px = (cx - tx) / scale; val py = (cy - ty) / scale
        scale = s.coerceIn(fit, base * 4f)
        tx = cx - px * scale; ty = cy - py * scale
        apply()
    }

    fun scaleBy(f: Float) = setScale(scale * f)

    /** true when the picture is bigger than the screen that way */
    fun canPanX(): Boolean = dw * scale > width + 1
    fun canPanY(): Boolean = dh * scale > height + 1

    /** moves the picture; returns false when it is already at that edge */
    fun pan(dx: Float, dy: Float): Boolean {
        val ox = tx; val oy = ty
        tx += dx; ty += dy
        apply()
        return tx != ox || ty != oy
    }

    private fun clamp() {
        val cw = dw * scale; val ch = dh * scale
        tx = if (cw <= width) (width - cw) / 2f else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) (height - ch) / 2f else ty.coerceIn(height - ch, 0f)
    }

    private fun apply() {
        clamp()
        m.reset()
        m.postScale(scale, scale)
        m.postTranslate(tx, ty)
        imageMatrix = m
        onChanged?.invoke()
    }
}
