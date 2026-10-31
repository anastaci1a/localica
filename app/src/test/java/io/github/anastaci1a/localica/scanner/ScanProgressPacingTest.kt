package io.github.anastaci1a.localica.scanner

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanProgressPacingTest {
    @Test fun countOnlyBurstsArePacedAndTheLatestCountIsDelivered() = runTest {
        val source = MutableStateFlow(ScanState())
        val delivered = mutableListOf<ScanState>()
        val collector = launch { source.pacedProgress(250).collect { delivered += it } }
        runCurrent()
        source.value = ScanState(running = true, totalFolders = 1); runCurrent()
        source.value = source.value.copy(folder = "root"); runCurrent()
        for (count in 25..500 step 25) { source.value = source.value.copy(count = count); advanceTimeBy(10); runCurrent() }
        // The burst lasted about 200 ms after the folder change: at most one paced count so far.
        assertEquals(listOf(ScanState(), ScanState(running = true, totalFolders = 1),
            ScanState(running = true, totalFolders = 1, folder = "root")), delivered.take(3))
        assertEquals(true, delivered.size <= 4)
        advanceTimeBy(300); runCurrent()
        assertEquals(500, delivered.last().count)
        collector.cancel()
    }

    @Test fun structuralChangesAreImmediateAndCarryTheLatestCount() = runTest {
        val running = ScanState(running = true, folder = "root", totalFolders = 2)
        val source = MutableStateFlow(running)
        val delivered = mutableListOf<ScanState>()
        val collector = launch { source.pacedProgress(250).collect { delivered += it } }
        runCurrent()
        source.value = running.copy(count = 25); runCurrent()
        source.value = running.copy(count = 50); runCurrent()
        // Folder completion is not count-only: it is delivered at once, superseding the pending count.
        source.value = running.copy(count = 75, completedFolders = 1); runCurrent()
        assertEquals(running.copy(count = 75, completedFolders = 1), delivered.last())
        source.value = running.copy(count = 75, completedFolders = 2, running = false, folder = null, message = "Scan complete.")
        runCurrent()
        assertEquals(false, delivered.last().running)
        assertEquals(75, delivered.last().count)
        assertEquals(listOf(75, 75), delivered.drop(1).map { it.count })
        collector.cancel()
    }
}
