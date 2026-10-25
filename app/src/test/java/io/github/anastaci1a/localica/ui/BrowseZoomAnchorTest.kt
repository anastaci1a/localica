package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.browse.BrowseStyle
import io.github.anastaci1a.localica.navigation.BrowseRoute
import io.github.anastaci1a.localica.navigation.MainTab
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class BrowseZoomAnchorTest {
    private val route = BrowseRoute(MainTab.Queues)

    @Test fun repeatedFractionalAnchorCorrectionCannotAccumulatePixelDrift() {
        for (top in listOf(-73, 0, 73, 600)) for (height in listOf(51, 137, 229, 641)) {
            for (fraction in listOf(0f, .13f, .5f, .79f, 1f)) {
                val anchor = BrowseZoomAnchor(route, BrowseStyle(), "occurrence", top, fraction)
                val initial = -137
                val correction = anchor.correction(initial, height)
                assertEquals("Only whole-pixel corrections reach LazyGrid", correction.toInt().toFloat(), correction, 0f)
                val settled = initial - correction.toInt()
                repeat(40) {
                    assertEquals("A held anchor must not feed fractional residual back into scrolling",
                        0f, anchor.correction(settled, height), 0f)
                }
                assertTrue("Keep the original fractional focus within the nearest representable pixel",
                    abs(settled - (top - height * fraction)) <= .5f)
            }
        }
    }

    @Test fun changingRowHeightReanchorsTheSameFractionWithoutReusingOldRounding() {
        val anchor = BrowseZoomAnchor(route, BrowseStyle(), "occurrence", 250, .37f)
        var position = 183
        for (height in listOf(80, 137, 229, 137, 80)) {
            position -= anchor.correction(position, height).toInt()
            assertEquals(0f, anchor.correction(position, height), 0f)
            assertTrue(abs(position - (anchor.top - height * anchor.fraction)) <= .5f)
        }
    }
}
