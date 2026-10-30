package io.github.anastaci1a.localica.library

import org.junit.Assert.*
import org.junit.Test

class OrderCommitTest {
    private data class Entry(val id: String, val trackId: String, val title: String = "Original")

    /** Entry-list equality/hash comparisons and unnecessary reads must not hide in the predicate. */
    private class TrackedList<T>(
        private val name: String,
        private val values: List<T>,
        private val events: MutableList<String>,
    ) : AbstractList<T>() {
        override val size: Int get() { events += "$name.size"; return values.size }
        override fun get(index: Int): T { events += "$name[$index]"; return values[index] }
        override fun equals(other: Any?): Boolean = error("Do not compare entry lists structurally")
        override fun hashCode(): Int = error("Do not hash entry lists")
    }

    private val order = SavedOrder(listOf("a", "b", "c"))
    private val entries = listOf(Entry("a", "same-track"), Entry("b", "same-track"), Entry("c", "other-track"))

    private fun changed(
        before: List<Entry>, after: List<Entry>, events: MutableList<String>,
        beforeOrder: SavedOrder = order, afterOrder: SavedOrder = order,
    ) = OrderCommit.hasChanged(beforeOrder, afterOrder, before, after) {
        events += "id:${it.id}"
        it.id
    }

    private fun scan(vararg ids: Pair<String, String>): List<String> = buildList {
        addAll(listOf("before.size", "after.size", "before.size"))
        ids.forEachIndexed { index, (before, after) ->
            add("before[$index]"); add("id:$before")
            add("after[$index]"); add("id:$after")
        }
    }

    @Test fun sameListReferenceSkipsEveryEntryReadAfterEqualSavedOrders() {
        val events = mutableListOf<String>()
        val shared = TrackedList("shared", entries, events)
        val equalOrder = order.copy(manual = order.manual.toMutableList())
        assertNotSame(order, equalOrder)
        assertFalse(changed(shared, shared, events, afterOrder = equalOrder))
        assertTrue(events.isEmpty())
    }

    @Test fun eachSavedOrderFieldChangeShortCircuitsDistinctEntryLists() {
        val changedOrders = listOf(
            order.copy(manual = order.manual.reversed()),
            order.copy(sort = TrackSort.Title),
            order.copy(ascending = false),
        )
        for (afterOrder in changedOrders) {
            val events = mutableListOf<String>()
            val before = TrackedList("before", entries, events)
            val after = TrackedList("after", emptyList<Entry>(), events)
            assertTrue(changed(before, after, events, afterOrder = afterOrder))
            assertTrue(events.isEmpty())
        }
    }

    @Test fun savedOrderChangeWinsEvenWhenEntriesHaveTheSameReference() {
        val events = mutableListOf<String>()
        val shared = TrackedList("shared", entries, events)
        assertTrue(changed(shared, shared, events, afterOrder = order.copy(ascending = false)))
        assertTrue(events.isEmpty())
    }

    @Test fun equalIdsInDistinctListsVisitEveryPositionOnceWithoutEntryListEquality() {
        val events = mutableListOf<String>()
        val before = TrackedList("before", entries, events)
        val after = TrackedList("after", entries.map { it.copy() }, events)
        assertNotSame(before, after)
        assertFalse(changed(before, after, events))
        assertEquals(scan("a" to "a", "b" to "b", "c" to "c"), events)
    }

    @Test fun metadataOnlyReplacementIsNotAnOrderChange() {
        val events = mutableListOf<String>()
        val replacement = entries.map { it.copy(title = "Updated ${it.id}") }
        assertNotEquals(entries, replacement)
        val before = TrackedList("before", entries, events)
        val after = TrackedList("after", replacement, events)
        assertFalse(changed(before, after, events))
        assertEquals(scan("a" to "a", "b" to "b", "c" to "c"), events)
    }

    @Test fun differentSizesInEitherDirectionSkipAllIndexingAndIdExtraction() {
        for ((old, new) in listOf(entries to entries.take(1), entries.take(1) to entries)) {
            val events = mutableListOf<String>()
            val before = TrackedList("before", old, events)
            val after = TrackedList("after", new, events)
            assertTrue(changed(before, after, events))
            assertEquals(listOf("before.size", "after.size"), events)
        }
    }

    @Test fun distinctEmptyListsDoNotIndexOrExtractIds() {
        val events = mutableListOf<String>()
        val before = TrackedList("before", emptyList<Entry>(), events)
        val after = TrackedList("after", emptyList<Entry>(), events)
        assertNotSame(before, after)
        assertFalse(changed(before, after, events, SavedOrder(emptyList()), SavedOrder(emptyList())))
        assertEquals(scan(), events)
    }

    @Test fun singletonMetadataReplacementIsUnchangedButAnotherOccurrenceIsChanged() {
        val original = Entry("a", "same-track")
        for (replacement in listOf(original.copy(title = "Updated"), original.copy(id = "b"))) {
            val events = mutableListOf<String>()
            val before = TrackedList("before", listOf(original), events)
            val after = TrackedList("after", listOf(replacement), events)
            assertEquals(replacement.id != original.id, changed(before, after, events))
            assertEquals(scan("a" to replacement.id), events)
        }
    }

    @Test fun duplicateTrackReorderingUsesOccurrenceIdsAndStopsAtTheFirstPair() {
        val events = mutableListOf<String>()
        val reversedDuplicates = listOf(entries[1], entries[0], entries[2])
        assertEquals(entries.map { it.trackId }, reversedDuplicates.map { it.trackId })
        val before = TrackedList("before", entries, events)
        val after = TrackedList("after", reversedDuplicates, events)
        assertTrue(changed(before, after, events))
        assertEquals(scan("a" to "b"), events)
    }

    @Test fun matchingPrefixStopsAtTheFirstDifferentOccurrenceWithoutReadingTheSuffix() {
        val events = mutableListOf<String>()
        val replacement = listOf(entries[0], entries[1].copy(id = "new-b"), entries[2])
        val before = TrackedList("before", entries, events)
        val after = TrackedList("after", replacement, events)
        assertTrue(changed(before, after, events))
        assertEquals(scan("a" to "a", "b" to "new-b"), events)
    }

    @Test fun inspectionLeavesTheOriginalListsAndTheirOccurrenceObjectsUntouched() {
        val events = mutableListOf<String>()
        val original = entries.toMutableList()
        val replacement = entries.reversed().toMutableList()
        val beforeSnapshot = original.toList()
        val afterSnapshot = replacement.toList()
        assertTrue(changed(original, replacement, events))
        assertEquals(beforeSnapshot, original)
        assertEquals(afterSnapshot, replacement)
        beforeSnapshot.indices.forEach { assertSame(beforeSnapshot[it], original[it]) }
        afterSnapshot.indices.forEach { assertSame(afterSnapshot[it], replacement[it]) }
        assertEquals(listOf("id:a", "id:c"), events)
    }
}
