package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Samples the same screen-space shadow region on every presented frame. */
internal class ShadowFrameProbe(private val region: Rect = Rect.Zero) {
    private var previous: Float? = null
    var maximumDelta = 0f; private set
    val luminances = mutableListOf<Float>()

    /** The legacy region is scene-local; the original display image stays in screen space. */
    fun sample(capture: PresentedCapture): Float {
        if (!capture.offscreen) checkNativeShadowMarker(capture)
        val bounds = checkNotNull(capture.sceneBounds) { "Incomplete: no native scene bounds" }
        return observeLuminance(ShadowFrameProbe(region.translate(bounds.topLeft)).sample(capture.image))
    }

    fun sample(image: ImageBitmap): Float {
        val pixels = image.toPixelMap()
        val left = floor(region.left).toInt().coerceAtLeast(0)
        val top = floor(region.top).toInt().coerceAtLeast(0)
        val right = ceil(region.right).toInt().coerceAtMost(image.width)
        val bottom = ceil(region.bottom).toInt().coerceAtMost(image.height)
        require(right > left && bottom > top) { "Empty shadow probe: $region" }
        var sum = 0f
        for (y in top until bottom) for (x in left until right) {
            val c = pixels[x, y]
            sum += .2126f * c.red + .7152f * c.green + .0722f * c.blue
        }
        val mean = sum / ((right - left) * (bottom - top))
        return observeLuminance(mean)
    }

    /** For synchronized native-window probes that already subtract their background. */
    fun observeLuminance(mean: Float): Float {
        require(mean.isFinite())
        previous?.let { maximumDelta = maxOf(maximumDelta, abs(mean - it)) }
        previous = mean
        luminances += mean
        return mean
    }

    fun beginSegment() { previous = null }

    fun assertContinuous(limit: Float, context: String) {
        assertTrue("$context shadow jump=$maximumDelta limit=$limit frames=$luminances", maximumDelta <= limit)
    }

    companion object {
        /** Adjacent bands reveal a hard crop; a complete spread approaches the background smoothly. */
        fun assertSpreadEdge(image: ImageBitmap, inside: Rect, outside: Rect, limit: Float, context: String) {
            val inner = ShadowFrameProbe(inside).sample(image)
            val outer = ShadowFrameProbe(outside).sample(image)
            assertTrue("$context hard spread edge: inside=$inner outside=$outer", abs(inner - outer) <= limit)
        }
    }
}
