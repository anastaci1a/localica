package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tanh

class GlassReboundGeometryTest {
    @Test fun crossLimitIsSmoothMonotonicAndSizedByTravelExtent() {
        for (extent in listOf(52f, 90f, 300f)) for (travel in listOf(52f, 90f, 300f)) {
            val cap = minOf(6f, travel * .04f)
            var previous = Float.NEGATIVE_INFINITY
            for (i in -500..500) {
                val scale = 1f + i / 1000f
                val next = glassCrossScale(scale, extent, travel, 6f)
                assertTrue(next >= previous)
                assertTrue(abs((next - 1f) * extent) <= cap + .0001f)
                val opposite = glassCrossScale(2f - scale, extent, travel, 6f)
                assertEquals(2f, next + opposite, .000001f)
                previous = next
            }
            assertEquals(1f, glassCrossScale(1f, extent, travel, 6f), 0f)
        }
    }

    @Test fun depthTimingPreservesHeldEquilibriumAndHighDampingMonotonicReturn() {
        for (pressure in listOf(0f, -.1f, -.22f)) {
            val body = GlassMomentum(40f)
            repeat(600) { body.advance(0f, 1f / 120, pressure, 1f) }
            assertEquals(pressure, body.strain, .0001f)
            assertEquals(.24f * tanh((-.24f - abs(pressure) * .3f) / .24f), body.shape.depth, .0001f)
            var previousDepth = body.shape.depth
            repeat(360) {
                body.advance(0f, 1f / 120)
                assertTrue(body.shape.depth >= previousDepth - .000001f)
                assertTrue(body.shape.depth <= .000001f)
                assertEquals(1f, body.scaleX * body.scaleY * body.shape.thickness, .00001f)
                previousDepth = body.shape.depth
            }
            assertTrue(body.settled)
        }
    }
}
