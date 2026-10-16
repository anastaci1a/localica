package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QueueReorderCommandTest {
    @Test fun latestIdentityOrderPreservesEveryQueueAndBothSelections() = fixture { f ->
        f.controller.switchTo("a")
        assertTrue(f.move("a", "a")) // Also acknowledges the preceding browse command.
        val before = f.controller.state.value
        val first = CompletableDeferred<Boolean>()
        val second = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            f.controller.moveQueueBefore("c", "a") { first.complete(it) }
            f.controller.moveQueueBefore("b", "c") { second.complete(it) }
        }
        assertTrue(withTimeout(10_000) { first.await() })
        assertTrue(withTimeout(10_000) { second.await() })
        assertEquals(listOf("b", "c", "a"), f.order())
        val after = f.controller.state.value
        assertEquals(before.copy(library = before.library.copy(queues = listOf(
            before.library.queues[1], before.library.queues[2], before.library.queues[0]))), after)
        assertEquals(after.library, f.db.queues().read())
        assertTrue(f.move("c", null))
        assertEquals(listOf("b", "a", "c"), f.order())
        // Existing offset-based accessibility actions remain valid.
        f.controller.moveQueue("c", -2)
        assertTrue(f.move("c", "c"))
        assertEquals(listOf("c", "b", "a"), f.order())
    }

    @Test fun staleTargetsRejectAndNoOpDropsDoNotWrite() = fixture { f ->
        val before = f.controller.state.value
        f.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_any_queue_update BEFORE UPDATE ON queues BEGIN SELECT RAISE(ABORT, 'Unexpected write'); END")
        assertFalse(f.move("missing", "a"))
        assertFalse(f.move("a", "missing"))
        assertTrue(f.move("a", "a"))
        assertTrue(f.move("a", "b"))
        assertTrue(f.move("c", null))
        assertEquals(before, f.controller.state.value)
        assertEquals(before.library, f.db.queues().read())
    }

    @Test fun failedSaveRollsBackOrderAndCanBeRetried() = fixture { f ->
        val before = f.controller.state.value
        f.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_reorder BEFORE UPDATE ON queues WHEN NEW.id = 'b' AND NEW.ordinal != OLD.ordinal BEGIN SELECT RAISE(ABORT, 'Injected reorder failure'); END")
        assertFalse(f.move("c", "a"))
        withTimeout(10_000) { while (f.controller.state.value.problem == null) delay(10) }
        assertEquals(before.library, f.controller.state.value.library)
        assertEquals(before.library, f.db.queues().read())
        assertEquals(before.browsedId, f.controller.state.value.browsedId)
        f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_reorder")
        assertTrue(f.move("c", "a"))
        assertEquals(listOf("c", "a", "b"), f.order())
        assertEquals(before.library.queues.associateBy { it.id },
            f.controller.state.value.library.queues.associateBy { it.id })
        assertEquals(f.controller.state.value.library, f.db.queues().read())
        assertNull(f.controller.state.value.problem)
    }

    private class Fixture(val db: QueueDatabase, val controller: QueueController) {
        fun order() = controller.state.value.library.queues.map { it.id }
        suspend fun move(id: String, before: String?): Boolean {
            val result = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) { controller.moveQueueBefore(id, before) { result.complete(it) } }
            return withTimeout(10_000) { result.await() }
        }
    }

    private fun fixture(action: suspend (Fixture) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            preferences.write(QueueSettings())
            val track = Track("content://test/same", "Duplicate", fileName = "same.mp3", relativePath = "same.mp3")
            val queues = listOf("a", "b", "c").mapIndexed { index, id ->
                val entries = (0..1).map { QueueEntry("$id-$it", track, "playlist-$id") }
                ListeningQueue(id, "Queue $id", index.toLong(), entries, entries[1].id, 1234L + index,
                    journey = QueueJourney.start(entries.map { it.id }, entries[1].id, JourneyModes()))
            }
            db.queues().save(QueueLibrary(), QueueLibrary(queues, "b"))
            val controller = withContext(Dispatchers.Main) {
                QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope)
            }
            withTimeout(10_000) { while (controller.state.value.loading) delay(10) }
            assertNull(controller.state.value.problem)
            action(Fixture(db, controller))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            preferences.write(original)
            db.close()
        }
    }
}
