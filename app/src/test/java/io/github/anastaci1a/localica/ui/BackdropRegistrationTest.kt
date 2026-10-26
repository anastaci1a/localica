package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

/** Coordinate contracts only: these tests do not establish the cause of a rendered stripe. */
class BackdropRegistrationTest {
    private fun mapping(xx: Float = 1f, xy: Float = 0f, yx: Float = 0f, yy: Float = 1f,
        tx: Float = 0f, ty: Float = 0f) = requireNotNull(BackdropRegistration.create(xx, xy, yx, yy, tx, ty))

    private fun assertPoint(x: Float, y: Float, actual: Offset, tolerance: Float = .0002f) {
        assertEquals("x", x, actual.x, tolerance)
        assertEquals("y", y, actual.y, tolerance)
    }

    @Test fun identityAndTranslationRetainLocalPixels() {
        assertPoint(13.25f, -8.5f, BackdropRegistration.Identity.map(Offset(13.25f, -8.5f)))
        val translated = mapping(tx = 73.5f, ty = -22.25f)
        assertPoint(86.75f, -30.75f, translated.map(Offset(13.25f, -8.5f)))
        assertPoint(13.25f, -8.5f, requireNotNull(translated.inverse()).map(Offset(86.75f, -30.75f)))
    }

    @Test fun forwardAndInterruptedReverseFitsUseScaledDistancesNotOnlyOrigins() {
        val sourceToRoot = mapping(tx = 415f, ty = 127f)
        val samples = listOf(.25f, .5f, .75f, 1f, .7f, .35f, .2f)
        for (fit in samples) {
            // The menu's fixed foreground is centered and fitted inside its current viewport.
            val pivot = Offset(200f, 300f)
            val buttonPosition = Offset(16f, 64f)
            val consumerToRoot = mapping(xx = fit, yy = fit,
                tx = 415f + pivot.x * (1f - fit) + buttonPosition.x * fit,
                ty = 127f + pivot.y * (1f - fit) + buttonPosition.y * fit)
            val relative = requireNotNull(consumerToRoot.followedBy(requireNotNull(sourceToRoot.inverse())))
            assertPoint(200f * (1f - fit) + 16f * fit,
                300f * (1f - fit) + 64f * fit, relative.map(Offset.Zero))
            assertPoint(200f * (1f - fit) + 336f * fit,
                300f * (1f - fit) + 112f * fit, relative.map(Offset(320f, 48f)))
            assertEquals(320f * fit,
                relative.map(Offset(320f, 0f)).x - relative.map(Offset.Zero).x, .0002f)
        }
    }

    @Test fun nonuniformScalePreservesBothIndependentAxes() {
        val relative = mapping(xx = .4f, yy = 1.6f, tx = 23f, ty = -9f)
        assertPoint(103f, 55f, relative.map(Offset(200f, 40f)))
        assertPoint(200f, 40f, requireNotNull(relative.inverse()).map(Offset(103f, 55f)))
        val corners = relative.corners(Rect(0f, 0f, 200f, 40f))
        assertPoint(23f, -9f, corners[0])
        assertPoint(103f, -9f, corners[1])
        assertPoint(103f, 55f, corners[2])
        assertPoint(23f, 55f, corners[3])
    }

    @Test fun sharedAncestorScaleRotationAndTranslationCancel() {
        val commonAncestor = mapping(xx = 0f, xy = -2f, yx = .5f, yy = 0f, tx = 950f, ty = 73f)
        val sourceLocal = mapping(tx = 30f, ty = 50f)
        val consumerLocal = mapping(xx = .5f, yy = 1.25f, tx = 46f, ty = 74f)
        val sourceToRoot = requireNotNull(sourceLocal.followedBy(commonAncestor))
        val consumerToRoot = requireNotNull(consumerLocal.followedBy(commonAncestor))
        val relative = requireNotNull(consumerToRoot.followedBy(requireNotNull(sourceToRoot.inverse())))
        assertPoint(16f, 24f, relative.map(Offset.Zero))
        assertPoint(66f, 74f, relative.map(Offset(100f, 40f)))
    }

    @Test fun separateWindowRootOffsetsAreResolvedBeforeRelativeMapping() {
        val sourceToScreen = requireNotNull(mapping(xx = 2f, yy = .5f, tx = 20f, ty = 40f)
            .followedBy(mapping(tx = 100f, ty = 200f)))
        val consumerToScreen = requireNotNull(mapping(xx = .75f, yy = 1.5f, tx = 8f, ty = 12f)
            .followedBy(mapping(tx = 300f, ty = 500f)))
        val relative = requireNotNull(consumerToScreen.followedBy(requireNotNull(sourceToScreen.inverse())))
        // Screen (323, 542) maps to source local ((323 - 120) / 2, (542 - 240) / .5).
        assertPoint(101.5f, 604f, relative.map(Offset(20f, 20f)))
    }

    @Test fun scrollingMovesSamplingWithoutChangingItsBasis() {
        for (scroll in listOf(0f, 37.5f, 120f, 12.25f)) {
            val sourceToRoot = mapping(tx = 10f, ty = 20f - scroll)
            val fixedConsumerToRoot = mapping(xx = .75f, yy = .75f, tx = 25f, ty = 60f)
            val relative = requireNotNull(fixedConsumerToRoot.followedBy(requireNotNull(sourceToRoot.inverse())))
            assertPoint(45f, 55f + scroll, relative.map(Offset(40f, 20f)))
            assertEquals(.75f, relative.xx, 0f)
            assertEquals(.75f, relative.yy, 0f)
        }
    }

    @Test fun compositionOrderIsSourceToIntermediateThenIntermediateToDestination() {
        val first = mapping(xx = 2f, yy = 3f, tx = 5f, ty = 7f)
        val second = mapping(xx = 0f, xy = -1f, yx = 1f, yy = 0f, tx = 11f, ty = 13f)
        assertPoint(-5f, 22f, requireNotNull(first.followedBy(second)).map(Offset(2f, 3f)))
        assertPoint(21f, 52f, requireNotNull(second.followedBy(first)).map(Offset(2f, 3f)))
    }

    @Test fun rotatedAndShearedFootprintRetainsAllFourCorners() {
        val relative = mapping(xx = 0f, xy = -2f, yx = 1f, yy = .5f, tx = 20f, ty = 30f)
        val corners = relative.corners(Rect(2f, 3f, 12f, 8f))
        assertPoint(14f, 33.5f, corners[0])
        assertPoint(14f, 43.5f, corners[1])
        assertPoint(4f, 46f, corners[2])
        assertPoint(4f, 36f, corners[3])
        assertEquals(4f, corners.minOf { it.x }, 0f)
        assertEquals(14f, corners.maxOf { it.x }, 0f)
        assertEquals(33.5f, corners.minOf { it.y }, 0f)
        assertEquals(46f, corners.maxOf { it.y }, 0f)
        assertPoint(7f, 6f, requireNotNull(relative.inverse()).map(Offset(8f, 40f)))
    }

    @Test fun reflectionIsValidRatherThanConfusedWithDegeneracy() {
        val reflected = mapping(xx = -1.25f, yy = .5f, tx = 80f, ty = 6f)
        assertPoint(30f, 16f, reflected.map(Offset(40f, 20f)))
        assertPoint(40f, 20f, requireNotNull(reflected.inverse()).map(Offset(30f, 16f)))
    }

    @Test fun extentSamplingRecoversAnAffineMapIncludingRootTranslation() {
        val recovered = requireNotNull(BackdropRegistration.fromMapping(320f, 180f) { p ->
            Offset(.75f * p.x + .25f * p.y + 1024f, -.5f * p.x + 1.5f * p.y - 73f)
        })
        assertPoint(1044f, -53f, recovered.map(Offset(20f, 20f)))
        assertPoint(1144f, 147f, recovered.map(Offset(100f, 180f)))
    }

    @Test fun invalidAndUnrepresentableMatricesAreRejected() {
        assertNull(BackdropRegistration.create(0f, 0f, 0f, 1f, 0f, 0f))
        assertNull(BackdropRegistration.create(1f, 2f, 2f, 4f, 0f, 0f))
        assertNull(BackdropRegistration.create(1f, 1f, 1f, 1.0000001f, 0f, 0f))
        assertNull(BackdropRegistration.create(Float.NaN, 0f, 0f, 1f, 0f, 0f))
        assertNull(BackdropRegistration.create(1f, 0f, 0f, 1f, Float.POSITIVE_INFINITY, 0f))
        assertNull(BackdropRegistration.create(Float.MAX_VALUE, 0f, 0f, Float.MAX_VALUE, 0f, 0f))
        assertNull(BackdropRegistration.create(Float.MIN_VALUE, 0f, 0f, Float.MIN_VALUE, 0f, 0f))
        assertNull(mapping(xx = 1e19f, yy = 1e19f).followedBy(mapping(xx = 1e19f, yy = 1e19f)))
    }

    @Test fun invalidExtentsAndNonFiniteSamplesCannotBecomeIdentityFallbacks() {
        for ((width, height) in listOf(0f to 10f, 10f to 0f, -1f to 10f,
                Float.NaN to 10f, 10f to Float.POSITIVE_INFINITY)) {
            assertNull(BackdropRegistration.fromMapping(width, height) { it })
        }
        assertNull(BackdropRegistration.fromMapping(100f, 40f) { Offset(Float.NaN, it.y) })
        assertNull(BackdropRegistration.fromMapping(100f, 40f) { Offset(12f, it.y) })
    }

    @Test fun perspectiveOrCornerInconsistentMappingsAreRejected() {
        assertNull(BackdropRegistration.fromMapping(320f, 180f) { p ->
            val denominator = 1f + p.x / 640f
            Offset(p.x / denominator, p.y / denominator)
        })
        assertNull(BackdropRegistration.fromMapping(100f, 40f) { p ->
            Offset(p.x + p.x * p.y / 100f, p.y)
        })
    }

    @Test fun scaledFootprintFitsWhereAnUnscaledExtentWouldEscape() {
        val bounds = Rect(0f, 0f, 320f, 48f)
        val scaled = mapping(xx = .5f, yy = .5f, tx = 24f, ty = 30f).corners(bounds)
        assertTrue(backdropContainsFootprint(200f, 100f, 20f, scaled))
        val translationOnly = mapping(tx = 24f, ty = 30f).corners(bounds)
        assertFalse(backdropContainsFootprint(200f, 100f, 20f, translationOnly))
        assertPoint(184f, 54f, scaled[2])
    }

    @Test fun roundedRecordingRejectsRectangularCoverageInsideItsMissingCorner() {
        val outsideRound = BackdropRegistration.Identity.corners(Rect(4f, 4f, 40f, 40f))
        assertTrue(backdropContainsFootprint(200f, 100f, 0f, outsideRound))
        assertFalse(backdropContainsFootprint(200f, 100f, 24f, outsideRound))
        val insideRound = BackdropRegistration.Identity.corners(Rect(8f, 8f, 40f, 40f))
        assertTrue(backdropContainsFootprint(200f, 100f, 24f, insideRound))
        val tangent = BackdropRegistration.Identity.corners(Rect(0f, 24f, 40f, 60f))
        assertTrue(backdropContainsFootprint(200f, 100f, 24f, tangent))
    }

    @Test fun shearedAndRotatedFootprintsRequireEveryCornerInsideTheRecording() {
        val sheared = mapping(xx = 1f, xy = .5f, yx = .25f, yy = 1f, tx = 20f, ty = 20f)
            .corners(Rect(0f, 0f, 40f, 30f))
        assertTrue(backdropContainsFootprint(100f, 100f, 10f, sheared))
        val rotated = mapping(xx = 1f, xy = -1f, yx = 1f, yy = 1f, tx = 50f, ty = 10f)
            .corners(Rect(0f, 0f, 60f, 20f))
        assertPoint(50f, 10f, rotated[0])
        assertPoint(90f, 90f, rotated[2])
        assertPoint(110f, 70f, rotated[1])
        assertFalse("Opposite corners alone do not establish containment",
            backdropContainsFootprint(100f, 100f, 10f, rotated))
    }

    @Test fun invalidRecordingBoundsAndFootprintsCannotBecomeEligible() {
        val valid = BackdropRegistration.Identity.corners(Rect(10f, 10f, 20f, 20f))
        for ((width, height) in listOf(0f to 100f, 100f to 0f, -1f to 100f,
                Float.NaN to 100f, 100f to Float.POSITIVE_INFINITY)) {
            assertFalse(backdropContainsFootprint(width, height, 10f, valid))
        }
        assertFalse(backdropContainsFootprint(100f, 100f, Float.NaN, valid))
        assertFalse(backdropContainsFootprint(100f, 100f, Float.POSITIVE_INFINITY, valid))
        assertFalse(backdropContainsFootprint(100f, 100f, 10f, emptyList()))
        assertFalse(backdropContainsFootprint(100f, 100f, 10f, valid.take(3)))
        assertFalse(backdropContainsFootprint(100f, 100f, 10f, valid + Offset(15f, 15f)))
        assertFalse(backdropContainsFootprint(100f, 100f, 10f, valid.dropLast(1) + Offset(Float.NaN, 15f)))
        assertFalse(backdropContainsFootprint(100f, 100f, 10f, valid.dropLast(1) + Offset(15f, Float.POSITIVE_INFINITY)))
        assertFalse(backdropContainsFootprint(100f, 100f, 0f, valid.dropLast(1) + Offset(100.001f, 15f)))
    }

    @Test fun defaultTextureMappingPreservesTheLegacyFormulaBitForBit() {
        val sourceSize = IntSize(413, 237)
        val imageSize = IntSize(127, 53)
        val vertices = listOf(Offset(0f, 1f), Offset(.1f, .3f), Offset(25.375f, 14.125f),
            Offset(-13.9375f, 87.625f), Offset(511.99997f, -222.03125f))
        val rays = listOf(Offset(.25f, -.5f), Offset(.2f, -.1f), Offset(-3.625f, 7.75f),
            Offset(1.125f, -.375f), Offset(-.03125f, 4.8125f))
        for (offset in listOf(Offset.Zero, Offset(37.25f, -6.125f))) {
            val sampling = BackdropSampleMapping(sourceSize, offset)
            val explicitTranslation = BackdropSampleMapping(sourceSize, Offset.Zero,
                mapping(tx = offset.x, ty = offset.y))
            for ((vertex, ray) in vertices.zip(rays)) {
                val expectedX = (vertex.x + ray.x + offset.x) * (imageSize.width.toFloat() / sourceSize.width)
                val expectedY = (vertex.y + ray.y + offset.y) * (imageSize.height.toFloat() / sourceSize.height)
                for (actual in listOf(sampling.texture(vertex + ray, imageSize),
                        explicitTranslation.texture(vertex + ray, imageSize))) {
                    assertEquals("Legacy x arithmetic must remain unchanged", expectedX.toRawBits(), actual.x.toRawBits())
                    assertEquals("Legacy y arithmetic must remain unchanged", expectedY.toRawBits(), actual.y.toRawBits())
                }
            }
        }
    }

    @Test fun textureRegistrationMapsTheRayEndpointBeforeImageScaling() {
        val sourceSize = IntSize(200, 300)
        val imageSize = IntSize(50, 120)
        val vertex = Offset(12f, 20f)
        val ray = Offset(3f, -4f)
        val nonuniform = BackdropSampleMapping(sourceSize, Offset.Zero,
            mapping(xx = .5f, yy = 2f, tx = 7f, ty = -9f))
        assertPoint(3.625f, 9.2f, nonuniform.texture(vertex + ray, imageSize))
        val affine = BackdropSampleMapping(sourceSize, Offset.Zero,
            mapping(xx = .75f, xy = .25f, yx = -.5f, yy = 1.5f, tx = 8f, ty = 11f))
        val actual = affine.texture(vertex + ray, imageSize)
        assertPoint(5.8125f, 11f, actual)
        assertNotEquals("Adding the local ray after registration is not equivalent", 6.25f, actual.x, .0001f)
        assertNotEquals("The y ray must also use the registered basis", 12.4f, actual.y, .0001f)
    }

    @Test fun textureSamplingCancelsACommonAncestorWithoutChangingItsRays() {
        val ancestor = mapping(xx = 0f, xy = -2f, yx = .5f, yy = 0f, tx = 950f, ty = 73f)
        val sourceToRoot = requireNotNull(mapping(tx = 30f, ty = 50f).followedBy(ancestor))
        val consumerToRoot = requireNotNull(mapping(xx = .5f, yy = 1.25f, tx = 46f, ty = 74f).followedBy(ancestor))
        val relative = requireNotNull(consumerToRoot.followedBy(requireNotNull(sourceToRoot.inverse())))
        val sampling = BackdropSampleMapping(IntSize(200, 160), Offset.Zero, relative)
        // Endpoint (104, 18) becomes source (68, 46.5), then image (17, 23.25).
        assertPoint(17f, 23.25f, sampling.texture(Offset(100f, 20f) + Offset(4f, -2f), IntSize(50, 80)))
    }

    @Test fun basisOnlyChangesInvalidateTheSamplingCacheAndReversalRestoresItsKey() {
        val sourceSize = IntSize(400, 300)
        val sourceOffset = Offset.Zero
        val original = BackdropSampleMapping(sourceSize, sourceOffset,
            mapping(xx = .5f, yy = .75f, tx = 24f, ty = 30f))
        val widened = BackdropSampleMapping(sourceSize, sourceOffset,
            mapping(xx = .8f, yy = .75f, tx = 24f, ty = 30f))
        val sheared = BackdropSampleMapping(sourceSize, sourceOffset,
            mapping(xx = .5f, xy = .125f, yy = .75f, tx = 24f, ty = 30f))
        val reversed = BackdropSampleMapping(sourceSize, sourceOffset,
            mapping(xx = .5f, yy = .75f, tx = 24f, ty = 30f))
        val cache = mutableMapOf(original to "initial", widened to "wide", sheared to "sheared")
        assertEquals(3, cache.size)
        assertNotEquals(original, widened)
        assertNotEquals(original, sheared)
        assertEquals(original, reversed)
        assertEquals(original.hashCode(), reversed.hashCode())
        assertEquals("initial", cache[reversed])
        assertEquals(sourceSize, widened.sourceSize)
        assertEquals(sourceOffset, widened.sourceOffset)
    }

    @Test fun textureProjectionDoesNotMutateGeometryRaysOrItsInputMapping() {
        val vertices = floatArrayOf(2f, 3f, 41.25f, 17.5f, 95f, 60f)
        val rays = floatArrayOf(-.25f, .5f, 2.75f, -1.5f, .125f, -.375f)
        val originalVertices = vertices.copyOf()
        val originalRays = rays.copyOf()
        val registration = mapping(xx = .4f, xy = .1f, yx = -.2f, yy = 1.6f, tx = 12f, ty = 9f)
        val sampling = BackdropSampleMapping(IntSize(500, 400), Offset.Zero, registration)
        val originalKey = sampling.copy()
        for (i in vertices.indices step 2) {
            sampling.texture(Offset(vertices[i] + rays[i], vertices[i + 1] + rays[i + 1]), IntSize(100, 80))
        }
        assertArrayEquals(originalVertices, vertices, 0f)
        assertArrayEquals(originalRays, rays, 0f)
        assertEquals(originalKey, sampling)
        assertSame(registration, sampling.registration)
    }
}
