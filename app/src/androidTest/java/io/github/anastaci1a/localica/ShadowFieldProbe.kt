package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** U12.01c: one presented native composition and the identities that make two captures comparable. [image] is retained, never recycled, for the test's lifetime. */
internal data class NativeShadowGeneration(val token: Int, val marker: IntOffset)

internal data class PresentedCapture(
    val image: ImageBitmap, val windowIds: List<String>, val hostEpoch: Long,
    val drawGeneration: Long, val clockSequence: Long,
    val nativePresentedNanos: Long?, val captureNanos: Long,
    val nativeGeneration: NativeShadowGeneration? = null, val offscreen: Boolean = false,
)

internal data class ShadowComparison(
    val maximumRgbaError: Float, val meanRgbaError: Float,
    val missingCoveragePixels: Int, val worstX: Int, val worstY: Int,
)

/**
 * Full-field RGBA oracle generalizing [ShadowFrameProbe] (whose adapters stay unchanged). Channels are the
 * capture's 8-bit sRGB values normalized to [0,1] and compared **premultiplied** (r·a, g·a, b·a, a), so a
 * transparent field, a black shadow and a white glow all register. Maximum error and missing coverage are the
 * acceptance; the mean is diagnostic only, because opposite errors cancel in it.
 *
 * Signal is a pixel's largest premultiplied difference from [compare]'s background (transparent when null).
 * A reference pixel with signal ≥ [minimumSignal] whose actual signal is below half of it is missing coverage,
 * so a blank or cropped field fails even where its alpha error is tiny. Every region must hold reference signal.
 */
internal class ShadowFieldProbe internal constructor(
    private val regions: List<Rect>, private val minimumSignal: Float,
) {
    init {
        require(regions.isNotEmpty() && regions.all { r -> listOf(r.left, r.top, r.right, r.bottom).all { it.isFinite() } && r.width > 0f && r.height > 0f }) { "Regions are finite and nonempty: $regions" }
        require(minimumSignal > 0f && minimumSignal <= 1f) { "A positive minimum signal keeps blank fields from passing" }
    }

    fun compare(actual: PresentedCapture, reference: PresentedCapture, background: PresentedCapture? = null): ShadowComparison {
        // Identity before pixel math: a stale or differently composed capture is a failing witness, never an optical pass.
        val captures = listOfNotNull(actual, reference, background)
        check(captures.all { it.image.width == reference.image.width && it.image.height == reference.image.height }) { "Capture sizes differ" }
        check(captures.all { it.image.colorSpace == reference.image.colorSpace }) { "Capture color spaces differ" }
        check(actual.windowIds.isNotEmpty() && actual.windowIds.distinct().size == actual.windowIds.size) { "Missing or duplicate native windows" }
        if (!actual.offscreen) {
            check(actual.windowIds.size == 1) { "Incomplete: every native window needs its own presented-generation correlation" }
            val native = checkNotNull(actual.nativeGeneration) { "Incomplete: native presentation correlation is missing" }
            check(native == reference.nativeGeneration) { "Incomplete: different native generations at the same Engine stamp" }
            checkNativeShadowMarker(actual)
            if (!reference.offscreen) checkNativeShadowMarker(reference)
        }
        check(actual.drawGeneration == reference.drawGeneration && actual.hostEpoch == reference.hostEpoch && actual.windowIds == reference.windowIds) {
            "Not the same native composition: actual ${actual.windowIds}/${actual.hostEpoch}/${actual.drawGeneration}, reference ${reference.windowIds}/${reference.hostEpoch}/${reference.drawGeneration}"
        }
        val a = actual.image.toPixelMap(); val e = reference.image.toPixelMap(); val g = background?.image?.toPixelMap()
        fun channels(map: androidx.compose.ui.graphics.PixelMap?, x: Int, y: Int): FloatArray {
            if (map == null) return FloatArray(4)
            val c = map[x, y]
            return floatArrayOf(c.red * c.alpha, c.green * c.alpha, c.blue * c.alpha, c.alpha)
        }
        fun difference(p: FloatArray, q: FloatArray) = maxOf(abs(p[0] - q[0]), abs(p[1] - q[1]), abs(p[2] - q[2]), abs(p[3] - q[3]))
        var maximum = 0f; var sum = 0.0; var count = 0; var missing = 0; var worstX = -1; var worstY = -1
        for (region in regions) {
            val left = floor(region.left).toInt(); val top = floor(region.top).toInt()
            val right = ceil(region.right).toInt(); val bottom = ceil(region.bottom).toInt()
            check(left >= 0 && top >= 0 && right <= reference.image.width && bottom <= reference.image.height) { "Region $region is clipped by the capture" }
            var signal = 0
            for (y in top until bottom) for (x in left until right) {
                val p = channels(a, x, y); val q = channels(e, x, y); val b = channels(g, x, y)
                val error = difference(p, q)
                if (error > maximum) { maximum = error; worstX = x; worstY = y }
                sum += error; count++
                if (difference(q, b) >= minimumSignal) { signal++; if (difference(p, b) < minimumSignal / 2f) missing++ }
            }
            check(signal > 0) { "Region $region holds no reference shadow: an all-zero field cannot pass" }
        }
        return ShadowComparison(maximum, (sum / count).toFloat(), missing, worstX, worstY)
    }

    fun assertWithin(result: ShadowComparison, limit: Float, context: String) {
        require(limit.isFinite() && limit >= 0f)
        assertTrue("$context: shadow field error ${result.maximumRgbaError} > $limit at (${result.worstX},${result.worstY}), mean ${result.meanRgbaError}",
            result.maximumRgbaError <= limit)
        assertTrue("$context: ${result.missingCoveragePixels} reference shadow pixels missing", result.missingCoveragePixels == 0)
    }
}
