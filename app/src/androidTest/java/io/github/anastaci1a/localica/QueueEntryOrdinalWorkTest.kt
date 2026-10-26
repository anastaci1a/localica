package io.github.anastaci1a.localica

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSnapshot
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** SQL transaction rollback and owner isolation belong to QueueEntryOrdinalPersistenceTest. */
@RunWith(AndroidJUnit4::class)
class QueueEntryOrdinalWorkTest {
    private fun queue(): ListeningQueue {
        val song = Track("content://song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        return ListeningQueue(id = "queue", name = "Fixture", entries = listOf(
            QueueEntry("one", song, "album"), QueueEntry("two", song.copy(), null),
            QueueEntry("three", song.copy(title = "Other title"), "playlist")), currentEntryId = "two")
    }
    private fun library(queue: ListeningQueue) = QueueLibrary(listOf(queue), queue.id)

    @Test fun successfulOrdinalWriteIncludesStationaryOccurrenceAndAvoidsPayloadReplacement() = runBlocking {
        val before = queue()
        val after = QueueEdits.reverse(before)
        val dao = RecordingDao()
        dao.save(library(before), library(after))
        assertEquals(listOf("queue" to listOf("three", "two", "one")), dao.ordinalCalls)
        assertTrue(dao.clears.isEmpty())
        assertTrue(dao.inserts.isEmpty())
        assertEquals(listOf(ActiveQueueRecord(queueId = "queue")), dao.active)
        assertEquals(1, dao.queues.size)
        assertEquals(before.journey, after.journey)
    }

    @Test fun shortOrdinalCountsRepairAllOccurrencesWithRealEncodedPayloadsAndOrigins() = runBlocking {
        val before = queue()
        val after = QueueEdits.reverse(before)
        val expected = after.entries.mapIndexed { ordinal, entry ->
            EntryRecord(entry.id, after.id, ordinal, TrackSnapshot.encode(entry.track), entry.sourcePlaylistId)
        }
        for (count in listOf(0, 2)) {
            val dao = RecordingDao().apply { matched = count }
            dao.save(library(before), library(after))
            assertEquals(listOf("queue" to listOf("three", "two", "one")), dao.ordinalCalls)
            assertEquals(listOf("queue"), dao.clears)
            assertEquals(listOf(expected), dao.inserts)
            assertEquals(listOf(ActiveQueueRecord(queueId = "queue")), dao.active)
        }
    }

    @Test fun negativeAndExcessCountsFailWithoutRepairOrActiveCompletion() = runBlocking {
        val before = queue()
        for (count in listOf(-1, 4)) {
            val dao = RecordingDao().apply { matched = count }
            try {
                dao.save(library(before), library(QueueEdits.reverse(before)))
                fail("Invalid count $count must fail")
            } catch (failure: IllegalStateException) {
                assertEquals("Unexpected saved occurrence count", failure.message)
            }
            assertEquals(listOf("queue" to listOf("three", "two", "one")), dao.ordinalCalls)
            assertTrue(dao.clears.isEmpty())
            assertTrue(dao.inserts.isEmpty())
            assertTrue(dao.active.isEmpty())
            // Earlier queue writes are observable in this fake: no simulated rollback claim.
            assertEquals(1, dao.queues.size)
        }
    }

    @Test fun writerExceptionPropagatesByIdentityWithoutRepairOrActiveCompletion() = runBlocking {
        val before = queue()
        val expected = IllegalArgumentException("owned writer failure")
        val dao = RecordingDao().apply { writerFailure = expected }
        try {
            dao.save(library(before), library(QueueEdits.reverse(before)))
            fail("Writer failure must propagate")
        } catch (actual: IllegalArgumentException) {
            assertSame(expected, actual)
        }
        assertEquals(listOf("queue" to listOf("three", "two", "one")), dao.ordinalCalls)
        assertTrue(dao.clears.isEmpty())
        assertTrue(dao.inserts.isEmpty())
        assertTrue(dao.active.isEmpty())
        assertEquals(1, dao.queues.size)
    }

    private class RecordingDao : QueueDao() {
        var matched: Int? = null
        var writerFailure: RuntimeException? = null
        val ordinalCalls = mutableListOf<Pair<String, List<String>>>()
        val queues = mutableListOf<QueueRecord>()
        val clears = mutableListOf<String>()
        val inserts = mutableListOf<List<EntryRecord>>()
        val active = mutableListOf<ActiveQueueRecord>()
        override suspend fun updateEntryOrdinals(queueId: String, entryIds: List<String>): Int {
            ordinalCalls += queueId to entryIds.toList()
            writerFailure?.let { throw it }
            return matched ?: entryIds.size
        }
        override suspend fun upsertQueue(queue: QueueRecord) { queues += queue }
        override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int = error("Order changed")
        override suspend fun clearEntries(id: String) { clears += id }
        override suspend fun insertEntries(entries: List<EntryRecord>) { inserts += entries.toList() }
        override suspend fun upsertActive(active: ActiveQueueRecord) { this.active += active }
        override suspend fun readQueues(): List<StoredQueue> = error("Not needed")
        override suspend fun readActive(): ActiveQueueRecord? = error("Not needed")
        override suspend fun deleteQueue(id: String): Unit = error("No removed queue")
        override suspend fun upsertJourney(journey: JourneyRecord): Unit = error("Journey unchanged")
        override suspend fun clearJourney(id: String): Unit = error("Journey unchanged")
        override suspend fun upsertVisits(visits: List<VisitRecord>): Unit = error("Journey unchanged")
        override suspend fun updateVisits(visits: List<VisitRecord>): Int = error("Journey unchanged")
        override suspend fun upsertCycleMembers(members: List<CycleMemberRecord>): Unit = error("Journey unchanged")
        override suspend fun updateCycleMembers(members: List<CycleMemberRecord>): Int = error("Journey unchanged")
        override suspend fun deleteVisits(queueId: String, tokens: List<String>): Unit = error("Journey unchanged")
        override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>): Unit = error("Journey unchanged")
    }
}
