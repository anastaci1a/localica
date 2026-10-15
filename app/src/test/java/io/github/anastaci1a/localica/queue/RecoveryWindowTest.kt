package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import org.junit.Assert.*
import org.junit.Test

class RecoveryWindowTest {
    private fun queue(vararg sources: String, current: Int = 0, loop: Boolean = false): ListeningQueue {
        val entries = sources.mapIndexed { index, source -> QueueEntry("entry-$index",
            Track(source, source, fileName = "$source.mp3", relativePath = "$source.mp3")) }
        return ListeningQueue(name = "Sources", entries = entries, currentEntryId = entries[current].id,
            journey = QueueJourney.start(entries.map { it.id }, entries[current].id, JourneyModes(loop = loop)))
    }
    @Test fun duplicatesAreBlockedAndUnrelatedCurrentIsRetained() {
        val queue = queue("bad", "good", "bad")
        assertTrue(recoveryWindow(queue) { it == "bad" }.isEmpty())
        val other = queue.copy(currentEntryId = "entry-1",
            journey = queue.journey!!.select("entry-1", queue.entries.map { it.id }))
        assertEquals(listOf("entry-1"), recoveryWindow(other) { it == "bad" }.map { it.entryId })
    }
    @Test fun windowStopsWithoutSkippingOrChangingDurableHistory() {
        val queue = queue("one", "two", "bad", "three", "bad", "four")
        val before = queue.journey
        assertEquals(listOf("entry-0", "entry-1"), recoveryWindow(queue) { it == "bad" }.map { it.entryId })
        assertEquals(before, queue.journey)
        assertEquals(6, queue.entries.size)
    }
    @Test fun previousAndRepeatVisitsDoNotWrapAcrossAnUnsafeGap() {
        val queue = queue("bad", "one", "two", "bad", current = 1, loop = true)
        assertEquals(listOf("entry-1", "entry-2"), recoveryWindow(queue) { it == "bad" }.map { it.entryId })
        val next = queue.journey!!.next!!
        val arrived = queue.copy(currentEntryId = next.entryId,
            journey = queue.journey.arrive(next.token, queue.entries.map { it.id }))
        assertEquals(listOf("entry-1", "entry-2"), recoveryWindow(arrived) { it == "bad" }.map { it.entryId })
        assertEquals(queue.entries, arrived.entries)
    }
    @Test fun unknownBlocksEverythingAndClearRestoresOnlyTheView() {
        val queue = queue("one", "two")
        assertTrue(recoveryWindow(queue) { true }.isEmpty())
        assertEquals(queue.journey!!.window(), recoveryWindow(queue) { false })
    }
}
