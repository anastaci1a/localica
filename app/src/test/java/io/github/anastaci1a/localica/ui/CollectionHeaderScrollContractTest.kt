package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class CollectionHeaderScrollContractTest {
    @Test fun carouselSuppliesLiveHorizontalDirectionsToItsSharedTiles() {
        var backward = false
        val context = ControlScrollContext().apply {
            node = ControlScrollNode { controlScrollDirections(false, true, backward, false) }
        }
        assertEquals(ControlScrollDirection.Left, context.directions())
        backward = true
        assertEquals(ControlScrollDirection.Left or ControlScrollDirection.Right, context.directions())
    }
}
