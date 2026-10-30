package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class QueueJourneyPreparationTest {
    private fun original(value: QueueJourney) = OriginalQueueJourney(value.history, value.cursor,
        value.future, value.cycle, value.visited, value.plans, value.modes,
        value.orderedTraversalId, value.orderedAnchorToken)
    private fun current(value: OriginalQueueJourney) = QueueJourney(value.history, value.cursor,
        value.future, value.cycle, value.visited, value.plans, value.modes,
        value.orderedTraversalId, value.orderedAnchorToken)
    private fun assertFixed(value: QueueJourney, ids: List<String>, selected: String?, modes: JourneyModes) {
        val expected = current(original(value).reconcile(ids, selected, modes, Random(91)))
        assertEquals(expected, value)
        assertSame(value, value.reconcile(ids.toList(), selected, modes, Random(91)))
    }

    @Test fun normalizedJourneysReuseExactTokensForEveryEffectiveModeAndCurrentSelection() {
        val ids = listOf("A", "B", "C")
        for (shuffle in listOf(false, true)) for (loop in listOf(false, true)) for (reshuffle in listOf(false, true)) {
            val modes = JourneyModes(shuffle, loop, reshuffle)
            for (selected in listOf(null, "A", "C", "absent")) {
                val prepared = QueueJourney.start(ids, selected, modes, Random(17))
                assertFixed(prepared, ids, selected, modes)
                assertEquals(prepared.window(), prepared.reconcile(ids, selected, modes).window())
            }
            assertFixed(QueueJourney.start(emptyList(), null, modes), emptyList(), null, modes)
        }
    }

    @Test fun changedOrderSelectionModesAndOrdinaryCopiesStillRunOriginalRepair() {
        val ids = listOf("A", "B", "C")
        val modes = JourneyModes()
        val prepared = QueueJourney.start(ids, "A", modes)
        val copied = prepared.copy()
        val repairedCopy = copied.reconcile(ids, "A", modes)
        assertNotSame(copied, repairedCopy)
        assertEquals(current(original(copied).reconcile(ids, "A", modes)), repairedCopy)
        for (nextIds in listOf(ids.reversed(), listOf("A", "C"), ids + "D")) {
            val changed = prepared.reconcile(nextIds, "A", modes)
            assertNotSame(prepared, changed)
            assertFixed(changed, nextIds, "A", modes)
        }
        val changedSelection = prepared.reconcile(ids, "B", modes)
        assertNotSame(prepared, changedSelection)
        assertEquals("B", changedSelection.current?.entryId)
        assertFixed(changedSelection, ids, "B", modes)
        for (nextModes in listOf(JourneyModes(true, true, true), JourneyModes(false, true, false))) {
            val changed = prepared.reconcile(ids, "A", nextModes, Random(42))
            assertNotSame(prepared, changed)
            assertFixed(changed, ids, "A", nextModes)
        }
    }

    @Test fun duplicateOccurrenceIdsKeepTheOriginalRepeatedRepairTokenBehavior() {
        val modes = JourneyModes(shuffle = true)
        val ids = listOf("A", "B", "B")
        val raw = QueueJourney(history = listOf(QueueVisit("A", 0, "anchor")), cursor = 0,
            visited = setOf("A"), modes = modes)
        val first = raw.reconcile(ids, "A", modes, Random(3))
        assertEquals(2, first.future.size)
        val second = first.reconcile(ids, "A", modes, Random(3))
        assertNotSame(first, second)
        assertEquals(current(original(first).reconcile(ids, "A", modes, Random(3))), second)
        assertEquals(first.future.last().token, second.future[0].token)
        assertEquals(second.future[0].token, second.future[1].token)
        assertNotSame(second, second.reconcile(ids, "A", modes))
    }

    @Test fun duplicateRestoredPlanMembersRemainOnTheRepairPathEvenWithUniqueOccurrences() {
        val ids = listOf("A", "B")
        val modes = JourneyModes(shuffle = true)
        val raw = QueueJourney(history = listOf(QueueVisit("A", 0, "anchor")), cursor = 0,
            visited = setOf("A"), plans = mapOf(0L to listOf("A", "B", "B")), modes = modes)
        val first = raw.reconcile(ids, "A", modes)
        val second = first.reconcile(ids, "A", modes)
        assertNotSame(first, second)
        assertEquals(current(original(first).reconcile(ids, "A", modes)), second)
        assertEquals(first.future.last().token, second.future.first().token)
        assertNotSame(second, second.reconcile(ids, "A", modes))
    }

    @Test fun preparationOwnsItsInputIdsAndLegitimateRepeatedTracksKeepDistinctOccurrences() {
        val track = Track("content://same", "Same")
        val entries = listOf(QueueEntry("occurrence-A", track), QueueEntry("occurrence-B", track))
        val ids = entries.mapTo(mutableListOf()) { it.id }
        val prepared = QueueJourney.start(ids, ids.first(), JourneyModes())
        val queue = ListeningQueue(name = "Before", entries = entries, journey = prepared)
        val renamed = queue.copy(name = "After", positionMs = 4500)
        assertSame(prepared, renamed.journey?.reconcile(ids.toList(), ids.first(), JourneyModes()))
        assertEquals(2, prepared.plans.getValue(0).size)
        ids.reverse()
        val changed = prepared.reconcile(ids, "occurrence-A", JourneyModes())
        assertNotSame(prepared, changed)
        assertEquals(ids, changed.plans.getValue(0))
        assertFixed(changed, ids, "occurrence-A", JourneyModes())
    }

    @Test fun arrivedAndRestoredJourneysDoNotInheritAnUnrelatedPreparationMarker() {
        val ids = listOf("A", "B", "C")
        val modes = JourneyModes(loop = true)
        val prepared = QueueJourney.start(ids, "A", modes)
        val visit = requireNotNull(prepared.next)
        val arrived = prepared.arrive(visit.token, ids)
        val normalized = arrived.reconcile(ids, visit.entryId, modes)
        assertNotSame(arrived, normalized)
        assertEquals(visit.token, normalized.current?.token)
        assertFixed(normalized, ids, visit.entryId, modes)
        val restored = current(original(normalized))
        assertNotSame(restored, restored.reconcile(ids, visit.entryId, modes))
    }
}
