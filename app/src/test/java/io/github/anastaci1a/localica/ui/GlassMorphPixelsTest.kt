package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class GlassMorphPixelsTest {
    private fun sweep(from: Rect, to: Rect, quiet: Boolean) = (0..4000).map { sample ->
        val p = glassMorphGeometryProgress(sample / 4000f, quiet)
        fun mix(a: Float, b: Float) = a + (b - a) * p
        glassMorphPixelBounds(Rect(mix(from.left, to.left), mix(from.top, to.top),
            mix(from.right, to.right), mix(from.bottom, to.bottom)), 1440, 3120)
    }

    private fun monotone(values: List<Int>) {
        val rising = values.last() >= values.first()
        values.zipWithNext().forEach { (a, b) -> assertTrue("Edge reverses: $a -> $b",
            if (rising) b >= a else b <= a) }
    }

    @Test fun popupEdgesAreMonotoneAndEndpointsKeepTheirRestingPixels() {
        val cases = listOf(
            Rect(573f, 1291f, 660f, 1351f) to Rect(12f, 952f, 660f, 1351f),
            Rect(31f, 73f, 118f, 133f) to Rect(31f, 73f, 679f, 472f),
            Rect(573f, 1291f, 660f, 1351f) to Rect(101f, 501f, 679f, 901f),
            Rect(120f, 900f, 660f, 960f) to Rect(12f, 180f, 708f, 900f))
        for ((from, to) in cases) for (quiet in listOf(false, true)) {
            val frames = sweep(from, to, quiet)
            monotone(frames.map { it.left }); monotone(frames.map { it.right })
            monotone(frames.map { it.top }); monotone(frames.map { it.bottom })
            assertEquals(motionPixelRect(from), frames.first())
            assertEquals(motionPixelRect(to), frames.last())
            // Relative placement mirrors these same edges; RTL cannot introduce a second rounding.
            monotone(frames.map { 1440 - it.right }); monotone(frames.map { 1440 - it.left })
            for (endpoint in listOf(frames.first(), frames.last())) {
                assertEquals(0f, endpoint.translationX, 0f); assertEquals(0f, endpoint.translationY, 0f)
                assertEquals(1f, endpoint.scaleX, 0f); assertEquals(1f, endpoint.scaleY, 0f)
            }
        }
    }

    @Test fun anchoredFarEdgesStayFixedThroughHalfPixelPositionAndSize() {
        val frames = sweep(Rect(573f, 1291f, 660f, 1351f), Rect(12f, 952f, 660f, 1351f), false)
        frames.forEach { assertEquals(660, it.right); assertEquals(1351, it.bottom) }
        val middle = frames[2000]
        assertEquals(293, middle.left)
        assertEquals(367, middle.width)
    }

    @Test fun viewportLimitsConstrainExtentsWithoutRoundingTheOriginSeparately() {
        val pixels = glassMorphPixelBounds(Rect(12.5f, 24.5f, 1800f, 4000f), 720, 1280)
        assertEquals(13, pixels.left); assertEquals(733, pixels.right)
        assertEquals(25, pixels.top); assertEquals(1305, pixels.bottom)
        val minimum = glassMorphPixelBounds(Rect(12.5f, 24.5f, 12.6f, 24.6f), 720, 1280)
        assertEquals(1, minimum.width); assertEquals(1, minimum.height)
    }
}
