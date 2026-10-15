package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupSizingTest {
    @Test fun normalChromeUsesAllRemainingWindowRatherThanLocalPercentage() {
        assertEquals(560, popupBodyHeightLimit(800, 240, 48))
        assertEquals(160, popupBodyHeightLimit(400, 240, 48))
    }

    @Test fun oversizedChromeLeavesBoundedScrollableBody() {
        assertEquals(48, popupBodyHeightLimit(160, 300, 48))
        assertEquals(24, popupBodyHeightLimit(24, 300, 48))
        assertEquals(0, popupBodyHeightLimit(0, 300, 48))
    }

    @Test fun changingWindowAndFontScaledChromeNeverExceedsWindow() {
        for (window in listOf(0, 1, 24, 160, 240, 400, 800)) {
            for (chrome in listOf(0, 80, 240, 480, 960)) {
                val body = popupBodyHeightLimit(window, chrome, 48)
                assertTrue(body in 0..window)
                if (window - chrome >= 48) assertEquals(window - chrome, body)
            }
        }
    }

    @Test fun liveAndMeasurementChooseTheSameAdaptiveLayoutAtBoundary() {
        assertTrue(choiceFitsInline(2, 100f, 200f, false))
        assertEquals(false, choiceFitsInline(2, 100f, 199f, false))
        assertEquals(false, choiceFitsInline(2, 100f, 400f, true))
        assertEquals(false, choiceFitsInline(5, 100f, 1000f, false))
    }
}
