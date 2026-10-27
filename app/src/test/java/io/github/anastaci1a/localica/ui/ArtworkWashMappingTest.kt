package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ArtworkWashMappingTest {
    private fun <T> known(result: ArtworkWashMappingResult<T>): T = when (result) {
        is ArtworkWashMappingResult.Known -> result.value
        is ArtworkWashMappingResult.Unavailable -> throw AssertionError("Expected known mapping, got ${result.reason}")
    }
    private fun mapping(rect: Rect) = known(ArtworkWashMapping.create(rect))
    private fun assertPoint(x: Float, y: Float, actual: Offset) {
        assertEquals(x, actual.x, .0001f); assertEquals(y, actual.y, .0001f)
    }

    @Test fun coverTopAndBottomUseExistingWashNormalizedPointOneAndPointFive() {
        val map = mapping(Rect(0f, 0f, 200f, 100f))
        assertPoint(0f, -.25f * 100f, known(map.forward(Offset.Zero)))
        val top = known(map.sample(Offset(100f, 0f)))
        val bottom = known(map.sample(Offset(100f, 100f)))
        assertPoint(.5f, .1f, top.normalized)
        assertPoint(.5f, .5f, bottom.normalized)
        assertFalse(top.wasClamped); assertFalse(bottom.wasClamped)
        assertPoint(128f, 64f, top.texture)
        assertPoint(128f, 320f, bottom.texture)
    }

    @Test fun translatedNonSquareBasisMatchesExistingForwardMatrixAndInverseWithoutMutatingInputs() {
        val bounds = Rect(-30f, 70f, 270f, 190f)
        val map = mapping(bounds)
        val texture = Offset(192f, 448f)
        // Independent existing matrix: width/256, height/256, (left, top-height/4).
        val expected = Offset(-30f + 192f * 300f / 256f, 70f - 30f + 448f * 120f / 256f)
        assertPoint(expected.x, expected.y, known(map.forward(texture)))
        assertPoint(texture.x, texture.y, known(map.inverse(expected)))
        assertEquals(Rect(-30f, 70f, 270f, 190f), bounds)
        assertEquals(Offset(192f, 448f), texture)
        assertEquals(bounds, map.coverBounds)
    }

    @Test fun shortAndTallControlPositionsMapTheirOwnCoordinatesRatherThanFixedBottomHalf() {
        val map = mapping(Rect(50f, 0f, 250f, 100f))
        val short = known(map.sample(Offset(150f, 80f)))
        val tall = known(map.sample(Offset(150f, 180f)))
        assertPoint(.5f, .42f, short.normalized)
        assertPoint(.5f, .82f, tall.normalized)
        assertFalse(short.wasClamped); assertFalse(tall.wasClamped)
        // This only distinguishes coordinate roles; it does not sample direct-cover
        // pixels or infer colors for a described white cover/black lower border.
        assertTrue(short.texture.y < 320f)
        assertTrue(tall.texture.y > 320f)
    }

    @Test fun clampPreservesRawOutsideCoordinatesAndUsesTheWholeTextureExtent() {
        val map = mapping(Rect(10f, 20f, 266f, 276f))
        val outside = known(map.forward(Offset(-128f, 960f)))
        val sample = known(map.sample(outside))
        assertPoint(-128f, 960f, sample.texture)
        assertPoint(-.5f, 1.5f, sample.normalized)
        assertPoint(0f, 1f, sample.clampedNormalized)
        assertTrue(sample.wasClamped)
        val other = known(map.sample(known(map.forward(Offset(384f, -320f)))))
        assertPoint(1f, 0f, other.clampedNormalized)
        val edge = known(map.sample(known(map.forward(Offset(256f, 640f)))))
        assertPoint(1f, 1f, edge.normalized)
        assertFalse(edge.wasClamped)
    }

    @Test fun missingAndInvalidGeometryAreUnavailableNotGuessed() {
        assertEquals(ArtworkWashMappingResult.Unavailable(ArtworkWashMappingResult.Reason.MissingGeometry),
            ArtworkWashMapping.create(null))
        for (bounds in listOf(Rect.Zero, Rect(2f, 0f, 1f, 1f), Rect(0f, 2f, 1f, 1f),
            Rect(Float.NaN, 0f, 1f, 1f), Rect(0f, 0f, Float.POSITIVE_INFINITY, 1f),
            Rect(-Float.MAX_VALUE, 0f, Float.MAX_VALUE, 1f))) {
            assertEquals("Invalid bounds $bounds", ArtworkWashMappingResult.Unavailable(
                ArtworkWashMappingResult.Reason.InvalidGeometry), ArtworkWashMapping.create(bounds))
        }
    }

    @Test fun underflowingScaleAndOverflowingTranslationDoNotBecomeKnownMatrices() {
        assertEquals(ArtworkWashMappingResult.Unavailable(ArtworkWashMappingResult.Reason.UnrepresentableBasis),
            ArtworkWashMapping.create(Rect(0f, 0f, Float.MIN_VALUE, 1f)))
        assertEquals(ArtworkWashMappingResult.Unavailable(ArtworkWashMappingResult.Reason.UnrepresentableBasis),
            ArtworkWashMapping.create(Rect(0f, -3e38f, 1f, -1e38f)))
        assertEquals(ArtworkWashMappingResult.Unavailable(ArtworkWashMappingResult.Reason.UnrepresentableBasis),
            ArtworkWashMapping.create(Rect(0f, 1e38f, 1f, 3e38f)))
    }

    @Test fun invalidPointsAndOverflowingForwardOrInverseFailExplicitly() {
        val large = mapping(Rect(0f, 0f, 1024f, 1024f))
        val small = mapping(Rect(0f, 0f, 64f, 64f))
        for (point in listOf(Offset(Float.NaN, 1f), Offset(1f, Float.NEGATIVE_INFINITY))) {
            val expected = ArtworkWashMappingResult.Unavailable(ArtworkWashMappingResult.Reason.NonFinitePoint)
            assertEquals(expected, large.forward(point)); assertEquals(expected, large.inverse(point))
            assertEquals(expected, large.sample(point))
        }
        val overflow = ArtworkWashMappingResult.Unavailable(ArtworkWashMappingResult.Reason.Overflow)
        assertEquals(overflow, large.forward(Offset(Float.MAX_VALUE, 0f)))
        assertEquals(overflow, small.inverse(Offset(Float.MAX_VALUE, 0f)))
        assertEquals(overflow, small.sample(Offset(Float.MAX_VALUE, 0f)))
    }

    @Test fun distinctGeometryInstancesStayIndependentAcrossResizeAndReversal() {
        val old = mapping(Rect(10f, 20f, 210f, 120f))
        val resized = mapping(Rect(40f, 80f, 440f, 280f))
        val point = Offset(128f, 320f)
        assertPoint(110f, 120f, known(old.forward(point)))
        assertPoint(240f, 280f, known(resized.forward(point)))
        assertPoint(110f, 120f, known(old.forward(point)))
        assertPoint(point.x, point.y, known(resized.inverse(known(resized.forward(point)))))
    }
}
