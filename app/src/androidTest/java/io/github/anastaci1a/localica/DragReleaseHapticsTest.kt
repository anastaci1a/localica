package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowsePreferences
import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.browse.Presentation
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * #736: every finger lift after a drag gives ControlHaptic.Strong through the shared drag
 * gesture, in every song-list owner (playlist list and gallery, Queue page, multi-select
 * stacks). A system cancel stays silent. The song row's own long-press keeps the pickup.
 */
class DragReleaseHapticsTest {
    @get:Rule val compose = createComposeRule()
    private val selection = SessionSelection()
    private val pulses = mutableListOf<ControlHaptic>()
    private val tracks = (0 until 8).map { Track("content://haptics/$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3", id = "haptics-$it") }

    private fun fixture(kind: MediaKind, gallery: Boolean = false, test: (List<String>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        val library = LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false)
        val host = SongDragHost()
        val queueEntries = tracks.mapIndexed { i, track -> QueueEntry("haptics-entry-$i", track) }
        val queue = ListeningQueue(id = "haptics-queue", name = "Haptics", entries = queueEntries, currentEntryId = queueEntries[0].id)
        val playlist = ManualPlaylist(id = "haptics-playlist", name = "Haptics", entries = tracks.mapIndexed { i, track -> SongRow("haptics-row-$i", track) })
        val route = if (kind == MediaKind.Queue) BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id), title = "Haptics", key = "haptics-queue")
            else BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id), key = "haptics-playlist")
        if (gallery) BrowsePreferences(context).write(route,
            BrowseStyle(sort = TrackSort.Manual, presentation = Presentation.Standard, itemsPerRow = 3), true)
        val ids = if (kind == MediaKind.Queue) queueEntries.map { it.id } else playlist.entries.map { it.id }
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalSessionSelection provides selection, LocalSongDragHost provides host,
                    LocalControlHaptics provides ControlHaptics { pulses += it }) {
                    BrowserScreen(library, PlaylistState(listOf(playlist), loading = false), route, {}, {}, {}, { _, _, _ -> }, null, null,
                        true, false, queueState = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false),
                        playingEntryId = queueEntries[0].id.takeIf { kind == MediaKind.Queue })
                    ForegroundSongDragOverlay(host)
                }
            }
        } }
        try {
            var arranged = false
            compose.waitUntil(5000) {
                if (!arranged && compose.onAllNodesWithTag("media-${ids[0]}").fetchSemanticsNodes().isNotEmpty()) {
                    compose.onNodeWithTag("browser-list").performScrollToKey(ids[0]); arranged = true
                }
                compose.onAllNodesWithTag("media-${ids[4]}").fetchSemanticsNodes().isNotEmpty()
            }
            test(ids)
        } finally { compose.runOnIdle { selection.clear() } }
    }

    private fun local(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center -
        compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot.topLeft

    private fun releaseThenCancel(ids: List<String>, multiple: Boolean) {
        if (multiple) {
            compose.onNodeWithTag("media-${ids[1]}").performTouchInput { longClick() }
            compose.onNodeWithTag("media-${ids[2]}").performClick()
            compose.waitForIdle()
        }
        val from = local("media-${ids[2]}")
        val to = local("media-${ids[4]}")
        compose.onNodeWithTag("browser-list").performTouchInput { down(from); advanceEventTime(650); moveTo(from + Offset(0f, 35f)); advanceEventTime(32) }
        compose.onNodeWithTag("drag-stack").assertExists()
        compose.onNodeWithTag("browser-list").performTouchInput { moveTo(to); advanceEventTime(32) }
        compose.runOnIdle { pulses.clear() }
        compose.onNodeWithTag("browser-list").performTouchInput { up() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals("Finger lift after a drag", listOf(ControlHaptic.Strong), pulses) }
        compose.runOnIdle { selection.clear() }
        compose.waitForIdle()
        val again = local("media-${ids[3]}")
        compose.onNodeWithTag("browser-list").performTouchInput { down(again); advanceEventTime(650); moveTo(again + Offset(0f, 35f)); advanceEventTime(32) }
        compose.onNodeWithTag("drag-stack").assertExists()
        compose.onNodeWithTag("browser-list").performTouchInput { moveBy(Offset(0f, 60f)); advanceEventTime(32) }
        compose.runOnIdle { pulses.clear() }
        compose.onNodeWithTag("browser-list").performTouchInput { cancel() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals("A system cancel stays silent", emptyList<ControlHaptic>(), pulses) }
    }

    @Test fun playlistListReleaseIsStrongAndCancelSilent() = fixture(MediaKind.Playlist) { releaseThenCancel(it, false) }
    @Test fun playlistGalleryReleaseIsStrongAndCancelSilent() = fixture(MediaKind.Playlist, gallery = true) { releaseThenCancel(it, false) }
    @Test fun queuePageReleaseIsStrongAndCancelSilent() = fixture(MediaKind.Queue) { releaseThenCancel(it, false) }
    @Test fun multiSelectStackReleaseIsStrongAndCancelSilent() = fixture(MediaKind.Queue) { releaseThenCancel(it, true) }
}
