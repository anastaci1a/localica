package io.github.anastaci1a.localica.queue

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import org.junit.Assert.*
import org.junit.Test

class SavedOrderTest {
    @Test fun baselineReconciliationPreservesExistingInsertionSemanticsAtScale() {
        val random = kotlin.random.Random(14028)
        repeat(100) {
            val ids = (0..200).map(Int::toString).shuffled(random).take(random.nextInt(201))
            val original = (0..200).map(Int::toString).shuffled(random).take(random.nextInt(201))
            val reference = original.filter { it in ids }.toMutableList()
            ids.forEachIndexed { index, id ->
                if (id !in reference) {
                    val previous = ids.take(index).lastOrNull { it in reference }
                    reference.add(if (previous == null) 0 else reference.indexOf(previous) + 1, id)
                }
            }
            assertEquals(reference, SavedOrder(original).reconcile(ids).manual)
        }
    }
    private fun track(title: String) = Track(title, title, fileName = "$title.mp3", relativePath = "$title.mp3")
    @Test fun sortAndRestoreKeepOccurrencesCursorAndPosition() {
        val original = QueueEdits.create(listOf(track("C"), track("A"), track("B"), track("A")), 3, "Mix").copy(positionMs = 1234)
        val sorted = QueueEdits.sort(original, TrackSort.Title, true)
        assertEquals(listOf("A", "A", "B", "C"), sorted.entries.map { it.track.title })
        assertEquals(original.currentEntryId, sorted.currentEntryId)
        assertEquals(original.positionMs, sorted.positionMs)
        assertEquals(original.entries, QueueEdits.sort(sorted, TrackSort.Manual, true).entries)
        assertEquals(sorted.order, QueueEdits.moveBefore(sorted, setOf(sorted.entries[0].id), sorted.entries[1].id).order)
        val moved = QueueEdits.moveBefore(sorted, setOf(sorted.entries.last().id), sorted.entries.first().id)
        assertEquals(TrackSort.Manual, moved.order.sort)
        assertEquals(moved.entries.map { it.id }, moved.order.manual)
    }
    @Test fun insertionsAndRemovalsUpdateManualBaselineWithoutLosingDuplicates() {
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Mix"), listOf(track("C"), track("A"), track("B"), track("A")))
        val sorted = PlaylistEdits.sort(playlist, TrackSort.Title, true)
        val edited = PlaylistEdits.remove(PlaylistEdits.append(sorted, listOf(track("D"))), setOf(playlist.entries[1].id))
        val restored = PlaylistEdits.sort(edited, TrackSort.Manual, true)
        assertEquals(listOf("C", "B", "A", "D"), restored.entries.map { it.track.title })
        assertEquals(4, restored.entries.map { it.id }.toSet().size)
        val random = PlaylistEdits.randomize(restored)
        assertEquals(random.entries.map { it.id }, random.order.manual)
    }
}
