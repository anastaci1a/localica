package io.github.anastaci1a.localica

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.BrowseRestoreSearchSlotEffect
import io.github.anastaci1a.localica.ui.BrowseScrollOwnership
import io.github.anastaci1a.localica.ui.BrowseSearchSlot
import io.github.anastaci1a.localica.ui.GlassAnchor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchSlotReversalTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aCloseCannotClearTheReopenedSlotBeforeCompositionCancelsIt() {
        val open = mutableStateOf(false)
        val slot = BrowseSearchSlot(mutableFloatStateOf(72f), mutableIntStateOf(37), mutableIntStateOf(0))
        val anchor = GlassAnchor()
        val motion = anchor.searchSlot
        var token = motion.begin()
        motion.settle(token, 1f)
        val grid = LazyGridState()
        val ownership = BrowseScrollOwnership()
        var compositions = 0
        compose.setContent {
            BrowseRestoreSearchSlotEffect(open, anchor, true, 200, slot, ownership, grid, null)
            SideEffect { compositions++ }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            val initialCompositions = compose.runOnUiThread { compositions }
            // Reopen and finish the old close's geometry in one native turn.
            // No frame runs, so the old close effect has not been cancelled.
            compose.runOnUiThread {
                Snapshot.withMutableSnapshot {
                    open.value = true
                    slot.measuredHeight = 200
                    motion.settle(token, 0f)
                }
            }
            compose.waitForIdle()
            compose.runOnUiThread {
                assertEquals("Recomposition must remain delayed", initialCompositions, compositions)
                assertEquals("Old close must retain the reopened viewport", 37, slot.originalOffset)
                assertEquals("Old close must retain the reopened vacancy", 72f, slot.vacancy, 0f)
            }

            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread { motion.settle(token, 1f) }
            compose.waitForIdle()
            compose.runOnUiThread { open.value = false; slot.measuredHeight = 0 }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread { motion.settle(token, 0f) }
            compose.waitForIdle()
            compose.runOnUiThread {
                assertEquals("Zero fraction alone is not actual-height acknowledgment", 37, slot.originalOffset)
                assertEquals(72f, slot.vacancy, 0f)
            }
            val beforeAcknowledgment = compose.runOnUiThread { compositions }
            compose.runOnUiThread {
                Snapshot.withMutableSnapshot { open.value = true; slot.measuredHeight = 200 }
            }
            compose.waitForIdle()
            compose.runOnUiThread {
                assertEquals("Reopen precedes cancellation of the height-waiting close", beforeAcknowledgment, compositions)
                assertEquals("A late actual-height receipt cannot retire a reopened slot", 37, slot.originalOffset)
                assertEquals(72f, slot.vacancy, 0f)
            }

            // A subsequent real close still restores and releases the slot.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread { motion.settle(token, 1f) }
            compose.waitForIdle()
            compose.runOnUiThread { open.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread { motion.settle(token, 0f) }
            compose.waitForIdle()
            compose.runOnUiThread {
                assertEquals(-1, slot.originalOffset)
                assertEquals(0f, slot.vacancy, 0f)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
