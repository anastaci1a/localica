package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ListEndContextsTest {
    @get:Rule val compose = localicaComposeRule()
    private val tracks = List(30) { Track("end:$it", "Song ${it.toString().padStart(2, '0')}",
        album = "Clearance", artist = "Artist", fileName = "$it.mp3", relativePath = "$it.mp3") }

    @Test fun songListEndClearsReturnArrow() = verify()
    @Test fun galleryEndClearsReturnArrow() = verify(gallery = true)
    @Test fun searchResultsEndClearsReturnArrow() = verify(search = true)
    @Test fun queueEndClearsDirectionalReturnArrow() = verify(queue = true)
    @Test fun selectionEndClearsPanelAsItsMeasuredHeightChanges() = verify(selection = true)

    private fun verify(gallery: Boolean = false, search: Boolean = false, queue: Boolean = false, selection: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
        val route = if (queue) BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, "end-queue"))
            else BrowseRoute(MainTab.Songs, search = search, text = if (search) "Song" else "", queryCommitted = search)
        val preferences = BrowsePreferences(context)
        preferences.write(route, preferences.read(route).copy(presentation = if (gallery) Presentation.Standard else Presentation.List,
            itemsPerRow = 3, quickSearch = false), false)
        val queueState = QueueUiState(QueueLibrary(listOf(ListeningQueue(id = "end-queue", name = "End",
            entries = tracks.map { QueueEntry(it.id, it) }, currentEntryId = tracks.first().id)), "end-queue"), loading = false)
        val session = SessionSelection()
        if (selection) session.select(null, listOf(SongRow(tracks.first().id, tracks.first())))
        var panelHeight by mutableStateOf(100.dp)
        compose.setContent { LocalicaTheme {
            val panel = rememberFloatingSelectionPanel(selection, true)
            CompositionLocalProvider(LocalBrowseObscuredInset provides (100.dp + panel.clearance), LocalSessionSelection provides session) {
                Box(Modifier.fillMaxSize()) {
                    BrowserScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaylistState(loading = false), route,
                        {}, {}, {}, { _, _, _ -> }, null, null, true, false, reducedMotion = true,
                        queueState = queueState, playingEntryId = if (queue) tracks.first().id else null)
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(100.dp).testTag("end-lower-chrome"))
                    FloatingSelectionPanel(panel, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)) {
                        Box(Modifier.fillMaxWidth().height(panelHeight).testTag("end-selection"))
                    }
                }
            }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
        fun atEnd() {
            val list = compose.onNodeWithTag("browser-list")
            list.performScrollToIndex(tracks.size)
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
            compose.waitForIdle()
            val last = compose.onNodeWithTag("media-end:29").fetchSemanticsNode().boundsInRoot
            val controls = compose.onAllNodes(hasTestTag("floating-return-control") or hasTestTag("end-selection") or hasTestTag("end-lower-chrome"))
                .fetchSemanticsNodes()
            assertTrue(controls.isNotEmpty())
            for (control in controls) assertTrue("Last row $last clears ${control.boundsInRoot}", last.bottom <= control.boundsInRoot.top)
            compose.onNodeWithTag("browse-scrollbar").assertExists()
        }
        atEnd()
        if (selection) {
            compose.runOnIdle { panelHeight = 200.dp }
            atEnd()
        }
    }
}
