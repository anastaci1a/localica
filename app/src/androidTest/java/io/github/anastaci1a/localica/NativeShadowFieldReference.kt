package io.github.anastaci1a.localica

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.ceil
import android.util.Half
import kotlin.math.roundToInt

/** Independent receipt kernel. No production field, shadow, geometry cache or renderer is called. */
@RequiresApi(29)
internal fun nativeShadowFieldReference(width: Int, height: Int, bodies: List<Pair<RoundRect, Float>>,
    density: Float, strength: Float, spread: Float, background: Int, excludeInterior: Boolean = false,
    scaleX: Float = 1f, scaleY: Float = 1f): ImageBitmap {
    require(bodies.isNotEmpty() && density.isFinite() && density > 0f && strength.isFinite() && spread.isFinite())
    require(Color.alpha(background) == 255)
    require(scaleX.isFinite() && scaleY.isFinite() && scaleX > 0f && scaleY > 0f)
    val reach = 48f * spread * density
    val shift = 8f * density
    require(reach.isFinite() && reach >= 0f)
    val count = ceil(reach * (7f / 6f)).toInt().coerceIn(16, 128)
    val opacity = bodies.maxOf { it.second }.coerceIn(0f, 1f)
    fun coverage(step: Int): Int = (shadowPolynomialReference(48f - 56f * step / count) * strength * opacity * 255f)
        .roundToInt().coerceIn(0, 255)
    val uniform = bodies.singleOrNull()?.first?.takeIf { r ->
        val c = r.topLeftCornerRadius
        c == r.topRightCornerRadius && c == r.bottomRightCornerRadius && c == r.bottomLeftCornerRadius &&
            c.x >= 0f && c.y >= 0f && c.x <= r.width / 2f && c.y <= r.height / 2f
    }
    val bounds = RectF(bodies.minOf { it.first.left }, bodies.minOf { it.first.top },
        bodies.maxOf { it.first.right }, bodies.maxOf { it.first.bottom }).apply {
        inset(-(reach * scaleX + 1f), -((reach + shift) * scaleY + 1f))
    }
    return renderNativeReferenceImage(width, height, "independent-uncut-field-reference") { canvas ->
        canvas.drawColor(background)
        fun field() {
            canvas.saveLayer(bounds, null)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            if (strength > 0f) repeat(count) { index ->
                val previous = coverage(index)
                val alpha = if (previous == 255) 0 else
                    (255f * (coverage(index + 1) - previous) / (255 - previous)).roundToInt()
                if (alpha > 0) {
                    paint.alpha = alpha
                    val expansion = reach * (1f - (7f / 6f) * (index + .5f) / count)
                    val ex = expansion * scaleX
                    val ey = expansion * scaleY
                    val dy = shift * scaleY
                    if (uniform != null) {
                        if (uniform.width + 2f * ex <= 0f || uniform.height + 2f * ey <= 0f) return@repeat
                        canvas.drawRoundRect(uniform.left - ex, uniform.top - ey + dy,
                            uniform.right + ex, uniform.bottom + ey + dy,
                            (uniform.topLeftCornerRadius.x + ex).coerceAtLeast(0f), (uniform.topLeftCornerRadius.y + ey).coerceAtLeast(0f), paint)
                    } else {
                        val ring = Path().apply {
                            bodies.forEach { (r, _) ->
                                if (r.width + 2f * ex <= 0f || r.height + 2f * ey <= 0f) return@forEach
                                addRoundRect(r.left - ex, r.top - ey + dy,
                                    r.right + ex, r.bottom + ey + dy,
                                    floatArrayOf((r.topLeftCornerRadius.x + ex).coerceAtLeast(0f), (r.topLeftCornerRadius.y + ey).coerceAtLeast(0f),
                                        (r.topRightCornerRadius.x + ex).coerceAtLeast(0f), (r.topRightCornerRadius.y + ey).coerceAtLeast(0f),
                                        (r.bottomRightCornerRadius.x + ex).coerceAtLeast(0f), (r.bottomRightCornerRadius.y + ey).coerceAtLeast(0f),
                                        (r.bottomLeftCornerRadius.x + ex).coerceAtLeast(0f), (r.bottomLeftCornerRadius.y + ey).coerceAtLeast(0f)), Path.Direction.CW)
                            }
                        }
                        canvas.drawPath(ring, paint)
                    }
                }
            }
            canvas.restore()
        }
        if (excludeInterior) withNativeShadowCutout(canvas, bodies) { field() } else field()
    }
}

internal fun shadowPolynomialReference(distance: Float): Float {
    fun h(v: Float) = Half.toFloat(Half.toHalf(v))
    val v = h((1f / 24f) * h(distance))
    val x = h(h(4f * h(h(v * .25f) + .5f).coerceIn(0f, 1f)) - 2f)
    val q = h(x * x)
    val p0 = h(.0029544830322265625f * q - .034454345703125f)
    val p1 = h(p0 * q + .168212890625f)
    val p2 = h(p1 * q - .560546875f)
    return h(h(.20f) * h(h(p2 * x + .5f) * .5f))
}
