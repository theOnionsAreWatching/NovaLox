package io.github.theonionsarewatching.nova.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import coil.size.Size
import coil.transform.Transformation

/** Round cover-cropped contact photo in the same 80-unit footprint as the
 *  initials circle, with a thin pale ring in the chat's colour. No initials
 *  or person overlay. */
class ContactBadgePhotoTransformation(private val color: Int) : Transformation {
    override val cacheKey = "dsms-contact-badge-photo-v5:$color"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val edge = minOf(512, maxOf(80, input.width, input.height))
        val result = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.scale(edge / 80f, edge / 80f)
        val pale = ColorUtils.blendARGB(color, Color.WHITE, 0.48f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val cx = 40f
        val cy = 40f
        val radius = 36f
        val shader = BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf((radius * 2f) / input.width, (radius * 2f) / input.height)
        shader.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(cx - input.width * scale / 2f, cy - input.height * scale / 2f)
        })
        val photo = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        paint.style = Paint.Style.FILL
        paint.shader = shader
        canvas.drawPath(photo, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = pale
        paint.strokeWidth = 2.25f
        canvas.drawCircle(cx, cy, 34.9f, paint)
        return result
    }
}
