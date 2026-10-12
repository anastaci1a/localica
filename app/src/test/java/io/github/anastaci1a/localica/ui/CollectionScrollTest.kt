package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionScrollTest {
    @Test fun headerRecyclingKeepsContinuousAndReversibleCoordinates() {
        val scroll = CollectionScroll()
        fun item(key: Any, index: Int, top: Int, height: Int = 80) = CollectionScroll.Item(key, index, top, height)
        assertEquals(-980f, scroll.update(listOf(item("browser-notice", 0, -980, 1000), item("a", 1, 20), item("b", 2, 100)), false))
        assertEquals(-1010f, scroll.update(listOf(item("a", 1, -10), item("b", 2, 70)), false))
        assertEquals(-1090f, scroll.update(listOf(item("b", 2, -10), item("c", 3, 70)), false))
        assertEquals(-1010f, scroll.update(listOf(item("a", 1, -10), item("b", 2, 70)), false))
        assertEquals(-980f, scroll.update(listOf(item("browser-notice", 0, -980, 1000), item("a", 1, 20)), false))
        assertEquals(0f, scroll.update(listOf(item("browser-notice", 0, 0, 1000)), true))
    }
}
