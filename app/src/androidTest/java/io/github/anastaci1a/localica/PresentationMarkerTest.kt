package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class PresentationMarkerTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun composedTokenWaitsForTheFrameThatAppliesLayoutInputs() {
        var stage by mutableIntStateOf(0)
        var edge by mutableIntStateOf(40)
        lateinit var view: View
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        compose.setContent {
            view = LocalView.current
            Box(Modifier.size(120.dp).testTag("marker-scene")) {
                Geometry(Modifier.align(Alignment.Center)) { edge }
                PresentationMarker { stage }
                PresentationMarker(Modifier.offset(x = 16.dp), composeStage = true) { stage }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            val scene = compose.onNodeWithTag("marker-scene")
            val start = compose.mainClock.currentTime
            val sample = IntOffset((16f * density).roundToInt() + 2, 2)
            compose.runOnUiThread { edge = 60; stage = 1 }
            // The legacy draw-only token can publish before the queued composition.
            val early = capturePresentedPopupPixels(compose, scene, 1) { view }
            assertEquals(start, compose.mainClock.currentTime)
            assertEquals("The composed marker cannot identify the previous layout as the new frame",
                0f, early[sample.x, sample.y].red, .01f)
            assertEquals(40f * density, compose.onNodeWithTag("marker-geometry").fetchSemanticsNode().boundsInRoot.width, 1f)
            compose.mainClock.advanceTimeByFrame()
            capturePresentedPopupPixels(compose, scene, 1, sample) { view }
            assertEquals(start + 16L, compose.mainClock.currentTime)
            assertEquals(60f * density, compose.onNodeWithTag("marker-geometry").fetchSemanticsNode().boundsInRoot.width, 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Composable private fun Geometry(modifier: Modifier, edge: () -> Int) {
        Box(modifier.size(edge().dp).background(Color.Blue).testTag("marker-geometry"))
    }
}
