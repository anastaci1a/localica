package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class ControlGeometryTest {
    @Test fun playerHandleUsesInverseInkPolarity() {
        val dark = playerHandleColor(androidx.compose.ui.graphics.Color.White)
        val light = playerHandleColor(androidx.compose.ui.graphics.Color.Black)
        assertTrue(dark.red < .35f)
        assertTrue(light.red > .8f)
        assertEquals(dark.red, dark.green, 0f)
        assertEquals(light.red, light.blue, 0f)
    }
    @Test fun endpointsAndNearestStopsShareOneMapping() {
        assertEquals(2f, controlValue(-1f, 2f..8f, 0), .00001f)
        assertEquals(8f, controlValue(2f, 2f..8f, 0), .00001f)
        assertEquals(5f, controlValue(.5f, 2f..8f, 0), .00001f)
        assertEquals(4f, controlValue(.68f, 1f..5f, 3), .00001f)
        assertEquals(3f, controlValue(.375f, 1f..5f, 3), .00001f)
    }
    @Test fun mirroredTravelHasIdenticalAxialDeformation() {
        val right = GlassMomentum(); val left = GlassMomentum()
        for (speed in listOf(0f, 1f, 4f, 2f, 0f, 0f, -3f, -1f, 0f)) {
            repeat(20) {
                right.advance(speed, 1f / 120)
                left.advance(-speed, 1f / 120)
                assertEquals(right.shape.strain, left.shape.strain, .000001f)
                assertEquals(right.shape.depth, left.shape.depth, .000001f)
            }
        }
    }
}
