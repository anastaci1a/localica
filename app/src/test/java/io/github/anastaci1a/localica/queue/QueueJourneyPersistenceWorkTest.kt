package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueueJourneyPersistenceWorkTest {
    @Test fun thirteenThousandOccurrencePlanReorderUsesBatchedUpdatesWithoutRetainedRowUpserts() = runBlocking {
        val before = queue(13_000, loop = true)
        val after = reorderedPlans(before, loop = true)
        val dao = RecordingDao(before)
        dao.save(library(before), library(after))
        assertEquals(38_996, dao.visitUpdates.single().size)
        assertEquals(39_000, dao.memberUpdates.single().size)
        assertTrue(dao.visitUpserts.isEmpty())
        assertTrue(dao.memberUpserts.isEmpty())
        dao.assertRows(after)
        dao.save(library(after), library(after))
        assertEquals(1, dao.visitUpdates.size)
        assertEquals(1, dao.memberUpdates.size)
    }

    @Test fun freshShuffleResetKeepsOnlyTheCurrentTokenAndAccountsForEveryNewVisit() = runBlocking {
        val before = queue(13_000, loop = true, shuffle = false).copy(positionMs = 2345, repeat = QueueRepeat.All)
        val after = before.copy(journey = before.journey!!.reconcile(before.entries.map { it.id },
            before.currentEntryId, JourneyModes(shuffle = true, loop = true), kotlin.random.Random(19)))
        val dao = RecordingDao(before)
        val old = before.journey!!.visitRecords(before.id).map { it.token }.toSet()
        dao.save(library(before), library(after))
        assertEquals(listOf(before.journey.current!!), after.journey!!.history)
        assertEquals(2345L, after.positionMs)
        assertEquals(before.repeat, after.repeat)
        assertEquals(before.order, after.order)
        assertEquals(1, dao.visitUpdates.single().size)
        assertEquals(before.journey.current!!.token, dao.visitUpdates.single().single().token)
        assertEquals(38_999, dao.visitUpserts.single().size)
        assertTrue(dao.visitUpserts.single().none { it.token in old })
        assertEquals(old - before.journey.current!!.token, dao.deletedVisits.toSet())
        assertTrue(dao.memberUpserts.isEmpty())
        assertEquals(before.journey.cycleRecords(before.id).map { it.cycle to it.entryId }.toSet(),
            after.journey.cycleRecords(after.id).map { it.cycle to it.entryId }.toSet())
        dao.assertRows(after)
    }

    @Test fun mixedNewCyclesAndRetainedRowsKeepTheirOriginalUpsertAndUpdateSemantics() = runBlocking {
        val before = queue(20, loop = false)
        val after = reorderedPlans(before, loop = true)
        val dao = RecordingDao(before)
        dao.save(library(before), library(after))
        val oldTokens = before.journey!!.visitRecords(before.id).map { it.token }.toSet()
        assertTrue(dao.visitUpdates.single().all { it.token in oldTokens })
        assertEquals(40, dao.visitUpserts.single().size)
        assertTrue(dao.visitUpserts.single().none { it.token in oldTokens })
        assertEquals(40, dao.memberUpserts.single().size)
        assertTrue(dao.memberUpdates.single().all { it.cycle == 0L })
        assertTrue(dao.memberUpserts.single().all { it.cycle > 0 })
        dao.assertRows(after)
    }

    @Test fun missingRetainedRowsRepairTheWholeChangedBatchWithoutDroppingOtherUpdates() = runBlocking {
        val before = queue(20, loop = true)
        val after = reorderedPlans(before, loop = true)
        val dao = RecordingDao(before)
        val missingVisit = after.journey!!.visitRecords(after.id).first { dao.visits[it.token] != it }
        val missingMember = after.journey.cycleRecords(after.id).first { dao.members[it.cycle to it.entryId] != it }
        dao.visits.remove(missingVisit.token)
        dao.members.remove(missingMember.cycle to missingMember.entryId)
        dao.save(library(before), library(after))
        assertEquals(dao.visitUpdates.single(), dao.visitUpserts.single())
        assertEquals(dao.memberUpdates.single(), dao.memberUpserts.single())
        dao.assertRows(after)
    }

    @Test fun impossibleUpdateCountsFailBeforeFallbackAndRemainRetryable() = runBlocking {
        for (badCount in listOf(-1, Int.MAX_VALUE)) {
            val before = queue(20, loop = true)
            val after = reorderedPlans(before, loop = true)
            val dao = RecordingDao(before).apply { visitCountOverride = badCount }
            try { dao.save(library(before), library(after)); fail("Impossible row count must fail") }
            catch (_: IllegalStateException) { }
            assertTrue(dao.visitUpserts.isEmpty())
            assertEquals(0, dao.activeWrites)
            dao.visitCountOverride = null
            dao.save(library(before), library(after))
            dao.assertRows(after)
            assertEquals(1, dao.activeWrites)
        }
    }

    private fun queue(size: Int, loop: Boolean, shuffle: Boolean = true): ListeningQueue {
        val track = Track("content://song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        val seed = QueueEdits.create(List(size) { track }, 0, "Queue")
        val ids = seed.entries.map { it.id }
        var journey = QueueJourney.start(ids, ids.first(), JourneyModes(shuffle = shuffle, loop = loop))
        repeat(3) { journey = journey.arrive(journey.next!!.token, ids) }
        return seed.copy(currentEntryId = journey.current!!.entryId, journey = journey)
    }
    /** Storage fixture: retain every old key while changing concrete plan/visit ordinals.
     * Real OFF→ON deliberately replaces planned token identities and is covered separately. */
    private fun reorderedPlans(queue: ListeningQueue, loop: Boolean): ListeningQueue {
        val journey = queue.journey!!
        val ids = queue.entries.map { it.id }.reversed()
        val added = if (loop && !journey.modes.loop) (1L..2L).associateWith { ids } else emptyMap()
        return queue.copy(journey = journey.copy(
            future = journey.future.groupBy { it.cycle }.values.flatMap { it.reversed() } +
                added.flatMap { (cycle, order) -> order.map { QueueVisit(it, cycle) } },
            plans = journey.plans.mapValues { (_, order) -> order.reversed() } + added,
            modes = journey.modes.copy(loop = loop)))
    }
    private fun library(queue: ListeningQueue) = QueueLibrary(listOf(queue), queue.id)

    private class RecordingDao(before: ListeningQueue) : QueueDao() {
        val visits = before.journey!!.visitRecords(before.id).associateBy { it.token }.toMutableMap()
        val members = before.journey!!.cycleRecords(before.id).associateBy { it.cycle to it.entryId }.toMutableMap()
        val deletedVisits = mutableListOf<String>()
        val visitUpdates = mutableListOf<List<VisitRecord>>()
        val visitUpserts = mutableListOf<List<VisitRecord>>()
        val memberUpdates = mutableListOf<List<CycleMemberRecord>>()
        val memberUpserts = mutableListOf<List<CycleMemberRecord>>()
        var visitCountOverride: Int? = null
        var activeWrites = 0
        fun assertRows(queue: ListeningQueue) {
            assertEquals(queue.journey!!.visitRecords(queue.id).associateBy { it.token }, visits)
            assertEquals(queue.journey.cycleRecords(queue.id).associateBy { it.cycle to it.entryId }, members)
        }
        override suspend fun updateVisits(visits: List<VisitRecord>): Int {
            visitUpdates += visits
            val retained = visits.filter { it.token in this.visits }
            retained.forEach { this.visits[it.token] = it }
            return visitCountOverride ?: retained.size
        }
        override suspend fun updateCycleMembers(members: List<CycleMemberRecord>): Int {
            memberUpdates += members
            val retained = members.filter { it.cycle to it.entryId in this.members }
            retained.forEach { this.members[it.cycle to it.entryId] = it }
            return retained.size
        }
        override suspend fun upsertVisits(visits: List<VisitRecord>) {
            visitUpserts += visits; visits.forEach { this.visits[it.token] = it }
        }
        override suspend fun upsertCycleMembers(members: List<CycleMemberRecord>) {
            memberUpserts += members; members.forEach { this.members[it.cycle to it.entryId] = it }
        }
        override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int = 1
        override suspend fun upsertQueue(queue: QueueRecord): Unit = error("Unchanged order")
        override suspend fun upsertActive(active: ActiveQueueRecord) { activeWrites++ }
        override suspend fun upsertJourney(journey: JourneyRecord) {}
        override suspend fun readQueues(): List<StoredQueue> = error("Not needed")
        override suspend fun readActive(): ActiveQueueRecord? = error("Not needed")
        override suspend fun clearJourney(id: String): Unit = error("Unexpected clear")
        override suspend fun deleteVisits(queueId: String, tokens: List<String>) { deletedVisits += tokens; tokens.forEach { visits.remove(it) } }
        override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>) { entries.forEach { members.remove(cycle to it) } }
        override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("Unchanged entries")
        override suspend fun clearEntries(id: String): Unit = error("Unchanged entries")
        override suspend fun deleteQueue(id: String): Unit = error("Unexpected delete")
    }
}
