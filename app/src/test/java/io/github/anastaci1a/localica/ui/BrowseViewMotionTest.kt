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

    @Test fun appearingAndDisappearingElementsFinishBeforeGeometryAndReverseContinuously() {
        for (p in listOf(0f, .05f, .2f, .45f, .8f, 1f, .8f, .45f, .2f, .05f, 0f)) {
            val incoming = browseViewVisibility(false, true, p)
            val outgoing = browseViewVisibility(true, false, p)
            assertTrue(incoming in 0f..1f)
            assertEquals(1f, incoming + outgoing, .0001f)
            if (p >= .45f) { assertEquals(1f, incoming, 0f); assertEquals(0f, outgoing, 0f) }
        }
        assertEquals(0f, browseViewVisibility(false, true, 0f), 0f)
        assertEquals(1f, browseViewVisibility(true, false, 0f), 0f)
        assertTrue(BrowseViewFadeMillis < 180)
    }

    @Test fun persistentElementsNeverDimAndOvershootingProgressIsClamped() {
        for (p in listOf(-1f, 0f, .2f, .5f, 1f, 2f)) {
            assertEquals(1f, browseViewVisibility(true, true, p), 0f)
            assertEquals(0f, browseViewVisibility(false, false, p), 0f)
        }
        assertEquals(0f, browseViewVisibility(false, true, -1f), 0f)
        assertEquals(1f, browseViewVisibility(false, true, 2f), 0f)
    }
}
