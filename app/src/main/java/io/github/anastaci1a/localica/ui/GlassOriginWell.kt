package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.IntSize
import kotlin.math.*

/** Shared shallow receiver geometry, not another glass/refraction material. */
internal data class GlassOriginWellProfile(
    val wallFraction: Float = .86f,
    val depthFraction: Float = .11f,
    val ambient: Float = .045f,
    val lightStrength: Float = .32f,
) {
    init {
        require(wallFraction.isFinite() && wallFraction in .1f..1f)
        require(depthFraction.isFinite() && depthFraction in 0f.. .25f)
        require(ambient.isFinite() && ambient in 0f.. .15f)
        require(lightStrength.isFinite() && lightStrength in 0f.. .5f)
    }
}

internal val DefaultGlassOriginWellProfile = GlassOriginWellProfile()

/** C2 height profile; zero slope at both the outside plane and the basin floor. */
internal fun glassWellDepth(t: Float): Float {
    val u = t.coerceIn(0f, 1f)
    return u * u * u * (10f + u * (-15f + 6f * u))
}
internal fun glassWellSlope(t: Float): Float {
    val u = t.coerceIn(0f, 1f)
    return 30f * u * u * (1f - u) * (1f - u)
}

/** Signed light change over a rounded basin. Positive is a restrained highlight. */
internal fun glassWellShade(x: Float, y: Float, width: Float, height: Float, radius: Float,
    profile: GlassOriginWellProfile): Float {
    val half = min(width, height) / 2f
    if (half <= 0f || !half.isFinite() || !radius.isFinite()) return 0f
    val r = radius.coerceIn(0f, half)
    if (r <= 0f) return 0f // Sharp-corner accessors have no curved receiver profile.
    val px = x - width / 2f; val py = y - height / 2f
    val qx = abs(px) - (width / 2f - r)
    val qy = abs(py) - (height / 2f - r)
    val ox = max(qx, 0f); val oy = max(qy, 0f)
    val length = hypot(ox, oy)
    val inward = r - length - min(max(qx, qy), 0f)
    if (inward <= 0f) return 0f
    // Rounded rectangles develop diagonal medial branches at inward distance r,
    // not only at the shape center. Flatten before the earliest branch.
    val wall = min(half, r) * profile.wallFraction
    val u = (inward / wall).coerceIn(0f, 1f)
    // The derivative is zero before reaching every distance-field medial axis.
    // Therefore nearest-edge normal ties cannot produce an internal crease.
    val slope = profile.depthFraction / profile.wallFraction * glassWellSlope(u)
    val nx: Float; val ny: Float
    if (length > .0001f) {
        nx = -sign(px) * ox / length * slope
        ny = -sign(py) * oy / length * slope
    } else if (qx > qy) { nx = -sign(px) * slope; ny = 0f }
    else { nx = 0f; ny = -sign(py) * slope }
    val normal = sqrt(1f + nx * nx + ny * ny)
    val illumination = (-.45f * nx - .65f * ny + .6123724f) / normal - .6123724f
    return (illumination * profile.lightStrength - profile.ambient * glassWellDepth(u)).coerceIn(-.25f, .08f)
}

/** Same center pivot, anisotropic body deformation and pre-scale translation as GlassMorph. */
internal fun glassWellCaster(size: Size, radius: Float, sx: Float, sy: Float, shift: Float): RoundRect {
    val center = Offset(size.width / 2f + shift, size.height / 2f)
    val half = Offset(size.width * sx / 2f, size.height * sy / 2f)
    val corner = radius.coerceIn(0f, size.minDimension / 2f)
    return RoundRect(Rect(center - half, center + half), CornerRadius(corner * sx, corner * sy))
}

/** One bounded stationary shade texture per popup. Motion only changes clipping/translation.
 * No readback, full-window layer, CPU per-frame raster, or extra animation timeline.
 * The raster contains light/occlusion only: backdrop texture remains untouched underneath.
 */
internal class GlassOriginWell {
    private data class Key(val size: Size, val radius: Float, val profile: GlassOriginWellProfile)
    private var key: Key? = null
    private var image: ImageBitmap? = null
    private val originPath = Path()
    private val casterPath = Path()
    internal var rasterizations = 0; private set

    fun DrawScope.draw(home: Rect, homeRadius: Float, caster: RoundRect, strength: Float,
        profile: GlassOriginWellProfile = DefaultGlassOriginWellProfile) {
        if (strength <= 0f || home.width <= 0f || home.height <= 0f ||
            !home.left.isFinite() || !home.top.isFinite() || !home.right.isFinite() || !home.bottom.isFinite() ||
            !homeRadius.isFinite() || !caster.left.isFinite() || !caster.top.isFinite() ||
            !caster.right.isFinite() || !caster.bottom.isFinite()) return
        val radius = homeRadius.coerceIn(0f, home.size.minDimension / 2f)
        val next = Key(home.size, radius, profile)
        if (key != next) {
            // At most9216samples on an opening, independent of screen density.
            val factor = min(1f, 96f / max(home.width, home.height))
            val width = max(2, ceil(home.width * factor).toInt())
            val height = max(2, ceil(home.height * factor).toInt())
            val pixels = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                val shade = glassWellShade((x + .5f) * home.width / width,
                    (y + .5f) * home.height / height, home.width, home.height, radius, profile)
                val alpha = (abs(shade) * 255f).roundToInt().coerceIn(0, 255)
                pixels[y * width + x] = (alpha shl 24) or if (shade > 0f) 0x00FFFFFF else 0
            }
            image = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
            key = next; rasterizations++
        }
        originPath.reset(); originPath.addRoundRect(RoundRect(home, CornerRadius(radius)))
        casterPath.reset(); casterPath.addRoundRect(caster)
        clipPath(originPath) {
            // A translucent caster must still cover the indentation completely;
            // merely drawing below glass would feed its shade through the material.
            clipPath(casterPath, ClipOp.Difference) {
                translate(home.left, home.top) {
                    image?.let { drawImage(it, dstSize = IntSize(home.width.roundToInt(), home.height.roundToInt()),
                        alpha = strength.coerceIn(0f, 1f), filterQuality = FilterQuality.Medium) }
                }
            }
        }
    }
}
