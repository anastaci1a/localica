package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class QueueTraversalTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun queue(size: Int, current: Int, modes: JourneyModes): ListeningQueue {
        val namespace = java.util.UUID.randomUUID().toString()
        val entries = List(size) { QueueEntry(id = "$namespace:occurrence:$it", track = Track("song:${it % 11}", "Song ${it % 11}",
            fileName = "song.mp3", relativePath = "song.mp3")) }
        return ListeningQueue(id = namespace, name = "Traversal", entries = entries, currentEntryId = entries[current].id,
            journey = QueueJourney.start(entries.map { it.id }, entries[current].id, modes, kotlin.random.Random(31)))
    }

    private fun compare(queue: ListeningQueue) {
        val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        try {
            runBlocking { room.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id)) }
            val db = room.openHelper.writableDatabase
            QueueSequence.createSchema(db)
            QueueTraversal.createSchema(db)
            db.beginTransaction()
            try {
                val sequence = QueueSequence(db)
                QueueSequenceMigration(sequence).import(queue.id)
                val traversal = QueueTraversal(sequence)
                val window = traversal.restore(queue.id, queue.currentEntryId, requireNotNull(queue.journey).modes)
                assertEquals(queue.journey!!.window(), window.visits)
                assertEquals(queue.journey!!.current, window.current)
                assertEquals(queue.journey!!.previous, window.previous)
                assertEquals(queue.journey!!.next, window.next)
                assertTrue(window.visits.size <= QueuePlaybackWindow.MaximumVisits)
                assertEquals(window.visits.map { it.entryId }.toSet(), sequence.entries(queue.id, window.positions.keys).map { it.id }.toSet())
                assertEquals(window, traversal.window(queue.id))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } finally { room.close() }
    }

    @Test fun orderedProjectionMatchesTheExistingTokensAcrossBothLoopBoundaries() {
        for (size in listOf(1, 7, 2103)) for (index in setOf(0, size / 2, size - 1)) {
            compare(queue(size, index, JourneyModes(loop = true)))
        }
    }

    @Test fun orderedProjectionMatchesTheExistingNonLoopingEndpoints() {
        for (index in listOf(0, 1050, 2102)) compare(queue(2103, index, JourneyModes()))
    }

    @Test fun shuffledProjectionPreservesRewoundHistoryConcreteFutureAndScheduledVisits() {
        var queue = queue(2103, 0, JourneyModes(shuffle = true, loop = true, reshuffle = true))
        val ids = queue.entries.map { it.id }
        var journey = queue.journey!!
        for (index in listOf(17, 41, 999, 1001)) journey = journey.select(ids[index], ids)
        journey = journey.arrive(journey.history[1].token, ids)
        queue = queue.copy(currentEntryId = journey.current!!.entryId, journey = journey)
        compare(queue)
        val inserted = QueueEdits.insert(queue, listOf(queue.entries[7].track, queue.entries[9].track), SongTap.PlayNext)
        val added = inserted.entries.filter { it.id !in ids }.map { it.id }
        val scheduled = journey.playNext(added, inserted.entries.map { it.id })
        compare(inserted.copy(journey = scheduled))
    }

    @Test fun missingLegacyJourneyInitializesExactShuffleOrderWithBoundedProjection() {
        for (size in listOf(1, 13, 211)) for (loop in listOf(false, true)) for (reshuffle in listOf(false, true)) {
            val modes = JourneyModes(shuffle = true, loop = loop, reshuffle = reshuffle)
            val queue = queue(size, size / 2, modes).copy(journey = null)
            val reference = QueueJourney.start(queue.entries.map { it.id }, queue.currentEntryId, modes, kotlin.random.Random(31))
            val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
            try {
                runBlocking { room.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id)) }
                val db = room.openHelper.writableDatabase
                QueueSequence.createSchema(db); QueueTraversal.createSchema(db)
                db.beginTransaction()
                try {
                    val sequence = QueueSequence(db)
                    QueueSequenceMigration(sequence).import(queue.id)
                    val traversal = QueueTraversal(sequence)
                    val window = traversal.restore(queue.id, queue.currentEntryId, modes, kotlin.random.Random(31))
                    assertEquals(reference.window().map { it.entryId to it.cycle }, window.visits.map { it.entryId to it.cycle })
                    assertEquals(reference.next?.entryId, window.next?.entryId)
                    assertEquals(reference.previous?.entryId, window.previous?.entryId)
                    assertTrue(window.visits.size <= QueuePlaybackWindow.MaximumVisits)
                    assertEquals(window, traversal.restore(queue.id, queue.currentEntryId, modes, kotlin.random.Random(31)))
                    db.query("SELECT COUNT(*) FROM queue_sequence_history WHERE queueId = ?", arrayOf(queue.id)).use {
                        assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } finally { room.close() }
        }
    }

    @Test fun storeRestoresOnlyTheActiveWindowAndRetainsExactLogicalCountsAndCursors() {
        val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        try {
            val first = queue(2103, 1050, JourneyModes())
            val second = queue(700, 400, JourneyModes())
            val saved = QueueLibrary(listOf(first, second), first.id)
            runBlocking {
                room.queues().save(QueueLibrary(), saved)
                val restored = QueueWindowStore(room).restore(QueueSettings())
                assertEquals(saved.activeId, restored.activeId)
                assertEquals(listOf(2103, 700), restored.queues.map { it.entryCount })
                assertEquals(first.currentEntryId, restored.active!!.currentEntryId)
                assertEquals(1050, restored.active!!.currentIndex)
                assertEquals(first.journey!!.window(), restored.active!!.playbackWindow!!.visits)
                assertTrue(restored.active!!.entries.size <= QueuePlaybackWindow.MaximumVisits)
                assertTrue(restored.queues[1].entries.isEmpty())
                assertEquals(second.currentEntryId, restored.queues[1].currentEntryId)
                assertEquals(400, restored.queues[1].currentIndex)
                val active = restored.active!!
                val token = active.playbackWindow!!.current!!.token
                val store = QueueWindowStore(room)
                val legacyOrder = room.openHelper.writableDatabase.query("SELECT orderState FROM queues WHERE id = ?",
                    arrayOf(first.id)).use { assertTrue(it.moveToFirst()); it.getString(0) }
                assertFalse(store.checkpoint(first.id, first.currentEntryId!!, "stale-token", 88))
                assertFalse(store.checkpoint(first.id, first.entries.first().id, token, 99))
                assertFalse(store.checkpoint(second.id, second.currentEntryId!!, token, 111))
                assertTrue(store.checkpoint(first.id, first.currentEntryId!!, token, 1234))
                val afterCheckpoint = store.restore(QueueSettings()).active!!
                assertEquals(1234L, afterCheckpoint.positionMs)
                assertEquals(active.storage, afterCheckpoint.storage)
                assertEquals(active.playbackWindow, afterCheckpoint.playbackWindow)
                room.openHelper.writableDatabase.query("SELECT orderState FROM queues WHERE id = ?",
                    arrayOf(first.id)).use { assertTrue(it.moveToFirst()); assertEquals(legacyOrder, it.getString(0)) }

            }
        } finally { room.close() }
    }
    @Test fun concurrentFirstWindowOperationsInstallSchemaOnceWithoutDeadlock() {
        val installations = java.util.concurrent.atomic.AtomicInteger()
        val room = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java)
            .setQueryCallback(androidx.room.RoomDatabase.QueryCallback { sql, _ ->
                if (sql.startsWith("CREATE TABLE IF NOT EXISTS queue_sequence_state")) installations.incrementAndGet()
            }, java.util.concurrent.Executor { it.run() }).build()
        try {
            val store = QueueWindowStore(room)
            runBlocking {
                withTimeout(10000) {
                    val first = async(Dispatchers.IO) { store.checkpoint("missing", "entry", "token", 1) }
                    val second = async(Dispatchers.IO) { store.checkpoint("missing", "entry", "token", 2) }
                    assertFalse(first.await())
                    assertFalse(second.await())
                }
            }
            assertEquals(1, installations.get())
        } finally { room.close() }
    }

}
