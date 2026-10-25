package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.OrderCheckpoint
import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.TrackSort
import org.junit.Assert.*
import org.junit.Test

class OrderCheckpointDifferenceTest {
    private val checkpoint = OrderCheckpoint.capture(listOf("a", "b", "c", "d"),
        SavedOrder(listOf("d", "c", "b", "a"), TrackSort.Title, false))

    @Test fun physicalOrderRatherThanActionCountOrSavedSettingsDeterminesRevert() {
        val opening = checkpoint.physical
        assertFalse(checkpoint.differsFrom(opening))
        assertTrue(checkpoint.differsFrom(opening.reversed()))
        assertFalse(checkpoint.differsFrom(opening.reversed().reversed()))
        val randomized = listOf("c", "a", "d", "b")
        assertTrue(checkpoint.differsFrom(randomized))
        assertTrue(checkpoint.differsFrom(randomized.reversed()))
        assertTrue(checkpoint.differsFrom(randomized.reversed().reversed()))
    }

    @Test fun membershipChangesUseTheSameReconciliationAsActualRestoration() {
        val unchanged = listOf("new-first", "a", "new-after-a", "c", "d", "new-last")
        assertFalse(checkpoint.differsFrom(unchanged))
        val changed = listOf("d", "new-after-d", "c", "a", "new-after-a")
        assertTrue(checkpoint.differsFrom(changed))
        assertFalse(checkpoint.differsFrom(checkpoint.reconcile(changed).physical))
        assertFalse(checkpoint.differsFrom(emptyList()))
        assertFalse(checkpoint.differsFrom(listOf("a")))
        assertFalse(checkpoint.differsFrom(listOf("new-only")))
    }

    @Test fun duplicateSongsUseIndependentOccurrenceIds() {
        val duplicates = OrderCheckpoint.capture(listOf("songA-first", "songA-second"),
            SavedOrder(listOf("songA-first", "songA-second")))
        assertTrue(duplicates.differsFrom(listOf("songA-second", "songA-first")))
        assertFalse(duplicates.differsFrom(listOf("songA-first", "songA-second")))
    }
}
