package io.github.anastaci1a.localica.queue

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class ShuffleTraversalTest {
    @Test fun selectedOccurrenceStartsTraversalWithoutChangingVisibleOrder() {
        val visible = (0..20).map { "occurrence:$it" }
        val first = shuffleTraversal(visible, visible[8], random = Random(7))
        assertEquals(visible[8], first.first())
        assertEquals(visible.toSet(), first.toSet())
        assertEquals(visible.size, first.size)
        val second = shuffleTraversal(visible, visible[4], random = Random(8))
        assertEquals(visible[4], second.first())
        assertEquals((0..20).map { "occurrence:$it" }, visible)
        assertNotEquals(first, second)
    }
    @Test fun visibleReorderingAndRemovalKeepTheRemainingTraversalAndAppendNewOccurrences() {
        val before = listOf("c", "a", "b", "d")
        assertEquals(listOf("c", "a", "d", "e"), shuffleTraversal(listOf("e", "d", "c", "a"), "a", before, Random(4)))
        assertEquals(emptyList<String>(), shuffleTraversal(emptyList(), null))
    }
}
