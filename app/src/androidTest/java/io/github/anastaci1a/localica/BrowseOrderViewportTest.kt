package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/** Exercises the actual browser controls, committed controller state and lazy-grid layout together. */
class BrowseOrderViewportTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val currentId = "occurrence-70"
    private val tracks = (0 until 100).map { index ->
        Track("missing:$index", "Song ${index.toString().padStart(3, '0')}",
            id = "track-$index", fileName = "$index.mp3", relativePath = "$index.mp3")
    }
    // Three occurrences share one track. Following its track ID or the first match is incorrect.
    private val rows = tracks.indices.map { index ->
        SongRow("occurrence-$index", tracks[if (index == 9 || index == 70) 40 else index])
    }
    private val otherTrack = Track("missing:other", "Other exclusive song", id = "other-track",
        fileName = "other.mp3", relativePath = "other.mp3")
    private val list get() = compose.onNodeWithTag("browser-list")
    private fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private class QueryDispatcher : CoroutineDispatcher() {
        @Volatile var held = false
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) tasks.add(block) else Dispatchers.Default.dispatch(context, block)
        }
        fun next() = requireNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Expected a held browse query" }
        fun releaseRemaining() {
            held = false
            while (true) (tasks.poll() ?: break).run()
        }
    }

    private class SortGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun await() { entered.complete(Unit); release.await() }
    }

    private class Fixture(
        val queues: QueueController?, val playlists: PlaylistController?,
        val queueDb: QueueDatabase, val playlistDb: PlaylistDatabase,
        route: BrowseRoute, val sortReads: AtomicInteger,
    ) {
        var route by mutableStateOf(route)
        var sorting by mutableStateOf(false)
        var topInset = 0f
        var cardInsetPx = 0
        val queue get() = queues!!.state.value.library.queues.first { it.id == "mix" }
        val playlist get() = playlists!!.state.value.playlists.first { it.id == "mix" }
    }

    private fun fixture(queue: Boolean = true, active: Boolean = true, reducedMotion: Boolean = true,
        initiallySorted: Boolean = false, staleUiSort: Boolean = false,
        queries: QueryDispatcher = QueryDispatcher(), sortGate: SortGate? = null, test: (Fixture) -> Unit) {
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        val queueDb = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val playlistDb = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val queuePreferences = QueuePreferences(context)
        val previousQueueSettings = queuePreferences.read()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sortReads = AtomicInteger()
        val source = LibrarySnapshot(tracks = tracks)
        val library = LibraryUiState(if (staleUiSort) source.copy(tracks = tracks.mapIndexed { index, track ->
            track.copy(title = "Song ${(99 - index).toString().padStart(3, '0')}")
        }) else source, loading = false)
        val kind = if (queue) MediaKind.Queue else MediaKind.Playlist
        val route = BrowseRoute(if (queue) MainTab.Queues else MainTab.Playlists,
            MediaRef(kind, "mix"), title = "Mix", key = "order-viewport-mix")
        var queued: QueueController? = null
        var listed: PlaylistController? = null
        try {
            runBlocking {
                if (queue) {
                    val manual = ListeningQueue(id = "mix", name = "Mix",
                        entries = rows.map { QueueEntry(it.id, it.track) }, currentEntryId = currentId, positionMs = 1234)
                    val opening = if (initiallySorted) QueueEdits.sort(manual, TrackSort.Title, true) else manual
                    val other = manual.copy(id = "other", name = "Other",
                        entries = rows.map { QueueEntry("other-${it.id}", it.track) } + QueueEntry("other-exclusive", otherTrack),
                        currentEntryId = "other-$currentId")
                    queueDb.queues().save(QueueLibrary(), QueueLibrary(listOf(opening, other), if (active) "mix" else "other"))
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        queued = QueueController(queueDb.queues(), queuePreferences,
                            (context.applicationContext as LocalicaApplication).library, scope,
                            sortSnapshot = { sortReads.incrementAndGet(); sortGate?.await(); source })
                    }
                    withTimeout(10_000) { queued!!.state.first { !it.loading } }
                } else {
                    val manual = ManualPlaylist(id = "mix", name = "Mix", entries = rows)
                    val opening = if (initiallySorted) PlaylistEdits.sort(manual, TrackSort.Title, true) else manual
                    val other = ManualPlaylist(id = "other", name = "Other",
                        entries = rows.map { it.copy(id = "other-${it.id}") } + SongRow("other-exclusive", otherTrack))
                    playlistDb.playlists().save(emptyList(), listOf(opening, other))
                    listed = PlaylistController(playlistDb.playlists(), scope, sortSnapshot = { source })
                    withTimeout(10_000) { listed!!.state.first { !it.loading } }
                }
            }
            val fixture = Fixture(queued, listed, queueDb, playlistDb, route, sortReads)
            BrowsePreferences(context).write(route, BrowseStyle(quickSearch = true), true)
            // Keep unused sources stable: their identity is part of the presentation revision.
            val noQueues = QueueUiState(loading = false)
            val noPlaylists = PlaylistState(loading = false)
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                    val queueState = fixture.queues?.state?.collectAsState()?.value ?: noQueues
                    val playlistState = fixture.playlists?.state?.collectAsState()?.value ?: noPlaylists
                    val anchor = rememberGlassAnchor()
                    fixture.topInset = with(LocalDensity.current) { listPillSlotHeight().toPx() }
                    fixture.cardInsetPx = with(LocalDensity.current) { BrowseCellGeometry(1).cardInset.dp.roundToPx() }
                    BrowserScreen(library, playlistState, fixture.route, { fixture.route = it }, {}, {},
                        { _, _, _ -> fail("Ordering must not start playback") }, fixture.queues, fixture.playlists,
                        true, false, sorting = fixture.sorting, onSortDismiss = { fixture.sorting = false },
                        queueState = queueState, playingTrackId = tracks[40].id,
                        playingEntryId = queueState.library.active?.takeIf { it.id == fixture.route.collection?.id }?.currentEntryId,
                        header = { BrowseOptionsPill(anchor) { fixture.sorting = true } },
                        sortOrigin = anchor, reducedMotion = reducedMotion, queryDispatcher = queries)
                }
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("media-${rows.first().id}").fetchSemanticsNodes().isNotEmpty() }
            test(fixture)
        } finally {
            compose.mainClock.autoAdvance = true
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            queries.releaseRemaining()
            queuePreferences.write(previousQueueSettings)
            queueDb.close(); playlistDb.close()
            context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        }
    }

    private fun openSort() = compose.onNodeWithContentDescription("Sort").performClick()
    private fun chooseTitle() {
        compose.onNodeWithText("Sort by").performScrollTo().performClick()
        compose.onNodeWithText("Title").performScrollTo().performClick()
        compose.onNodeWithText("Select", substring = false).performClick()
    }
    private fun action(label: String) {
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText(label).assertIsEnabled() }.isSuccess }
        val control = compose.onNodeWithText(label)
        if (label in setOf("Randomize", "Reverse", "Revert")) control.performScrollTo()
        control.performClick()
    }
    private fun waitForAction(label: String) {
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText(label).assertIsEnabled() }.isSuccess }
        compose.waitForIdle()
    }
    private fun nextQuery(queries: QueryDispatcher): Runnable {
        compose.waitForIdle()
        return queries.next()
    }
    private fun assertTop(fixture: Fixture? = null, action: String? = null) {
        try {
            compose.waitUntil(5000) { position() == 0f }
        } catch (failure: ComposeTimeoutException) {
            val library = fixture?.queues?.state?.value?.library
            val queue = library?.queues?.firstOrNull { it.id == fixture?.route?.collection?.id }
            throw AssertionError("Top did not settle: action=$action route=${fixture?.route} " +
                "scroll=${runCatching { position() }.getOrNull()} " +
                "activeQueue=${library?.activeId} activeOccurrence=${library?.active?.currentEntryId} " +
                "controllerOrder=${queue?.order} " +
                "controllerEntriesGeneration=${queue?.entries?.let(System::identityHashCode)} " +
                "controllerOccurrenceIds=${queue?.entries?.map { it.id }} " +
                "visibleSemantics=${runCatching { list.printToString(maxDepth = 3) }.getOrNull()}", failure)
        }
        assertEquals(0f, position(), 0f)
    }
    private fun assertCurrentAtInset(fixture: Fixture, occurrenceId: String = currentId) {
        // ListMotion aligns the outer lazy cell. The media semantics tag is inside
        // animateBounds' shared card padding, which is rounded independently at layout.
        val expectedCellTop = fixture.topInset.roundToInt().toFloat()
        val expectedCardTop = expectedCellTop + fixture.cardInsetPx
        try {
            compose.waitUntil(5000) {
                runCatching {
                    val row = compose.onNodeWithTag("media-$occurrenceId").fetchSemanticsNode().boundsInRoot
                    kotlin.math.abs(row.top - list.fetchSemanticsNode().boundsInRoot.top - expectedCardTop) <= 1.1f
                }.getOrDefault(false)
            }
        } catch (failure: ComposeTimeoutException) {
            val node = runCatching { compose.onNodeWithTag("media-$occurrenceId").fetchSemanticsNode() }.getOrNull()
            val row = node?.boundsInRoot
            val cell = node?.layoutInfo?.parentInfo?.coordinates?.boundsInRoot()
            val viewport = runCatching { list.fetchSemanticsNode().boundsInRoot }.getOrNull()
            val library = fixture.queues?.state?.value?.library
            val queue = library?.queues?.firstOrNull { it.id == fixture.route.collection?.id }
            throw AssertionError("Follow did not settle: occurrence=$occurrenceId " +
                "expectedCellTop=$expectedCellTop expectedCardTop=$expectedCardTop " +
                "cardBounds=$row outerCellBounds=$cell " +
                "viewport=$viewport scroll=${runCatching { position() }.getOrNull()} " +
                "controllerSourceIndex=${queue?.entries?.indexOfFirst { it.id == occurrenceId }} " +
                "expectedIndexWithHeader=${queue?.entries?.indexOfFirst { it.id == occurrenceId }?.takeIf { it >= 0 }?.plus(1)} " +
                "activeQueue=${library?.activeId} activeOccurrence=${library?.active?.currentEntryId} " +
                "controllerEntriesGeneration=${queue?.entries?.let(System::identityHashCode)} " +
                "visibleSemantics=${runCatching { list.printToString(maxDepth = 3) }.getOrNull()}", failure)
        }
        val node = compose.onNodeWithTag("media-$occurrenceId").assertIsDisplayed().fetchSemanticsNode()
        val row = node.boundsInRoot
        val cell = requireNotNull(node.layoutInfo.parentInfo).coordinates.boundsInRoot()
        assertEquals("The outer lazy cell aligns with the floating-pill inset",
            expectedCellTop, cell.top - list.fetchSemanticsNode().boundsInRoot.top, 1.1f)
        assertEquals("Follow the exact occurrence below the floating search pill and shared card inset, including the full-span header",
            expectedCardTop, row.top - list.fetchSemanticsNode().boundsInRoot.top, 1.1f)
    }

    @Test fun activeQueueApplyRandomizeAndRevertUseTheExactOccurrenceAndHeaderInset() = fixture { fixture ->
        list.performScrollToIndex(85)
        openSort(); chooseTitle(); action("Apply")
        compose.waitUntil(5000) { fixture.queue.order.sort == TrackSort.Title }
        assertCurrentAtInset(fixture)
        assertEquals(currentId, fixture.queue.currentEntryId)
        assertEquals(1234L, fixture.queue.positionMs)

        action("Randomize")
        compose.waitUntil(5000) { fixture.queue.order.sort == TrackSort.Manual }
        assertTop()
        assertEquals(currentId, fixture.queue.entries.first().id)
        action("Revert")
        compose.waitUntil(5000) { fixture.queue.entries.map { it.id } == rows.map { it.id } }
        assertCurrentAtInset(fixture)
        assertEquals(1234L, fixture.queue.positionMs)
        compose.onNodeWithText("Revert").assertDoesNotExist()
    }

    @Test fun activeQueueReverseUsesCommittedOccurrenceAndTwoReversalsHideRevert() = fixture { fixture ->
        val opening = fixture.queue
        list.performScrollToIndex(85)
        openSort(); action("Reverse")
        compose.waitUntil(5000) { fixture.queue.entries == opening.entries.reversed() }
        assertCurrentAtInset(fixture)
        assertEquals(currentId, fixture.queue.currentEntryId)
        assertEquals(1234L, fixture.queue.positionMs)
        assertNotEquals(currentId, fixture.queue.entries.first().id)
        compose.onNodeWithText("Revert").assertExists()
        action("Reverse")
        compose.waitUntil(5000) { fixture.queue.entries == opening.entries }
        assertCurrentAtInset(fixture)
        compose.onNodeWithText("Revert").assertDoesNotExist()
        assertEquals(opening, fixture.queue)
    }

    @Test fun inactiveQueueReverseReturnsToTopWithoutSwitchingPlayback() = fixture(active = false) { fixture ->
        val opening = fixture.queue
        list.performScrollToIndex(85)
        openSort(); action("Reverse")
        compose.waitUntil(5000) { fixture.queue.entries == opening.entries.reversed() }
        assertTop(fixture, "Reverse")
        assertEquals("other", fixture.queues!!.state.value.library.activeId)
        action("Revert")
        compose.waitUntil(5000) { fixture.queue.entries == opening.entries }
        compose.onNodeWithText("Revert").assertDoesNotExist()
        assertTop(fixture, "Revert")
    }

    @Test fun playlistReverseWaitsForItsCommittedPresentationThenReturnsToTop() {
        val queries = QueryDispatcher()
        fixture(queue = false, queries = queries) { fixture ->
            val opening = fixture.playlist
            list.performScrollToIndex(80)
            val before = position()
            queries.held = true
            openSort(); action("Reverse")
            compose.waitUntil(5000) { fixture.playlist.entries == opening.entries.reversed() }
            val pending = nextQuery(queries)
            waitForAction("Close")
            assertEquals("Reverse must not scroll the obsolete presentation", before, position(), 0f)
            action("Close")
            compose.mainClock.advanceTimeBy(1000)
            assertEquals(before, position(), 0f)
            pending.run()
            assertTop()
        }
    }

    @Test fun inactiveQueueApplyRandomizeAndRevertReturnToTop() = fixture(active = false) { fixture ->
        list.performScrollToIndex(85)
        openSort(); chooseTitle(); action("Apply")
        compose.waitUntil(5000) { fixture.queue.order.sort == TrackSort.Title }
        assertTop(fixture, "Apply")
        action("Close")
        list.performScrollToIndex(75)
        openSort(); action("Randomize")
        compose.waitUntil(5000) { fixture.queue.order.sort == TrackSort.Manual }
        assertTop(fixture, "Randomize")
        // Keep this popup's opening checkpoint while moving the underlying grid through its semantics action.
        list.performScrollToIndex(65)
        action("Revert")
        compose.waitUntil(5000) { fixture.queue.order.sort == TrackSort.Title }
        assertTop(fixture, "Revert")
        assertEquals("other", fixture.queues!!.state.value.library.activeId)
    }

    @Test fun playlistRandomizeWaitsForItsPresentationAndSurvivesClosingThePopup() {
        val queries = QueryDispatcher()
        fixture(queue = false, initiallySorted = true, queries = queries) { fixture ->
            list.performScrollToIndex(80)
            val before = position()
            queries.held = true
            openSort(); action("Randomize")
            compose.waitUntil(5000) { fixture.playlist.order.sort == TrackSort.Manual }
            val pending = nextQuery(queries)
            waitForAction("Close")
            assertEquals("A committed source is not yet a laid-out presentation", before, position(), 0f)
            action("Close")
            compose.mainClock.advanceTimeBy(1000)
            assertEquals("Closing an accepted action must not scroll stale rows", before, position(), 0f)
            pending.run()
            assertTop()
        }
    }

    @Test fun repeatedApplyDiscardsOldPresentationAndAnimatesOnlyTheLatestDestination() {
        val queries = QueryDispatcher()
        fixture(reducedMotion = false, queries = queries) { fixture ->
            list.performScrollToIndex(85)
            val before = position()
            queries.held = true
            openSort(); chooseTitle(); action("Apply")
            compose.waitUntil(5000) { fixture.queue.order.sort == TrackSort.Title }
            val old = nextQuery(queries)
            waitForAction("Close")
            compose.onNodeWithText("Descending").performScrollTo().performClick()
            action("Apply")
            compose.waitUntil(5000) { !fixture.queue.order.ascending }
            val latest = nextQuery(queries)
            waitForAction("Close")
            action("Close")
            old.run()
            compose.waitForIdle()
            assertEquals("An obsolete query cannot consume the latest follow", before, position(), 0f)

            compose.mainClock.autoAdvance = false
            try {
                latest.run()
                val positions = mutableListOf<Float>()
                repeat(42) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                    positions += position()
                }
                assertCurrentAtInset(fixture)
                assertTrue("Normal motion must travel over multiple frames", positions.distinct().size > 4)
                val settled = position()
                compose.mainClock.advanceTimeBy(1000)
                assertEquals("The bounded journey must stay settled", settled, position(), 0f)
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test fun aListTouchCancelsThePendingFollowBeforeTheDestinationPresentationExists() {
        val queries = QueryDispatcher()
        fixture(queries = queries) { fixture ->
            list.performScrollToIndex(55)
            val anchor = compose.onNodeWithTag("media-occurrence-54")
            val originalTop = anchor.fetchSemanticsNode().boundsInRoot.top
            queries.held = true
            openSort(); chooseTitle(); action("Apply")
            compose.waitUntil(5000) { fixture.queue.order.sort == TrackSort.Title }
            val pending = nextQuery(queries)
            waitForAction("Close"); action("Close")
            list.performTouchInput { down(center); cancel() }
            pending.run()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000)
            assertTrue("Touch must prevent a deferred jump to top", position() > 0f)
            assertEquals("The keyed row stays under the user's chosen viewport", originalTop,
                anchor.fetchSemanticsNode().boundsInRoot.top, 1.1f)
            compose.onNodeWithTag("media-$currentId").assertIsNotDisplayed()
        }
    }

    @Test fun cursorOnlyAdvanceWhilePresentationWaitsFollowsTheNewExactOccurrence() = cursorWhilePending(false)
    @Test fun switchingPlaybackQueuesWhilePresentationWaitsFallsBackToTop() = cursorWhilePending(true)

    private fun cursorWhilePending(switchQueue: Boolean) {
        val queries = QueryDispatcher()
        fixture(queries = queries) { fixture ->
            list.performScrollToIndex(85)
            queries.held = true
            openSort(); chooseTitle(); action("Apply")
            compose.waitUntil(5000) { fixture.queue.order.sort == TrackSort.Title }
            val old = nextQuery(queries)
            val committedEntries = fixture.queue.entries
            val nextOccurrence = if (switchQueue) "other-occurrence-9" else "occurrence-9"
            fixture.queues!!.selectEntry(nextOccurrence)
            compose.waitUntil(5000) { fixture.queues.state.value.library.active?.currentEntryId == nextOccurrence }
            val latest = nextQuery(queries)
            assertSame("Cursor/journey changes retain the committed structural generation", committedEntries, fixture.queue.entries)
            waitForAction("Close"); action("Close")
            old.run()
            latest.run()
            if (switchQueue) assertTop() else assertCurrentAtInset(fixture, nextOccurrence)
        }
    }

    @Test fun changingRoutesRejectsTheOldPresentationWithoutMovingTheNewRoute() {
        val queries = QueryDispatcher()
        fixture(queries = queries) { fixture ->
            list.performScrollToIndex(80)
            queries.held = true
            openSort(); chooseTitle(); action("Apply")
            compose.waitUntil(5000) { fixture.queue.order.sort == TrackSort.Title }
            val old = nextQuery(queries)
            waitForAction("Close"); action("Close")
            compose.runOnIdle { fixture.route = fixture.route.copy(collection = MediaRef(MediaKind.Queue, "other"),
                title = "Other", key = "order-viewport-other") }
            nextQuery(queries).run()
            compose.waitForIdle()
            list.performScrollToIndex(60)
            val before = position()
            old.run()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000)
            assertEquals("A previous route's follow cannot move the new route", before, position(), 0f)
            compose.onNodeWithTag("media-other-occurrence-59").assertExists()
        }
    }

    @Test fun equalButReplacedEntriesRetireAWaitingFollowWhenAnotherCollectionPublishesAChange() {
        val queries = QueryDispatcher()
        fixture(queries = queries) { fixture ->
            list.performScrollToIndex(85)
            val anchor = compose.onNodeWithTag("media-occurrence-84")
            val originalTop = anchor.fetchSemanticsNode().boundsInRoot.top
            queries.held = true
            openSort(); chooseTitle(); action("Apply")
            compose.waitUntil(5000) { fixture.queue.order.sort == TrackSort.Title }
            val old = nextQuery(queries)
            val committedEntries = fixture.queue.entries
            waitForAction("Close"); action("Close")
            val refreshed = CompletableDeferred<Boolean>()
            fixture.queues!!.refreshTrack(otherTrack.copy(title = "Changed other song")) { refreshed.complete(it) }
            runBlocking { assertTrue(withTimeout(5000) { refreshed.await() }) }
            assertEquals(committedEntries, fixture.queue.entries)
            assertNotSame("Another collection's change publishes the target's equal replacement list",
                committedEntries, fixture.queue.entries)
            val replacement = nextQuery(queries)
            old.run()
            replacement.run()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000)
            assertEquals("A retired generation must not resurrect its follow", originalTop,
                anchor.fetchSemanticsNode().boundsInRoot.top, 1.1f)
            compose.onNodeWithTag("media-$currentId").assertIsNotDisplayed()
        }
    }

    @Test fun closingThePopupBeforeCommitRejectsItsDelayedCallback() {
        val gate = SortGate()
        fixture(sortGate = gate) { fixture ->
            list.performScrollToIndex(85)
            val anchor = compose.onNodeWithTag("media-occurrence-84")
            val originalTop = anchor.fetchSemanticsNode().boundsInRoot.top
            val listNode = list.fetchSemanticsNode()
            fun logViewport(stage: String) = compose.runOnUiThread {
                // Read the existing semantics tree on main without an idle wait or advancing time.
                val viewport = listNode.boundsInRoot
                val occurrences = mutableListOf<Pair<String, androidx.compose.ui.geometry.Rect>>()
                fun collect(node: androidx.compose.ui.semantics.SemanticsNode) {
                    val tag = node.config.getOrNull(SemanticsProperties.TestTag)
                    if (tag?.startsWith("media-occurrence-") == true) occurrences += tag to node.boundsInRoot
                    node.children.forEach(::collect)
                }
                collect(listNode)
                val visible = occurrences.filter { (_, bounds) -> bounds.overlaps(viewport) }
                android.util.Log.i("BrowseOrderViewportTest", "$stage sorting=${fixture.sorting} " +
                    "gridPosition=${listNode.config[SemanticsProperties.VerticalScrollAxisRange].value()} " +
                    "visibleKeys=${visible.take(20).map { it.first }} " +
                    "occurrence84Bounds=${occurrences.firstOrNull { it.first == "media-occurrence-84" }?.second}")
            }
            openSort(); chooseTitle(); action("Apply")
            compose.waitUntil(5000) { gate.entered.isCompleted }
            compose.onNodeWithText("Close").assertIsNotEnabled()
            // Back can dismiss the popup while the serialized command is still reading its sort source.
            compose.awaitExpectedWindowFocus(compose.onNodeWithTag("browse-options-dialog"))
            logViewport("beforeBack")
            pressSystemBack()
            compose.waitUntil(5000) { !fixture.sorting }
            gate.release.complete(Unit)
            compose.waitUntil(5000) { fixture.queue.order.sort == TrackSort.Title }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000)
            logViewport("afterCommitAndSettle")
            assertEquals("A closed popup's completion cannot navigate the browser", originalTop,
                anchor.fetchSemanticsNode().boundsInRoot.top, 1.1f)
            compose.onNodeWithTag("media-$currentId").assertIsNotDisplayed()
        }
    }

    @Test fun failedRandomizeKeepsTheViewportAndSuccessfulRetryMovesIt() = fixture(queue = false, initiallySorted = true) { fixture ->
        list.performScrollToIndex(80)
        val before = position()
        fixture.playlistDb.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_order BEFORE UPDATE ON playlists BEGIN SELECT RAISE(ABORT, 'Injected order failure'); END")
        openSort(); action("Randomize")
        compose.waitUntil(5000) { fixture.playlists!!.state.value.problem != null }
        compose.onNodeWithText("Couldn’t save this order. Try again.").assertExists()
        assertEquals(before, position(), 0f)
        compose.onNodeWithText("Revert").assertDoesNotExist()
        fixture.playlistDb.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_order")
        action("Randomize")
        compose.waitUntil(5000) { fixture.playlist.order.sort == TrackSort.Manual }
        assertTop()
    }

    @Test fun anApplyThatIsUnchangedAtTheControllerDoesNotMoveTheViewport() =
        fixture(initiallySorted = true, staleUiSort = true) { fixture ->
            list.performScrollToIndex(80)
            val before = position()
            val opening = fixture.queue
            openSort(); action("Apply")
            compose.waitUntil(5000) { fixture.sortReads.get() == 1 }
            waitForAction("Apply")
            compose.mainClock.advanceTimeBy(1000)
            assertEquals("Success without an ordering change must not enqueue navigation", before, position(), 0f)
            assertEquals(opening, fixture.queue)
        }
}
