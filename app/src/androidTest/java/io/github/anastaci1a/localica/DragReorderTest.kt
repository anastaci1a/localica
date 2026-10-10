package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DragReorderTest {
    @get:Rule val compose = createComposeRule()
    private val selection = SessionSelection()
    private fun fixture(failWrites: Boolean = false, count: Int = 8, test: (PlaylistController, ManualPlaylist) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = PlaylistController(db.playlists(), scope)
        try {
            runBlocking {
                controller.state.first { !it.loading }
                val saved = CompletableDeferred<Boolean>()
                controller.create("Mix", (0 until count).map { Track("song-$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }) { saved.complete(it) }
                assertTrue(withTimeout(5000) { saved.await() })
            }
            val playlist = controller.state.value.playlists.single()
            if (failWrites) db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_move BEFORE INSERT ON playlist_entries BEGIN SELECT RAISE(ABORT, 'Injected failure'); END")
            compose.setContent { LocalicaTheme {
                val state by controller.state.collectAsState()
                CompositionLocalProvider(LocalSessionSelection provides selection) {
                    BrowserScreen(LibraryUiState(loading = false), state,
                        BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id)), {}, {}, {},
                        { _, _, _ -> fail("A held song must not play") }, null, controller, true, false, selectionVisible = false)
                }
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("media-${playlist.entries[4].id}").fetchSemanticsNodes().isNotEmpty() }
            test(controller, playlist)
        } finally { scope.cancel(); db.close() }
    }
    @Test fun disjointSelectionLiftsIntoStackAndMovesInSourceOrder() = fixture { controller, playlist ->
        val ids = playlist.entries.map { it.id }
        compose.onNodeWithTag("media-${ids[2]}").performTouchInput { longClick() }
        compose.onNodeWithTag("media-${ids[0]}").performClick()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithTag("media-${ids[0]}").assertIsSelected()
        val list = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot
        val from = compose.onNodeWithTag("media-${ids[2]}").fetchSemanticsNode().boundsInRoot.center - list.topLeft
        val to = compose.onNodeWithTag("media-${ids[4]}").fetchSemanticsNode().boundsInRoot.center - list.topLeft
        compose.onNodeWithTag("browser-list").performTouchInput {
            down(from); advanceEventTime(650); moveTo(from + Offset(0f, 35f)); advanceEventTime(32)
        }
        compose.onNodeWithContentDescription("Dragging 2 songs").assertExists()
        compose.onNodeWithTag("drop-gap").assertExists()
        compose.onNodeWithTag("browser-list").performTouchInput { moveTo(to); advanceEventTime(50); up() }
        compose.waitUntil(5000) { controller.state.value.playlists.single().entries.map { it.id } != ids }
        val result = controller.state.value.playlists.single().entries.map { it.id }
        assertEquals(8, result.toSet().size)
        assertEquals(result.indexOf(ids[0]) + 1, result.indexOf(ids[2]))
        assertTrue(result.indexOf(ids[0]) > 0)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(setOf(ids[0], ids[2]), selection.entries.map { it.row.id }.toSet()) }
    }
    @Test fun releasingOnForegroundCancelTargetRestoresOrder() = fixture { controller, playlist ->
        val id = playlist.entries[1].id
        compose.onNodeWithTag("media-$id").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, 70f)); advanceEventTime(32)
        }
        val list = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag("cancel-drag").fetchSemanticsNode().boundsInRoot.center - list.topLeft
        compose.onNodeWithTag("browser-list").performTouchInput { moveTo(target); advanceEventTime(32) }
        compose.onNodeWithTag("cancel-drag").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Cancel move"))
        compose.onNodeWithTag("browser-list").performTouchInput { moveTo(target - Offset(100f, 100f)); advanceEventTime(32) }
        compose.onNodeWithTag("cancel-drag").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Drag here to cancel"))
        compose.onNodeWithTag("browser-list").performTouchInput { moveTo(target); advanceEventTime(32); up() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        assertEquals(playlist, controller.state.value.playlists.single())
        compose.runOnIdle { assertEquals(listOf(id), selection.entries.map { it.row.id }) }
    }
    @Test fun canceledDragKeepsOrderAndSelection() = fixture { controller, playlist ->
        val id = playlist.entries[1].id
        compose.onNodeWithTag("media-$id").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, 90f)); advanceEventTime(32)
        }
        compose.onNodeWithTag("drag-stack").assertExists()
        compose.onNodeWithTag("browser-list").performTouchInput { cancel() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        assertEquals(playlist, controller.state.value.playlists.single())
        compose.runOnIdle { assertEquals(listOf(id), selection.entries.map { it.row.id }) }
    }
    @Test fun failedSaveRestoresOrderWithoutClearingSelection() = fixture(failWrites = true) { controller, playlist ->
        val id = playlist.entries[1].id
        compose.onNodeWithTag("media-$id").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, 240f)); advanceEventTime(50); up()
        }
        compose.waitUntil(5000) { controller.state.value.problem != null }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        assertEquals(playlist, controller.state.value.playlists.single())
        compose.runOnIdle { assertEquals(listOf(id), selection.entries.map { it.row.id }) }
    }
    @Test fun heldStackScrollsAtEdgeAndStillCancels() = fixture(count = 40) { controller, playlist ->
        val id = playlist.entries[1].id
        compose.onNodeWithTag("media-$id").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, 70f)); advanceEventTime(32)
        }
        val list = compose.onNodeWithTag("browser-list")
        list.performTouchInput { moveTo(Offset(width * 0.2f, height - 5f)); advanceEventTime(32) }
        compose.waitUntil(5000) { list.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value() > 8f }
        list.performTouchInput { cancel() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        assertEquals(playlist, controller.state.value.playlists.single())
    }
}
