package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
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
        val image = compose.onNodeWithTag("pill-scene").captureToImage()
        File(root, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private val session = SessionSelection()
    private var dragging by mutableStateOf(false)

    private fun content() {
        val tracks = (0..3).map { Track("t$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        compose.setContent { LocalicaTheme {
            Box(Modifier.size(380.dp, 520.dp).background(Color(0xFFE9E3F5)).testTag("pill-scene")) {
                Column(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    repeat(6) { Box(Modifier.fillMaxWidth().height(44.dp).padding(vertical = 4.dp).background(Color(0xFFB9A8E0))) }
                }
                SelectionPanelVisibility(session.panelVisible, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
                    SessionSelectionPanel(session, null, null, PlaylistState(loading = false), dragging = dragging)
                }
            }
        } }
        compose.runOnIdle { session.select(null, tracks.take(3).map { SongRow(it.id, it) }) }
        compose.waitForIdle()
    }

    private fun pillWidth() = compose.onNodeWithTag("selection-pill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width

    private fun sampleWidths(change: () -> Unit): List<Float> {
        compose.mainClock.autoAdvance = false
        try {
            val widths = mutableListOf(pillWidth())
            compose.runOnIdle(change)
            repeat(22) { compose.mainClock.advanceTimeByFrame(); widths += pillWidth() }
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
        assertTrue("Ease-out: the morph starts quickly and settles: $steps", steps.first() >= steps.last())
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
        compose.selectionMenuAction("Select all")
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
            compose.runOnIdle { session.clear() }
            var frames = 0
            while (compose.onAllNodesWithTag("selection-pill", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() && frames < 40) {
                compose.mainClock.advanceTimeByFrame(); frames++
            }
            assertTrue("The pill leaves over several frames, not at once: $frames", frames in 3..30)
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithTag("selection-pill", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { dragging = false }
    }
}
