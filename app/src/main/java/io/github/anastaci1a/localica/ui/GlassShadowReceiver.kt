package io.github.anastaci1a.localica.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import kotlin.math.*

/** Immutable field snapshot in the shadow renderer's untransformed coordinates.
 * The mask is never edited after publication. Mutable shader/matrix state belongs
 * to each renderer, never this snapshot shared with background raster preparation.
 */
internal data class GlassShadowReceiver(val bounds: Rect, val mask: ImageBitmap, val sampleBounds: Rect = bounds)

/** Inverse of GlassMorph's translate(shift), scale(sx,sy,center) draw transform. */
internal fun glassReceiverInCasterSpace(home: Rect, size: Size, sx: Float, sy: Float, shift: Float): Rect? {
    if (!home.left.isFinite() || !home.top.isFinite() || !home.right.isFinite() || !home.bottom.isFinite() ||
        !size.width.isFinite() || !size.height.isFinite() || !sx.isFinite() || !sy.isFinite() || !shift.isFinite() ||
        home.width <= 0f || home.height <= 0f || sx <= .001f || sy <= .001f) return null
    val cx = size.width / 2f; val cy = size.height / 2f
    fun x(value: Float) = cx + (value - cx - shift) / sx
    fun y(value: Float) = cy + (value - cy) / sy
    val result = Rect(x(home.left),y(home.top),x(home.right),y(home.bottom))
    if (!result.left.isFinite() || !result.top.isFinite() || !result.right.isFinite() || !result.bottom.isFinite() ||
        !result.width.isFinite() || !result.height.isFinite() || result.width <= 0f || result.height <= 0f) return null
    return result
}

/** Relative receiver response: normal shadow at/outside rim, weaker at the floor.
 * Multiplying each existing ring is monotone but NOT a linear multiplier of the
 * final composited shadow alpha: A = 1 - product(1 - ringAlpha * weight).
 */
internal fun glassWellShadowWeight(x: Float, y: Float, size: Size, radius: Float,
    wallFraction: Float, floorWeight: Float): Float {
    val half = size.minDimension / 2f
    if (half <= 0f || radius <= 0f) return 1f
    val r = radius.coerceAtMost(half)
    val qx = abs(x-size.width/2f) - (size.width/2f-r)
    val qy = abs(y-size.height/2f) - (size.height/2f-r)
    val inward = r - hypot(max(qx,0f),max(qy,0f)) - min(max(qx,qy),0f)
    if (inward <= 0f) return 1f
    val u = (inward / (r * wallFraction)).coerceIn(0f,1f)
    return 1f - (1f-floorWeight) * glassWellDepth(u)
}

/** Stationary <=96x96 weight texture. Caster motion only changes its local mapping. */
internal class GlassShadowReceiverField {
    private data class Key(val size: Size, val radius: Float, val wall: Float, val floor: Float)
    private var key: Key? = null
    private var mask: ImageBitmap? = null
    internal var rasterizations = 0; private set
    fun receiver(home: Rect, radius: Float, casterSize: Size, sx: Float, sy: Float, shift: Float,
        profile: GlassOriginWellProfile = DefaultGlassOriginWellProfile, floorWeight: Float = .45f,
        physicalSize: Size = home.size): GlassShadowReceiver? {
        require(floorWeight.isFinite() && floorWeight in 0f..1f)
        // Use the original untransformed source size, not subtraction of moving
        // translated edges, which can jitter in low float bits and rerasterize.
        if (!physicalSize.width.isFinite() || !physicalSize.height.isFinite() || physicalSize.minDimension <= 0f) return null
        val stableHome = Rect(home.topLeft,physicalSize)
        val local = glassReceiverInCasterSpace(stableHome,casterSize,sx,sy,shift) ?: return null
        if (!radius.isFinite() || radius <= 0f || floorWeight == 1f) return null
        val r = radius.coerceAtMost(physicalSize.minDimension/2f)
        val next = Key(physicalSize,r,profile.wallFraction,floorWeight)
        if (next != key) {
            // Endpoint samples sit on the physical home edge; two guard samples
            // lie OUTSIDE it. Pixel-center mapping below keeps the entire exterior
            // exactly opaque under CLAMP without flattening a narrow button rim.
            val factor = min(1f,91f/max(physicalSize.width,physicalSize.height))
            val columns = ceil(physicalSize.width*factor).toInt().coerceIn(16,91)
            val rows = ceil(physicalSize.height*factor).toInt().coerceIn(16,91)
            val w = columns+5; val h = rows+5 // <=96x96 including guards/endpoints
            val pixels = IntArray(w*h)
            for (y in 0 until h) for (x in 0 until w) {
                val weight = glassWellShadowWeight(((x-2f)/columns)*physicalSize.width,
                    ((y-2f)/rows)*physicalSize.height,physicalSize,r,profile.wallFraction,floorWeight)
                pixels[y*w+x] = (weight.coerceIn(0f,1f)*255f).roundToInt() shl 24
            }
            mask = Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888).asImageBitmap()
            key = next; rasterizations++
        }
        val image = requireNotNull(mask)
        val dx = physicalSize.width/(image.width-5)
        val dy = physicalSize.height/(image.height-5)
        // Pixel center(2.5,2.5) is the home top-left; last physical edge has an
        // equally opaque sample, then two outside guard samples.
        val sample = Rect(stableHome.left-2.5f*dx,stableHome.top-2.5f*dy,
            stableHome.right+2.5f*dx,stableHome.bottom+2.5f*dy)
        val localSample = glassReceiverInCasterSpace(sample,casterSize,sx,sy,shift) ?: return null
        return GlassShadowReceiver(local,image,localSample)
    }
}

/** One Main/worker-local sampler; never share mutable shader matrices across threads. */
internal class GlassShadowReceiverBrush {
    private var image: ImageBitmap? = null
    private var shader: android.graphics.BitmapShader? = null
    private var brush: ShaderBrush? = null
    private val matrix = android.graphics.Matrix()
    private var bounds: Rect? = null
    fun get(receiver: GlassShadowReceiver): ShaderBrush {
        if (image !== receiver.mask) {
            image = receiver.mask
            shader = android.graphics.BitmapShader(receiver.mask.asAndroidBitmap(),
                android.graphics.Shader.TileMode.CLAMP,android.graphics.Shader.TileMode.CLAMP)
            brush = ShaderBrush(requireNotNull(shader)); bounds = null
        }
        if (bounds != receiver.sampleBounds) {
            val r = receiver.sampleBounds
            matrix.setScale(r.width/receiver.mask.width,r.height/receiver.mask.height)
            matrix.postTranslate(r.left,r.top)
            requireNotNull(shader).setLocalMatrix(matrix)
            bounds = r
        }
        return requireNotNull(brush)
    }
}

/** Conservative fast rejection; false positives cost a sampler, never change output. */
internal fun glassReceiverTouchesShadow(receiver: GlassShadowReceiver, bodies: List<RoundRect>, reach: Float, shift: Float): Boolean {
    val home = receiver.bounds
    return bodies.any { body ->
        val outer = Rect(body.left-reach,body.top-reach+shift,body.right+reach,body.bottom+reach+shift)
        val inner = Rect(body.left+max(body.topLeftCornerRadius.x,body.bottomLeftCornerRadius.x),
            body.top+max(body.topLeftCornerRadius.y,body.topRightCornerRadius.y),
            body.right-max(body.topRightCornerRadius.x,body.bottomRightCornerRadius.x),
            body.bottom-max(body.bottomLeftCornerRadius.y,body.bottomRightCornerRadius.y))
        outer.overlaps(home) && !(home.left >= inner.left && home.top >= inner.top &&
            home.right <= inner.right && home.bottom <= inner.bottom)
    }
}
