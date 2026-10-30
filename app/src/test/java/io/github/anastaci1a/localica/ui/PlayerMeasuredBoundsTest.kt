package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class PlayerMeasuredBoundsTest {
    private fun rect(value: Int) = Rect(value.toFloat(), 1f, value + 10f, 11f)

    @Test fun unchangedBoundsRefreshEveryNameOnceAndRetainPublishedMap() {
        val previous = linkedMapOf("background" to rect(0), "cover" to rect(1), "text" to rect(2))
        val reads = mutableListOf<String>()
        val names = linkedSetOf("text", "detached", "background", "cover")
        val result = preparePlayerMeasuredBounds(previous, names) {
            reads += it
            previous[it]
        }
        assertSame(previous, result)
        assertEquals(names.toList(), reads)
        assertSame(previous, preparePlayerMeasuredBounds(previous, previous.keys) { previous[it] })
    }

    @Test fun changedDetachedReattachedAndRemovedNamesMatchOriginalOrderedPreparation() {
        val original = linkedMapOf("background" to rect(0), "cover" to rect(1), "text" to rect(2))
        val states = listOf(
            linkedMapOf("text" to rect(8), "background" to rect(0), "cover" to rect(1)),
            linkedMapOf("cover" to null, "background" to rect(0), "text" to rect(2)),
            linkedMapOf("text" to rect(2), "cover" to rect(9), "background" to rect(0)),
            linkedMapOf("background" to rect(0)),
            linkedMapOf("background" to null, "cover" to null, "text" to null),
            linkedMapOf("new" to rect(4), "text" to rect(2)),
            linkedMapOf<String, Rect?>())
        for (state in states) {
            for (offset in 0..state.size) {
                val keys = state.keys.toList()
                val names = (keys.drop(offset) + keys.take(offset)).toCollection(linkedSetOf())
                val reads = mutableListOf<String>()
                val expected = names.mapNotNull { key -> state[key]?.let { key to it } }.toMap()
                val result = preparePlayerMeasuredBounds(original, names) { reads += it; state[it] }
                assertEquals(expected, result)
                assertEquals(names.toList(), reads)
                if (original != expected) assertEquals(expected.keys.toList(), result.keys.toList())
                else assertSame(original, result)
                assertEquals(linkedMapOf("background" to rect(0), "cover" to rect(1), "text" to rect(2)), original)
            }
        }
    }

    @Test fun failedMeasurementLeavesPublishedBoundsUnchangedAndRetryRefreshesAllChildren() {
        val previous = linkedMapOf("background" to rect(0), "cover" to rect(1), "text" to rect(2))
        val failure = IllegalStateException("coordinate unavailable")
        try {
            preparePlayerMeasuredBounds(previous, previous.keys) {
                if (it == "text") throw failure
                rect(9)
            }
            fail("Expected measurement failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(rect(0), previous["background"])
        assertEquals(rect(1), previous["cover"])
        assertEquals(rect(2), previous["text"])
        val result = preparePlayerMeasuredBounds(previous, previous.keys) { rect(9) }
        assertEquals(previous.keys.associateWith { rect(9) }, result)
        assertNotSame(previous, result)
    }
}
