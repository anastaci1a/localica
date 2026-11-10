package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueueTraversalTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun queue(size: Int, current: Int, modes: JourneyModes): ListeningQueue {
        val entries = List(size) { QueueEntry(id = "occurrence:$it", track = Track("song:${it % 11}", "Song ${it % 11}",
            fileName = "song.mp3", relativePath = "song.mp3")) }
        return ListeningQueue(name = "Traversal", entries = entries, currentEntryId = entries[current].id,
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
            }
        } finally { room.close() }
    }
}
