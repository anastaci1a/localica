package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.toSize
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * #748: every multi-select bar button that opens a glass popup opens it from its own bounds
 * and retracts into them, exactly like the more-actions menu. A frozen clock steps every
 * frame from the activation to the settled popup and from Back to the removed surface. The
 * test retains a per-frame geometry trace (layout bounds of the popup surface and of the
 * button, in screen pixels) and a native screenshot of the first visible and last
 * retracting frames for both buttons. The trace is layout evidence read after each frame;
 * the screenshots are the native presentation of the same frames.
 */
class PopupAnchorMorphTest {
    @get:Rule val compose = createComposeRule()

    private fun SemanticsNode.screenBounds(): Rect = layoutInfo.coordinates.let { Rect(it.positionOnScreen(), it.size.toSize()) }

    private fun surfaceBounds(): Rect? =
        compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().singleOrNull()?.screenBounds()

    /** Every edge of [actual] lies within [fraction] of the button's larger side from the button's edge. */
    private fun nearButton(actual: Rect, home: Rect, fraction: Float): Boolean {
        val tolerance = maxOf(home.width, home.height) * fraction
        return kotlin.math.abs(actual.left - home.left) <= tolerance && kotlin.math.abs(actual.top - home.top) <= tolerance &&
            kotlin.math.abs(actual.right - home.right) <= tolerance && kotlin.math.abs(actual.bottom - home.bottom) <= tolerance
    }

    private fun runBar(button: String, popupText: String, evidence: String) {
        val rows = listOf(SongRow("a", Track("a", "Alpha", fileName = "a.mp3", relativePath = "a.mp3")))
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides false) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    SelectionActions(rows, setOf("a"), {}, null, null, PlaylistState(loading = false))
                }
            }
        } } }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = nativeEvidenceDirectory("popup-anchor-748-$evidence", instrumentation.targetContext.getExternalFilesDir(null))
        val trace = StringBuilder("phase,frame,left,top,right,bottom\n")
        fun record(phase: String, frame: Int, rect: Rect) { trace.append("$phase,$frame,${rect.left},${rect.top},${rect.right},${rect.bottom}\n") }
        fun screenshot(name: String) {
            val image = instrumentation.uiAutomation.takeScreenshot() ?: return
            try { File(directory, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { image.recycle() }
        }
        val home = compose.onNodeWithContentDescription(button).fetchSemanticsNode().screenBounds()
        record("button", 0, home)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription(button).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            var first: Rect? = null
            repeat(60) { frame ->
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val surface = surfaceBounds()
                if (surface != null) {
                    record("open", frame, surface)
                    if (first == null) { first = surface; screenshot("first-visible-frame") }
                }
            }
            val start = requireNotNull(first) { "$button must present a popup surface" }
            assertTrue("first visible frame $start must start at the bounds of $button $home", nearButton(start, home, .25f))
            compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
            compose.onNodeWithText(popupText).assertExists()
            val settled = requireNotNull(surfaceBounds())
            record("settled", 0, settled)
            assertTrue("settled popup $settled must be larger than its origin $start", settled.width > start.width * 1.5f)
            pressSystemBack()
            var last: Rect? = null
            repeat(60) { frame ->
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val surface = surfaceBounds()
                if (surface != null) { last = surface; record("close", frame, surface); if (frame == 0) screenshot("first-retract-frame") }
            }
            screenshot("last-retract-frame")
            compose.mainClock.advanceTimeBy(2000); compose.waitForIdle()
            compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
            val end = requireNotNull(last) { "dismissal of $button must show a retracting surface" }
            assertTrue("last retracting frame $end must end at the bounds of $button $home (settled $settled)", nearButton(end, home, .5f))
            compose.onNodeWithContentDescription(button).assertExists()
            assertEquals(home, compose.onNodeWithContentDescription(button).fetchSemanticsNode().screenBounds())
        } finally {
            compose.mainClock.autoAdvance = true
            File(directory, "trace.csv").writeText(trace.toString())
        }
    }

    @Test fun moreActionsMenuExpandsFromItsBarButtonAndReturnsIntoIt() = runBar("More selection actions", "Select all", "more")
    @Test fun addToPlaylistsPopupExpandsFromItsBarButtonAndReturnsIntoIt() = runBar("Add to playlist", "Add to playlists", "add")
}
