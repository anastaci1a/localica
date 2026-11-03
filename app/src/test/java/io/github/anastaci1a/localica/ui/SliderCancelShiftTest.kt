package io.github.anastaci1a.localica.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SliderCancelShiftTest {
    @Test fun aSliderClearOfTheBubbleNeverMovesItsMenu() {
        assertEquals(0f, sliderCancelShift(sliderBottom = 400f, targetTop = 900f, gap = 40f), 0f)
        assertEquals("Exactly the gap is still clear", 0f, sliderCancelShift(860f, 900f, 40f), 0f)
    }

    @Test fun anOverlappingSliderRisesByTheOverlapPlusTheGap() {
        assertEquals(60f, sliderCancelShift(sliderBottom = 920f, targetTop = 900f, gap = 40f), 0f)
        assertEquals(10f, sliderCancelShift(870f, 900f, 40f), 0f)
    }
}
