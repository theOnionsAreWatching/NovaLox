package io.github.theonionsarewatching.nova.ui

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.core.graphics.ColorUtils

/** Letter / group avatar backgrounds: 18 deep colours (white text reads on
 *  all of them, on every theme), a diagonal gradient and a thin lighter ring.
 *  Same key = same colour. Drawables are cached per colour, so scrolling
 *  only reuses them (no per-frame work). */
object Avatars {
    val palette = intArrayOf(
        0xFF1565C0.toInt(), 0xFF00796B.toInt(), 0xFF2E7D32.toInt(), 0xFFE65100.toInt(),
        0xFFC62828.toInt(), 0xFF6A1B9A.toInt(), 0xFFAD1457.toInt(), 0xFF546E7A.toInt(),
        0xFF283593.toInt(), 0xFF00838F.toInt(), 0xFF558B2F.toInt(), 0xFFB26A00.toInt(),
        0xFF4527A0.toInt(), 0xFFD84315.toInt(), 0xFF9B1B30.toInt(), 0xFF0277BD.toInt(),
        0xFF5D4037.toInt(), 0xFF1E8C6A.toInt()
    )
    /** groups: their own cool slate-to-indigo family */
    private val groupPalette = intArrayOf(
        0xFF3949AB.toInt(), 0xFF00695C.toInt(), 0xFF5E35B1.toInt(), 0xFF37474F.toInt(),
        0xFF0277BD.toInt(), 0xFF6A1B9A.toInt()
    )

    fun colorFor(key: String) = palette[Math.floorMod(key.hashCode(), palette.size)]

    private val cache = HashMap<Long, Drawable.ConstantState>()

    private fun build(color: Int, group: Boolean, ringPx: Int): Drawable {
        val light = ColorUtils.blendARGB(color, 0xFFFFFFFF.toInt(), if (group) 0.30f else 0.22f)
        val dark = ColorUtils.blendARGB(color, 0xFF000000.toInt(), if (group) 0.35f else 0.18f)
        val fill = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(light, color, dark)).apply {
            shape = GradientDrawable.OVAL
            setStroke(ringPx, ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), if (group) 0x88 else 0x55))
        }
        // soft inner highlight on the top half
        val shine = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x33FFFFFF, 0x00FFFFFF)).apply { shape = GradientDrawable.OVAL }
        return LayerDrawable(arrayOf(fill, shine)).apply { setLayerInset(1, ringPx * 2, ringPx, ringPx * 2, 0) }
    }

    private fun get(color: Int, group: Boolean, ringPx: Int): Drawable {
        val k = (color.toLong() shl 8) or (ringPx.toLong() shl 1) or (if (group) 1L else 0L)
        cache[k]?.let { return it.newDrawable().mutate() }
        val d = build(color, group, ringPx)
        d.constantState?.let { cache[k] = it }
        return d
    }

    fun letter(ctx: android.content.Context, key: String): Drawable =
        get(colorFor(key), false, ThemeUtils.dp(ctx, 1).coerceAtLeast(1))

    fun group(ctx: android.content.Context, key: String): Drawable =
        get(groupPalette[Math.floorMod(key.hashCode(), groupPalette.size)], true,
            ThemeUtils.dp(ctx, 2).coerceAtLeast(1))
}
