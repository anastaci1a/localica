package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class BrowseViewMotionTest {
    @Test fun virtualizedArtworkUsesTheLiveEndpointScaleInsteadOfFittingTheWholeCard() {
        val source = androidx.compose.ui.geometry.Rect(0f, 8f, 52f, 60f)
        val target = androidx.compose.ui.geometry.Rect(12f, 8f, 112f, 108f)
        for (p in listOf(0f, .1f, .25f, .5f, 1f, .5f, .25f, 0f)) {
            val frame = pinchElementRect(source, target, p)
            assertEquals(52f + 48f * p, frame.width, .001f)
            assertEquals(frame.width, frame.height, .001f)
            assertEquals(12f * p, frame.left, .001f)
            val reversed = pinchElementRect(target, source, 1f - p)
            assertEquals(frame.left, reversed.left, .001f)
            assertEquals(frame.width, reversed.width, .001f)
        }
    }

    @Test fun reversedElementFadesMatchAtEverySpatialPosition() {
        for (window in listOf(.45f, .3f, .15f)) for (step in 0..1000) {
            val p = step / 1000f
            assertEquals("Reverse uses the same spatial fade", browseViewVisibility(true, false, p, window),
                browseViewVisibility(false, true, 1f - p, window), .0001f)
            if (p >= window) assertEquals(0f, browseViewVisibility(true, false, p, window), .0001f)
            if (p <= 1f - window) assertEquals(0f, browseViewVisibility(false, true, p, window), .0001f)
        }
        assertEquals(1f, browseViewVisibility(true, false, 0f), 0f)
        assertEquals(1f, browseViewVisibility(false, true, 1f), 0f)
    }

    @Test fun persistentElementsNeverDimAndOvershootingProgressIsClamped() {
        for (p in listOf(-1f, 0f, .2f, .5f, 1f, 2f)) {
            assertEquals(1f, browseViewVisibility(true, true, p), 0f)
            assertEquals(0f, browseViewVisibility(false, false, p), 0f)
        }
        assertEquals(0f, browseViewVisibility(false, true, -1f), 0f)
        assertEquals(1f, browseViewVisibility(false, true, 2f), 0f)
    }

    @Test fun invisibleDeparturesStopAtTheirFadeEndSizeAndPosition() {
        val source = androidx.compose.ui.geometry.Rect(2f, 8f, 54f, 60f)
        val target = androidx.compose.ui.geometry.Rect(102f, 208f, 206f, 312f)
        for (window in listOf(.45f, .3f, .15f)) {
            val end = pinchElementRect(source, target, window)
            for (step in 0..1000) {
                val p = step / 1000f
                val geometry = browsePinchGeometryProgress(true, p, window)
                val frame = pinchElementRect(source, target, geometry)
                assertEquals("Live geometry is unchanged", p, browsePinchGeometryProgress(false, p, window), 0f)
                if (p <= window) assertEquals("Departures follow ordinary geometry until invisible", p, geometry, 0f)
                else assertEquals("No invisible growth or travel", end, frame)
                assertTrue(frame.width <= end.width && frame.left <= end.left && frame.top <= end.top)
            }
            assertTrue("Fade end must not accelerate growth to the full target", end.width < target.width)
        }
    }

    @Test fun fartherJourneysFadeEarlierAndTheirReverseArrivalsFadeLater() {
        val near = browsePinchFadeWindow(100f, 400f)
        val far = browsePinchFadeWindow(800f, 400f)
        assertTrue(far < near)
        val p = (near + far) / 2f
        assertEquals(0f, browseViewVisibility(true, false, p, far), .0001f)
        assertTrue(browseViewVisibility(true, false, p, near) > 0f)
        assertEquals(0f, browseViewVisibility(false, true, 1f - p, far), .0001f)
        assertTrue(browseViewVisibility(false, true, 1f - p, near) > 0f)
        assertEquals(near, browsePinchFadeWindow(200f, 800f), .0001f)
    }
}
