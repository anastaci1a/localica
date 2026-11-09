package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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

/** Real browse owners retain the same constant inset in every projection. */
class SelectionInsetContextsTest {
    @get:Rule val compose = localicaComposeRule()
    private val tracks = List(30) { Track("inset:$it", "Song ${it.toString().padStart(2, '0')}",
        album = "Inset", artist = "Artist", fileName = "$it.mp3", relativePath = "$it.mp3") }

    @Test fun list() = verify()
    @Test fun gallery() = verify(presentation = Presentation.Standard)
    @Test fun oneColumnGallery() = verify(presentation = Presentation.Large)
    @Test fun search() = verify(search = true)
    @Test fun popupSearch() = verify(search = true, popup = true)
    @Test fun queue() = verify(queue = true)
    @Test fun albumSubview() = verify(album = true)

    private fun verify(presentation: Presentation = Presentation.List, search: Boolean = false,
        popup: Boolean = false, queue: Boolean = false, album: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
        val library = LibrarySnapshot(tracks = tracks)
        val albumRef = if (album) MediaRef(MediaKind.Album,
            LibraryViews.groups(library, SongListView.Albums, LibraryQuery()).single().id) else null
        val route = BrowseRoute(if (queue) MainTab.Queues else MainTab.Songs,
            collection = if (queue) MediaRef(MediaKind.Queue, "inset-queue") else albumRef,
            search = search, text = if (search) "Song" else "", queryCommitted = search)
        val preferences = BrowsePreferences(context)
        preferences.write(route, preferences.read(route).copy(presentation = presentation, itemsPerRow = if (presentation == Presentation.Large) 1 else 3, quickSearch = false), false)
        val state = QueueUiState(QueueLibrary(listOf(ListeningQueue(id = "inset-queue", name = "Inset",
            entries = tracks.map { QueueEntry(it.id, it) }, currentEntryId = tracks.first().id)), "inset-queue"), loading = false)
        val session = SessionSelection()
        lateinit var panel: FloatingSelectionPanelState
        var inset = 0f
        var pixelDensity = 1f
        compose.setContent { LocalicaTheme {
            panel = rememberFloatingSelectionPanel(session.panelVisible, true)
            val density = LocalDensity.current
            pixelDensity = density.density
            SideEffect { inset = with(density) { panel.clearance.toPx() } }
            CompositionLocalProvider(LocalBrowseObscuredInset provides (if (popup) 0.dp else 40.dp + panel.clearance),
                LocalBrowseSelectionPanel provides (if (popup) null else panel), LocalSessionSelection provides session) {
                Box(Modifier.fillMaxSize()) {
                    BrowserScreen(LibraryUiState(library, loading = false), PlaylistState(loading = false), route,
                        {}, {}, {}, { _, _, _ -> }, null, null, true, false, reducedMotion = true,
                        queueState = state, popupDismiss = if (popup) ({}) else null)
                    if (!popup) FloatingSelectionPanel(panel, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)) {
                        Box(Modifier.height(80.dp).fillMaxWidth())
                    }
                }
            }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onNodeWithTag("browser-list")
        list.performScrollToIndex(30)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
        compose.waitForIdle()
        val last = compose.onNodeWithTag("media-inset:29")
        val before = last.fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { session.select(route.collection, listOf(SongRow(tracks.first().id, tracks.first()))) }
        compose.waitForIdle()
        val expected = if (popup) compose.onNodeWithTag("selection-pill").fetchSemanticsNode().boundsInRoot.height + 20f * pixelDensity else inset
        assertEquals("Real host moves by the complete panel inset", expected,
            before - last.fetchSemanticsNode().boundsInRoot.top, 1.1f)
        compose.runOnIdle { session.clear() }
        compose.waitForIdle()
        assertEquals("Real host removes occupied tail", before, last.fetchSemanticsNode().boundsInRoot.top, 1.1f)
    }
}
