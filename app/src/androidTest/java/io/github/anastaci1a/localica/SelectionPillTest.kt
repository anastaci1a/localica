package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** #534: the compact selection pill morphs between its states and minimizes while dragging. */
class SelectionPillTest {
    @get:Rule val compose = createComposeRule()

    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = (InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(context.getExternalFilesDir(null))).let { File(it, "selection-pill") }.apply { mkdirs() }
        if (name == "more-menu") {
            assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .takeScreenshot(File(root, "$name-api${android.os.Build.VERSION.SDK_INT}.png")))
            return
        }
        val image = compose.onNodeWithTag("pill-scene").captureToImage()
        File(root, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private val session = SessionSelection()
    private var dragging by mutableStateOf(false)
    private lateinit var backdrop: FrostedBackdrop
    private lateinit var panelMotion: FloatingSelectionPanelState

    private fun content(width: Dp = 380.dp, fontScale: Float = 1f, reduced: Boolean = false) {
        val tracks = (0..3).map { Track("t$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalReducedMotion provides reduced) {
            backdrop = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.size(width, 520.dp).testTag("pill-scene")) {
                Box(Modifier.matchParentSize().recordFrostedBackdrop(backdrop).background(Color(0xFFE9E3F5))) {
                    Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                        repeat(10) { Box(Modifier.fillMaxWidth().height(44.dp).padding(vertical = 4.dp).background(Color(0xFFB9A8E0))) }
                    }
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides backdrop) {
                panelMotion = rememberFloatingSelectionPanel(session.panelVisible, reduced)
                FloatingSelectionPanel(panelMotion, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
                    SessionSelectionPanel(session, null, null, PlaylistState(loading = false), dragging = dragging)
                }
                }
            }
        } } }
        compose.runOnIdle { session.select(null, tracks.take(3).map { SongRow(it.id, it) }) }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { backdrop.snapshot != null }
        compose.waitForIdle()
    }

    private fun pillWidth() = compose.onNodeWithTag("selection-pill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width

    private fun sampleWidths(change: () -> Unit): List<Float> {
        compose.mainClock.autoAdvance = false
        try {
            val widths = mutableListOf(pillWidth())
            compose.runOnIdle(change)
            repeat(70) { compose.mainClock.advanceTimeByFrame(); widths += pillWidth() }
            return widths
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun assertContinuousMorph(widths: List<Float>, shrinking: Boolean) {
        val total = kotlin.math.abs(widths.last() - widths.first())
        assertTrue("The pill changes size: $widths", total > 40f)
        widths.zipWithNext().forEach { (a, b) ->
            assertTrue("Monotone morph: $widths", if (shrinking) b <= a + .5f else b >= a - .5f)
            assertTrue("No single-frame jump: $widths", kotlin.math.abs(b - a) <= total * .4f + 1f)
        }
        val steps = widths.zipWithNext { a, b -> kotlin.math.abs(b - a) }.filter { it > .5f }
        assertTrue("The glass spring has visible intermediate frames: $steps", steps.size > 3)
        assertTrue("The glass spring settles after its peak: $steps", steps.last() <= steps.max() * .2f + .5f)
    }

    @Test fun compactPillOffersFrequentActionsAndMorphsIntoItsMenu() {
        content()
        compose.onNodeWithText("3 selected").assertIsDisplayed()
        listOf("Close selection", "Play next", "Add to playlist", "More selection actions", "Collapse selection")
            .forEach { compose.onNodeWithContentDescription(it).assertExists() }
        save("expanded")
        compose.onNodeWithContentDescription("More selection actions").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Select all").fetchSemanticsNodes().isNotEmpty() }
        listOf("Play now", "Add to queue", "New queue", "Select all").forEach { compose.onNodeWithText(it, substring = false).assertExists() }
        compose.waitForIdle()
        save("more-menu")
        compose.onNodeWithText("Select all", substring = false).performClick()
        compose.awaitNoGlassMenu()
    }

    @Test fun collapseMorphsToTheMinimizedPillAndBack() {
        content()
        val widths = sampleWidths { session.collapsed = true }
        assertContinuousMorph(widths, shrinking = true)
        compose.onNodeWithTag("selection-minimized", useUnmergedTree = true).assertExists()
        save("minimized")
        val back = sampleWidths { session.collapsed = false }
        assertContinuousMorph(back, shrinking = false)
        compose.onNodeWithText("3 selected").assertIsDisplayed()
    }

    @Test fun draggingMinimizesThenCancelRestoresAndReleaseDisappears() {
        content()
        // Drag starts: the pill stays, morphing down instead of hiding.
        val down = sampleWidths { dragging = true }
        assertContinuousMorph(down, shrinking = true)
        compose.onNodeWithTag("selection-minimized", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Expand selection").assertDoesNotExist()
        save("dragging")
        // Cancelled with X: selection remains, and the pill morphs back out.
        val up = sampleWidths { dragging = false }
        assertContinuousMorph(up, shrinking = false)
        // A completed drop ends the selection: the minimized pill eases away at the end.
        compose.runOnIdle { dragging = true }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { session.clear(); dragging = false }
            compose.mainClock.advanceTimeBy(64)
            // The unmerged debug tree can retain cleared descendants; the normal
            // semantics tree reflects what accessibility can activate.
            compose.onNodeWithTag("selection-pill").assertDoesNotExist()
            val departing = compose.onNodeWithTag("pill-scene").captureToImage().toPixelMap()
            save("exiting")
            compose.mainClock.advanceTimeBy(2000)
            val absent = compose.onNodeWithTag("pill-scene").captureToImage().toPixelMap()
            var changed = 0
            for (y in 0 until absent.height) for (x in 0 until absent.width)
                if (absent[x, y] != departing[x, y]) changed++
            assertTrue("The inert outgoing panel remains drawn during its exit", changed > 20)
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithTag("selection-pill", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { dragging = false }
    }

    @Test fun outgoingControlsLoseInputImmediatelyAndInterruptedMorphReverses() {
        content()
        compose.runOnIdle { dragging = true }
        compose.waitForIdle()
        val compact = pillWidth()
        compose.runOnIdle { dragging = false }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            val full = pillWidth()
            compose.runOnIdle { dragging = true }
            compose.mainClock.advanceTimeBy(160)
            val partial = pillWidth()
            val span = full - compact
            assertTrue("Interrupt after meaningful visible travel: full=$full compact=$compact partial=$partial", full - partial > span * .2f)
            compose.onNodeWithContentDescription("Close selection").assertDoesNotExist()
            compose.onNodeWithContentDescription("More selection actions").assertDoesNotExist()
            compose.onNodeWithTag("selection-minimized", useUnmergedTree = true).assertHasNoClickAction()
            compose.runOnIdle { dragging = false }
            compose.mainClock.advanceTimeByFrame()
            val reversed = pillWidth()
            assertTrue("A reversal continues from the displayed width: full=$full compact=$compact partial=$partial reversed=$reversed",
                kotlin.math.abs(reversed - partial) < span * .2f + 1f)
            compose.onNodeWithContentDescription("Close selection").assertExists()
            compose.mainClock.advanceTimeBy(2000)
            assertEquals(full, pillWidth(), 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun narrowScreenAndLargeFontKeepTheCountAndEveryActionReachable() {
        content(width = 320.dp, fontScale = 2f)
        compose.onNodeWithText("3 selected").assertIsDisplayed()
        val scene = compose.onNodeWithTag("pill-scene").fetchSemanticsNode().boundsInRoot
        listOf("Close selection", "Play next", "Add to playlist", "More selection actions", "Collapse selection").forEach {
            val bounds = compose.onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$it remains inside the scene: $bounds / $scene", scene.contains(bounds.topLeft) && scene.contains(bounds.bottomRight))
        }
        save("large-font-narrow")
    }

    @Test fun reducedMotionSnapsBothFacesAndRevokesHiddenInput() {
        content(reduced = true)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { session.collapsed = true }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithContentDescription("Close selection").assertDoesNotExist()
            val compact = pillWidth()
            compose.mainClock.advanceTimeBy(500)
            assertEquals(compact, pillWidth(), 0f)
            compose.runOnIdle { session.collapsed = false }
            compose.mainClock.advanceTimeByFrame()
            assertTrue(pillWidth() > compact)
            compose.onNodeWithContentDescription("Close selection").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun completingADragRetainsTheMinimizedFootprintDuringLargeFontExit() {
        content(width = 320.dp, fontScale = 2f)
        compose.runOnIdle { dragging = true }
        compose.waitForIdle()
        val minimizedHeight = panelMotion.height
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { session.clear(); dragging = false }
            var previous = panelMotion.clearance.value
            repeat(30) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    assertEquals("Hidden expanded content cannot grow the exiting footprint", minimizedHeight, panelMotion.height)
                    assertTrue("Exit clearance only decreases", panelMotion.clearance.value <= previous + .1f)
                    previous = panelMotion.clearance.value
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
