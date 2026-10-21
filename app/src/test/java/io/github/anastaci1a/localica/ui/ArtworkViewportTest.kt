package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class ArtworkViewportTest {
    private val portraitViewport = Size(400f, 800f)
    private val landscapeArt = Size(1200f, 600f)

    @Test fun fitUsesBothImageDimensionsAndCentersTheLetterboxedAxis() {
        assertEquals(Size(400f, 200f), fittedArtworkSize(portraitViewport, landscapeArt))
        assertEquals(ArtworkPanBounds(0f, 0f), artworkPanBounds(portraitViewport, landscapeArt, 1f))
        assertEquals(ArtworkPanBounds(200f, 0f), artworkPanBounds(portraitViewport, landscapeArt, 2f))
        assertEquals(ArtworkPanBounds(800f, 100f), artworkPanBounds(portraitViewport, landscapeArt, 5f))
        assertEquals(ArtworkPanBounds(0f, 200f), artworkPanBounds(Size(800f, 400f), Size(600f, 1200f), 2f))
        assertEquals(Size.Zero, fittedArtworkSize(Size.Zero, landscapeArt))
        assertEquals(Size.Zero, fittedArtworkSize(portraitViewport, Size.Zero))
    }

    @Test fun zoomKeepsTheSameImagePointUnderTheMovingCentroid() {
        val viewport = Size(400f, 400f)
        val image = Size(400f, 400f)
        val initial = ArtworkTransform(2f, Offset(40f, -20f))
        val centroid = Offset(260f, 230f)
        val pan = Offset(12f, -7f)
        val zoomed = transformArtwork(initial, viewport, image, 1.5f, pan, centroid)
        val center = Offset(200f, 200f)
        val before = (centroid - center - initial.offset) / initial.scale
        val after = (centroid + pan - center - zoomed.offset) / zoomed.scale
        assertEquals(before.x, after.x, .001f)
        assertEquals(before.y, after.y, .001f)
    }

    @Test fun zoomAndPanClampToImageEdgesAndResetExactlyToCenter() {
        val zoomed = transformArtwork(ArtworkTransform(), portraitViewport, landscapeArt, 2f,
            Offset(10000f, -10000f), Offset(200f, 400f))
        assertEquals(ArtworkTransform(2f, Offset(200f, 0f)), zoomed)
        val reset = transformArtwork(zoomed, portraitViewport, landscapeArt, .1f,
            Offset(300f, 300f), Offset.Zero)
        assertEquals(ArtworkTransform(), reset)
        assertEquals(6f, transformArtwork(zoomed, portraitViewport, landscapeArt, 100f,
            Offset.Zero, Offset.Zero).scale, 0f)
    }

    @Test fun decayRetainsReleaseVelocityAndSlowsWithoutFrameRateDependence() {
        val bounds = ArtworkPanBounds(10000f, 10000f)
        val initial = ArtworkPanMotion(Offset(20f, 40f), Offset(1200f, -800f))
        val first = advanceArtworkPan(initial, bounds, .1f, 1f)
        val second = advanceArtworkPan(first, bounds, .1f, 1f)
        val whole = advanceArtworkPan(initial, bounds, .2f, 1f)
        assertTrue(first.offset.x > initial.offset.x)
        assertTrue(first.velocity.x < initial.velocity.x)
        assertTrue(second.offset.x - first.offset.x < first.offset.x - initial.offset.x)
        assertEquals(whole.offset.x, second.offset.x, .001f)
        assertEquals(whole.offset.y, second.offset.y, .001f)
        assertEquals(whole.velocity.x, second.velocity.x, .001f)
    }

    @Test fun edgeStopsOnlyTheOutwardComponentAndCornerStopsBoth() {
        val edge = advanceArtworkPan(ArtworkPanMotion(Offset(99f, 0f), Offset(1000f, 100f)),
            ArtworkPanBounds(100f, 100f), .016f, 1f)
        assertEquals(100f, edge.offset.x, 0f)
        assertEquals(0f, edge.velocity.x, 0f)
        assertTrue(edge.offset.y > 0f)
        assertTrue(edge.velocity.y > 0f)
        val corner = advanceArtworkPan(ArtworkPanMotion(Offset(99f, -99f), Offset(1000f, -1000f)),
            ArtworkPanBounds(100f, 100f), .016f, 1f)
        assertEquals(Offset(100f, -100f), corner.offset)
        assertFalse(corner.moving)
    }

    @Test fun smallerGeometryClampsTheCurrentFrameAndDecayEventuallyStops() {
        val clamped = advanceArtworkPan(ArtworkPanMotion(Offset(200f, -200f), Offset(300f, -300f)),
            ArtworkPanBounds(50f, 0f), 0f, 1f)
        assertEquals(Offset(50f, 0f), clamped.offset)
        assertFalse(clamped.moving)
        var pan = ArtworkPanMotion(Offset.Zero, Offset(100f, -200f))
        repeat(200) { pan = advanceArtworkPan(pan, ArtworkPanBounds(1000f, 1000f), .016f, 5f) }
        assertFalse(pan.moving)
        val settled = advanceArtworkPan(pan, ArtworkPanBounds(1000f, 1000f), 1f, 5f)
        assertEquals(pan, settled)
    }
}
