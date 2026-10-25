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

    @Test fun virtualizedDepartureScalesToItsEndpointByTheFadeWithoutAcceleratingLiveItems() {
        val source = androidx.compose.ui.geometry.Rect(2f, 8f, 54f, 60f)
        val target = androidx.compose.ui.geometry.Rect(12f, 20f, 112f, 120f)
        for (p in listOf(0f, .1f, .225f, .45f, .8f, 1f)) {
            val outgoing = browsePinchGeometryProgress(true, p)
            val live = browsePinchGeometryProgress(false, p)
            assertEquals("Live geometry keeps the finger's original progression", p, live, 0f)
            assertEquals((p / .45f).coerceIn(0f, 1f), outgoing, 0f)
            val frame = pinchElementRect(source, target, outgoing)
            assertEquals(52f + 48f * outgoing, frame.width, .001f)
            assertEquals(frame.width, frame.height, .001f)
            assertEquals(2f + 10f * outgoing, frame.left, .001f)
            assertEquals(8f + 12f * outgoing, frame.top, .001f)
            if (p in .001f.. .449f) {
                assertTrue("Departure must scale faster, not only become transparent", outgoing > live)
                assertTrue(frame.width > pinchElementRect(source, target, live).width)
            }
            if (p >= .45f) {
                assertEquals(target, frame)
                assertEquals(0f, browseViewVisibility(true, false, p), 0f)
            }
        }
        assertEquals(source, pinchElementRect(source, target, browsePinchGeometryProgress(true, 0f)))
    }

    @Test fun departingGeometryRetracesShrinkingAndGrowingEndpointsWithoutOvershootOrDeadlineJump() {
        val source = androidx.compose.ui.geometry.Rect(10f, 20f, 110f, 100f)
        val target = androidx.compose.ui.geometry.Rect(-20f, -10f, 32f, 30f)
        val frames = (0..1000).map { step ->
            val p = step / 1000f
            val q = browsePinchGeometryProgress(true, p)
            assertTrue(q in 0f..1f)
            assertTrue("Departing geometry never trails ordinary geometry", q >= p)
            pinchElementRect(source, target, q).also { frame ->
                assertTrue(frame.width in target.width..source.width)
                assertTrue(frame.height in target.height..source.height)
            }
        }
        frames.zipWithNext().forEach { (before, after) ->
            assertTrue(after.width <= before.width)
            assertTrue("Continuous before/after fade completion", kotlin.math.abs(after.width - before.width) < .11f)
        }
        for (step in 1000 downTo 0) {
            val p = step / 1000f
            assertEquals("Finger reversal has no independent scale clock", frames[step],
                pinchElementRect(source, target, browsePinchGeometryProgress(true, p)))
        }
        for (departing in listOf(false, true)) {
            assertEquals(0f, browsePinchGeometryProgress(departing, -1f), 0f)
            assertEquals(1f, browsePinchGeometryProgress(departing, 2f), 0f)
        }
        assertEquals(source, frames.first())
        assertEquals(target, frames.last())
    }

}
