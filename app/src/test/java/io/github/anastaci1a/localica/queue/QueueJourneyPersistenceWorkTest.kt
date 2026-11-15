package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueueJourneyPersistenceWorkTest {
    @Test fun thirteenThousandOccurrencePlanReorderStreamsOnlyChangedCyclesAndActualHistory() = runBlocking {
        val before = queue(13_000, loop = true)
        val path = before.journey!!
        val after = before.copy(journey = path.copy(history = path.history.reversed(), cursor = 0,
            plans = path.plans.mapValues { (_, order) -> order.reversed() }))
        val dao = RecordingDao(before)
        dao.save(library(before), library(after))
        assertEquals(4, dao.visitUpdates.single().size)
        assertEquals(39_000, dao.memberUpserts.sumOf { it.size })
        assertEquals(3, dao.clearedCycles.size)
        assertTrue(dao.visitUpserts.isEmpty())
        dao.assertRows(after)
        dao.save(library(after), library(after))
        assertEquals(1, dao.visitUpdates.size)
        assertEquals(3, dao.memberUpserts.size)
    }

    @Test fun freshShuffleResetKeepsOnlyTheCurrentActualTokenAndStreamsPermutations() = runBlocking {
        val before = queue(13_000, loop = true, shuffle = false).copy(positionMs = 2345, repeat = QueueRepeat.All)
        val after = before.copy(journey = before.journey!!.reconcile(before.ids,
            before.currentEntryId, JourneyModes(shuffle = true, loop = true), kotlin.random.Random(19)))
        val dao = RecordingDao(before)
        dao.save(library(before), library(after))
        assertEquals(listOf(before.journey.current!!), after.journey!!.history)
        assertEquals(2345L, after.positionMs)
        assertEquals(before.repeat, after.repeat)
        assertEquals(before.order, after.order)
        assertEquals(1, dao.visitUpdates.single().size)
        assertTrue(dao.visitUpserts.isEmpty())
        assertEquals(before.journey.history.dropLast(1).map { it.token }.toSet(), dao.deletedVisits.toSet())
        assertEquals(39_000, dao.memberUpserts.sumOf { it.size })
        dao.assertRows(after)
    }

    @Test fun newLookaheadCyclesLeaveRetainedPermutationRowsUntouched() = runBlocking {
        val before = queue(20, loop = false)
        val after = before.copy(journey = before.journey!!.reconcile(before.ids, before.currentEntryId,
            before.journey.modes.copy(loop = true)))
        val dao = RecordingDao(before)
        dao.save(library(before), library(after))
        assertTrue(dao.visitUpdates.isEmpty())
        assertTrue(dao.visitUpserts.isEmpty())
        assertEquals(setOf(1L, 2L), dao.clearedCycles.toSet())
        assertEquals(40, dao.memberUpserts.sumOf { it.size })
        assertTrue(dao.visitedUpdates.isEmpty())
        dao.assertRows(after)
    }

    @Test fun restoredEqualLookaheadsKeepTheirRowsAndOnlyUpdateActualVisitedFlags() = runBlocking {
        val saved = queue(20, loop = true)
        val stored = saved.journey!!
        val restored = stored.record(saved.id).restore(stored.visitRecords(saved.id), stored.cycleRecords(saved.id).toList())
        assertNotSame(restored.plans.getValue(0), restored.plans.getValue(1))
        val before = saved.copy(journey = restored)
        val prepared = restored.reconcile(before.ids, before.currentEntryId, restored.modes)
        assertSame(prepared.plans.getValue(0), prepared.plans.getValue(1))
        val arrived = prepared.arrive(prepared.next!!.token, before.ids)
        val after = before.copy(journey = arrived, currentEntryId = arrived.current!!.entryId)
        val dao = RecordingDao(before)
        dao.save(library(before), library(after))
        assertTrue(dao.memberUpserts.isEmpty())
        assertTrue(dao.clearedCycles.isEmpty())
        assertEquals(listOf(listOf(arrived.current!!.entryId) to true), dao.visitedUpdates)
        dao.assertRows(after)
        val reopened = arrived.record(after.id).restore(dao.visits.values.toList(), dao.members.values.toList())
        assertEquals(restored.visited + arrived.current!!.entryId, reopened.visited)
        assertEquals(arrived.history, reopened.history)
        dao.save(library(after), library(after.copy(positionMs = 4321)))
        assertTrue(dao.memberUpserts.isEmpty())
        assertTrue(dao.clearedCycles.isEmpty())
        assertEquals(1, dao.visitedUpdates.size)
    }

    @Test fun missingActualVisitAndMembershipRepairWithoutProjectingUnchangedPermutations() = runBlocking {
        val before = queue(20, loop = true)
        val path = before.journey!!
        val next = path.arrive(path.next!!.token, before.ids)
        val history = next.history.reversed()
        val after = before.copy(journey = next.copy(history = history, cursor = 0), currentEntryId = next.current!!.entryId)
        val dao = RecordingDao(before)
        dao.visits.remove(history.last().token)
        dao.members.remove(next.cycle to next.current!!.entryId)
        dao.save(library(before), library(after))
        assertEquals(dao.visitUpdates.single(), dao.visitUpserts.first())
        assertEquals(1, dao.memberUpserts.single().size)
        assertTrue(dao.clearedCycles.isEmpty())
        dao.assertRows(after)
    }

    @Test fun impossibleUpdateCountsFailBeforeFallbackAndRemainRetryable() = runBlocking {
        for (badCount in listOf(-1, Int.MAX_VALUE)) {
            val before = queue(20, loop = true)
            val path = before.journey!!
            val after = before.copy(journey = path.copy(history = path.history.reversed(), cursor = 0))
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

    @Test fun sharedCyclePermutationClearsOldActiveFlagsAndSetsOnlyNewArrivals() = runBlocking {
        val before = queue(4, loop = true)
        val path = before.journey!!
        val next = path.arrive(path.next!!.token, before.ids)
        assertEquals(1L, next.cycle)
        val after = before.copy(journey = next.copy(plans = path.plans), currentEntryId = next.current!!.entryId)
        val dao = RecordingDao(before)
        dao.save(library(before), library(after))
        assertEquals(2, dao.visitedUpdates.size)
        assertEquals(listOf(false, true), dao.visitedUpdates.map { it.second })
        assertTrue(dao.clearedCycles.isEmpty())
        dao.assertRows(after)
    }

    private fun queue(size: Int, loop: Boolean, shuffle: Boolean = true): ListeningQueue {
        val track = Track("content://song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        val seed = QueueEdits.create(List(size) { track }, 0, "Queue")
        var path = QueueJourney.start(seed.ids, seed.ids.first(), JourneyModes(shuffle = shuffle, loop = loop))
        repeat(3) { path = path.arrive(path.next!!.token, seed.ids) }
        return seed.copy(currentEntryId = path.current!!.entryId, journey = path)
    }
    private fun library(queue: ListeningQueue) = QueueLibrary(listOf(queue), queue.id)

    private class RecordingDao(before: ListeningQueue) : QueueDao() {
        val visits = before.journey!!.visitRecords(before.id).associateBy { it.token }.toMutableMap()
        val members = before.journey!!.cycleRecords(before.id).associateBy { it.cycle to it.entryId }.toMutableMap()
        val deletedVisits = mutableListOf<String>()
        val visitUpdates = mutableListOf<List<VisitRecord>>()
        val visitUpserts = mutableListOf<List<VisitRecord>>()
        val memberUpserts = mutableListOf<List<CycleMemberRecord>>()
        val visitedUpdates = mutableListOf<Pair<List<String>, Boolean>>()
        val clearedCycles = mutableListOf<Long>()
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
        override suspend fun updateVisited(id: String, cycle: Long, entries: List<String>, visited: Boolean): Int {
            visitedUpdates += entries to visited
            return entries.count { entry -> members[cycle to entry]?.let { members[cycle to entry] = it.copy(visited = visited); true } == true }
        }
        override suspend fun clearCycle(id: String, cycle: Long) { clearedCycles += cycle; members.keys.removeAll { it.first == cycle } }
        override suspend fun upsertVisits(visits: List<VisitRecord>) {
            visitUpserts += visits; visits.forEach { this.visits[it.token] = it }
        }
        override suspend fun upsertCycleMembers(members: Sequence<CycleMemberRecord>) {
            val rows = members.toList()
            memberUpserts += rows; rows.forEach { this.members[it.cycle to it.entryId] = it }
        }
        override suspend fun deleteSparseMembers(id: String, cycle: Long, entries: List<String>) { entries.forEach { members.remove(cycle to it) } }
        override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int = 1
        override suspend fun upsertQueue(queue: QueueRecord): Unit = error("Unchanged order")
        override suspend fun upsertActive(active: ActiveQueueRecord) { activeWrites++ }
        override suspend fun upsertJourney(journey: JourneyRecord) {}
        override suspend fun readQueues(): List<StoredQueue> = error("Not needed")
        override suspend fun readActive(): ActiveQueueRecord? = error("Not needed")
        override suspend fun clearJourney(id: String): Unit = error("Unexpected clear")
        override suspend fun deleteVisits(queueId: String, tokens: List<String>) { deletedVisits += tokens; tokens.forEach { visits.remove(it) } }
        override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("Unchanged entries")
        override suspend fun clearEntries(id: String): Unit = error("Unchanged entries")
        override suspend fun deleteQueue(id: String): Unit = error("Unexpected delete")
    }
}
