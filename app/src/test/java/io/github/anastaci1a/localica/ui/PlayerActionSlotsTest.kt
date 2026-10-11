package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class PlayerActionSlotsTest {
    @Test fun placementSwapsAndReplacementReturnsAnActionToThePalette() {
        val original = PlayerActionSettings()
        val source = ActionSlot(0, 0)
        val target = ActionSlot(0, 7)
        val swapped = original.place(PlayerAction.Info, target)
        assertEquals(PlayerAction.Info, swapped.slots[target])
        assertEquals(PlayerAction.Repeat, swapped.slots[source])
        val replaced = swapped.place(PlayerAction.Lyrics, target)
        assertFalse(PlayerAction.Info in replaced.slots.values)
        assertEquals(PlayerAction.Lyrics, replaced.slots[target])
        assertEquals(replaced.slots.values.size, replaced.slots.values.toSet().size)
    }
    @Test fun layoutChangesPreserveHiddenAssignmentsAndEmptySlots() {
        val all = PlayerActionSettings(twoRows = true, small = true).place(PlayerAction.Lyrics, ActionSlot(1, 2))
        val hidden = all.copy(twoRows = false, small = false)
        assertEquals(all.slots, hidden.slots)
        assertEquals(1, hidden.hiddenCount)
        assertEquals(all, hidden.copy(twoRows = true, small = true))
        assertFalse(hidden.slots.containsKey(ActionSlot(0, 4)))
        assertEquals(5, hidden.visibleSlots.size)
        assertEquals(14, all.visibleSlots.size)
        assertTrue(all.copy(centered = false).columns.size >= 9)
    }
}
