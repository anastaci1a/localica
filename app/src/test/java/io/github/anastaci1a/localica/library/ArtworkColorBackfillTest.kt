package io.github.anastaci1a.localica.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkColorBackfillTest {
    private class FakeStore(count: Int) : ArtworkColorStore {
        val tracks = (0 until count).map { ArtworkSource("t$it", 1L, "u$it") }
        val saved = linkedMapOf<String, Int>()
        override suspend fun pending(limit: Int) = tracks.filter { it.trackId !in saved }.take(limit)
        override suspend fun sources(uris: List<String>) = tracks.filter { it.uri in uris }
        override suspend fun save(colors: List<Pair<ArtworkSource, Int>>) { colors.forEach { (s, c) -> saved[s.trackId] = c } }
    }

    @Test fun bulkBackfillCompletesAndStoresMissingArt() = runTest {
        val store = FakeStore(120)
        val backfill = ArtworkColorBackfill(store, { uri -> if (uri == "u3") ArtworkColors.MISSING else 0xFF112233.toInt() },
            MutableStateFlow(0), MutableStateFlow(false), batch = 50)
        val job = launch { backfill.run() }
        backfill.request()
        advanceUntilIdle()
        assertEquals(120, store.saved.size)
        assertEquals(ArtworkColors.MISSING, store.saved["t3"])
        job.cancel()
    }

    @Test fun bulkWorkPausesDuringForegroundDecodesButVisibleRequestsDoNot() = runTest {
        val store = FakeStore(20)
        val foreground = MutableStateFlow(1)
        val computedWhileBusy = mutableListOf<String>()
        val backfill = ArtworkColorBackfill(store, { uri -> if (foreground.value > 0) computedWhileBusy += uri; 0xFF445566.toInt() },
            foreground, MutableStateFlow(false), batch = 5)
        val job = launch { backfill.run() }
        backfill.request()
        runCurrent()
        assertTrue("No bulk item while a foreground decode is in flight", store.saved.isEmpty())
        backfill.prioritize(listOf("u17", "u12"))
        runCurrent()
        assertEquals("Visible requests run despite foreground decodes", setOf("t17", "t12"), store.saved.keys)
        assertEquals(listOf("u12", "u17"), computedWhileBusy) // latest request first
        foreground.value = 0
        advanceUntilIdle()
        assertEquals(20, store.saved.size)
        job.cancel()
    }

    @Test fun pausingCancelsTheCurrentPassKeepsFinishedWorkAndResumes() = runTest {
        val store = FakeStore(10)
        val paused = MutableStateFlow(false)
        val gate = CompletableDeferred<Unit>()
        var started = 0
        val backfill = ArtworkColorBackfill(store, { _ -> if (++started == 3) gate.await(); 0xFF000001.toInt() },
            MutableStateFlow(0), paused, batch = 10)
        val job = launch { backfill.run() }
        backfill.request()
        runCurrent()
        assertEquals(3, started)
        paused.value = true // scan started or app stopped
        runCurrent()
        assertEquals("Finished items are saved when the pass is cancelled", setOf("t0", "t1"), store.saved.keys)
        gate.complete(Unit)
        backfill.request()
        advanceUntilIdle()
        assertEquals(2, store.saved.size)
        paused.value = false
        advanceUntilIdle()
        assertEquals(10, store.saved.size)
        job.cancel()
    }

    @Test fun staleVisibleRequestsDropAndOffersSkipDecoding() = runTest {
        val store = FakeStore(100)
        val decoded = mutableListOf<String>()
        val paused = MutableStateFlow(true)
        val backfill = ArtworkColorBackfill(store, { uri -> decoded += uri; 0xFF010203.toInt() },
            MutableStateFlow(0), paused, priorityCapacity = 4)
        val job = launch { backfill.run() }
        (0 until 10).forEach { backfill.prioritize(listOf("u$it")) }
        backfill.withdraw(listOf("u9"))
        backfill.offer("u50", 0xFF0A0B0C.toInt())
        paused.value = false
        runCurrent()
        assertEquals(0xFF0A0B0C.toInt(), store.saved["t50"])
        assertTrue("u50" !in decoded)
        assertEquals(listOf("u8", "u7", "u6"), decoded.take(3))
        job.cancel()
    }
}
