package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercise the production presentation clock independently of optical raster tolerances. */
class SelectorCrossMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun contactDuringCenterTravelAndRegrabCaptureTheDisplayedCrossFrame() {
        val rest = SelectorReturnFrame(240f, 64f, 72f, 56f, 4f, 28f, 32f, 0f)
        var raw by mutableStateOf(rest.copy(lensCross = 60f))
        var held by mutableStateOf(false)
        lateinit var displayed: SelectorReturnFrame
        compose.setContent {
            val frame = rememberSelectorCrossFrame(raw, rest, held, bodySettled = true,
                limits = SelectorReturnLimits(4f, 4f, -1f))
            SideEffect { displayed = frame }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            val moving = compose.runOnIdle { displayed }
            compose.runOnIdle { raw = rest; held = true }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals("Center-travel contact starts at the shown contour", moving, displayed) }
            compose.mainClock.advanceTimeBy(1000)
            compose.runOnIdle {
                assertEquals(rest.lensCross, displayed.lensCross, .001f)
                raw = rest.copy(shellTravel = 244f, shellCross = 56f, lensTravel = 68f, gap = 0f, outerCorner = 28f, shellStart = -4f)
            }
            compose.mainClock.advanceTimeByFrame()
            val edge = compose.runOnIdle { displayed }
            assertEquals("Holding at an edge does not change lens cross size", rest.lensCross, edge.lensCross, 0f)
            compose.runOnIdle { held = false; raw = rest }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals("Release starts at the shown contour", edge, displayed) }
            compose.mainClock.advanceTimeBy(64)
            val releasing = compose.runOnIdle { displayed }
            assertTrue("Release advances the shared full frame", releasing != edge)
            assertTrue("Lens remains inside the cross envelope", releasing.lensCross <= releasing.shellCross)
            compose.runOnIdle { held = true; raw = edge }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals("Regrab starts at the shown contour", releasing, displayed) }
            compose.mainClock.advanceTimeBy(1000)
            compose.runOnIdle {
                assertEquals(edge.shellCross, displayed.shellCross, .001f)
                assertEquals(rest.lensCross, displayed.lensCross, .001f)
                held = false; raw = rest
            }
            // The signed mode retains configured damping; wait for its exact terminal
            // state rather than assuming the former critical-only return duration.
            var settlingFrames = 0
            while (compose.runOnIdle { displayed != rest } && settlingFrames++ < 100) {
                compose.mainClock.advanceTimeBy(40)
            }
            compose.runOnIdle { assertEquals("Shared geometry reaches exact rest within 4 seconds", rest, displayed) }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
