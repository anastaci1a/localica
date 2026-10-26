package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackLocation
import org.junit.Assert.*
import org.junit.Test

class QueueEntryRecordsTest {
    private fun track() = Track("content://first", "Song", fileName = "song.mp3", relativePath = "song.mp3",
        id = "stable", locations = listOf(TrackLocation("root", "content://first", "song.mp3")))

    @Test fun equalDistinctTracksShareEncodingWithoutCollapsingOccurrences() {
        val first = track()
        val equal = first.copy(locations = first.locations.map { it.copy() })
        assertNotSame(first, equal)
        val entries = listOf(QueueEntry("second", first, "album"), QueueEntry("first", equal, null),
            QueueEntry("third", first, "other"))
        val before = entries.toList()
        var calls = 0
        val records = queueEntryRecords("queue", entries) { calls++; "encoded-$calls" }
        assertEquals(1, calls)
        assertEquals(listOf(EntryRecord("second", "queue", 0, "encoded-1", "album"),
            EntryRecord("first", "queue", 1, "encoded-1", null),
            EntryRecord("third", "queue", 2, "encoded-1", "other")), records)
        assertEquals(before, entries)
        assertSame(first, entries[0].track)
        assertSame(equal, entries[1].track)
    }

    @Test fun sameIdWithDifferentMetadataUriOrLocationsGetsIndependentEncoding() {
        val original = track()
        val tracks = listOf(original, original.copy(title = "Changed"), original.copy(uri = "content://second"),
            original.copy(locations = listOf(TrackLocation("other-root", "content://third", "other.mp3"))))
        val seen = mutableListOf<Track>()
        val records = queueEntryRecords("queue", tracks.mapIndexed { index, track -> QueueEntry("$index", track) }) {
            seen += it
            "encoded-${seen.size}"
        }
        assertEquals(tracks, seen)
        assertEquals(listOf("encoded-1", "encoded-2", "encoded-3", "encoded-4"), records.map { it.track })
        assertTrue(tracks.all { it.id == original.id })
    }

    @Test fun separateConversionsNeverReusePreviousEncoding() {
        val entries = listOf(QueueEntry("occurrence", track(), "origin"))
        var calls = 0
        val encode: (Track) -> String = { "encoded-${++calls}" }
        val first = queueEntryRecords("one", entries, encode)
        val second = queueEntryRecords("two", entries, encode)
        assertEquals(listOf(EntryRecord("occurrence", "one", 0, "encoded-1", "origin")), first)
        assertEquals(listOf(EntryRecord("occurrence", "two", 0, "encoded-2", "origin")), second)
        assertEquals(2, calls)
    }

    @Test fun emptyInputDoesNotInvokeEncoder() {
        assertTrue(queueEntryRecords("queue", emptyList()) { error("Unexpected encoding") }.isEmpty())
    }

    @Test fun encoderFailurePropagatesUnchangedAndStopsConversion() {
        val failure = IllegalStateException("encoder failure")
        val entries = listOf(QueueEntry("first", track()), QueueEntry("second", track().copy(title = "Second")),
            QueueEntry("third", track().copy(title = "Third")))
        var calls = 0
        try {
            queueEntryRecords("queue", entries) {
                calls++
                if (calls == 2) throw failure
                "encoded"
            }
            fail("Encoder failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(2, calls)
        assertEquals(listOf("first", "second", "third"), entries.map { it.id })
    }
}
