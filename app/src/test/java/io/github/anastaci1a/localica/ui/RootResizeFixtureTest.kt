package io.github.anastaci1a.localica.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class RootResizeFixtureTest {
    @Test fun resizeWitnessCrossesTheDominantEdgePlateauWithoutChangingTheBasisLaw() {
        val density = Density(1f)
        for (inset in listOf(0f, 42f, 72f, 126f)) {
            val before = playerBasis(density, IntSize(280, 400), inset.dp, 80.dp)
            val oldResize = playerBasis(density, IntSize(320, 440), inset.dp, 80.dp)
            val corrected = playerBasis(density, IntSize(320, 560), inset.dp, 80.dp)
            if (inset == 126f) {
                assertEquals("The bottom edge can remain dominant across the old resize", 206f, before.edgeTravelPx, 0f)
                assertEquals(206f, oldResize.edgeTravelPx, 0f)
            }
            assertTrue("The corrected input must exercise reference travel at inset $inset", corrected.edgeTravelPx > before.edgeTravelPx)
        }
    }
}
