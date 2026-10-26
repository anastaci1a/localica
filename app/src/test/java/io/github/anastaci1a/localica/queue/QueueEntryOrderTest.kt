package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackLocation
import org.junit.Assert.*
import org.junit.Test

class QueueEntryOrderTest {
    private fun track() = Track("content://song", "Title", fileName = "song.mp3", relativePath = "song.mp3",
        id = "track", locations = listOf(TrackLocation("root", "content://song", "song.mp3")))

    @Test fun reorderPreservesDistinctOccurrencesOfTheSameTrack() {
        val song = track()
        val before = listOf(QueueEntry("one", song, "album"), QueueEntry("two", song, "playlist"),
            QueueEntry("three", song, null))
        assertTrue(queueEntryOrderEligible(before, before.reversed()))
        assertTrue(queueEntryOrderEligible(before, before))
        assertTrue(queueEntryOrderEligible(emptyList(), emptyList()))
    }

    @Test fun duplicateOccurrenceIdsAreRejectedOnEitherSideEvenWithoutReordering() {
        val first = QueueEntry("one", track())
        val second = first.copy(id = "two")
        val unique = listOf(first, second)
        val duplicated = listOf(first, first.copy())
        assertFalse(queueEntryOrderEligible(duplicated, unique))
        assertFalse(queueEntryOrderEligible(unique, duplicated))
        assertFalse(queueEntryOrderEligible(duplicated, duplicated))
        assertFalse(queueEntryOrderEligible(listOf(first, first.copy(sourcePlaylistId = "different")), unique))
    }

    @Test fun cardinalityAndExactOccurrenceMembershipAreRequired() {
        val first = QueueEntry("one", track())
        val before = listOf(first, first.copy(id = "two"))
        assertFalse(queueEntryOrderEligible(before, before.take(1)))
        assertFalse(queueEntryOrderEligible(before.take(1), before))
        assertFalse(queueEntryOrderEligible(before, listOf(first, first.copy(id = "replacement"))))
        assertFalse(queueEntryOrderEligible(emptyList(), listOf(first)))
        assertFalse(queueEntryOrderEligible(listOf(first), emptyList()))
    }

    @Test fun everyTrackValueChangeRejectsOrdinalOnlyEligibility() {
        val song = track()
        val changed = listOf(
            song.copy(uri = "content://other"), song.copy(title = "Other"), song.copy(artist = "Artist"),
            song.copy(album = "Album"), song.copy(durationMs = 1L), song.copy(fileName = "other.mp3"),
            song.copy(relativePath = "other.mp3"), song.copy(id = "other"), song.copy(albumArtist = "Album artist"),
            song.copy(trackNumber = 1), song.copy(discNumber = 1), song.copy(year = 2000), song.copy(genre = "Genre"),
            song.copy(comment = "Comment"), song.copy(composer = "Composer"), song.copy(lyricist = "Lyricist"),
            song.copy(modifiedAt = 1L), song.copy(addedAt = 1L), song.copy(playCount = 1L),
            song.copy(locations = emptyList()),
            song.copy(locations = listOf(song.locations.single().copy(folderUri = "other-root"))),
            song.copy(locations = listOf(song.locations.single().copy(uri = "content://other"))),
            song.copy(locations = listOf(song.locations.single().copy(relativePath = "other.mp3"))),
            song.copy(locations = song.locations + TrackLocation("second-root", "content://second", "second.mp3")),
        )
        val original = QueueEntry("occurrence", song, "origin")
        for ((index, replacement) in changed.withIndex()) {
            assertNotEquals("Mutation $index must change the complete value", song, replacement)
            assertFalse("Mutation $index must not borrow the original entry", queueEntryOrderEligible(
                listOf(original), listOf(original.copy(track = replacement))))
        }
        val twoLocations = song.copy(locations = song.locations + TrackLocation("second", "second", "second"))
        assertFalse(queueEntryOrderEligible(listOf(original.copy(track = twoLocations)),
            listOf(original.copy(track = twoLocations.copy(locations = twoLocations.locations.reversed())))))
    }

    @Test fun originChangesRejectEligibilityIncludingNullTransitions() {
        val original = QueueEntry("occurrence", track(), "origin")
        for (origin in listOf(null, "different")) {
            assertFalse(queueEntryOrderEligible(listOf(original), listOf(original.copy(sourcePlaylistId = origin))))
            assertFalse(queueEntryOrderEligible(listOf(original.copy(sourcePlaylistId = origin)), listOf(original)))
        }
    }

    @Test fun equalDistinctValuesQualifyWithoutMutatingOrReorderingInputs() {
        val song = track()
        val equal = song.copy(locations = song.locations.map { it.copy() })
        assertNotSame(song, equal)
        assertNotSame(song.locations, equal.locations)
        val before = mutableListOf(QueueEntry("one", song, "origin"), QueueEntry("two", song))
        val after = mutableListOf(before[1].copy(track = equal), before[0].copy(track = equal))
        val originalBefore = before.toList()
        val originalAfter = after.toList()
        assertTrue(queueEntryOrderEligible(before, after))
        assertEquals(originalBefore, before)
        assertEquals(originalAfter, after)
        before.indices.forEach { assertSame(originalBefore[it], before[it]) }
        after.indices.forEach { assertSame(originalAfter[it], after[it]) }
        assertSame(song, before.first().track)
        assertSame(equal, after.first().track)
    }
}
