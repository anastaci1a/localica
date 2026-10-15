package io.github.anastaci1a.localica.queue

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** The prepared-move publication relies on normalization being redundant after a valid arrival. */
class PreparedJourneyPublicationTest {
    @Test fun preparedMovesMatchRenormalizationAcrossTraversalAndMembershipChanges() {
        var checked = 0
        for (size in listOf(1, 2, 7, 50)) for (seed in 0..9) {
            val random = Random(seed * 101 + size)
            var serial = size
            var ids = List(size) { "entry:$it" }
            var modes = JourneyModes()
            var journey = QueueJourney.start(ids, ids.first(), modes, random)
            repeat(150) {
                when (random.nextInt(7)) {
                    0 -> modes = modes.copy(shuffle = !modes.shuffle)
                    1 -> modes = modes.copy(loop = !modes.loop)
                    2 -> modes = modes.copy(reshuffle = !modes.reshuffle)
                    3 -> ids = ids.shuffled(random)
                    4 -> ids = ids + "entry:${serial++}"
                    5 -> if (ids.size > 1) {
                        val removed = random.nextInt(ids.size)
                        ids = ids.filterIndexed { index, _ -> index != removed }
                    }
                    else -> journey = journey.select(ids.random(random), ids, random)
                }
                journey = journey.reconcile(ids, journey.current?.entryId, modes, random)
                val visit = if (random.nextBoolean()) journey.next else journey.previous
                if (visit != null) {
                    val prepared = journey.reconcile(ids, journey.current?.entryId, modes, random).arrive(visit.token, ids, random)
                    assertEquals(prepared, prepared.reconcile(ids, prepared.current?.entryId, modes, random))
                    assertEquals(visit, prepared.current)
                    journey = prepared
                    checked++
                }
            }
        }
        assertTrue("Exercise thousands of independent forward/backward publications", checked > 4_000)
    }

    @Test fun largePreparedMovesRetainTokensAcrossRepeatAndBackwardBoundaries() {
        val ids = List(13_000) { "entry:$it" }
        for (shuffle in listOf(false, true)) for (reshuffle in listOf(false, true)) {
            val modes = JourneyModes(shuffle = shuffle, loop = true, reshuffle = reshuffle)
            val initial = QueueJourney.start(ids, ids.last(), modes, Random(17))
            val visit = initial.next!!
            val prepared = initial.reconcile(ids, initial.current?.entryId, modes, Random(19)).arrive(visit.token, ids, Random(23))
            assertEquals(visit, prepared.current)
            assertEquals(prepared, prepared.reconcile(ids, prepared.current?.entryId, modes, Random(29)))
            val back = prepared.arrive(prepared.previous!!.token, ids, Random(31))
            assertEquals(initial.current, back.current)
            assertEquals(visit, back.next)
            assertEquals(back, back.reconcile(ids, back.current?.entryId, modes, Random(37)))
        }
    }

    @Test fun missingCurrentAndTrimmedHistoryMustNormalizeBeforePreparingArrival() {
        val originalIds = listOf("removed", "kept", "next")
        val original = QueueJourney.start(originalIds, "removed", JourneyModes())
        val ids = originalIds.drop(1)
        val normalized = original.reconcile(ids, "removed", JourneyModes())
        assertNotEquals(original, normalized)
        assertEquals("kept", normalized.current?.entryId)
        assertTrue(normalized.history.none { it.entryId == "removed" })
        val prepared = normalized.arrive(normalized.next!!.token, ids)
        assertEquals("next", prepared.current?.entryId)
        assertEquals(prepared, prepared.reconcile(ids, prepared.current?.entryId, JourneyModes()))
    }
}
