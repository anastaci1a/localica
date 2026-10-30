package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import org.junit.Assert.*
import org.junit.Test

class QueueCursorCaptureTest {
    private class Reads<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size get() = values.size
        override fun get(index: Int): T { reads++; return values[index] }
    }
    private fun queue(size: Int = 3): ListeningQueue {
        val track = Track("uri", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        val seed = QueueEdits.create(List(size) { track }, 0, "Queue")
        val visits = seed.entries.take(2).mapIndexed { i, entry -> QueueVisit(entry.id, 0, "visit-$i") }
        return seed.copy(journey = QueueJourney(history = visits, cursor = 1),
            currentEntryId = visits.last().entryId, positionMs = 1234)
    }

    @Test fun unchangedCurrentVisitPreservesIdentityWithoutPreparingEntryIds() {
        val original = queue(13313)
        val entries = Reads(original.entries)
        val input = original.copy(entries = entries)
        repeat(100) { assertSame(input, input.captureVisit("visit-1") { 1234 }) }
        assertEquals(0, entries.reads)
        assertSame(original.journey, input.journey)
        assertSame(original.order, input.order)
    }

    @Test fun positionOrSelectedOccurrenceChangesPreserveEveryOtherFieldAndSkipEntryPreparation() {
        val original = queue()
        val entries = Reads(original.entries)
        val input = original.copy(entries = entries)
        assertEquals(input.copy(positionMs = 0), input.captureVisit("visit-1") { 0 })
        val staleSelection = input.copy(currentEntryId = "older-selection")
        assertEquals(input.copy(positionMs = 50), staleSelection.captureVisit("visit-1") { 50 })
        assertEquals(0, entries.reads)
        assertSame(input.entries, input.captureVisit("visit-1") { 50 }!!.entries)
    }

    @Test fun historicalArrivalKeepsOriginalJourneyAndOccurrenceSemantics() {
        val input = queue()
        val expectedJourney = input.journey!!.arrive("visit-0", input.entries.map { it.id })
        val expected = input.copy(journey = expectedJourney, currentEntryId = expectedJourney.current!!.entryId, positionMs = 0)
        val captured = input.captureVisit("visit-0") { 0 }!!
        assertEquals(expected, captured)
        assertSame(input.order, captured.order)
        assertSame(input.entries, captured.entries)
        assertSame(captured, captured.captureVisit("visit-0") { 0 })
    }

    @Test fun missingJourneyAndUnknownOrBranchedTokensCannotReplaceSelection() {
        val input = queue()
        assertNull(input.copy(journey = null).captureVisit("visit-1") { error("No journey must not read native position") })
        assertNull(input.captureVisit("unknown") { error("Unknown visit must not read native position") })
        val branched = input.copy(journey = input.journey!!.copy(history = input.journey.history.take(1), cursor = 0))
        assertNull(branched.captureVisit("visit-1") { 0 })
        assertEquals(1234L, input.positionMs)
    }
}
