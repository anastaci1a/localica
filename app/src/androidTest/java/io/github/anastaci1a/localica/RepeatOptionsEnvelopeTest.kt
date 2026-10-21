package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RepeatOptionsEnvelopeTest {
    @get:Rule val compose = createComposeRule()

    private fun scrollRange() = compose.onNodeWithTag("repeat-options-scroll")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    @Test fun nativeWindowKeepsItsBoundsWhileAdvancedTouchScrollingLeavesTheDraftUnchanged() {
        var open by mutableStateOf(true)
        var settings by mutableStateOf(QueueSettings())
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                if (open) RepeatOptionsDialog(settings, { open = false }, { settings = it(settings) })
            }
        } }
        compose.onNodeWithText("Repeat modes").assertIsDisplayed()
        val dialog = compose.onNodeWithTag("repeat-options-dialog")
        val bounds = dialog.fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("repeat-options-body").fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("repeat-options-tabs").fetchSemanticsNode().boundsInRoot
        val apply = compose.onNodeWithText("Apply").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Advanced").performClick()
        assertEquals(bounds, dialog.fetchSemanticsNode().boundsInRoot)
        assertEquals(body, compose.onNodeWithTag("repeat-options-body").fetchSemanticsNode().boundsInRoot)
        assertTrue(scrollRange().maxValue() > 0f)
        // A quick vertical drag over the choice track belongs to its scrolling parent,
        // not the selector. Both the viewport and the fixed chrome must stay put.
        compose.onNodeWithTag("repeat-options-scroll").performTouchInput { swipeUp(durationMillis = 120) }
        compose.waitUntil(5000) { scrollRange().value() > 0f }
        assertEquals(bounds, dialog.fetchSemanticsNode().boundsInRoot)
        assertEquals(body, compose.onNodeWithTag("repeat-options-body").fetchSemanticsNode().boundsInRoot)
        assertEquals(tabs, compose.onNodeWithTag("repeat-options-tabs").fetchSemanticsNode().boundsInRoot)
        assertEquals(apply, compose.onNodeWithText("Apply").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertFalse(open); assertEquals(AdvancedRepeat(), settings.advancedRepeat) }
    }

    @Test fun simpleFitsAndAdvancedScrollsInsideTheSameCompactSurface() {
        var settings by mutableStateOf(QueueSettings())
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalInlineGlassDialog provides true, LocalReducedMotion provides true) {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    RepeatOptionsDialog(settings, {}, { settings = it(settings) })
                }
            }
        } }
        val dialog = compose.onNodeWithTag("repeat-options-dialog")
        val original = dialog.fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("repeat-options-tabs").fetchSemanticsNode().boundsInRoot
        val apply = compose.onNodeWithText("Apply").fetchSemanticsNode().boundsInRoot
        assertEquals("Simple needs no artificial scrolling", 0f, scrollRange().maxValue(), 0f)
        compose.onNodeWithText("Repeat song").assertIsDisplayed()
        compose.onNodeWithText("Reset").assertIsDisplayed()
        compose.onNodeWithText("Advanced").performClick()
        assertEquals(original, dialog.fetchSemanticsNode().boundsInRoot)
        assertTrue("Advanced is bounded by the compact Simple envelope", scrollRange().maxValue() > 0f)
        assertEquals(0f, scrollRange().value(), 0f)
        compose.onNodeWithText("Repeat this queue").performScrollTo().performClick()
        compose.onNodeWithText("Reset").performScrollTo().assertIsDisplayed()
        val advancedPosition = scrollRange().value()
        assertTrue(advancedPosition > 0f)
        assertEquals(tabs, compose.onNodeWithTag("repeat-options-tabs").fetchSemanticsNode().boundsInRoot)
        assertEquals(apply, compose.onNodeWithText("Apply").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Simple").performClick()
        assertEquals(original, dialog.fetchSemanticsNode().boundsInRoot)
        assertEquals(0f, scrollRange().maxValue(), 0f)
        compose.onNodeWithText("Repeat song").performClick()
        compose.onNodeWithText("Advanced").performClick()
        assertEquals(advancedPosition, scrollRange().value(), 0f)
        compose.onNodeWithText("Repeat this queue").assertIsSelected()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(AdvancedRepeat(), settings.advancedRepeat) }
        // Inert envelope faces must never duplicate accessible live controls.
        compose.onAllNodesWithText("Advanced").assertCountEquals(1)
        compose.onAllNodesWithText("Reset").assertCountEquals(1)
    }

    @Test fun envelopeSurvivesModeDraftFontAndWindowChangesWithReachableActions() {
        var width by mutableStateOf(360.dp)
        var height by mutableStateOf(640.dp)
        var scale by mutableFloatStateOf(1f)
        var initialAdvanced by mutableStateOf(false)
        var generation by mutableIntStateOf(0)
        var densityValue = 1f
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            densityValue = density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale),
                LocalInlineGlassDialog provides true, LocalReducedMotion provides true) {
                Box(Modifier.width(width).height(height)) {
                    key(generation) {
                        RepeatOptionsDialog(QueueSettings(advancedRepeat = if (initialAdvanced) AdvancedRepeat() else null), {}, {})
                    }
                }
            }
        } }
        for ((w, h, font) in listOf(Triple(360.dp, 640.dp, 1f), Triple(400.dp, 240.dp, 1.5f), Triple(220.dp, 320.dp, 2f))) {
            var envelope: IntSize? = null
            for (advanced in listOf(false, true)) {
                compose.runOnIdle { width = w; height = h; scale = font; initialAdvanced = advanced; generation++ }
                val dialog = compose.onNodeWithTag("repeat-options-dialog")
                val size = dialog.fetchSemanticsNode().size
                if (envelope == null) envelope = size else assertEquals("Initial mode does not choose the envelope", envelope, size)
                if (font == 1f) assertTrue("Compact surface wraps below the available window", size.height < h.value * densityValue)
                compose.onNodeWithText("Advanced").performScrollTo().performClick()
                compose.onNodeWithText("Repeat this song").performScrollTo().performClick()
                compose.onNodeWithText("Queue-end options apply after you turn off repeating the current song.")
                    .performScrollTo().assertIsDisplayed()
                assertEquals("Conditional explanation cannot resize the surface", envelope, dialog.fetchSemanticsNode().size)
                compose.onNodeWithText("Reset").performScrollTo().performClick()
                assertEquals(envelope, dialog.fetchSemanticsNode().size)
                compose.onNodeWithText("Simple").performScrollTo().performClick()
                compose.onNodeWithText("Repeat song").performScrollTo().performClick()
                assertEquals(envelope, dialog.fetchSemanticsNode().size)
                compose.onNodeWithText("Apply").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
            }
        }
        // Resize the existing draft back to normal: no remembered oversized high-water mark.
        compose.runOnIdle { width = 360.dp; height = 640.dp; scale = 1f }
        assertEquals(0f, scrollRange().maxValue(), 0f)
        compose.onNodeWithText("Repeat song").assertIsSelected()
        compose.onNodeWithText("Apply").assertIsDisplayed()
    }

    @Test fun cancelDiscardsBothModeDraftsAndReopeningRebuildsTheEnvelope() {
        var open by mutableStateOf(true)
        var submissions = 0
        val settings = QueueSettings(advancedRepeat = AdvancedRepeat(songEnd = SongEnd.NextPaused))
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalInlineGlassDialog provides true, LocalReducedMotion provides true) {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    if (open) RepeatOptionsDialog(settings, { open = false }, { submissions++ })
                }
            }
        } }
        val original = compose.onNodeWithTag("repeat-options-dialog").fetchSemanticsNode().size
        compose.onNodeWithText("Simple").performClick()
        compose.onNodeWithText("Repeat song").performClick()
        compose.onNodeWithText("Advanced").performClick()
        compose.onNodeWithText("Repeat this queue").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("repeat-options-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, submissions); open = true }
        assertEquals(original, compose.onNodeWithTag("repeat-options-dialog").fetchSemanticsNode().size)
        compose.onNodeWithText("Load next song and pause").assertIsSelected()
        assertEquals(0f, scrollRange().value(), 0f)
        compose.onNodeWithText("Repeat this queue").performScrollTo().assertIsNotSelected()
    }
}
