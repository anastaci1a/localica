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
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * #748: every multi-select bar button that opens a glass popup opens it from its own bounds
 * and retracts into them, exactly like the more-actions menu. Frames are stepped with a
 * frozen clock so the first presented popup bounds are comparable with the button's.
 */
class PopupAnchorMorphTest {
    @get:Rule val compose = createComposeRule()

    private fun SemanticsNode.screenBounds(): Rect = layoutInfo.coordinates.let { Rect(it.positionOnScreen(), it.size.toSize()) }

    private fun runBar(button: String, popupText: String) {
        val rows = listOf(SongRow("a", Track("a", "Alpha", fileName = "a.mp3", relativePath = "a.mp3")))
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides false) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    SelectionActions(rows, setOf("a"), {}, null, null, PlaylistState(loading = false))
                }
            }
        } } }
        compose.waitForIdle()
        val home = compose.onNodeWithContentDescription(button).fetchSemanticsNode().screenBounds()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription(button).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            val opening = mutableListOf<Rect>()
            repeat(60) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val surfaces = compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes()
                if (surfaces.isNotEmpty() && opening.size < 3) opening += surfaces.single().screenBounds()
            }
            assertTrue("$button must present a popup surface", opening.isNotEmpty())
            val first = opening.first()
            val reach = maxOf(home.width, home.height)
            // The first presented frame belongs to the button: it overlaps the button's bounds
            // and has not yet grown into the settled popup.
            assertTrue("first popup frame $first must contain the centre of $button $home", first.contains(home.center))
            assertTrue("first popup frame $first must start at the size of $button $home",
                first.width <= home.width + reach && first.height <= home.height + reach)
            compose.mainClock.advanceTimeBy(1500); compose.waitForIdle()
            compose.onNodeWithText(popupText).assertExists()
            val settled = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().screenBounds()
            assertTrue("settled popup $settled must be larger than its origin $first", settled.width > first.width * 1.5f)
            pressSystemBack()
            val closing = mutableListOf<Rect>()
            repeat(40) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val surfaces = compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes()
                if (surfaces.isNotEmpty()) closing += surfaces.single().screenBounds()
            }
            compose.mainClock.advanceTimeBy(2000); compose.waitForIdle()
            compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(0)
            val end = closing.lastOrNull()
            assertTrue("dismissal must retract toward $button: last frame $end settled $settled home $home",
                end != null && end.width < settled.width * .75f &&
                (end.center - home.center).getDistance() < (settled.center - home.center).getDistance())
            compose.onNodeWithContentDescription(button).assertExists()
            assertEquals(home, compose.onNodeWithContentDescription(button).fetchSemanticsNode().screenBounds())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun moreActionsMenuExpandsFromItsBarButtonAndReturnsIntoIt() = runBar("More selection actions", "Select all")
    @Test fun addToPlaylistsPopupExpandsFromItsBarButtonAndReturnsIntoIt() = runBar("Add to playlist", "Add to playlists")
}
