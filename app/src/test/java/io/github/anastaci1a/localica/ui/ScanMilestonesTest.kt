package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.scanner.ScanState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanMilestonesTest {
    @Test fun perFileCountTicksDoNotReplaceLibraryStateButMilestonesCarryTheCount() = runBlocking {
        val started = ScanState(running = true, totalFolders = 2)
        val ticks = (25..500 step 25).map { started.copy(count = it, folder = "a") }
        val folderDone = ticks.last().copy(completedFolders = 1, folder = "b")
        val moreTicks = (525..800 step 25).map { folderDone.copy(count = it) }
        val finished = moreTicks.last().copy(completedFolders = 2, running = false, folder = null, message = "Scan complete.")
        val published = flowOf(started, *ticks.toTypedArray(), folderDone, *moreTicks.toTypedArray(), finished)
            .scanMilestones().toList()
        // The first tick is published because it also changes the folder; later ticks are not.
        assertEquals(listOf(started, ticks.first(), folderDone, finished), published)
        assertEquals(500, published[2].count)
        assertEquals(800, published.last().count)
    }
}
