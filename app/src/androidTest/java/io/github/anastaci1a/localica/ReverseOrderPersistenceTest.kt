package io.github.anastaci1a.localica

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ReverseOrderPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tracks = listOf("C", "A", "B", "A").map {
        Track("missing:$it", it, fileName = "$it.mp3", relativePath = "$it.mp3")
    }

    private suspend fun receipt(command: ((OrderCommit?) -> Unit) -> Unit): OrderCommit? {
        val result = CompletableDeferred<OrderCommit?>()
        command { result.complete(it) }
        return withTimeout(10_000) { result.await() }
    }

    private suspend fun completed(command: ((Boolean) -> Unit) -> Unit): Boolean {
        val result = CompletableDeferred<Boolean>()
        command { result.complete(it) }
        return withTimeout(10_000) { result.await() }
    }

    @Test fun queueReverseCommitsDuplicateOccurrencesCursorAndManualBaselineThenRestoresCheckpoint() = queueFixture { f ->
        val before = f.queue()
        val checkpoint = OrderCheckpoint.capture(before.entries.map { it.id }, before.order)
        val commit = requireNotNull(receipt { f.controller.reverseOrder(before.id, it) })
        val reversed = f.queue()
        assertTrue(commit.changed)
        assertSame(reversed.entries, commit.revision)
        assertEquals(before.entries.reversed(), reversed.entries)
        assertEquals(SavedOrder(reversed.entries.map { it.id }), commit.order)
        assertEquals(before.currentEntryId, commit.currentEntryId)
        assertNotEquals(reversed.currentEntryId, reversed.entries.first().id)
        assertEquals(before.positionMs, reversed.positionMs)
        assertEquals(before.journey, reversed.journey)
        assertEquals(reversed, f.db.queues().read().active)
        assertTrue(completed { f.controller.reverse(before.id, it) })
        assertEquals(before.entries, f.queue().entries)
        assertEquals(SavedOrder(before.entries.map { it.id }), f.queue().order)
        assertTrue(completed { f.controller.revertOrder(before.id, checkpoint, it) })
        assertEquals(before, f.queue())
        assertEquals(before, f.db.queues().read().active)
    }

    @Test fun playlistReverseCommitsDuplicatesAndManualBaselineThenRestoresCheckpoint() = playlistFixture { f ->
        val before = f.playlist()
        val checkpoint = OrderCheckpoint.capture(before.entries.map { it.id }, before.order)
        val commit = requireNotNull(receipt { f.controller.reverseOrder(before.id, it) })
        val reversed = f.playlist()
        assertTrue(commit.changed)
        assertSame(reversed.entries, commit.revision)
        assertNull(commit.currentEntryId)
        assertEquals(before.entries.reversed(), reversed.entries)
        assertEquals(SavedOrder(reversed.entries.map { it.id }), commit.order)
        assertTrue(reversed.modifiedAt > before.modifiedAt)
        assertEquals(before.copy(entries = reversed.entries, order = reversed.order, modifiedAt = reversed.modifiedAt), reversed)
        assertEquals(reversed, f.db.playlists().read().single())
        assertTrue(completed { f.controller.reverse(before.id, it) })
        assertEquals(before.entries, f.playlist().entries)
        assertEquals(SavedOrder(before.entries.map { it.id }), f.playlist().order)
        assertTrue(completed { f.controller.revertOrder(before.id, checkpoint, it) })
        assertEquals(before.copy(modifiedAt = f.playlist().modifiedAt), f.playlist())
        assertEquals(f.playlist(), f.db.playlists().read().single())
    }

    @Test fun queueFailedReverseLeavesDurableOrderUntouchedAndCanRetry() = queueFixture { f ->
        val before = f.queue()
        f.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_reverse BEFORE UPDATE OF orderState ON queues BEGIN SELECT RAISE(ABORT, 'Injected order failure'); END")
        assertNull(receipt { f.controller.reverseOrder(before.id, it) })
        assertFalse(completed { f.controller.reverse(before.id, it) })
        assertSame(before.entries, f.queue().entries)
        assertEquals(before, f.db.queues().read().active)
        f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_reverse")
        assertTrue(requireNotNull(receipt { f.controller.reverseOrder(before.id, it) }).changed)
        assertEquals(before.entries.reversed(), f.queue().entries)
        assertEquals(f.queue(), f.db.queues().read().active)
        assertNull(receipt { f.controller.reverseOrder("missing", it) })
        assertFalse(completed { f.controller.reverse("missing", it) })
    }

    @Test fun playlistFailedReverseLeavesDurableOrderUntouchedAndCanRetry() = playlistFixture { f ->
        val before = f.playlist()
        f.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_reverse BEFORE UPDATE ON playlists BEGIN SELECT RAISE(ABORT, 'Injected order failure'); END")
        assertNull(receipt { f.controller.reverseOrder(before.id, it) })
        assertFalse(completed { f.controller.reverse(before.id, it) })
        assertSame(before.entries, f.playlist().entries)
        assertEquals(before, f.db.playlists().read().single())
        f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_reverse")
        assertTrue(requireNotNull(receipt { f.controller.reverseOrder(before.id, it) }).changed)
        assertEquals(before.entries.reversed(), f.playlist().entries)
        assertEquals(f.playlist(), f.db.playlists().read().single())
        assertNull(receipt { f.controller.reverseOrder("missing", it) })
        assertFalse(completed { f.controller.reverse("missing", it) })
    }

    @Test fun queueEmptyAndSingletonReverseDoNotWriteOrResetSavedSort() {
        for (count in 0..1) queueFixture(count) { f ->
            val before = f.queue()
            f.db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_noop BEFORE UPDATE ON queues BEGIN SELECT RAISE(ABORT, 'No-op must not write'); END")
            val commit = requireNotNull(receipt { f.controller.reverseOrder(before.id, it) })
            assertFalse(commit.changed)
            assertSame(before.entries, commit.revision)
            assertEquals(before.order, commit.order)
            assertTrue(completed { f.controller.reverse(before.id, it) })
            assertEquals(before, f.queue())
            assertEquals(before, f.db.queues().read().active)
        }
    }

    @Test fun playlistEmptyAndSingletonReverseDoNotWriteOrResetSavedSort() {
        for (count in 0..1) playlistFixture(count) { f ->
            val before = f.playlist()
            f.db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_noop BEFORE UPDATE ON playlists BEGIN SELECT RAISE(ABORT, 'No-op must not write'); END")
            val commit = requireNotNull(receipt { f.controller.reverseOrder(before.id, it) })
            assertFalse(commit.changed)
            assertSame(before.entries, commit.revision)
            assertEquals(before.order, commit.order)
            assertTrue(completed { f.controller.reverse(before.id, it) })
            assertEquals(before, f.playlist())
            assertEquals(before, f.db.playlists().read().single())
        }
    }

    @Test fun queuedQueueReversalsReadEachActorCurrentOrderAfterSuspendedSort() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        queueFixture(sortSnapshot = { entered.complete(Unit); release.await(); LibrarySnapshot(tracks = tracks) }) { f ->
            val sorted = CompletableDeferred<List<QueueEntry>>()
            val first = CompletableDeferred<Pair<OrderCommit?, ListeningQueue>>()
            val second = CompletableDeferred<Pair<OrderCommit?, ListeningQueue>>()
            f.controller.applySort(f.queue().id, TrackSort.Title, false) { sorted.complete(f.queue().entries) }
            withTimeout(10_000) { entered.await() }
            f.controller.reverseOrder(f.queue().id) { first.complete(it to f.queue()) }
            f.controller.reverseOrder(f.queue().id) { second.complete(it to f.queue()) }
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            release.complete(Unit)
            val original = withTimeout(10_000) { sorted.await() }
            val reversed = withTimeout(10_000) { first.await() }
            val repeated = withTimeout(10_000) { second.await() }
            assertEquals(original.reversed(), reversed.second.entries)
            assertEquals(original, repeated.second.entries)
            for ((value, published) in listOf(reversed, repeated)) {
                assertTrue(requireNotNull(value).changed)
                assertSame(published.entries, value.revision)
                assertEquals(SavedOrder(published.entries.map { it.id }), value.order)
            }
            assertEquals(f.queue(), f.db.queues().read().active)
        }
    }

    @Test fun queuedPlaylistReversalsReadEachActorCurrentOrderAfterSuspendedSort() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        playlistFixture(sortSnapshot = { entered.complete(Unit); release.await(); LibrarySnapshot(tracks = tracks) }) { f ->
            val sorted = CompletableDeferred<List<SongRow>>()
            val first = CompletableDeferred<Pair<OrderCommit?, ManualPlaylist>>()
            val second = CompletableDeferred<Pair<OrderCommit?, ManualPlaylist>>()
            f.controller.applySort(f.playlist().id, TrackSort.Title, false) { sorted.complete(f.playlist().entries) }
            withTimeout(10_000) { entered.await() }
            f.controller.reverseOrder(f.playlist().id) { first.complete(it to f.playlist()) }
            f.controller.reverseOrder(f.playlist().id) { second.complete(it to f.playlist()) }
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            release.complete(Unit)
            val original = withTimeout(10_000) { sorted.await() }
            val reversed = withTimeout(10_000) { first.await() }
            val repeated = withTimeout(10_000) { second.await() }
            assertEquals(original.reversed(), reversed.second.entries)
            assertEquals(original, repeated.second.entries)
            for ((value, published) in listOf(reversed, repeated)) {
                assertTrue(requireNotNull(value).changed)
                assertSame(published.entries, value.revision)
                assertEquals(SavedOrder(published.entries.map { it.id }), value.order)
            }
            assertEquals(f.playlist(), f.db.playlists().read().single())
        }
    }

    private class QueueFixture(val db: QueueDatabase, val controller: QueueController) {
        fun queue() = requireNotNull(controller.state.value.library.active)
    }

    private fun queueFixture(count: Int = tracks.size,
        sortSnapshot: suspend () -> LibrarySnapshot = { LibrarySnapshot(tracks = tracks) },
        action: suspend (QueueFixture) -> Unit) = runBlocking {
        val preferencesName = "reverse-${UUID.randomUUID()}"
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferencesName, mode)
        })
        val settings = QueueSettings(shuffle = true)
        preferences.write(settings)
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val entries = tracks.take(count).mapIndexed { index, track -> QueueEntry("entry-$index", track, "origin-$index") }
            val current = entries.lastOrNull()?.id
            val queue = QueueEdits.sort(ListeningQueue(name = "Mix", entries = entries, currentEntryId = current,
                positionMs = 1234, journey = QueueJourney.start(entries.map { it.id }, current, JourneyModes.from(settings))),
                TrackSort.Title, true)
            db.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id))
            val controller = withContext(Dispatchers.Main) {
                QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library,
                    scope, sortSnapshot = sortSnapshot)
            }
            withTimeout(10_000) { controller.state.first { !it.loading } }
            assertNull(controller.state.value.problem)
            action(QueueFixture(db, controller))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
            context.deleteSharedPreferences(preferencesName)
        }
    }

    private class PlaylistFixture(val db: PlaylistDatabase, val controller: PlaylistController) {
        fun playlist() = controller.state.value.playlists.single()
    }

    private fun playlistFixture(count: Int = tracks.size,
        sortSnapshot: suspend () -> LibrarySnapshot = { LibrarySnapshot(tracks = tracks) },
        action: suspend (PlaylistFixture) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val playlist = PlaylistEdits.sort(ManualPlaylist(name = "Mix", createdAt = 1, modifiedAt = 42,
                entries = tracks.take(count).mapIndexed { index, track -> SongRow("entry-$index", track) }), TrackSort.Title, true)
            db.playlists().save(emptyList(), listOf(playlist))
            val controller = PlaylistController(db.playlists(), scope, sortSnapshot = sortSnapshot)
            withTimeout(10_000) { controller.state.first { !it.loading } }
            assertNull(controller.state.value.problem)
            action(PlaylistFixture(db, controller))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
        }
    }
}
