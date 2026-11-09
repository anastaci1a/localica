package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class ToastOccurrenceTargetTest {
    private fun frame(keys: List<String>, lazyKeys: List<String> = keys,
        pending: Boolean = false, published: Any? = "route", current: Any = "route") =
        ToastOccurrenceFrame(current, published, pending, keys.size, lazyKeys.size + 1,
            lazyKeys.mapIndexed { index, key -> index + 1 to key },
            itemKey = { keys.getOrNull(it) }, occurrenceIndex = keys::indexOf)

    @Test fun everyRetargetRejectsSameCountPublicationBeforeLazyKeysCatchUp() {
        val old = listOf("occurrence-a", "occurrence-b", "occurrence-c")
        val reordered = listOf("occurrence-b", "occurrence-a", "occurrence-c")
        assertEquals(2, toastOccurrenceTarget(frame(old), "occurrence-b"))
        assertNull(toastOccurrenceTarget(frame(reordered, old), "occurrence-b"))
        assertEquals(1, toastOccurrenceTarget(frame(reordered), "occurrence-b"))
        // A second reorder during the same stock ease must pass the identical guard again.
        assertNull(toastOccurrenceTarget(frame(old, reordered), "occurrence-b"))
        assertEquals(2, toastOccurrenceTarget(frame(old), "occurrence-b"))
    }

    @Test fun pendingRouteAndMeasurementGapsCannotSupplyAnIndexMidEase() {
        val keys = listOf("a", "b")
        val ready = frame(keys)
        assertEquals(2, toastOccurrenceTarget(ready, "b"))
        assertNull(toastOccurrenceTarget(ready.copy(pending = true), "b"))
        assertNull(toastOccurrenceTarget(ready.copy(currentRoute = "replacement"), "b"))
        assertNull(toastOccurrenceTarget(ready.copy(publishedRoute = null), "b"))
        assertNull(toastOccurrenceTarget(ready.copy(lazyCount = 2), "b"))
        assertEquals(2, toastOccurrenceTarget(ready, "b"))
    }

    @Test fun exactOccurrencesStayDistinctAndSettledRemovalDoesNotScheduleLaterJump() {
        val repeatedSongOccurrences = listOf("song-copy-one", "song-copy-two")
        assertEquals(2, toastOccurrenceTarget(frame(repeatedSongOccurrences), "song-copy-two"))
        assertEquals(-1, toastOccurrenceTarget(frame(listOf("song-copy-one")), "song-copy-two"))
        assertEquals(-1, toastOccurrenceTarget(frame(repeatedSongOccurrences), null))
    }
}
