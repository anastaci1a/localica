package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import org.junit.Assert.*
import org.junit.Test

class OrderCheckpointTest {
    private fun track(id: String) = Track(id, id, fileName = "$id.mp3", relativePath = "$id.mp3")

    @Test fun repeatedReversalsAndRandomizationsRestorePhysicalAndManualOrdersIndependently() {
        val initial = QueueEdits.create(listOf(track("C"), track("A"), track("A"), track("B")), 2, "Mix")
        val opening = QueueEdits.sort(initial, TrackSort.Title, false).copy(positionMs = 1234)
        val checkpoint = OrderCheckpoint.capture(opening.entries.map { it.id }, opening.order)
        var edited = opening
        repeat(4) {
            edited = QueueEdits.reverse(QueueEdits.randomize(QueueEdits.reverse(edited), true, kotlin.random.Random(it)))
            assertEquals(edited.entries.map { it.id }, edited.order.manual)
        }
        val ids = edited.entries.map { it.id }
        val journey = QueueJourney.start(ids, edited.currentEntryId, JourneyModes(shuffle = true))
        edited = edited.copy(name = "Renamed", positionMs = 4567, journey = journey, lastActivatedAt = 99,
            entries = edited.entries.map { it.copy(track = it.track.copy(title = "Updated", playCount = 7)) })
        val restored = QueueEdits.revertOrder(edited, checkpoint)
        assertEquals(checkpoint.physical, restored.entries.map { it.id })
        assertEquals(checkpoint.saved, restored.order)
        assertEquals(edited, restored.copy(entries = edited.entries, order = edited.order))
        assertTrue(restored.entries.all { it.track.title == "Updated" && it.track.playCount == 7L })
        assertEquals(2, restored.entries.count { it.track.id == "A" })
        assertSame(journey, restored.journey)
        assertEquals(initial.entries.map { it.id }, QueueEdits.sort(restored, TrackSort.Manual, true).entries.map { it.id })
    }

    @Test fun restoreReconcilesNewAndRemovedOccurrencesWithoutRestoringStaleMetadata() {
        val original = PlaylistEdits.append(ManualPlaylist(name = "Mix", coverUri = "old"),
            listOf(track("C"), track("A"), track("A"), track("B")))
        val opening = PlaylistEdits.sort(original, TrackSort.Title, false)
        val checkpoint = OrderCheckpoint.capture(opening.entries.map { it.id }, opening.order)
        val reversed = PlaylistEdits.reverse(opening)
        val removed = original.entries[1].id
        val current = PlaylistEdits.append(PlaylistEdits.remove(reversed, setOf(removed)), listOf(track("A"), track("New")))
            .let { list -> list.copy(name = "Current name", coverUri = "current", modifiedAt = 42,
                entries = list.entries.map { it.copy(track = it.track.copy(playCount = 3), sourcePlaylistId = "origin") }) }
        val restored = PlaylistEdits.revertOrder(current, checkpoint)
        val newIds = current.entries.takeLast(2).map { it.id }
        assertEquals(listOf(original.entries[0].id) + newIds + listOf(original.entries[3].id, original.entries[2].id),
            restored.entries.map { it.id })
        assertEquals(listOf(original.entries[0].id) + newIds + listOf(original.entries[2].id, original.entries[3].id),
            restored.order.manual)
        assertEquals(TrackSort.Title, restored.order.sort)
        assertFalse(restored.order.ascending)
        assertEquals(current, restored.copy(entries = current.entries, order = current.order))
        assertEquals(current.entries.associateBy { it.id }, restored.entries.associateBy { it.id })
        assertFalse(restored.entries.any { it.id == removed })
    }

    @Test fun aNewCheckpointRestoresTheNewOpeningAndAnEmptyMembershipStaysEmpty() {
        val original = QueueEdits.create(listOf(track("A"), track("B"), track("C")), 1, "Mix")
        val randomized = QueueEdits.randomize(original, false, kotlin.random.Random(3))
        val reopened = OrderCheckpoint.capture(randomized.entries.map { it.id }, randomized.order)
        val after = QueueEdits.sort(randomized, TrackSort.Title, false)
        assertEquals(randomized, QueueEdits.revertOrder(after, reopened))
        val empty = after.copy(entries = emptyList(), currentEntryId = null, order = SavedOrder(emptyList()))
        assertEquals(empty, QueueEdits.revertOrder(empty, reopened))
    }

    @Test fun capturesDefensiveCopiesAndReconcilesInsertionsAtTheirCurrentNeighbor() {
        val physical = mutableListOf("a", "b", "c")
        val manual = mutableListOf("c", "a", "b")
        val checkpoint = OrderCheckpoint.capture(physical, SavedOrder(manual, TrackSort.Year, false))
        physical.reverse(); manual.clear()
        val restored = checkpoint.reconcile(listOf("new-first", "c", "new-after-c", "a", "new-after-a"))
        assertEquals(listOf("new-first", "a", "new-after-a", "c", "new-after-c"), restored.physical)
        assertEquals(listOf("new-first", "c", "new-after-c", "a", "new-after-a"), restored.saved.manual)
        assertEquals(TrackSort.Year, restored.saved.sort)
        assertFalse(restored.saved.ascending)
    }
}
