package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class BrowsePinchIndicesTest {
    @Test fun uniqueItemsReuseTheExistingMapWithoutVisitingTheirKeys() {
        for (size in listOf(0, 3, 13_313)) {
            val items = List(size) { "occurrence-$it" }
            val shared = items.withIndex().associate { it.value to it.index }
            val actual = retainedPinchItemIndices(items, shared) {
                fail("Unique keys must reuse the existing map without another catalog traversal")
                it
            }
            assertSame(shared, actual)
        }
    }

    @Test fun duplicateKeysKeepTheFirstMatchWithoutChangingTheSharedLastWinsMap() {
        val items = listOf("a", "b", "a", "c", "b")
        val shared = items.withIndex().associate { it.value to it.index }
        var visits = 0
        val actual = retainedPinchItemIndices(items, shared) { visits++; it }
        assertEquals(items.size, visits)
        assertNotSame(shared, actual)
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 3), actual)
        assertEquals(mapOf("a" to 2, "b" to 4, "c" to 3), shared)
        for (key in items + "missing") assertEquals(items.indexOf(key), actual.retainedPinchItemIndex(key))
    }

    @Test fun reorderedAndRemovedItemsUseTheirCurrentPositionsWithoutRenumberingOccurrences() {
        val snapshots = listOf(listOf("first", "second", "third"),
            listOf("third", "first", "second"), listOf("third", "second"), emptyList())
        val indices = snapshots.map { items ->
            retainedPinchItemIndices(items, items.withIndex().associate { it.value to it.index }) { it }
        }
        for ((items, actual) in snapshots.zip(indices)) {
            for (key in listOf("first", "second", "third", "missing")) {
                assertEquals(items.indexOf(key), actual.retainedPinchItemIndex(key))
            }
        }
        assertEquals(0, indices.first().retainedPinchItemIndex("first"))
        assertEquals(-1, indices[2].retainedPinchItemIndex("first"))
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
