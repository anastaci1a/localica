package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class PlayerArtworkCornerTest {
    private data class Geometry(val width: Float = 412f, val height: Float = 960f,
        val aspect: Float = 1f, val lyrics: Float = 0f, val background: Float = 0f) {
        private val virtualHeight = maxOf(height, 608f)
        private val art = minOf(width - 48f, maxOf(virtualHeight - 488f, 96f))
        private val floatingWidth = minOf(art, art * aspect)
        private val floatingHeight = minOf(art, art / aspect)
        val fullWidth = floatingWidth + (width - floatingWidth) * background
        val fullHeight = floatingHeight + (width / aspect - floatingHeight) * background
        private val ordinary = maxOf(minOf((76f + art + 8f + virtualHeight - 304f) / 2f,
            virtualHeight - 392f), 184f)
        fun sample(p: Float): PlayerArtworkTrajectorySample {
            val titleTop = 7f + (ordinary - 7f) * p + (maxOf(ordinary, virtualHeight - 304f) - ordinary) * lyrics * p * p
            val miniTextWidth = maxOf(width - 252f, 40f)
            return PlayerArtworkTrajectorySample(p, 16f + ((width - fullWidth) / 2f - 16f) * p * p,
                9.6f + (76f + (art - fullHeight) / 2f - 9.6f) * p - 48f * p * (1f - p) - 24f * lyrics * p,
                titleTop, titleTop + 28f * (13f / 24f + (1f - 13f / 24f) * p),
                58f + miniTextWidth + (-26f + width - 64f - miniTextWidth) * p, 58f - 26f * p)
        }
        fun curve(points: List<PlayerArtworkTrajectorySample> = (0..256).map { sample(it / 256f) }) =
            playerArtworkGrowthCurve(32f, fullWidth, fullHeight, points)
        fun horizontal(p: Float, curve: PlayerArtworkGrowthCurve): Float = sample(p).let {
            it.artworkLeft + 32f + (fullWidth - 32f) * playerArtworkSizeProgress(p, curve) - it.titleLeft }
        fun vertical(p: Float, curve: PlayerArtworkGrowthCurve): Float = sample(p).let {
            it.artworkTop + 32f + (fullHeight - 32f) * playerArtworkSizeProgress(p, curve) - it.titleTop }
    }

    private fun assertContactAndExclusion(geometry: Geometry, curve: PlayerArtworkGrowthCurve) {
        assertTrue("Must exercise corner admission: $geometry", curve.cornerAligned)
        assertFalse(curve.constrained)
        var lo = 0f; var hi = 1f
        assertTrue(geometry.vertical(lo, curve) > 0f)
        assertTrue(geometry.vertical(hi, curve) <= 0f)
        repeat(30) { val mid = (lo + hi) / 2f; if (geometry.vertical(mid, curve) > 0f) lo = mid else hi = mid }
        val contact = (lo + hi) / 2f
        assertEquals("Vertical contact: $geometry", 0f, geometry.vertical(contact, curve), .01f)
        assertEquals("Both corners meet at the same progress: $geometry", 0f, geometry.horizontal(contact, curve), .02f)
        var previous = 0f
        val forward = FloatArray(10001)
        for (index in 0..10000) {
            val p = index / 10000f
            val sample = geometry.sample(p)
            val q = playerArtworkSizeProgress(p, curve)
            assertTrue("Monotone growth: $geometry p=$p", q >= previous)
            if (index > 0) assertTrue("Immediate growth: $geometry p=$p", q > 0f)
            assertTrue("No title overlap: $geometry p=$p",
                geometry.vertical(p, curve) <= .03f || geometry.horizontal(p, curve) <= .03f || sample.artworkTop >= sample.titleBottom)
            forward[index] = q
            previous = q
        }
        for (index in 10000 downTo 0) {
            assertEquals("Descending samples retrace the forward growth without clock/state", forward[index],
                playerArtworkSizeProgress(index / 10000f, curve), 0f)
        }
        assertEquals(0f, playerArtworkSizeProgress(0f, curve), 0f)
        assertEquals(1f, playerArtworkSizeProgress(1f, curve), 0f)
    }

    @Test fun measuredCornerContactAcceleratesTallScreenGrowthWithoutTitleOverlap() {
        for (width in listOf(320f, 360f, 412f, 480f, 640f)) for (height in listOf(800f, 960f)) {
            val geometry = Geometry(width, height)
            val curve = geometry.curve()
            assertNull("Keep the earlier rational growth for tall layouts", curve.power)
            assertTrue(playerArtworkSizeProgress(.1f, curve) > playerArtworkSizeProgress(.1f, 0f))
            assertContactAndExclusion(geometry, curve)
        }
    }

    @Test fun compactSquareUsesBoundedPowerInsteadOfOverlappingQuadraticFallback() {
        for (width in listOf(360f, 412f)) {
            val geometry = Geometry(width, 600f)
            val curve = geometry.curve()
            assertTrue(requireNotNull(curve.power) in 2f..4f)
            assertContactAndExclusion(geometry, curve)
            // The old fallback really violates this fixture; the regression is not vacuous.
            val old = PlayerArtworkGrowthCurve(0f, constrained = true)
            assertTrue(geometry.horizontal(.2f, old) > 0f && geometry.vertical(.2f, old) > 0f)
            assertTrue("Noticeable growth by halfway", (geometry.fullWidth - 32f) * playerArtworkSizeProgress(.5f, curve) > 6f)
            assertTrue("Bounded endpoint slope", (1f - playerArtworkSizeProgress(.9999f, curve)) / .0001f <= 4.01f)
        }
    }

    @Test fun aspectLyricsAndIntermediateBackgroundMatrixCannotSilentlyDowngradeToRightEdge() {
        var admitted = 0; var compact = 0; var unsupported = 0
        for (width in listOf(320f, 360f, 412f, 480f, 640f)) for (height in listOf(360f, 600f, 800f, 960f))
            for (aspect in listOf(1f / 3f, 1f, 3f)) for (lyrics in listOf(0f, .5f, 1f))
                for (background in listOf(0f, .5f, 1f)) {
                    val geometry = Geometry(width, height, aspect, lyrics, background)
                    val curve = geometry.curve()
                    if (curve.cornerAligned) {
                        admitted++
                        if (curve.power != null) compact++
                        assertContactAndExclusion(geometry, curve)
                    } else {
                        unsupported++
                        assertTrue("Measured callers cannot claim the old right-edge admission", curve.constrained)
                        val q = playerArtworkSizeProgress(.5f, curve)
                        assertTrue("Constrained geometry retains a bounded best effort", q in .0625f..0.5f)
                    }
                }
        assertTrue("Matrix must exercise corner admission", admitted > 100)
        assertTrue("Matrix must exercise compact power curves", compact > 0)
        assertTrue("Matrix must exercise genuine unsupported geometry", unsupported > 0)
    }

    @Test fun compactExtensionDoesNotJumpWhenAnimatedGeometryCrossesItsBoundary() {
        var low = 320f; var high = 360f
        repeat(20) {
            val width = (low + high) / 2f
            if (Geometry(width, 600f).curve().power == null) low = width else high = width
        }
        val rational = Geometry(low - .01f, 600f).curve()
        val compact = Geometry(high + .01f, 600f).curve()
        assertTrue(rational.cornerAligned && compact.cornerAligned)
        assertNull(rational.power)
        assertNotNull(compact.power)
        for (index in 0..1000) {
            val p = index / 1000f
            assertEquals("Upstream layout animation must not cause a family-switch jump at p=$p",
                playerArtworkSizeProgress(p, rational), playerArtworkSizeProgress(p, compact), .001f)
        }
    }

    @Test fun losingCornerAdmissionKeepsBestEffortInsteadOfJumpingBackToQuadratic() {
        val before = Geometry(412f, 600f, background = .136f)
        val after = Geometry(412f, 600f, background = .137f)
        val a = before.curve(); val b = after.curve()
        assertTrue("Exercise actual admission loss", a.cornerAligned && b.constrained)
        assertFalse(b.cornerAligned)
        assertTrue("Unsupported geometry must retain its fitted ease", playerArtworkSizeProgress(.5f, b) < .15f)
        for (index in 0..1000) {
            val p = index / 1000f
            val widthBefore = (before.fullWidth - 32f) * playerArtworkSizeProgress(p, a)
            val widthAfter = (after.fullWidth - 32f) * playerArtworkSizeProgress(p, b)
            assertEquals("Only the small upstream geometry change may affect size", widthBefore, widthAfter, .5f)
        }
        for (base in listOf(Geometry(412f, 600f), Geometry(412f, 960f), Geometry(640f, 600f))) {
            var previous: PlayerArtworkGrowthCurve? = null
            var admissionChanges = 0
            for (index in 0..1000) {
                val curve = base.copy(background = index / 1000f).curve()
                previous?.let { old ->
                    if (old.cornerAligned != curve.cornerAligned) admissionChanges++
                    for (step in 0..100) {
                        val p = step / 100f
                        assertEquals("Geometry retarget must not jump at $base background=$index p=$p",
                            playerArtworkSizeProgress(p, old), playerArtworkSizeProgress(p, curve), .01f)
                    }
                }
                previous = curve
            }
            if (base.height == 600f && base.width == 412f) assertTrue(admissionChanges > 0)
        }
    }

    @Test fun animatedExtremeAspectsCrossMiniDimensionsWithoutSwitchingToQuadratic() {
        for (boundary in listOf(32f / 120f, 120f / 32f)) {
            val before = Geometry(320f, 600f, aspect = boundary - .0001f)
            val exact = Geometry(320f, 600f, aspect = boundary)
            val after = Geometry(320f, 600f, aspect = boundary + .0001f)
            assertTrue("Fixture crosses an actual 32 dp dimension", if (boundary < 1f)
                before.fullWidth < 32f && after.fullWidth > 32f else before.fullHeight > 32f && after.fullHeight < 32f)
            val a = before.curve(); val b = exact.curve(); val c = after.curve()
            for (index in 0..1000) {
                val p = index / 1000f
                val qa = playerArtworkSizeProgress(p, a); val qb = playerArtworkSizeProgress(p, b); val qc = playerArtworkSizeProgress(p, c)
                assertTrue(qa.isFinite() && qb.isFinite() && qc.isFinite())
                assertEquals("No singularity at exactly mini size", qa, qb, .002f)
                assertEquals("Shrinking through mini size retains the bounded curve", qb, qc, .002f)
                assertEquals("No rendered width jump", 32f + (before.fullWidth - 32f) * qa,
                    32f + (after.fullWidth - 32f) * qc, .03f)
                assertEquals("No rendered height jump", 32f + (before.fullHeight - 32f) * qa,
                    32f + (after.fullHeight - 32f) * qc, .03f)
            }
        }
        for (aspect in listOf(.01f, .1f, .2f, .2666f, 3.7501f, 4f, 10f, 100f)) {
            val geometry = Geometry(320f, 600f, aspect)
            val curve = geometry.curve()
            assertEquals(0f, playerArtworkSizeProgress(0f, curve), 0f)
            assertEquals(1f, playerArtworkSizeProgress(1f, curve), 0f)
            if (curve.cornerAligned) assertContactAndExclusion(geometry, curve)
            else assertTrue(curve.constrained)
        }
    }

    @Test fun geometryThatCannotClearAtMiniSizeStaysExplicitlyUnsupported() {
        val geometry = Geometry(480f, 600f)
        val point = geometry.sample(.19f)
        assertTrue("Even a zero-growth cover intersects", point.artworkLeft + 32f > point.titleLeft && point.artworkTop + 32f > point.titleTop)
        val curve = geometry.curve()
        assertFalse(curve.cornerAligned)
        assertTrue(curve.constrained)
        assertEquals("Infeasible compact contact clamps continuously at the slowest bounded curve",
            .0625f, playerArtworkSizeProgress(.5f, curve), .00001f)
    }

    @Test fun negativeCurvatureIsImmediateMonotoneEaseInWithExactEndpoints() {
        for (bend in listOf(-.95f, -.8f, -.5f, 0f, 2f)) {
            assertEquals(0f, playerArtworkSizeProgress(0f, bend), 0f)
            assertEquals(1f, playerArtworkSizeProgress(1f, bend), 0f)
            var previous = 0f
            for (index in 1..10000) {
                val p = index / 10000f
                val q = playerArtworkSizeProgress(p, bend)
                assertTrue(q > previous)
                if (p < 1f) assertTrue(q < p)
                previous = q
            }
            assertTrue(playerArtworkSizeProgress(.00001f, bend) / .00001f < .001f)
        }
    }

    @Test fun malformedMeasuredGeometryNeverClaimsAdmissionOrBorrowsLegacyBounds() {
        val geometry = Geometry()
        val points = (0..256).map { geometry.sample(it / 256f) }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, points[128].titleRight + 1f)) {
            val damaged = points.toMutableList().apply { this[128] = this[128].copy(titleLeft = bad) }
            val curve = geometry.curve(damaged)
            assertTrue(curve.constrained)
            assertFalse(curve.cornerAligned)
        }
        for (incomplete in listOf(points.drop(1), points.dropLast(1))) assertTrue(geometry.curve(incomplete).constrained)
        val reversed = geometry.curve(points.asReversed())
        assertEquals(geometry.curve(), reversed)
    }

    @Test fun backgroundArtworkDoesNotPretendToClearTitle() {
        val geometry = Geometry(aspect = 1f / 3f, background = 1f)
        val curve = geometry.curve()
        assertFalse(curve.cornerAligned)
        assertTrue(curve.constrained)
        assertEquals(0f, playerArtworkSizeProgress(0f, curve), 0f)
        assertEquals(1f, playerArtworkSizeProgress(1f, curve), 0f)
    }
}
