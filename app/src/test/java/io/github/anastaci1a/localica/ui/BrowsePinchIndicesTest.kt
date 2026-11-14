package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class BrowsePinchIndicesTest {
    @Test fun reorderedAndRemovedItemsUseTheirCurrentPositionsWithoutRenumberingOccurrences() {
        val snapshots = listOf(listOf("first", "second", "third"),
            listOf("third", "first", "second"), listOf("third", "second"), emptyList())
        val indices = snapshots.map { items ->
            items.withIndex().associate { it.value to it.index }
        }
        for ((items, actual) in snapshots.zip(indices)) {
            for (key in listOf("first", "second", "third", "missing")) {
                assertEquals(items.indexOf(key), actual.retainedPinchItemIndex(key))
            }
        }
        assertEquals(0, indices.first().retainedPinchItemIndex("first"))
        assertEquals(-1, indices[2].retainedPinchItemIndex("first"))
    }

    @Test fun restingLivePaintOrderReusesSortedListsAndKeepsTheSortedOracleOtherwise() {
        val sorted = listOf(0, 1, 2, 5, 9)
        assertSame("An already ordered viewport must not allocate a paint list each frame",
            sorted, liveItemsInIndexOrder(sorted) { it })
        assertSame(emptyList<Int>(), liveItemsInIndexOrder(emptyList<Int>()) { it })
        // Animation extras may be reported out of order; keep the previous stable sort.
        val unordered = listOf(4 to "a", 1 to "b", 3 to "c", 2 to "d")
        assertEquals(unordered.sortedBy { it.first }, liveItemsInIndexOrder(unordered) { it.first })
    }

    @Test fun missingAndNonStringKeysKeepMinusOneWithoutStringCoercion() {
        val indices = mapOf("42" to 0, "item" to 1)
        assertEquals(-1, indices.retainedPinchItemIndex("browser-notice"))
        assertEquals(-1, indices.retainedPinchItemIndex(DropGapKey))
        assertEquals(-1, indices.retainedPinchItemIndex(42))
        assertEquals(0, indices.retainedPinchItemIndex("42"))
        assertEquals(-1, emptyMap<String, Int>().retainedPinchItemIndex("item"))
        // Missing anchors still enter the existing truncating row arithmetic;
        // the caller, not this lookup, decides whether a retained item is omitted.
        assertEquals(-1, indices.retainedPinchItemIndex("missing") / 1)
        assertEquals(0, indices.retainedPinchItemIndex("missing") / 2)
    }
}
