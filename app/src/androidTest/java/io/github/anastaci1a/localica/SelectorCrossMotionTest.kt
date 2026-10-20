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
        val rest = SelectorCrossFrame(64f, 56f, 28f, 32f)
        var raw by mutableStateOf(SelectorCrossFrame(64f, 60f, 28f, 32f))
        var held by mutableStateOf(false)
        lateinit var displayed: SelectorCrossFrame
        compose.setContent {
            val frame = rememberSelectorCrossFrame(raw, rest, held, bodySettled = true,
                maximumCorner = 100f, maximumOuterCorner = 100f)
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
                assertEquals(rest.lens, displayed.lens, .001f)
                raw = SelectorCrossFrame(56f, 56f, 28f, 28f)
            }
            compose.mainClock.advanceTimeByFrame()
            val edge = compose.runOnIdle { displayed }
            assertEquals("Holding at an edge does not change lens cross size", rest.lens, edge.lens, 0f)
            compose.runOnIdle { held = false; raw = rest }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals("Release starts at the shown contour", edge, displayed) }
            compose.mainClock.advanceTimeBy(64)
            val releasing = compose.runOnIdle { displayed }
            assertTrue(releasing.shell > edge.shell && releasing.shell < rest.shell)
            compose.runOnIdle { held = true; raw = edge }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals("Regrab starts at the shown contour", releasing, displayed) }
            compose.mainClock.advanceTimeBy(1000)
            compose.runOnIdle {
                assertEquals(edge.shell, displayed.shell, .001f)
                assertEquals(rest.lens, displayed.lens, .001f)
                held = false; raw = rest
            }
            compose.mainClock.advanceTimeBy(1600)
            compose.runOnIdle { assertEquals(rest, displayed) }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
