package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DragReorderTest {
    @get:Rule val compose = createComposeRule()
    private fun saveDragFrame(name: String) {
        val args = InstrumentationRegistry.getArguments()
        val root = args.getString("additionalTestOutputDir")?.let { java.io.File(it) }
            ?: requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val output = java.io.File(root, "drag-stack-557").apply { mkdirs() }
        java.io.File(output, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private val selection = SessionSelection()
    private var interactionEnabled by mutableStateOf(true)
    private var checkpoint by mutableStateOf(QueueUiState(loading = false), neverEqualPolicy())
    private var routeGeneration by mutableIntStateOf(0)
    private class QueryGate : CoroutineDispatcher() {
        @Volatile var blocked = false
        val pending = java.util.concurrent.LinkedBlockingQueue<Pair<kotlin.coroutines.CoroutineContext, Runnable>>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (blocked) pending.add(context to block) else Dispatchers.Default.dispatch(context, block)
        }
        fun release() {
            blocked = false
            while (true) {
                val task = pending.poll() ?: break
                Dispatchers.Default.dispatch(task.first, task.second)
            }
        }
    }
    private val queries = QueryGate()
    private var fixtureDragScene: FrostedBackdrop? = null
    private val fixtureBackdrops = mutableMapOf<FrostedBackdrop, BrowseBackdropScene>()
    private lateinit var fixtureDragHost: SongDragHost
    private lateinit var fixtureFloatingPanel: FloatingSelectionPanelState
    private lateinit var backDispatcher: androidx.activity.OnBackPressedDispatcher
    private fun fixture(gallery: Boolean = false, failWrites: Boolean = false, count: Int = 8, foregroundFooter: Boolean = false, filtered: Boolean = false, panelClearance: Boolean = false, test: (PlaylistController, ManualPlaylist) -> Unit) {
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
            if (gallery) io.github.anastaci1a.localica.browse.BrowsePreferences(context).write(
                BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id)),
                io.github.anastaci1a.localica.browse.BrowseStyle(sort = TrackSort.Manual,
                    presentation = io.github.anastaci1a.localica.browse.Presentation.Standard, itemsPerRow = 3), true)
            if (failWrites) db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_move BEFORE INSERT ON playlist_entries BEGIN SELECT RAISE(ABORT, 'Injected failure'); END")
            // Incidental selection/overlay recomposition must retain the source revision,
            // as the real screen's collected LibraryUiState does until library data changes.
            val library = LibraryUiState(loading = false)
            compose.setContent { LocalicaTheme {
                backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                val state by controller.state.collectAsState()
                val host = remember { SongDragHost() }
                val route = remember(playlist.id, routeGeneration) { BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id), text = if (filtered) "Song" else "", key = "drag-fixture-$routeGeneration") }
                val floatingPanel = rememberFloatingSelectionPanel(selection.panelVisible, false)
                fixtureDragHost = host
                fixtureFloatingPanel = floatingPanel
                val chrome = if (foregroundFooter) 80.dp else 0.dp
                CompositionLocalProvider(LocalSongDragSceneObserver provides { fixtureDragScene = it }) {
                SongDragScene(host, Modifier.fillMaxSize().testTag("fixture-viewport")) {
                Box(Modifier.fillMaxSize().padding(bottom = if (foregroundFooter && !panelClearance) chrome else 0.dp).clipToBounds()) {
                CompositionLocalProvider(LocalSessionSelection provides selection, LocalSongDragHost provides host,
                    LocalBrowseBackdropObserver provides { scene, attached ->
                        if (attached) fixtureBackdrops[scene.source] = scene else fixtureBackdrops.remove(scene.source)
                    },
                    LocalBrowseObscuredInset provides if (panelClearance) chrome + floatingPanel.clearance else 0.dp) {
                    BrowserScreen(library, state,
                        route, {}, {}, {},
                        { _, _, _ -> fail("A held song must not play") }, null, controller, interactionEnabled, false, queueState = checkpoint, queryDispatcher = queries)
                } }
                if (foregroundFooter) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(80.dp).background(Color.Magenta).testTag("fixture-footer"))
                if (panelClearance) {
                    FloatingSelectionPanel(floatingPanel, Modifier.align(Alignment.BottomCenter).padding(bottom = chrome)) {
                        Box(Modifier.fillMaxWidth().testTag("fixture-selection-panel")) {
                            SessionSelectionPanel(selection, null, controller, state, dragging = host.state?.drag != null)
                        }
                    }
                    Box(Modifier.align(Alignment.BottomEnd).padding(bottom = chrome + floatingPanel.clearance)
                        .size(48.dp).testTag("fixture-lifted-control"))
                } else SelectionPanelVisibility(selection.panelVisible && host.state?.drag == null,
                    Modifier.align(Alignment.BottomCenter)) {
                    Box(Modifier.size(1.dp).testTag("fixture-selection-panel"))
                }
                } }
            } }
            var arranged = false
            compose.waitUntil(5000) {
                // The playlist hero is real; arrange the song viewport once before
                // measuring drag geometry, rather than assuming five rows start onscreen.
                var sourceReady = false
                compose.runOnUiThread {
                    sourceReady = selection.visible.any { it.id == playlist.entries.first().id }
                }
                if (!arranged && sourceReady) {
                    compose.onNodeWithTag("browser-list").performScrollToKey(playlist.entries.first().id)
                    arranged = true
                }
                compose.onAllNodesWithTag("media-${playlist.entries[4].id}").fetchSemanticsNodes().isNotEmpty()
            }
            test(controller, playlist)
        } finally { queries.release(); scope.cancel(); db.close() }
    }
    private fun assertReleasePresentation(filtered: Boolean) = fixture(filtered = filtered) { _, playlist ->
        val id = playlist.entries[1].id
        val row = compose.onNodeWithTag("media-$id")
        val originalBounds = row.fetchSemanticsNode().boundsInRoot
        var held = false
        try {
            row.performTouchInput { down(center); held = true; advanceEventTime(650); moveTo(center) }
            compose.runOnIdle {
                assertEquals(listOf(id), selection.entries.map { it.row.id })
                assertTrue(selection.active)
                assertFalse("A newly recognized hold must not expose the panel", selection.panelVisible)
            }
            assertEquals("Recognizing selection must not move its held row", originalBounds, row.fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("fixture-selection-panel").assertDoesNotExist()
            row.performTouchInput { up(); held = false }
            compose.runOnIdle { assertTrue(selection.panelVisible) }
            compose.onNodeWithTag("fixture-selection-panel").assertExists()
            // A pre-existing (including collapsed) panel retains its presentation during another hold.
            compose.runOnIdle { selection.collapsed = true }
            row.performTouchInput { down(center); held = true; advanceEventTime(650); moveTo(center) }
            compose.runOnIdle { assertTrue(selection.panelVisible); assertTrue(selection.collapsed) }
            row.performTouchInput { up(); held = false }
        } finally {
            if (held) {
                compose.mainClock.autoAdvance = false
                try { row.performTouchInput { cancel() } } finally { compose.mainClock.autoAdvance = true }
            }
        }
    }
    @Test fun mutableSongPanelWaitsForPointerRelease() = assertReleasePresentation(false)
    @Test fun filteredSongPanelWaitsForPointerReleaseWithoutReordering() = assertReleasePresentation(true)
    @Test fun accessibilitySelectionWithoutPointerShowsPanelImmediately() = fixture { _, playlist ->
        val id = playlist.entries[1].id
        compose.onNodeWithTag("media-$id").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { assertTrue(it()) }
        compose.runOnIdle {
            assertEquals(listOf(id), selection.entries.map { it.row.id })
            assertTrue(selection.panelVisible)
        }
    }
    @Test fun backClearsMutableListSelectionWithoutLeavingTheList() = fixture { _, playlist ->
        val id = playlist.entries[1].id
        compose.onNodeWithTag("media-$id").performTouchInput { longClick() }
        compose.onNodeWithText("Select song").assertDoesNotExist()
        compose.runOnIdle { assertTrue(selection.active); backDispatcher.onBackPressed() }
        compose.onNodeWithTag("media-$id").assertIsDisplayed().assertIsNotSelected()
        compose.runOnIdle { assertFalse(selection.active); assertTrue(selection.entries.isEmpty()) }
    }
    @Test fun disablingDragCancelsHeldTransactionAndRejectsNewDowns() = fixture { controller, playlist ->
        val original = playlist.entries.map { it.id }
        val list = compose.onNodeWithTag("browser-list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val from = compose.onNodeWithTag("media-${original[1]}").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        compose.runOnIdle { interactionEnabled = false }
        list.performTouchInput { down(from); advanceEventTime(650); moveTo(from + Offset(0f, 40f)); up() }
        compose.runOnIdle { assertFalse(selection.active) }
        compose.onNodeWithTag("drag-stack").assertDoesNotExist()
        compose.runOnIdle { interactionEnabled = true }
        var held = false
        try {
            list.performTouchInput { down(from); held = true; advanceEventTime(650); moveTo(from + Offset(0f, 40f)) }
            compose.onNodeWithTag("drag-stack").assertExists()
            compose.runOnIdle { interactionEnabled = false }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle {
                // Interruption rolls back the drag while retaining its selection.
                assertTrue(selection.active)
                assertEquals(listOf(original[1]), selection.entries.map { it.row.id })
                assertEquals(original, controller.state.value.playlists.single().entries.map { it.id })
            }
            list.performTouchInput { up(); held = false }
        } finally {
            if (held) {
                compose.mainClock.autoAdvance = false
                try { list.performTouchInput { cancel() } } finally { compose.mainClock.autoAdvance = true }
            }
        }
    }

    /** Reproduces the presentation invalidation caused by QueueController's checkpoint publication.
     * The query gate makes even a fast device observe the pending interval deterministically. */
    private fun retainedAcrossCheckpoints(multiple: Boolean, moving: Boolean, edgeScroll: Boolean = false) =
        fixture(count = if (edgeScroll) 60 else 8) { controller, playlist ->
            val ids = playlist.entries.map { it.id }
            val list = compose.onNodeWithTag("browser-list")
            if (multiple) {
                compose.onNodeWithTag("media-${ids[1]}").performTouchInput { longClick() }
                compose.onNodeWithTag("media-${ids[2]}").performClick()
            }
            val bounds = list.fetchSemanticsNode().boundsInRoot
            val from = compose.onNodeWithTag("media-${ids[1]}").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
            val destination = compose.onNodeWithTag("media-${ids[4]}").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
            var held = false
            try {
                list.performTouchInput { down(from); held = true; advanceEventTime(650); moveTo(from + Offset(0f, 40f)) }
                val owner = compose.runOnIdle { requireNotNull(fixtureDragHost.state) }
                val transaction = compose.runOnIdle { requireNotNull(owner.drag) }
                val originalScroll = compose.runOnIdle { owner.grid.firstVisibleItemIndex }
                compose.mainClock.autoAdvance = false
                compose.runOnIdle {
                    queries.blocked = true
                    checkpoint = QueueUiState(loading = false, library = QueueLibrary(listOf(
                        ListeningQueue(id = "unrelated", name = "Playback", entries = emptyList(), positionMs = 5000))))
                }
                compose.mainClock.advanceTimeBy(64)
                compose.waitUntil(5000) { queries.pending.isNotEmpty() }
                compose.runOnIdle { assertSame(transaction, owner.drag); assertFalse(transaction.dropping) }
                if (moving || edgeScroll) list.performTouchInput {
                    moveTo(if (edgeScroll) Offset(8f, bounds.height - 8f) else destination)
                }
                // A stationary finger receives no move event throughout the checkpoint interval.
                compose.mainClock.advanceTimeBy(6200)
                compose.runOnIdle {
                    assertSame(transaction, owner.drag)
                    assertFalse(transaction.dropping)
                    assertEquals(if (multiple) setOf(ids[1], ids[2]) else setOf(ids[1]), transaction.keys)
                    if (edgeScroll) {
                        assertTrue(owner.grid.firstVisibleItemIndex > originalScroll)
                    }
                }
                queries.release()
                compose.mainClock.advanceTimeBy(64)
                // Independently publish equal content with a fresh identity. Production
                // StateFlow may suppress a truly unchanged paused capture; this fixture
                // deliberately exercises a refresh even when its content is unchanged.
                compose.runOnIdle {
                    queries.blocked = true
                    checkpoint = checkpoint.copy(library = checkpoint.library.copy(queues = checkpoint.library.queues.map { it }))
                }
                compose.mainClock.advanceTimeBy(64)
                compose.waitUntil(5000) { queries.pending.isNotEmpty() }
                compose.runOnIdle { assertSame(transaction, owner.drag); assertFalse(transaction.dropping) }
                queries.release()
                compose.runOnIdle {
                    checkpoint = checkpoint.copy(library = checkpoint.library.update(checkpoint.library.queues.single().copy(positionMs = 10_000)))
                }
                compose.mainClock.advanceTimeBy(128)
                compose.runOnIdle { assertSame(transaction, owner.drag); assertFalse(transaction.dropping) }
                fun reportDrop(phase: String) {
                    compose.runOnUiThread {
                        val current = owner.drag
                        android.util.Log.i("DragCheckpoint", "phase=$phase virtualMs=" + compose.mainClock.currentTime +
                            " uptimeMs=" + android.os.SystemClock.uptimeMillis() +
                            " sameOwner=" + (fixtureDragHost.state === owner) + " sameDrag=" + (current === transaction) +
                            " dropping=" + current?.dropping + " landing=" + current?.landing?.value +
                            " landingRunning=" + current?.landing?.isRunning + " handoff=" + current?.handoff?.value +
                            " handoffRunning=" + current?.handoff?.isRunning + " destinations=" + current?.destinations?.keys +
                            " pendingQueries=" + queries.pending.size + " queryBlocked=" + queries.blocked +
                            " savedOrder=" + controller.state.value.playlists.single().entries.map { it.id })
                    }
                }
                list.performTouchInput { up(); held = false }
                compose.mainClock.autoAdvance = true
                try { reportDrop("after-up") } catch (_: Throwable) { /* Diagnostic only. */ }
                try {
                    compose.waitUntil(5000) {
                        // No contact or intermediate placement oracle remains after UP.
                        // Batch at most four ordinary frames for this lifecycle endpoint;
                        // the original wall deadline and exact retirement condition stay.
                        compose.runOnUiThread {
                            repeat(4) { if (owner.drag != null) compose.mainClock.advanceTimeByFrame() }
                        }
                        owner.drag == null
                    }
                } catch (failure: Throwable) {
                    try { reportDrop("retirement-failure") } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
                    throw failure
                }
                compose.runOnIdle {
                    assertFalse(selection.active)
                    val saved = controller.state.value.playlists.single().entries.map { it.id }
                    assertEquals(ids.toSet(), saved.toSet())
                    if (moving || edgeScroll) assertNotEquals(ids, saved)
                    if (multiple) assertEquals(saved.indexOf(ids[1]) + 1, saved.indexOf(ids[2]))
                }
            } finally {
                queries.release()
                if (held) { compose.mainClock.autoAdvance = false; list.performTouchInput { cancel() } }
                compose.mainClock.autoAdvance = true
            }
        }
    @Test fun stationarySingleDragSurvivesPlaybackCheckpoint() = retainedAcrossCheckpoints(false, false)
    @Test fun stationaryMultiDragSurvivesPlaybackCheckpoint() = retainedAcrossCheckpoints(true, false)
    @Test fun movingMultiDragSurvivesPlaybackCheckpoint() = retainedAcrossCheckpoints(true, true)
    @Test fun edgeScrollingMultiDragSurvivesPlaybackCheckpoint() = retainedAcrossCheckpoints(true, true, true)

    @Test fun removedOccurrenceCancelsBeforePendingPresentationPublishes() = fixture { controller, playlist ->
        val ids = playlist.entries.map { it.id }
        val list = compose.onNodeWithTag("browser-list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val from = compose.onNodeWithTag("media-${ids[1]}").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        list.performTouchInput { down(from); advanceEventTime(650); moveTo(from + Offset(0f, 40f)) }
        val owner = compose.runOnIdle { requireNotNull(fixtureDragHost.state) }
        try {
            queries.blocked = true
            compose.runOnIdle { controller.removeEntries(playlist.id, setOf(ids[1])) }
            compose.waitUntil(5000) { controller.state.value.playlists.single().entries.none { it.id == ids[1] } }
            compose.waitUntil(5000) { owner.drag == null || owner.drag!!.dropping }
            compose.runOnIdle { assertTrue(selection.active) }
            list.performTouchInput { up() }
            assertEquals(ids.filterNot { it == ids[1] }, controller.state.value.playlists.single().entries.map { it.id })
        } finally { queries.release() }
    }

    @Test fun routeChangeCancelsHeldDrag() = fixture { controller, playlist ->
        val ids = playlist.entries.map { it.id }
        val list = compose.onNodeWithTag("browser-list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val from = compose.onNodeWithTag("media-${ids[1]}").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        list.performTouchInput { down(from); advanceEventTime(650); moveTo(from + Offset(0f, 40f)) }
        val owner = compose.runOnIdle { requireNotNull(fixtureDragHost.state) }
        compose.runOnIdle { routeGeneration++ }
        compose.waitUntil(5000) { owner.drag == null || owner.drag!!.dropping }
        list.performTouchInput { up() }
        assertEquals(ids, controller.state.value.playlists.single().entries.map { it.id })
    }

    @Test fun queuedPlaylistDropRejectsAnInterveningOrderChange() = fixture { controller, playlist ->
        val ids = playlist.entries.map { it.id }
        val first = CompletableDeferred<Boolean>()
        val stale = CompletableDeferred<Boolean>()
        compose.runOnIdle {
            controller.moveBefore(playlist.id, setOf(ids.last()), ids.first()) { first.complete(it) }
            controller.moveBefore(playlist.id, setOf(ids[1]), null, ids) { stale.complete(it) }
        }
        runBlocking { assertTrue(withTimeout(5000) { first.await() }); assertFalse(withTimeout(5000) { stale.await() }) }
        assertEquals(listOf(ids.last()) + ids.dropLast(1), controller.state.value.playlists.single().entries.map { it.id })
    }

    /** #533: adjacent hovering rows start the drag exactly where they hover, fly into the
     * stack and land without single-frame jumps, then the moved rows ease out of hover. */
    @Test fun adjacentHoverSelectionFliesIntoTheStackAndLandsWithoutJumps() = fixture { controller, playlist ->
        val ids = playlist.entries.map { it.id }
        compose.onNodeWithTag("media-${ids[1]}").performTouchInput { longClick() }
        compose.onNodeWithTag("media-${ids[2]}").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("media-${ids[2]}").assertIsSelected()
        val list = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot
        val rest = compose.onNodeWithTag("media-${ids[1]}").fetchSemanticsNode().boundsInRoot
        val from = rest.center - list.topLeft
        val to = compose.onNodeWithTag("media-${ids[5]}").fetchSemanticsNode().boundsInRoot.center - list.topLeft
        compose.mainClock.autoAdvance = false
        var held = false
        try {
            compose.onNodeWithTag("browser-list").performTouchInput { down(from); held = true }
            compose.mainClock.advanceTimeBy(700)
            compose.onNodeWithTag("browser-list").performTouchInput { moveTo(from + Offset(0f, 35f)) }
            val path = mutableListOf<androidx.compose.ui.geometry.Rect>()
            repeat(24) { frame ->
                compose.mainClock.advanceTimeByFrame()
                if (frame in listOf(0, 4, 8, 16, 23)) saveDragFrame("list-pickup-$frame")
                compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().firstOrNull()?.let { path += it.boundsInRoot }
            }
            assertTrue("The drag lifted a stack", path.size > 10)
            assertEquals("The top card starts where its row hovered", rest.left, path.first().left, 3f)
            assertEquals("The first flight frame preserves the source width", rest.width, path.first().width, 3f)
            assertEquals("The first flight frame preserves the source height", rest.height, path.first().height, 3f)
            val widthChange = kotlin.math.abs(path.last().width - path.first().width)
            path.zipWithNext().forEach { (a, b) ->
                assertTrue("Stack fitting cannot snap the source width", kotlin.math.abs(b.width - a.width) <= widthChange * .35f + 2f)
            }
            assertTrue("The top card starts at its row, not the stack: ${path.first()} vs $rest",
                kotlin.math.abs(path.first().top - rest.top) <= kotlin.math.abs(path.last().top - rest.top) / 2f + 3f)
            val travel = (path.last().topLeft - path.first().topLeft).getDistance()
            path.zipWithNext().forEach { (a, b) ->
                assertTrue("No single-frame jump into the stack: $path", (b.topLeft - a.topLeft).getDistance() <= travel * .35f + 3f)
            }
            compose.onNodeWithTag("browser-list").performTouchInput { moveTo(to) }
            repeat(6) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag("browser-list").performTouchInput { up(); held = false }
            val landing = mutableListOf<androidx.compose.ui.geometry.Rect>()
            repeat(40) { frame ->
                compose.mainClock.advanceTimeByFrame()
                if (frame in listOf(0, 8, 16, 32)) saveDragFrame("list-landing-$frame")
                compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().firstOrNull()?.let { landing += it.boundsInRoot }
            }
            assertTrue("The stack lands over several frames", landing.size > 8)
            val distance = (landing.last().topLeft - landing.first().topLeft).getDistance()
            landing.zipWithNext().forEach { (a, b) ->
                assertTrue("No single-frame jump while landing: $landing", (b.topLeft - a.topLeft).getDistance() <= distance * .35f + 3f)
            }
        } finally {
            if (held) compose.onNodeWithTag("browser-list").performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        val result = controller.state.value.playlists.single().entries.map { it.id }
        assertEquals("Adjacent rows stay together in source order", result.indexOf(ids[1]) + 1, result.indexOf(ids[2]))
        compose.runOnIdle { assertTrue("A completed move ends the selection", selection.entries.isEmpty()) }
        compose.onNodeWithTag("media-${ids[1]}").assertIsNotSelected()
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
        compose.runOnIdle { assertTrue(selection.entries.isEmpty()); assertFalse(selection.active) }
    }
    @Test fun gallerySelectionStaysGalleryWhileLiftedAndLandsAtItsActualBounds() = fixture(gallery = true) { _, playlist ->
        val id = playlist.entries[1].id
        val restingCard = compose.onNodeWithTag("media-$id").fetchSemanticsNode().boundsInRoot
        val restingArt = compose.onNodeWithTag("browse-art-$id", true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("media-$id").performTouchInput { longClick() }
        compose.onNodeWithTag("media-$id").assertIsSelected()
        compose.onNodeWithText("Select song").assertDoesNotExist()
        compose.onNodeWithTag("media-$id").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, 70f)); advanceEventTime(32)
        }
        val stack = compose.onNodeWithTag("drag-stack").fetchSemanticsNode().boundsInRoot
        val art = compose.onNodeWithTag("browse-art-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag("browse-text-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("A lifted tile retains its large artwork", art.width > stack.width * .65f)
        assertEquals("Lifting must preserve the artwork width", restingArt.width, art.width, 2f)
        assertEquals("Lifting must preserve the content inset", restingArt.left - restingCard.left, art.left - stack.left, 2f)
        assertTrue("Gallery text stays below the artwork", text.top >= art.bottom - 1f)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("browser-list").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(440)
            val returned = compose.onNodeWithTag("media-$id").fetchSemanticsNode().boundsInRoot
            val landing = compose.onNodeWithTag("drag-stack").fetchSemanticsNode().boundsInRoot
            assertEquals(returned.left, landing.left, 2f)
            assertEquals(returned.top, landing.top, 2f)
            assertEquals(returned.width, landing.width, 2f)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun threeGalleryPanesRetainTheirContentThroughMovementAndCancel() = fixture(gallery = true) { controller, playlist ->
        val ids = playlist.entries.map { it.id }
        compose.onNodeWithTag("media-${ids[0]}").performTouchInput { longClick() }
        compose.onNodeWithTag("media-${ids[1]}").performClick()
        compose.onNodeWithTag("media-${ids[2]}").performClick()
        val list = compose.onNodeWithTag("browser-list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val from = compose.onNodeWithTag("media-${ids[0]}").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        compose.runOnIdle { assertNull("Idle browsing allocates no drag scene", fixtureDragScene) }
        var held = false
        try {
            list.performTouchInput { down(from); held = true; advanceEventTime(650); moveTo(from + Offset(0f, 60f)) }
            compose.onNodeWithContentDescription("Dragging 3 songs").assertExists()
            compose.runOnIdle { assertTrue("A held stack records the full scene", requireNotNull(fixtureDragScene).recordings > 0) }
            saveDragFrame("gallery-three-picked-up")
            list.performTouchInput { moveTo(from + Offset(80f, 150f)); advanceEventTime(64) }
            saveDragFrame("gallery-three-moved")
            compose.onNodeWithTag("browse-art-${ids[0]}", true).assertExists()
            compose.onNodeWithTag("drag-card-${ids[2]}").assertExists()
            list.performTouchInput { cancel(); held = false }
        } finally {
            if (held) list.performTouchInput { cancel() }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        assertEquals(ids, controller.state.value.playlists.single().entries.map { it.id })
        compose.runOnIdle { assertNull("Landing releases the drag recording", fixtureDragScene) }
        saveDragFrame("gallery-three-canceled")
    }

    @Test fun heldCardTracksTheFingerWithinOneFrame() = fixture { _, playlist ->
        val id = playlist.entries[1].id
        compose.onNodeWithTag("media-$id").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, 70f)); advanceEventTime(32)
        }
        val stack = compose.onNodeWithTag("drag-stack")
        val before = stack.fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("browser-list").performTouchInput { moveBy(Offset(0f, 120f)) }
            compose.mainClock.advanceTimeByFrame()
            val after = stack.fetchSemanticsNode().boundsInRoot.top
            assertEquals("Top card must follow the pointer without spring lag", 120f, after - before, 2f)
            compose.onNodeWithTag("browser-list").performTouchInput { cancel() }
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun trailingCardsStayCloseAndStackDrawsOverBottomActions() = fixture(foregroundFooter = true) { _, playlist ->
        val ids = playlist.entries.map { it.id }
        compose.onNodeWithTag("media-${ids[1]}").performTouchInput { longClick() }
        compose.onNodeWithTag("media-${ids[2]}").performClick()
        compose.onNodeWithTag("media-${ids[3]}").performClick()
        compose.onNodeWithTag("media-${ids[1]}").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, 70f)); advanceEventTime(32)
        }
        val lower = compose.onNodeWithTag("drag-card-${ids[2]}")
        val before = lower.fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("browser-list").performTouchInput { moveBy(Offset(0f, 120f)) }
            compose.mainClock.advanceTimeByFrame()
            val moved = lower.fetchSemanticsNode().boundsInRoot.top - before
            assertTrue("Lower card must stay near the pointer, moved $moved", moved in 75f..145f)
            compose.mainClock.advanceTimeBy(180)
            val top = compose.onNodeWithTag("drag-stack")
            val bottom = compose.onNodeWithTag("drag-card-${ids[3]}")
            val initialTop = top.fetchSemanticsNode().boundsInRoot.top
            val homes = listOf(lower, bottom).map { it.fetchSemanticsNode().boundsInRoot.top - initialTop }
            // Continuous motion and reversals must update both trailing cards every frame.
            for (direction in listOf(1f, -1f, 1f)) repeat(5) {
                val beforeCards = listOf(lower, bottom).map { it.fetchSemanticsNode().boundsInRoot.top }
                compose.onNodeWithTag("browser-list").performTouchInput { moveBy(Offset(0f, direction * 24f)) }
                compose.mainClock.advanceTimeByFrame()
                val topY = top.fetchSemanticsNode().boundsInRoot.top
                listOf(lower, bottom).forEachIndexed { index, card ->
                    val y = card.fetchSemanticsNode().boundsInRoot.top
                    assertTrue("Card must update on every moving frame", kotlin.math.abs(y - beforeCards[index]) > 1f)
                    assertTrue("Trail must remain bounded after reversing", kotlin.math.abs(y - topY - homes[index]) < 70f)
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
        val list = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("fixture-footer").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("browser-list").performTouchInput { moveTo(footer.center - list.topLeft); advanceEventTime(32) }
        val stack = compose.onNodeWithTag("drag-stack")
        stack.assert(hasAnyAncestor(hasTestTag("drag-foreground")))
        val card = stack.fetchSemanticsNode().boundsInRoot
        assertTrue("Card must extend beyond the clipped list into the footer", card.bottom > footer.top)
        val title = compose.onNode(hasText("Song 1") and hasAnyAncestor(hasTestTag("drag-stack")), useUnmergedTree = true)
        title.assertIsDisplayed()
        val ink = title.fetchSemanticsNode().boundsInRoot.intersect(footer)
        assertFalse("The dragged title must overlap the footer", ink.isEmpty)
        saveDragFrame("stack-over-footer")
        val image = compose.onRoot().captureToImage().toPixelMap()
        // A uniform magenta backdrop can remain magenta through live frost. Test
        // the card's foreground ink, which an overpainted footer would erase.
        val visibleInk = (ink.top.toInt() until ink.bottom.toInt()).sumOf { y ->
            (ink.left.toInt() until ink.right.toInt()).count { x -> image[x, y].toArgb() != Color.Magenta.toArgb() }
        }
        assertTrue("Selection footer must not paint over the dragged title: $visibleInk visible pixels", visibleInk > 8)
        compose.onNodeWithTag("browser-list").performTouchInput { cancel() }
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
        compose.runOnIdle { assertEquals(listOf(id), selection.entries.map { it.row.id }); assertTrue(selection.panelVisible) }
    }
    @Test fun releasingAtOriginalPositionFinishesSelection() = fixture { controller, playlist ->
        val id = playlist.entries[1].id
        compose.onNodeWithTag("media-$id").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(70f, 0f)); advanceEventTime(32); up()
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        assertEquals(playlist.entries, controller.state.value.playlists.single().entries)
        compose.runOnIdle { assertFalse(selection.active); assertTrue(selection.entries.isEmpty()) }
    }
    private fun openPanelDragClearance(chrome: Boolean) = fixture(foregroundFooter = chrome, panelClearance = true) { controller, playlist ->
        val id = playlist.entries[1].id
        val row = compose.onNodeWithTag("media-$id")
        row.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { assertTrue(it()) }
        val root = compose.onNodeWithTag("fixture-viewport").fetchSemanticsNode().boundsInRoot
        val scale = compose.onNodeWithTag("fixture-lifted-control").fetchSemanticsNode().boundsInRoot.width / 48f
        val base = if (chrome) 80f else 0f
        fun controlBottom() = compose.onNodeWithTag("fixture-lifted-control").fetchSemanticsNode().boundsInRoot.bottom
        fun assertShown() {
            val panelTop = compose.onNodeWithTag("fixture-selection-panel").fetchSemanticsNode().boundsInRoot.top
            assertEquals(8f * scale, panelTop - controlBottom(), 1.1f)
        }
        fun assertUtilityRow() {
            val utility = compose.onNodeWithContentDescription("Playlist actions").fetchSemanticsNode().boundsInRoot
            val cancel = compose.onNodeWithTag("cancel-drag").fetchSemanticsNode().boundsInRoot
            val rise = compose.runOnIdle {
                val source = fixtureDragHost.state!!
                source.drag!!.cancelMotion!!.offsetY(source.cancelTravel)
            }
            assertEquals("X rises into the actual collection utility row", utility.center.y + rise, cancel.center.y, 1.1f)
        }
        assertShown()
        // Existing selection stays active throughout both pointer cancellation and X release.
        for (cancelPointer in listOf(true, false)) {
            var held = false
            try {
                row.performTouchInput { down(center); held = true; advanceEventTime(650); moveTo(center) }
                compose.mainClock.autoAdvance = false
                try {
                    row.performTouchInput { moveBy(Offset(70f, 0f)); advanceEventTime(32) }
                    // Source recomposition publishes the recognized drag through its SideEffect.
                    // The foreground and panel consume that published state on the next frame.
                    compose.mainClock.advanceTimeByFrame()
                    compose.runOnIdle {
                        assertNotNull("The source must publish the recognized drag", fixtureDragHost.state?.drag)
                        assertFalse("Publication must retain the active pointer", fixtureDragHost.state!!.drag!!.dropping)
                        assertEquals("Publication must not skip the panel's shown geometry", 1f, fixtureFloatingPanel.progress, 0f)
                    }
                    compose.mainClock.advanceTimeByFrame()
                    compose.onNodeWithTag("cancel-drag").assertExists()
                    assertUtilityRow()
                    val shownBottom = controlBottom()
                    val firstRise = compose.runOnIdle {
                        val source = fixtureDragHost.state!!
                        source.drag!!.cancelMotion!!.offsetY(source.cancelTravel)
                    }
                    assertTrue("The first visible X must still be entering", firstRise > 0f)
                    assertEquals("The first published X uses the same clearance plus entrance", firstRise,
                        compose.onNodeWithTag("cancel-drag").fetchSemanticsNode().boundsInRoot.bottom - shownBottom, 1.1f)
                    repeat(18) {
                        compose.mainClock.advanceTimeByFrame()
                        assertUtilityRow()
                        val bottom = controlBottom()
                        val cancelBottom = compose.onNodeWithTag("cancel-drag").fetchSemanticsNode().boundsInRoot.bottom
                        val rise = compose.runOnIdle {
                            val source = fixtureDragHost.state!!
                            source.drag!!.cancelMotion!!.offsetY(source.cancelTravel)
                        }
                        assertEquals("X shares the live row plus its entrance on each frame", rise, cancelBottom - bottom, 1.1f)
                        compose.runOnIdle {
                            val source = fixtureDragHost.state!!
                            assertEquals("Hit geometry reads the same published inset without another composition",
                                cancelBottom - source.cancelRadius, source.viewportOrigin.y + source.cancelCenter.y, 1.1f)
                        }
                        compose.runOnIdle { assertEquals("The minimized panel stays fully shown", 1f, fixtureFloatingPanel.progress, 0f) }
                        assertShown()
                    }
                    compose.mainClock.advanceTimeBy(100)
                    compose.runOnIdle { assertEquals(1f, fixtureDragHost.state!!.drag!!.cancelMotion!!.progress, 0f) }
                    assertUtilityRow()
                } finally { compose.mainClock.autoAdvance = true }
                compose.onNodeWithTag("drag-stack").assertExists()
                compose.onNodeWithTag("fixture-selection-panel").assertExists()
                compose.onNodeWithTag("selection-minimized").assertExists().assertHasNoClickAction()
                val clearance = compose.runOnIdle { fixtureFloatingPanel.clearance.value }
                assertEquals((base + clearance) * scale, root.bottom - controlBottom(), 1.1f)
                val cancel = compose.onNodeWithTag("cancel-drag").fetchSemanticsNode().boundsInRoot
                assertEquals("X stays above the minimized panel and chrome", (base + clearance) * scale, root.bottom - cancel.bottom, 1.1f)
                assertUtilityRow()
                val list = compose.onNodeWithTag("browser-list")
                if (cancelPointer) list.performTouchInput { cancel(); held = false }
                else {
                    val target = cancel.center - list.fetchSemanticsNode().boundsInRoot.topLeft
                    list.performTouchInput { moveTo(target); advanceEventTime(32); up(); held = false }
                }
                compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
                assertEquals(playlist, controller.state.value.playlists.single())
                compose.runOnIdle { assertTrue(selection.panelVisible); assertEquals(listOf(id), selection.entries.map { it.row.id }) }
                compose.onNodeWithContentDescription("More selection actions").assertIsDisplayed()
                assertShown()
            } finally { if (held) compose.onNodeWithTag("browser-list").performTouchInput { cancel() } }
        }
    }
    @Test fun openPanelDragReturnsXAndControlsAboveChrome() = openPanelDragClearance(true)
    @Test fun openPanelDragReturnsXAndControlsWithoutChrome() = openPanelDragClearance(false)
    @Test fun canceledDragKeepsOrderAndSelection() = fixture { controller, playlist ->
        val id = playlist.entries[1].id
        compose.onNodeWithTag("media-$id").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, 90f)); advanceEventTime(32)
        }
        compose.onNodeWithTag("drag-stack").assertExists()
        compose.runOnIdle { assertFalse(selection.panelVisible) }
        compose.onNodeWithTag("browser-list").performTouchInput { cancel() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        assertEquals(playlist, controller.state.value.playlists.single())
        compose.runOnIdle { assertEquals(listOf(id), selection.entries.map { it.row.id }); assertTrue(selection.panelVisible) }
    }
    @Test fun failedSaveRestoresOrderWithoutClearingSelection() = fixture(failWrites = true) { controller, playlist ->
        val id = playlist.entries[1].id
        val list = compose.onNodeWithTag("browser-list")
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val source = compose.onNodeWithTag("media-$id").fetchSemanticsNode().boundsInRoot
        val destination = compose.onNodeWithTag("media-${playlist.entries[3].id}")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center
        val from = source.center - viewport.topLeft
        val to = destination - viewport.topLeft
        // A fixed pixel delta can remain in the original insertion slot when
        // list artwork grows. Exercise an actual reorder before rejecting its save.
        list.performTouchInput {
            down(from); advanceEventTime(650); moveTo(to); advanceEventTime(50)
        }
        compose.onNodeWithTag("drag-stack").assertExists()
        val gap = compose.onNodeWithTag("drop-gap").fetchSemanticsNode().boundsInRoot
        assertTrue("The drop must move beyond the original row: source=$source, gap=$gap", gap.top > source.bottom)
        list.performTouchInput { up() }
        compose.waitUntil(5000) { controller.state.value.problem != null }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        assertEquals(playlist, controller.state.value.playlists.single())
        compose.runOnIdle { assertEquals(listOf(id), selection.entries.map { it.row.id }); assertTrue(selection.panelVisible) }
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
