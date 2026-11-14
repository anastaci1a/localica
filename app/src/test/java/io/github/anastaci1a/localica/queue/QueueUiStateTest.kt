package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class QueueUiStateTest {
    @Test fun millionVisitHistoryIsNotReadBySymmetricSnapshotEqualityHashingOrStateFlowPublication() = runBlocking {
        var reads = 0
        val history = object : AbstractList<QueueVisit>() {
            override val size = 1_000_000
            override fun get(index: Int): QueueVisit { reads++; return QueueVisit("entry", index.toLong(), "visit:$index") }
        }
        val queue = queue(QueueJourney(history, history.lastIndex))
        val before = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        val equal = before.copy()
        assertEquals(before, equal)
        assertEquals(equal, before)
        assertEquals(before.hashCode(), equal.hashCode())
        val changed = before.copy(library = before.library.update(queue.copy(journey = queue.journey!!.copy(modes = JourneyModes(shuffle = true)))))
        assertNotEquals(before, changed)
        assertNotEquals(changed, before)
        val state = MutableStateFlow(before)
        val observed = mutableListOf<QueueUiState>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { state.take(2).toList(observed) }
        state.value = equal
        assertSame(before, state.value)
        state.value = changed
        collector.join()
        assertSame(changed, state.value)
        assertEquals(listOf(before, changed), observed)
        assertEquals("Publication never compares or hashes journey history", 0, reads)
    }

    @Test fun workerNoOpsReuseTheLibraryButPreparedAndRealChangesStillPublish() = runBlocking {
        val ids = listOf("entry")
        val journey = QueueJourney.start(ids, "entry", JourneyModes())
        val queue = queue(journey)
        val before = QueueLibrary(listOf(queue), queue.id)
        val equal = before.copy(queues = listOf(queue.copy()))
        assertSame(before, withContext(Dispatchers.Default) { equal.reuse(before) })
        val changed = before.update(queue.copy(name = "Changed"))
        assertSame(changed, withContext(Dispatchers.Default) { changed.reuse(before) })
        val cold = before.update(queue.copy(journey = journey.copy()))
        assertEquals(before, cold)
        val prepared = cold.update(cold.active!!.copy(journey = cold.active!!.journey!!.reconcile(ids, "entry", JourneyModes())))
        assertEquals(cold, prepared)
        assertSame(prepared, withContext(Dispatchers.Default) { prepared.reuse(cold) })
        assertNotEquals(QueueUiState(cold), QueueUiState(prepared))
        val restored = journey.record(queue.id).restore(journey.visitRecords(queue.id), journey.cycleRecords(queue.id).toList()).reconcile(ids, "entry", JourneyModes())
        assertEquals(journey, restored)
        assertEquals(before, before.update(queue.copy(journey = restored)))
    }

    private fun queue(journey: QueueJourney) = ListeningQueue(id = "queue", name = "Before",
        entries = listOf(QueueEntry("entry", Track("content://song", "Song", fileName = "song.mp3", relativePath = "song.mp3"))),
        currentEntryId = "entry", journey = journey)
}
