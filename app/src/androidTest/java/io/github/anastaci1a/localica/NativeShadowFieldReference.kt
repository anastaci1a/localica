package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/** Independent receipt kernel. No production field, shadow, geometry cache or renderer is called. */
@RequiresApi(29)
internal fun nativeShadowFieldReference(width: Int, height: Int, bodies: List<Pair<RoundRect, Float>>,
    density: Float, strength: Float, spread: Float, background: Int, outsideMask: Bitmap? = null,
    scaleX: Float = 1f, scaleY: Float = 1f): ImageBitmap {
    require(bodies.isNotEmpty() && density.isFinite() && density > 0f && strength.isFinite() && spread.isFinite())
    require(Color.alpha(background) == 255)
    require(scaleX.isFinite() && scaleY.isFinite() && scaleX > 0f && scaleY > 0f)
    require(outsideMask == null || outsideMask.width == width && outsideMask.height == height)
    val reach = (4f + spread * 28f) * density
    val shift = 2f * density
    require(reach.isFinite() && reach >= 0f)
    val count = ceil(reach).toInt().coerceIn(16, 128)
    val opacity = bodies.maxOf { it.second }.coerceIn(0f, 1f)
    // Declared optical-density kernel: quantize cumulative coverage, then derive SrcOver increments.
    fun coverage(step: Int): Int {
        val u = step.toFloat() / count
        return ((1f - exp(-.34594453f * (strength * opacity) * u * u * u)) * 255f)
            .roundToInt().coerceIn(0, 255)
    }
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
        canvas.saveLayer(bounds, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        if (strength > 0f) repeat(count) { index ->
            val previous = coverage(index)
            val alpha = if (previous == 255) 0 else
                (255f * (coverage(index + 1) - previous) / (255 - previous)).roundToInt()
            if (alpha > 0) {
                paint.alpha = alpha
                val expansion = reach * (1f - (index + .5f) / count)
                val ex = expansion * scaleX
                val ey = expansion * scaleY
                val dy = shift * scaleY
                if (uniform != null) {
                    canvas.drawRoundRect(uniform.left - ex, uniform.top - ey + dy,
                        uniform.right + ex, uniform.bottom + ey + dy,
                        uniform.topLeftCornerRadius.x + ex, uniform.topLeftCornerRadius.y + ey, paint)
                } else {
                    val ring = Path().apply {
                        bodies.forEach { (r, _) ->
                            addRoundRect(r.left - ex, r.top - ey + dy,
                                r.right + ex, r.bottom + ey + dy,
                                floatArrayOf(r.topLeftCornerRadius.x + ex, r.topLeftCornerRadius.y + ey,
                                    r.topRightCornerRadius.x + ex, r.topRightCornerRadius.y + ey,
                                    r.bottomRightCornerRadius.x + ex, r.bottomRightCornerRadius.y + ey,
                                    r.bottomLeftCornerRadius.x + ex, r.bottomLeftCornerRadius.y + ey), Path.Direction.CW)
                        }
                    }
                    canvas.drawPath(ring, paint)
                }
            }
        }
        outsideMask?.let { canvas.drawBitmap(it, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }) }
        canvas.restore()
    }
}
