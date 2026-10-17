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

class OrderCommitPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tracks = listOf("Beta", "Alpha").map {
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

    @Test fun queueNoopsUsePublishedIdentityAndBooleanAdaptersStillSucceed() = queueFixture { f ->
        val before = f.queue()
        val checkpoint = OrderCheckpoint.capture(before.entries.map { it.id }, before.order)
        val commits = listOf(
            receipt { f.controller.randomizeOrder(before.id, it) },
            receipt { f.controller.applySortOrder(before.id, TrackSort.Manual, true, it) },
            receipt { f.controller.revertOrderWithCommit(before.id, checkpoint, it) },
        )
        commits.forEach { value ->
            val commit = requireNotNull(value)
            assertFalse(commit.changed)
            // Each edit allocates an equivalent list. StateFlow retains the old snapshot.
            assertSame(before.entries, commit.revision)
            assertSame(f.queue().entries, commit.revision)
            assertEquals(before.order, commit.order)
            assertEquals(before.currentEntryId, commit.currentEntryId)
        }
        assertTrue(completed { f.controller.randomize(before.id, it) })
        assertTrue(completed { f.controller.applySort(before.id, TrackSort.Manual, true, it) })
        assertTrue(completed { f.controller.revertOrder(before.id, checkpoint, it) })
        assertEquals(before, f.queue())
        assertEquals(f.controller.state.value.library, f.db.queues().read())
    }

    @Test fun queuedQueueReceiptsUseEachActorPredecessorAndCompletionSelection() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        queueFixture(sortSnapshot = { entered.complete(Unit); release.await(); LibrarySnapshot(tracks = tracks) }) { f ->
            val results = List(4) { CompletableDeferred<Pair<OrderCommit?, ListeningQueue>>() }
            fun observe(index: Int): (OrderCommit?) -> Unit = { results[index].complete(it to f.queue()) }
            withContext(Dispatchers.Main) {
                f.controller.applySortOrder("active", TrackSort.Title, true, observe(0))
                f.controller.applySortOrder("active", TrackSort.Title, true, observe(1))
                f.controller.randomizeOrder("active", observe(2))
                f.controller.randomizeOrder("active", observe(3))
            }
            withTimeout(10_000) { entered.await() }
            assertTrue(results.none { it.isCompleted })
            release.complete(Unit)
            val observations = withTimeout(10_000) { results.map { it.await() } }
            assertEquals(listOf(true, false, true, false), observations.map { requireNotNull(it.first).changed })
            observations.forEach { (value, published) ->
                val commit = requireNotNull(value)
                assertSame(published.entries, commit.revision)
                assertEquals(published.order, commit.order)
                assertEquals("active-0", commit.currentEntryId)
                assertEquals(1234L, published.positionMs)
            }
            assertSame(observations[0].first!!.revision, observations[1].first!!.revision)
            assertSame(observations[2].first!!.revision, observations[3].first!!.revision)
            assertEquals(listOf("active-1", "active-0"), observations[0].second.entries.map { it.id })
            assertEquals(listOf("active-0", "active-1"), observations[2].second.entries.map { it.id })

            // The switch is queued first, so a receipt for the old active queue has no cursor.
            val inactive = CompletableDeferred<OrderCommit?>()
            withContext(Dispatchers.Main) {
                f.controller.switchTo("inactive")
                f.controller.randomizeOrder("active") { inactive.complete(it) }
            }
            assertNull(requireNotNull(withTimeout(10_000) { inactive.await() }).currentEntryId)
            assertEquals("inactive", f.controller.state.value.library.activeId)
            assertEquals("inactive-0", requireNotNull(receipt { f.controller.randomizeOrder("inactive", it) }).currentEntryId)
            assertEquals(f.controller.state.value.library, f.db.queues().read())
        }
    }

    @Test fun queueFailedSaveAndMissingTargetReturnNullAndSuccessfulRetryCommits() = queueFixture { f ->
        val before = f.queue()
        val checkpoint = OrderCheckpoint.capture(before.entries.map { it.id }, before.order)
        f.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_order BEFORE UPDATE OF orderState ON queues BEGIN SELECT RAISE(ABORT, 'Injected order failure'); END")
        assertNull(receipt { f.controller.applySortOrder(before.id, TrackSort.Title, true, it) })
        assertSame(before.entries, f.queue().entries)
        assertEquals(before, f.db.queues().read().active)
        assertNull(receipt { f.controller.randomizeOrder("missing", it) })
        assertFalse(completed { f.controller.randomize("missing", it) })
        f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_order")
        val sorted = requireNotNull(receipt { f.controller.applySortOrder(before.id, TrackSort.Title, true, it) })
        assertTrue(sorted.changed)
        assertSame(f.queue().entries, sorted.revision)
        assertEquals(f.queue(), f.db.queues().read().active)
        val reverted = requireNotNull(receipt { f.controller.revertOrderWithCommit(before.id, checkpoint, it) })
        assertTrue(reverted.changed)
        assertEquals(before.order, reverted.order)
        assertSame(f.queue().entries, reverted.revision)
        assertEquals(before, f.db.queues().read().active)
    }

    @Test fun queuedPlaylistRandomizeCountsSavedOrderChangeThenActualNoop() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        playlistFixture(sortSnapshot = { entered.complete(Unit); release.await(); LibrarySnapshot(tracks = tracks) }) { f ->
            val opening = f.playlist()
            val noop = requireNotNull(receipt { f.controller.randomizeOrder(opening.id, it) })
            assertFalse(noop.changed)
            assertSame(opening.entries, noop.revision)
            assertNull(noop.currentEntryId)
            val sorted = CompletableDeferred<Boolean>()
            val first = CompletableDeferred<Pair<OrderCommit?, ManualPlaylist>>()
            val second = CompletableDeferred<Pair<OrderCommit?, ManualPlaylist>>()
            f.controller.applySort(opening.id, TrackSort.Title, true) { sorted.complete(it) }
            withTimeout(10_000) { entered.await() }
            f.controller.randomizeOrder(opening.id) { first.complete(it to f.playlist()) }
            f.controller.randomizeOrder(opening.id) { second.complete(it to f.playlist()) }
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            release.complete(Unit)
            assertTrue(withTimeout(10_000) { sorted.await() })
            val changed = withTimeout(10_000) { first.await() }
            val unchanged = withTimeout(10_000) { second.await() }
            assertTrue(requireNotNull(changed.first).changed)
            assertFalse(requireNotNull(unchanged.first).changed)
            assertEquals(opening.entries, changed.second.entries) // Only SavedOrder changed.
            assertEquals(opening.order, changed.first!!.order)
            assertSame(changed.second.entries, changed.first!!.revision)
            assertSame(unchanged.second.entries, unchanged.first!!.revision)
            assertSame(changed.first!!.revision, unchanged.first!!.revision)
            assertNull(changed.first!!.currentEntryId)
            assertTrue(completed { f.controller.randomize(opening.id, it) })
            assertEquals(f.playlist(), f.db.playlists().read().single())
        }
    }

    @Test fun playlistFailedSaveAndDeletedTargetReturnNullAndRetryCommits() = playlistFixture { f ->
        val id = f.playlist().id
        assertTrue(completed { f.controller.applySort(id, TrackSort.Title, true, it) })
        val before = f.playlist()
        f.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_order BEFORE UPDATE ON playlists BEGIN SELECT RAISE(ABORT, 'Injected order failure'); END")
        assertNull(receipt { f.controller.randomizeOrder(id, it) })
        assertSame(before.entries, f.playlist().entries)
        assertEquals(before, f.db.playlists().read().single())
        assertFalse(completed { f.controller.randomize(id, it) })
        f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_order")
        val committed = requireNotNull(receipt { f.controller.randomizeOrder(id, it) })
        assertTrue(committed.changed)
        assertSame(f.playlist().entries, committed.revision)
        assertEquals(f.playlist(), f.db.playlists().read().single())
        val deleted = CompletableDeferred<Boolean>()
        val missing = CompletableDeferred<OrderCommit?>()
        f.controller.delete(id) { deleted.complete(it) }
        f.controller.randomizeOrder(id) { missing.complete(it) }
        assertTrue(withTimeout(10_000) { deleted.await() })
        assertNull(withTimeout(10_000) { missing.await() })
        assertTrue(f.controller.state.value.playlists.isEmpty())
    }

    private class QueueFixture(val db: QueueDatabase, val controller: QueueController) {
        fun queue() = controller.state.value.library.queues.single { it.id == "active" }
    }

    private fun queueFixture(sortSnapshot: suspend () -> LibrarySnapshot = { LibrarySnapshot(tracks = tracks) },
        action: suspend (QueueFixture) -> Unit) = runBlocking {
        val preferencesName = "order-commit-${UUID.randomUUID()}"
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferencesName, mode)
        })
        preferences.write(QueueSettings(switching = QueueSwitch.Paused))
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val queues = listOf("active", "inactive").map { id ->
                ListeningQueue(id = id, name = id, entries = tracks.mapIndexed { index, track ->
                    QueueEntry("$id-$index", track, "source-playlist")
                }, positionMs = 1234)
            }
            db.queues().save(QueueLibrary(), QueueLibrary(queues, "active"))
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

    private fun playlistFixture(sortSnapshot: suspend () -> LibrarySnapshot = { LibrarySnapshot(tracks = tracks) },
        action: suspend (PlaylistFixture) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            // A singleton makes both the unchanged and saved-settings-only randomizations deterministic.
            val playlist = ManualPlaylist(id = "playlist", name = "Mix", entries = listOf(SongRow("only", tracks.first())))
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
