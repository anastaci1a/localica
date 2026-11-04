package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

/** #554: a popup's home is the trigger as presented (lifted), through the shared anchor API. */
class GlassLiftTest {
    private val rest = Rect(100f, 200f, 148f, 248f)

    @Test fun anAnchorWithoutPresentationHomesAtItsLayoutBounds() {
        val anchor = GlassAnchor().also { it.bounds = rest }
        assertEquals(rest, anchor.presentedBounds)
        assertEquals(1f, anchor.presentedScale, 0f)
    }

    @Test fun liftScalesBoundsAboutThePivotSoTheButtonGrowsAndMovesAway() {
        val lift = GlassLift(1.1f, Offset(60f, 220f))
        val anchor = GlassAnchor().also { it.bounds = rest; it.presentation = { lift } }
        val presented = anchor.presentedBounds
        assertEquals(1.1f * rest.width, presented.width, .001f)
        assertEquals(1.1f * rest.height, presented.height, .001f)
        // Left of the pivot, so it moves further left; the pivot-relative offset scales too.
        assertEquals(60f + (100f - 60f) * 1.1f, presented.left, .001f)
        assertEquals(220f + (200f - 220f) * 1.1f, presented.top, .001f)
        assertEquals(1.1f, anchor.presentedScale, 0f)
    }

    @Test fun presentationFollowsTheLiveLiftAndReturnsHomeAtRest() {
        var lift: GlassLift? = GlassLift(1.05f, Offset(0f, 0f))
        val anchor = GlassAnchor().also { it.bounds = rest; it.presentation = { lift } }
        assertNotEquals(rest, anchor.presentedBounds)
        lift = null
        assertEquals(rest, anchor.presentedBounds)
        lift = GlassLift(1f, Offset(500f, 500f))
        assertEquals(rest, anchor.presentedBounds)
    }

    @Test fun anUnmeasuredAnchorStaysUnmeasuredSoDetachedPlacementIsKept() {
        val anchor = GlassAnchor().also { it.presentation = { GlassLift(1.2f, Offset(10f, 10f)) } }
        assertEquals(Rect.Zero, anchor.presentedBounds)
    }
}
