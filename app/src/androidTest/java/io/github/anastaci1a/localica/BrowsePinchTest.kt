package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BrowsePinchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneStepUntilEveryFingerLiftsAndDisabledViewsIgnorePinches() {
        val steps = mutableListOf<Boolean>()
        var enabled by mutableStateOf(true)
        compose.setContent { Box(Modifier.fillMaxSize().pinchToChangeView(enabled) { zoomIn, _ -> steps += zoomIn }.testTag("pinch")) }
        val target = compose.onNodeWithTag("pinch")
        target.performTouchInput {
            down(0, Offset(centerX - 50, centerY)); down(1, Offset(centerX + 50, centerY))
            moveTo(0, Offset(centerX - 100, centerY)); moveTo(1, Offset(centerX + 100, centerY))
            up(1)
            down(1, Offset(centerX + 50, centerY))
            moveTo(0, Offset(centerX - 150, centerY)); moveTo(1, Offset(centerX + 150, centerY))
            up(0); up(1)
        }
        assertEquals(listOf(true), steps)
        target.performTouchInput { pinch(false) }
        assertEquals(listOf(true, false), steps)
        compose.runOnIdle { enabled = false }
        target.performTouchInput { pinch(true) }
        assertEquals(2, steps.size)
    }

    @Test fun deepListKeepsItsAnchorAndSelectionAcrossAllSixViews() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        val session = SessionSelection()
        val tracks = (0..119).map { index ->
            Track("pinch:$index", "Song ${index.toString().padStart(3, '0')}", fileName = "$index.mp3", relativePath = "$index.mp3")
        }
        prefs.write(route, BrowseStyle(), false)
        try {
            compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalSessionSelection provides session) {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false)
            } } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
            val list = compose.onNodeWithTag("browser-list")
            list.performScrollToIndex(57)
            val item = compose.onNodeWithTag("media-pinch:60")
            item.performTouchInput { longClick() }
            val original = item.fetchSemanticsNode().boundsInRoot
            val viewport = list.fetchSemanticsNode().boundsInRoot
            list.performTouchInput { pinch(true, original.center.y - viewport.top) }
            waitForView("Gallery, 5 columns")
            assertEquals("Keep the item near the pinch at its prior vertical position", original.top,
                item.fetchSemanticsNode().boundsInRoot.top, 4f)
            for (columns in 4 downTo 1) {
                list.performTouchInput { pinch(true) }; waitForView(if (columns == 1) "Gallery, 1 column" else "Gallery, $columns columns")
                assertEquals(columns, prefs.read(route).itemsPerRow)
            }
            for (columns in 2..5) {
                list.performTouchInput { pinch(false) }; waitForView("Gallery, $columns columns")
            }
            list.performTouchInput { pinch(false) }; waitForView("List")
            assertEquals(Presentation.List, prefs.read(route).presentation)
            assertEquals(listOf("pinch:60"), session.entries.map { it.row.track.id })
        } finally { prefs.write(route, old, false) }
    }

    @Test fun twoFingerHoldInMutablePlaylistCannotBeginSongSelectionOrReordering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tracks = (0..29).map { Track("held:$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Pinch"), tracks)
        val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id))
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        val session = SessionSelection()
        prefs.write(route, BrowseStyle(sort = TrackSort.Manual), false)
        try {
            compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalSessionSelection provides session) {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(listOf(playlist), false), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false)
            } } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Song 0").fetchSemanticsNodes().isNotEmpty() }
            val item = compose.onNodeWithTag("media-${playlist.entries.first().id}").fetchSemanticsNode().boundsInRoot
            val list = compose.onNodeWithTag("browser-list")
            val viewport = list.fetchSemanticsNode().boundsInRoot
            val y = item.center.y - viewport.top
            list.performTouchInput {
                down(0, Offset(centerX - 50, y)); down(1, Offset(centerX + 50, y))
            }
            compose.mainClock.advanceTimeBy(800)
            compose.runOnIdle { assertFalse("Pinching must not also long-press a song", session.active) }
            list.performTouchInput {
                moveTo(0, Offset(centerX - 100, y)); moveTo(1, Offset(centerX + 100, y)); up(0); up(1)
            }
            waitForView("Gallery, 5 columns")
            assertFalse(session.active)
            compose.onNodeWithTag("drop-gap").assertDoesNotExist()
        } finally { prefs.write(route, old, false) }
    }

    private fun waitForView(label: String) {
        compose.waitUntil(5000) { compose.onNodeWithTag("browser-list").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == label }
        compose.waitForIdle()
    }

    private fun TouchInjectionScope.pinch(zoomIn: Boolean, y: Float = centerY) {
        val from = if (zoomIn) 50f else 110f
        val to = if (zoomIn) 110f else 50f
        down(0, Offset(centerX - from, y)); down(1, Offset(centerX + from, y))
        repeat(8) { n ->
            val distance = from + (to - from) * (n + 1) / 8f
            moveTo(0, Offset(centerX - distance, y), delayMillis = 24)
            moveTo(1, Offset(centerX + distance, y), delayMillis = 24)
        }
        up(0); up(1)
    }
}
