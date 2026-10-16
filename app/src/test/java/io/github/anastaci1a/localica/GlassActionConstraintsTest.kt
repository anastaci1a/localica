package io.github.anastaci1a.localica

import androidx.compose.ui.unit.Constraints
import io.github.anastaci1a.localica.ui.glassActionNavigationConstraints
import org.junit.Assert.*
import org.junit.Test

class GlassActionConstraintsTest {
    @Test fun scrollingParentRetainsUnboundedHeightAtBothDeviceDensities() {
        for ((width, stripHeight) in listOf(420 to 107, 630 to 162)) {
            val parent = Constraints(minWidth = width, maxWidth = width, maxHeight = Constraints.Infinity)
            val footer = glassActionNavigationConstraints(parent, stripHeight)
            assertEquals(width, footer.minWidth)
            assertEquals(width, footer.maxWidth)
            assertEquals(0, footer.minHeight)
            assertEquals(Constraints.Infinity, footer.maxHeight)
        }
    }

    @Test fun boundedParentReservesOnlyRemainingHeightAndClearsMinimum() {
        val parent = Constraints(minWidth = 420, maxWidth = 420, minHeight = 100, maxHeight = 480)
        val footer = glassActionNavigationConstraints(parent, 107)
        assertEquals(0, footer.minHeight)
        assertEquals(373, footer.maxHeight)
        assertEquals(0, glassActionNavigationConstraints(parent, 480).maxHeight)
        assertEquals(0, glassActionNavigationConstraints(parent, 600).maxHeight)
    }
}
