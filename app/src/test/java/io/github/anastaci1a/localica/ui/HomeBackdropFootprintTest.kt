package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

/** Pure coordinate contracts; these checks do not establish rendered or Android behavior. */
class HomeBackdropFootprintTest {
    private fun mapping(xx: Float = 1f, xy: Float = 0f, yx: Float = 0f, yy: Float = 1f,
        tx: Float = 0f, ty: Float = 0f) = requireNotNull(BackdropRegistration.create(xx, xy, yx, yy, tx, ty))

    private fun footprint(width: Float, height: Float,
        localToScreen: BackdropRegistration = BackdropRegistration.Identity) =
        requireNotNull(HomeBackdropFootprint.create(Size(width, height), localToScreen))

    private fun assertPoint(x: Float, y: Float, actual: Offset, tolerance: Float = .0002f) {
        assertEquals("x", x, actual.x, tolerance)
        assertEquals("y", y, actual.y, tolerance)
    }

    private fun assertCorners(expected: List<Offset>, actual: List<Offset>?) {
        val corners = requireNotNull(actual)
        assertEquals(4, corners.size)
        expected.zip(corners).forEach { (point, corner) -> assertPoint(point.x, point.y, corner) }
    }

    @Test fun identityRetainsLocalExtentAndCornerOrder() {
        val home = footprint(40f, 20f)
        assertEquals(Size(40f, 20f), home.size)
        assertSame(BackdropRegistration.Identity, home.localToScreen)
        assertCorners(listOf(Offset.Zero, Offset(40f, 0f), Offset(40f, 20f), Offset(0f, 20f)),
            home.cornersInSource(BackdropRegistration.Identity))
        assertPoint(13.25f, -8.5f,
            requireNotNull(home.sourceToLocal(BackdropRegistration.Identity)).map(Offset(13.25f, -8.5f)))
    }

    @Test fun zeroNegativeAndNonFiniteDimensionsAreUnavailable() {
        for (invalid in listOf(0f, -0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(HomeBackdropFootprint.create(Size(invalid, 20f), BackdropRegistration.Identity))
            assertNull(HomeBackdropFootprint.create(Size(40f, invalid), BackdropRegistration.Identity))
        }
        assertNull(HomeBackdropFootprint.create(Size.Unspecified, BackdropRegistration.Identity))
    }

    @Test fun missingAndDegenerateUpstreamMappingsNeverBecomeIdentity() {
        val home = footprint(40f, 20f)
        val degenerate = BackdropRegistration.create(1f, 2f, 2f, 4f, 0f, 0f)
        val nonFinite = BackdropRegistration.create(1f, 0f, 0f, 1f, Float.NaN, 0f)
        for (unavailable in listOf(null, degenerate, nonFinite)) {
            assertNull(HomeBackdropFootprint.create(Size(40f, 20f), unavailable))
            assertNull(home.sourceToLocal(unavailable))
            assertNull(home.cornersInSource(unavailable))
        }
    }

    @Test fun independentNonuniformBasesMapSourceToLocalRatherThanTheReverse() {
        val home = footprint(40f, 20f, mapping(xx = 2f, yy = .5f, tx = 100f, ty = 40f))
        val sourceToScreen = mapping(xx = .5f, yy = 2f, tx = 30f, ty = 10f)
        val sourceToLocal = requireNotNull(home.sourceToLocal(sourceToScreen))
        // Source (20, 30) -> screen (40, 70) -> home (-30, 60).
        assertPoint(-30f, 60f, sourceToLocal.map(Offset(20f, 30f)))
        assertPoint(40f, 20f, sourceToLocal.map(Offset(300f, 20f)))
        assertCorners(listOf(Offset(140f, 15f), Offset(300f, 15f), Offset(300f, 20f), Offset(140f, 20f)),
            home.cornersInSource(sourceToScreen))
    }

    @Test fun rotationAndShearRetainActualCornersRatherThanTheirBoundingBox() {
        val home = footprint(10f, 5f, mapping(xx = 0f, xy = -2f, yx = 1f, yy = .5f, tx = 20f, ty = 30f))
        val corners = requireNotNull(home.cornersInSource(BackdropRegistration.Identity))
        assertCorners(listOf(Offset(20f, 30f), Offset(20f, 40f), Offset(10f, 42.5f), Offset(10f, 32.5f)), corners)
        assertFalse("The axis-aligned top-left is not a transformed local corner", Offset(10f, 30f) in corners)
        assertFalse("The axis-aligned bottom-right is not a transformed local corner", Offset(20f, 42.5f) in corners)
        val sourceToLocal = requireNotNull(home.sourceToLocal(BackdropRegistration.Identity))
        assertPoint(10f, 0f, sourceToLocal.map(corners[1]))
        assertPoint(0f, 5f, sourceToLocal.map(corners[3]))
    }

    @Test fun reflectionPreservesLocalCornerOrderAndRemainsInvertible() {
        val home = footprint(40f, 20f, mapping(xx = -1.25f, yy = .5f, tx = 80f, ty = 6f))
        assertCorners(listOf(Offset(80f, 6f), Offset(30f, 6f), Offset(30f, 16f), Offset(80f, 16f)),
            home.cornersInSource(BackdropRegistration.Identity))
        assertPoint(40f, 20f,
            requireNotNull(home.sourceToLocal(BackdropRegistration.Identity)).map(Offset(30f, 16f)))
    }

    @Test fun commonAncestorBasisAndTranslationCancelInBothDirections() {
        val ancestor = mapping(xx = 0f, xy = -2f, yx = .5f, yy = 0f, tx = 950f, ty = 73f)
        val sourceToScreen = requireNotNull(mapping(tx = 30f, ty = 50f).followedBy(ancestor))
        val homeToScreen = requireNotNull(mapping(xx = .5f, yy = 1.25f, tx = 46f, ty = 74f).followedBy(ancestor))
        val home = footprint(100f, 40f, homeToScreen)
        assertCorners(listOf(Offset(16f, 24f), Offset(66f, 24f), Offset(66f, 74f), Offset(16f, 74f)),
            home.cornersInSource(sourceToScreen))
        assertPoint(100f, 40f, requireNotNull(home.sourceToLocal(sourceToScreen)).map(Offset(66f, 74f)))
    }

    @Test fun separateWindowOffsetsResolveThroughTheSharedScreenFrame() {
        val sourceToScreen = requireNotNull(mapping(xx = 2f, yy = .5f, tx = 20f, ty = 40f)
            .followedBy(mapping(tx = 100f, ty = 200f)))
        val homeToScreen = requireNotNull(mapping(xx = .75f, yy = 1.5f, tx = 8f, ty = 12f)
            .followedBy(mapping(tx = 300f, ty = 500f)))
        val home = footprint(20f, 20f, homeToScreen)
        assertCorners(listOf(Offset(94f, 544f), Offset(101.5f, 544f), Offset(101.5f, 604f), Offset(94f, 604f)),
            home.cornersInSource(sourceToScreen))
        assertPoint(20f, 20f, requireNotNull(home.sourceToLocal(sourceToScreen)).map(Offset(101.5f, 604f)))
    }

    @Test fun unrepresentableRelativeMappingsRemainUnavailable() {
        val home = footprint(40f, 20f, mapping(xx = 1e-19f, yy = 1e-19f))
        val sourceToScreen = mapping(xx = 1e19f, yy = 1e19f)
        assertNull(home.sourceToLocal(sourceToScreen))
        assertNull(home.cornersInSource(sourceToScreen))
    }

    @Test fun finiteInputsCannotPublishOverflowedSourceCorners() {
        val home = footprint(Float.MAX_VALUE, 20f, mapping(xx = 2f))
        assertNull(home.cornersInSource(BackdropRegistration.Identity))
    }

    @Test fun snapshotIdentityIncludesBothSizeAndTheOriginalBasis() {
        val registration = mapping(xx = .5f, xy = .25f, yy = 1.25f, tx = 24f, ty = 30f)
        val home = footprint(40f, 20f, registration)
        val equivalent = footprint(40f, 20f, mapping(xx = .5f, xy = .25f, yy = 1.25f, tx = 24f, ty = 30f))
        assertEquals(home, equivalent)
        assertEquals(home.hashCode(), equivalent.hashCode())
        assertNotEquals(home, footprint(41f, 20f, registration))
        assertNotEquals(home, footprint(40f, 20f, mapping(xx = .5f, xy = .5f, yy = 1.25f, tx = 24f, ty = 30f)))
        home.sourceToLocal(BackdropRegistration.Identity)
        home.cornersInSource(BackdropRegistration.Identity)
        assertSame(registration, home.localToScreen)
        assertEquals(equivalent, home)
    }
}
