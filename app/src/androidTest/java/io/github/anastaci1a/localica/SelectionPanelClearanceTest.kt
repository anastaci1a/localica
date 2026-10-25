package io.github.anastaci1a.localica

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class SelectionPanelClearanceTest {
    @get:Rule val compose = createComposeRule()
    private var visible by mutableStateOf(false)
    private lateinit var panel: FloatingSelectionPanelState
    private lateinit var referenceProgress: State<Float>

    private fun fixture(chrome: Int, reducedMotion: Boolean = false, inline: Boolean = false, cap: Int = 160) {
        compose.setContent {
            panel = rememberFloatingSelectionPanel(visible, reducedMotion)
            // Independent clock oracle: unlike the panel this has no measurement/occupancy state.
            referenceProgress = animateFloatAsState(if (visible) 1f else 0f,
                tween(if (reducedMotion) 0 else 220, easing = androidx.compose.animation.core.LinearOutSlowInEasing), label = "Reference panel motion")
            CompositionLocalProvider(LocalBrowseObscuredInset provides (chrome.dp + panel.clearance)) {
                Box(Modifier.size(320.dp, 480.dp).testTag("viewport")) {
                    if (inline) Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Box(Modifier.align(Alignment.BottomEnd).size(48.dp).testTag("control"))
                        }
                        FloatingSelectionPanel(panel, Modifier.heightIn(max = cap.dp).testTag("panel-window")) {
                            Box(Modifier.fillMaxWidth().height(96.dp).testTag("panel"))
                        }
                        Spacer(Modifier.height(chrome.dp))
                    } else {
                    FloatingSelectionPanel(panel, Modifier.align(Alignment.BottomCenter).padding(bottom = chrome.dp).heightIn(max = cap.dp).testTag("panel-window")) {
                        Box(Modifier.fillMaxWidth().height(96.dp).testTag("panel"))
                    }
                    Box(Modifier.align(Alignment.BottomEnd).padding(bottom = LocalBrowseObscuredInset.current)
                        .size(48.dp).testTag("control"))
                    }
                }
            }
        }
    }

    private fun assertFrame(chrome: Int): Float {
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val control = compose.onNodeWithTag("control").fetchSemanticsNode().boundsInRoot
        val density = viewport.width / 320f
        var progress = 0f
        compose.runOnIdle {
            progress = panel.progress
            assertEquals("Panel retains the uninterrupted tween at this clock phase",
                referenceProgress.value, progress, .0001f)
        }
        val contentPixels = (96f * density).roundToInt()
        val chromePixels = (chrome * density).roundToInt()
        val occupiedPixels = contentPixels * progress
        val clearancePixels = occupiedPixels + 8f * density * progress
        assertEquals("Every frame uses the panel's same occupancy", chromePixels + clearancePixels,
            viewport.bottom - control.bottom, 1.1f)
        if (progress > 0f || visible) {
            val content = compose.onNodeWithTag("panel")
            val node = content.fetchSemanticsNode()
            val bounds = node.boundsInRoot
            val window = compose.onNodeWithTag("panel-window").fetchSemanticsNode()
            val windowPixels = clearancePixels.roundToInt()
            val gapPixels = (clearancePixels - occupiedPixels).roundToInt()
            val visiblePixels = (windowPixels - gapPixels).coerceIn(0, contentPixels)
            assertEquals("Reveal window matches rounded occupancy on every frame",
                windowPixels, window.size.height)
            assertEquals("Content keeps its measured height even behind an empty clip",
                contentPixels, node.size.height)
            assertEquals("Unclipped placement remains anchored to actual bottom chrome",
                viewport.bottom - chromePixels - windowPixels + gapPixels, node.positionInRoot.y, 1.1f)
            assertEquals("Unclipped placement retains its gap even when the clip rounds to zero",
                gapPixels.toFloat(), node.positionInRoot.y - control.bottom, 1.1f)
            if (visiblePixels == 0) {
                // Clipped bounds report an empty rectangle at the origin, not the content's
                // placement. A positive animation fraction can still occupy zero pixels.
                assertEquals("Zero-pixel reveal must have empty clipped bounds", androidx.compose.ui.geometry.Rect.Zero, bounds)
                content.assertIsNotDisplayed()
            } else {
                assertEquals("Gap follows visible panel without an extra frame", gapPixels.toFloat(),
                    bounds.top - control.bottom, 1.1f)
                assertEquals("Every nonempty clipped pixel band remains visible",
                    visiblePixels.toFloat(), bounds.height, .01f)
                assertEquals("Visible pixels end at remaining bottom chrome",
                    viewport.bottom - chromePixels, bounds.bottom, 1.1f)
            }
        } else compose.onNodeWithTag("panel").assertDoesNotExist()
        return progress
    }

    private fun interruptedMotion(chrome: Int, inline: Boolean = false) {
        fixture(chrome, inline = inline)
        compose.runOnIdle { visible = true }
        assertEquals(1f, assertFrame(chrome), 0f)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { visible = false }
            compose.mainClock.advanceTimeByFrame()
            val start = assertFrame(chrome)
            compose.mainClock.advanceTimeBy(64)
            val middle = assertFrame(chrome)
            assertTrue("Hide must animate rather than snap", middle > 0f && middle < start)
            // Cancel/interrupt hide before completion, then repeat a complete hide/reveal.
            compose.runOnIdle { visible = true }
            compose.mainClock.advanceTimeByFrame()
            // Clock subscribers animate before recomposition accepts the new target. Compare
            // against the same-frame oracle, not the preceding outgoing frame's value.
            assertFrame(chrome)
            compose.mainClock.advanceTimeBy(300)
            assertEquals(1f, assertFrame(chrome), 0f)
            compose.runOnIdle { visible = false }
            repeat(18) { compose.mainClock.advanceTimeByFrame(); assertFrame(chrome) }
            assertEquals(0f, assertFrame(chrome), 0f)
            compose.onNodeWithTag("panel").assertDoesNotExist()
            compose.runOnIdle { visible = true }
            repeat(18) { compose.mainClock.advanceTimeByFrame(); assertFrame(chrome) }
            assertEquals(1f, assertFrame(chrome), 0f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun liftedControlsTrackInterruptedPanelWithChrome() = interruptedMotion(80)
    @Test fun liftedControlsTrackInterruptedPanelWithoutChrome() = interruptedMotion(0)
    @Test fun inlineQueueViewportTracksInterruptedPanel() = interruptedMotion(0, inline = true)
    @Test fun boundedPanelReservesGapInsideHeightCap() {
        fixture(80, cap = 64)
        compose.runOnIdle { visible = true }
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val density = viewport.width / 320f
        val bounds = compose.onNodeWithTag("panel").fetchSemanticsNode().boundsInRoot
        val control = compose.onNodeWithTag("control").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { assertEquals(56f, panel.height.value, .01f) }
        assertEquals(56f * density, bounds.height, 1.1f)
        assertEquals(8f * density, bounds.top - control.bottom, 1.1f)
        assertEquals(80f * density, viewport.bottom - bounds.bottom, 1.1f)
    }
    @Test fun reducedMotionUsesSameShownAndHiddenGeometry() {
        fixture(80, reducedMotion = true)
        compose.runOnIdle { visible = true }
        assertEquals(1f, assertFrame(80), 0f)
        compose.runOnIdle { visible = false }
        assertEquals(0f, assertFrame(80), 0f)
    }
}
