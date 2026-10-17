package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Readiness can change independently of the lifetime of an already owned contact. */
class SharedKeyedDragRefreshTest {
    @get:Rule val compose = createComposeRule()
    private var ready by mutableStateOf(true)
    private var eligible by mutableStateOf(true)
    private var revision by mutableIntStateOf(0)
    private var callbackRevision by mutableIntStateOf(0)
    private lateinit var state: KeyedDragState<String, Unit>
    private val entries = (0..5).map { "occurrence-$it" }
    private val committed = mutableListOf<Int>()

    private fun fixture(legacyReadinessWiring: Boolean = false) {
        compose.setContent { LocalicaTheme {
            val grid = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            state = remember { object : KeyedDragState<String, Unit>(grid, scope, { it }) {
                override fun capturePresentation() = Unit
            }.also { it.items = entries } }
            val captured = callbackRevision
            Box(Modifier.fillMaxWidth().height(360.dp).testTag("host")
                .onSizeChanged { state.viewportWidth = it.width; state.viewportHeight = it.height }
                .sharedKeyedDrag(state, eligible && (!legacyReadinessWiring || ready), true, ownerKey = revision, acquisitionReady = ready,
                    itemAt = state::hit, commit = { _, _, complete -> committed.add(captured); complete(true) })) {
                LazyVerticalGrid(GridCells.Fixed(1), state = grid) {
                    items(state.rendered(entries), key = { it ?: DropGapKey }) { item ->
                        Box(Modifier.fillMaxWidth().height(52.dp)) { Text(item.orEmpty()) }
                    }
                }
            }
        } }
        compose.waitForIdle()
    }
    private fun start() = compose.onNodeWithTag("host").performTouchInput {
        val from = state.bounds().getValue(entries[1]).center
        down(from); advanceEventTime(650); moveTo(from + Offset(0f, 40f))
    }
    @Test fun legacyReadinessWiringReproducesHeldContactCancellation() {
        // Paired native witness for the old BrowserScreen wiring, without changing
        // production code or requiring a second APK build just to enable the bug.
        fixture(legacyReadinessWiring = true); start()
        compose.runOnIdle { assertNotNull(state.drag); ready = false }
        compose.waitUntil(5000) { state.drag == null }
        compose.onNodeWithTag("host").performTouchInput { up() }
        compose.runOnIdle { assertTrue(committed.isEmpty()) }
    }
    @Test fun pendingReadinessRejectsNewContactButDoesNotCancelAcquiredDrag() {
        fixture()
        compose.runOnIdle { ready = false }
        start()
        compose.onNodeWithTag("host").performTouchInput { up() }
        compose.runOnIdle { assertNull(state.drag); ready = true }
        start()
        val original = compose.runOnIdle { requireNotNull(state.drag) }
        compose.runOnIdle { ready = false; callbackRevision = 1 }
        compose.mainClock.advanceTimeBy(6200)
        compose.runOnIdle { assertSame(original, state.drag); assertFalse(original.dropping) }
        compose.onNodeWithTag("host").performTouchInput { up() }
        compose.waitUntil(5000) { state.drag == null }
        // The transaction retains the source revision captured at lift, while its target
        // insertion is still resolved from the live finger/layout on release.
        compose.runOnIdle { assertEquals(listOf(0), committed) }
    }
    @Test fun structuralOwnerChangeCancelsWithoutCommit() {
        fixture(); start()
        compose.runOnIdle { revision++ }
        compose.waitUntil(5000) { state.drag == null }
        compose.onNodeWithTag("host").performTouchInput { up() }
        compose.runOnIdle { assertTrue(committed.isEmpty()) }
    }
    @Test fun eligibilityRevocationStillCancelsWithoutCommit() {
        fixture(); start()
        compose.runOnIdle { eligible = false }
        compose.waitUntil(5000) { state.drag == null }
        compose.onNodeWithTag("host").performTouchInput { up() }
        compose.runOnIdle { assertTrue(committed.isEmpty()) }
    }
    @Test fun queuedQueueDropRejectsChangedOccurrenceOrderEvenForDuplicateTracks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val track = Track("same-track", "Same song", fileName = "same.mp3", relativePath = "same.mp3")
        val entries = (0..3).map { QueueEntry("duplicate-$it", track) }
        val queue = ListeningQueue(id = "queue", name = "Duplicates", entries = entries)
        val ids = entries.map { it.id }
        try {
            db.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id))
            val controller = withContext(Dispatchers.Main) {
                QueueController(db.queues(), QueuePreferences(context),
                    (context.applicationContext as LocalicaApplication).library, scope)
            }
            withTimeout(5000) { controller.state.first { !it.loading } }
            val first = CompletableDeferred<Boolean>()
            val stale = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                controller.moveBefore(queue.id, setOf(ids.last()), ids.first()) { first.complete(it) }
                controller.moveBefore(queue.id, setOf(ids[1]), null, ids) { stale.complete(it) }
            }
            assertTrue(withTimeout(5000) { first.await() })
            assertFalse(withTimeout(5000) { stale.await() })
            val expected = listOf(ids.last()) + ids.dropLast(1)
            assertEquals(expected, controller.state.value.library.queues.single().entries.map { it.id })
            assertEquals(expected, db.queues().read().queues.single().entries.map { it.id })
            assertEquals(queue.currentEntryId, controller.state.value.library.queues.single().currentEntryId)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
        }
    }

}
