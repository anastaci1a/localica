package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RevertOrderPersistenceTest {
    private val tracks = listOf("C", "A", "A", "B").map { Track("missing:$it", it, fileName = "$it.mp3", relativePath = "$it.mp3") }
    private suspend fun completed(command: ((Boolean) -> Unit) -> Unit): Boolean {
        val result = CompletableDeferred<Boolean>()
        command { result.complete(it) }
        return withTimeout(10000) { result.await() }
    }

    @Test fun playlistRevertUsesCurrentMembershipAndFailedPersistenceCanRetry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val opening = PlaylistEdits.sort(PlaylistEdits.append(ManualPlaylist(name = "Mix"), tracks), TrackSort.Title, false)
        db.playlists().save(emptyList(), listOf(opening))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = PlaylistController(db.playlists(), scope)
        try {
            withTimeout(10000) { controller.state.first { !it.loading } }
            val checkpoint = OrderCheckpoint.capture(opening.entries.map { it.id }, opening.order)
            assertTrue(completed { controller.reverse(opening.id, it) })
            assertTrue(completed { controller.randomize(opening.id, it) })
            assertTrue(completed { controller.reverse(opening.id, it) })
            assertTrue(completed { controller.append(opening.id, listOf(tracks.first()), it) })
            assertTrue(completed { controller.removeEntries(opening.id, setOf(opening.entries.first().id), it) })
            assertTrue(completed { controller.rename(opening.id, "Renamed", it) })
            val current = controller.state.value.playlists.single()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_revert BEFORE UPDATE ON playlists BEGIN SELECT RAISE(ABORT, 'Injected write failure'); END")
            assertFalse(completed { controller.revertOrder(opening.id, checkpoint, it) })
            assertEquals(current, controller.state.value.playlists.single())
            assertEquals(current, db.playlists().read().single())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_revert")
            assertTrue(completed { controller.revertOrder(opening.id, checkpoint, it) })
            val restored = controller.state.value.playlists.single()
            val expected = PlaylistEdits.revertOrder(current, checkpoint)
            assertEquals(expected.copy(modifiedAt = restored.modifiedAt), restored)
            assertEquals(restored, db.playlists().read().single())
        } finally { scope.cancel(); db.close() }
    }

    @Test fun queueRevertUsesCurrentMembershipAndFailedPersistenceCanRetry() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val opening = QueueEdits.sort(QueueEdits.create(tracks, 2, "Mix"), TrackSort.Title, false).copy(positionMs = 1234)
        db.queues().save(QueueLibrary(), QueueLibrary(listOf(opening), opening.id))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: QueueController
        try {
            instrumentation.runOnMainSync {
                controller = QueueController(db.queues(), QueuePreferences(context), (context.applicationContext as LocalicaApplication).library, scope)
            }
            withTimeout(10000) { controller.state.first { !it.loading } }
            val checkpoint = OrderCheckpoint.capture(opening.entries.map { it.id }, opening.order)
            assertTrue(completed { controller.reverse(opening.id, it) })
            assertTrue(completed { controller.randomize(opening.id, it) })
            assertTrue(completed { controller.reverse(opening.id, it) })
            controller.rename(opening.id, "Renamed")
            assertTrue(completed { controller.removeEntries(setOf(opening.entries.first().id), opening.id, it) })
            val current = controller.state.value.library.active!!
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_revert BEFORE UPDATE OF orderState ON queues BEGIN SELECT RAISE(ABORT, 'Injected write failure'); END")
            assertFalse(completed { controller.revertOrder(opening.id, checkpoint, it) })
            assertEquals(current, controller.state.value.library.active)
            assertEquals(current, db.queues().read().active)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_revert")
            assertTrue(completed { controller.revertOrder(opening.id, checkpoint, it) })
            val restored = controller.state.value.library.active!!
            assertEquals(QueueEdits.revertOrder(current, checkpoint), restored)
            assertEquals(restored, db.queues().read().active)
            assertEquals(current.currentEntryId, restored.currentEntryId)
            assertEquals(1234L, restored.positionMs)
        } finally { instrumentation.runOnMainSync { scope.cancel() }; db.close() }
    }
}
