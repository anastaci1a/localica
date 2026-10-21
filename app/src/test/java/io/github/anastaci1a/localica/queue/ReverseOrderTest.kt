package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import org.junit.Assert.*
import org.junit.Test

class ReverseOrderTest {
    private fun track(title: String) = Track(title, title, fileName = "$title.mp3", relativePath = "$title.mp3")
    private val tracks = listOf("C", "A", "B", "A").map(::track)

    @Test fun queueReversesPhysicalOccurrencesWithoutPinningCurrentOrChangingItsCursorAndHistory() {
        val original = QueueEdits.create(tracks, 3, "Mix", listOf("one", "two", null, "four"))
            .copy(positionMs = 1234, lastActivatedAt = 42)
        val sorted = QueueEdits.sort(original, TrackSort.Title, true)
        val journey = QueueJourney.start(sorted.entries.map { it.id }, sorted.currentEntryId, JourneyModes(shuffle = true))
        val before = sorted.copy(journey = journey)
        val reversed = QueueEdits.reverse(before)
        assertEquals(before.entries.reversed(), reversed.entries)
        assertNotEquals(before.currentEntryId, reversed.entries.first().id)
        assertEquals(2, reversed.entries.count { it.track.id == "A" })
        assertEquals(before.entries.associateBy { it.id }, reversed.entries.associateBy { it.id })
        assertEquals(SavedOrder(reversed.entries.map { it.id }), reversed.order)
        assertSame(journey, reversed.journey)
        assertEquals(before, reversed.copy(entries = before.entries, order = before.order))
        assertEquals(reversed.entries, QueueEdits.sort(
            QueueEdits.sort(reversed, TrackSort.Title, false), TrackSort.Manual, true).entries)
    }

    @Test fun playlistReversesPhysicalDuplicateOccurrencesAndMakesThemTheNewManualBaseline() {
        val original = PlaylistEdits.append(ManualPlaylist(name = "Mix", coverUri = "cover", modifiedAt = 42), tracks)
        val before = PlaylistEdits.sort(original, TrackSort.Title, true)
        val reversed = PlaylistEdits.reverse(before)
        assertEquals(before.entries.reversed(), reversed.entries)
        assertEquals(2, reversed.entries.count { it.track.id == "A" })
        assertEquals(before.entries.associateBy { it.id }, reversed.entries.associateBy { it.id })
        assertEquals(SavedOrder(reversed.entries.map { it.id }), reversed.order)
        assertEquals(before, reversed.copy(entries = before.entries, order = before.order))
        assertEquals(reversed.entries, PlaylistEdits.sort(
            PlaylistEdits.sort(reversed, TrackSort.Title, false), TrackSort.Manual, true).entries)
    }

    @Test fun repeatedReversalsReadTheLatestPhysicalOrderRatherThanTheOriginalManualBaseline() {
        val queue = QueueEdits.sort(QueueEdits.create(tracks, 3, "Mix"), TrackSort.Title, true)
        val playlist = PlaylistEdits.sort(PlaylistEdits.append(ManualPlaylist(name = "Mix"), tracks), TrackSort.Title, true)
        val reversedQueue = QueueEdits.reverse(QueueEdits.reverse(queue))
        val reversedPlaylist = PlaylistEdits.reverse(PlaylistEdits.reverse(playlist))
        assertEquals(queue.entries, reversedQueue.entries)
        assertEquals(playlist.entries, reversedPlaylist.entries)
        assertEquals(SavedOrder(queue.entries.map { it.id }), reversedQueue.order)
        assertEquals(SavedOrder(playlist.entries.map { it.id }), reversedPlaylist.order)
        assertNotEquals(queue.order, reversedQueue.order)
        assertNotEquals(playlist.order, reversedPlaylist.order)
    }

    @Test fun emptyAndSingletonReversalsKeepSavedSortSettingsAndObjectIdentity() {
        for (count in 0..1) {
            val queue = ListeningQueue(name = "Small", entries = tracks.take(count).map { QueueEntry(track = it) })
                .let { QueueEdits.sort(it, TrackSort.Title, false) }
            val playlist = PlaylistEdits.append(ManualPlaylist(name = "Small", modifiedAt = 42), tracks.take(count))
                .let { PlaylistEdits.sort(it, TrackSort.Title, false) }
            assertSame(queue, QueueEdits.reverse(queue))
            assertSame(playlist, PlaylistEdits.reverse(playlist))
        }
    }
}
