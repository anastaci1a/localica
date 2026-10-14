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
    @Test fun playbackEndsAtLastVisibleAssignmentButEditorKeepsDestinations() {
        val settings = PlayerActionSettings(slots = mapOf(ActionSlot(0, 2) to PlayerAction.Info,
            ActionSlot(1, 7) to PlayerAction.Repeat), centered = false)
        assertEquals(listOf(0, 1, 2), settings.playbackColumns)
        assertTrue(settings.columns.size >= 9)
        assertEquals((0..7).toList(), settings.copy(twoRows = true).playbackColumns)
    }
    @Test fun selectedOptionsPreserveCanonicalOrderInBothGroups() {
        val options = listOf("Centered", "2 rows", "Compact")
        assertEquals(listOf("2 rows", "Compact", "Centered"), orderedBubbleGroups(options, setOf("Compact", "2 rows")))
        assertEquals(options, orderedBubbleGroups(options, options.toSet()))
        assertEquals(options, orderedBubbleGroups(options, emptySet()))
    }
}
