package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueuePersistenceWorkTest {
    @Test fun thirteenThousandEntryCursorCheckpointDoesNotReadOrEncodeUnchangedOrder() = runBlocking {
        val queue = queue(13_000)
        val manual = CountingList(queue.order.manual)
        val before = queue.copy(order = SavedOrder(manual))
        val dao = RecordingDao()
        dao.save(library(before), library(before.copy(positionMs = 1234)))
        assertEquals(0, manual.reads)
        assertEquals(1, dao.metadata.size)
        assertEquals(1234L, dao.metadata.single().positionMs)
        assertEquals(0, dao.fullRows)
        assertEquals(0, dao.journeys)
        assertEquals(0, dao.visits)
        assertEquals(0, dao.members)
    }

    @Test fun equalOrderValueUsesMetadataUpdateEvenWhenItIsANewObject() = runBlocking {
        val before = queue(13_000)
        val after = before.copy(positionMs = 10, order = before.order.copy(manual = before.order.manual.toList()))
        assertNotSame(before.order, after.order)
        val dao = RecordingDao()
        dao.save(library(before), library(after))
        assertEquals(1, dao.metadata.size)
        assertEquals(0, dao.fullRows)
    }

    @Test fun everyMetadataFieldAndQueueOrdinalAreUpdatedWithoutAnOrderWrite() = runBlocking {
        val first = queue(2)
        val second = queue(1)
        val changed = first.copy(name = "Renamed", createdAt = 42, currentEntryId = first.entries.last().id,
            positionMs = 5678, shuffle = true, repeat = QueueRepeat.All, lastActivatedAt = 99)
        val before = QueueLibrary(listOf(first, second), first.id)
        val after = QueueLibrary(listOf(second, changed), first.id)
        val dao = RecordingDao()
        dao.save(before, after)
        assertEquals(QueueMetadataRecord(changed.id, "Renamed", 42, changed.currentEntryId,
            5678, true, "All", 1, 99), dao.metadata.single { it.id == first.id })
        assertEquals(0, dao.metadata.single { it.id == second.id }.ordinal)
        assertEquals(0, dao.fullRows)
    }

    @Test fun thirteenThousandEntryNextOnlyWritesChangedJourneyRowsAndMetadata() = runBlocking {
        for (loop in listOf(false, true)) {
            val seed = queue(13_000)
            val ids = seed.entries.map { it.id }
            val journey = QueueJourney.start(ids, ids.first(), JourneyModes(loop = loop))
            val before = seed.copy(journey = journey)
            val next = journey.arrive(journey.next!!.token, ids)
            val after = before.copy(journey = next, currentEntryId = next.current!!.entryId)
            val dao = RecordingDao()
            dao.save(library(before), library(after))
            assertEquals(1, dao.metadata.size)
            assertEquals(0, dao.fullRows)
            assertEquals(1, dao.journeys)
            // Ordered projection owns a distinct token from the redundant stored future row.
            assertEquals(1, dao.visits)
            assertEquals(listOf(journey.future.first().token), dao.deletedVisits)
            assertEquals(1, dao.members)
        }
    }

    @Test fun missingPersistedRowFailsBeforeOtherWritesAndSameSnapshotCanBeRetried() = runBlocking {
        val before = queue(2)
        val after = before.copy(positionMs = 999)
        val dao = RecordingDao().apply { updatedRows = 0 }
        try { dao.save(library(before), library(after)); fail("Missing durable queue must not be silently accepted") }
        catch (_: IllegalStateException) { }
        assertEquals(0, dao.activeWrites)
        dao.updatedRows = 1
        dao.save(library(before), library(after))
        assertEquals(1, dao.activeWrites)
        assertEquals(999L, dao.metadata.last().positionMs)
        assertEquals(0, dao.fullRows)
    }

    private fun queue(size: Int): ListeningQueue {
        val track = Track("content://song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        return QueueEdits.create(List(size) { track }, 0, "Queue")
    }
    private fun library(queue: ListeningQueue) = QueueLibrary(listOf(queue), queue.id)
    private class CountingList(private val items: List<String>) : AbstractList<String>() {
        var reads = 0
        override val size get() = items.size
        override fun get(index: Int): String { reads++; return items[index] }
    }

    private class RecordingDao : QueueDao() {
        val metadata = mutableListOf<QueueMetadataRecord>()
        var updatedRows = 1
        var fullRows = 0
        var journeys = 0
        val deletedVisits = mutableListOf<String>()
        var visits = 0
        var members = 0
        var activeWrites = 0
        override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int { metadata += queue; return updatedRows }
        override suspend fun upsertQueue(queue: QueueRecord) { fullRows++ }
        override suspend fun upsertActive(active: ActiveQueueRecord) { activeWrites++ }
        override suspend fun upsertJourney(journey: JourneyRecord) { journeys++ }
        override suspend fun upsertVisits(visits: List<VisitRecord>) { this.visits += visits.size }
        override suspend fun updateVisits(visits: List<VisitRecord>): Int { this.visits += visits.size; return visits.size }
        override suspend fun upsertCycleMembers(members: List<CycleMemberRecord>) { this.members += members.size }
        override suspend fun updateCycleMembers(members: List<CycleMemberRecord>): Int { this.members += members.size; return members.size }
        override suspend fun readQueues(): List<StoredQueue> = error("Not needed")
        override suspend fun readActive(): ActiveQueueRecord? = error("Not needed")
        override suspend fun clearJourney(id: String) = error("Unexpected clear")
        override suspend fun deleteVisits(queueId: String, tokens: List<String>) { deletedVisits += tokens }
        override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>) = error("Unexpected delete")
        override suspend fun insertEntries(entries: List<EntryRecord>) = error("Unchanged entries must not be written")
        override suspend fun clearEntries(id: String) = error("Unchanged entries must not be cleared")
        override suspend fun deleteQueue(id: String) = error("Unexpected delete")
    }
}
