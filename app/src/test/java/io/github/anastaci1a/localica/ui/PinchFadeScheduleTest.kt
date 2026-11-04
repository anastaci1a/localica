package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class PinchFadeScheduleTest {
    @Test fun actualMidpointRemainsFixedDuringRetargetAndResetsForTheNextPreview() {
        val focus = PinchFadeFocus()
        val midpoint = Offset(17f, 93f)
        assertEquals(midpoint, focus.resolve(1, midpoint))
        assertEquals(midpoint, focus.resolve(1, Offset(200f, 300f)))
        val next = Offset(44f, 51f)
        assertEquals(next, focus.resolve(2, next))
        assertEquals(next, focus.resolve(2, midpoint))
    }

    @Test fun singletonFadesAcrossTheCompleteTransition() {
        val window = pinchFadeWindow(0, 1)
        assertEquals(0f, window.start, 0f)
        assertEquals(1f, window.width, 0f)
        assertEquals(1f, window.alpha(0f), 0f)
        assertEquals(.5f, window.alpha(.5f), .00001f)
        assertEquals(0f, window.alpha(1f), 0f)
    }

    @Test fun evenStartsEqualWidthsAndHalfOverlapCoverTheWholeTransition() {
        for (count in listOf(2, 3, 8, 100)) {
            val windows = List(count) { pinchFadeWindow(it, count) }
            val step = 1f / (count + 1)
            assertEquals(0f, windows.first().start, 0f)
            assertEquals(1f, windows.last().start + windows.last().width, .00001f)
            windows.forEach { assertEquals(2f * step, it.width, .00001f) }
            windows.zipWithNext().forEach { (a, b) ->
                assertEquals(step, b.start - a.start, .00001f)
                assertEquals(a.width / 2f, a.start + a.width - b.start, .00001f)
                val overlap = (b.start + a.start + a.width) / 2f
                assertTrue(a.alpha(overlap) in .00001f.. .99999f)
                assertTrue(b.alpha(overlap) in .00001f.. .99999f)
            }
        }
    }

    @Test fun endpointDistanceOrdersFarthestFirstWithStableTies() {
        val distances = linkedMapOf("near" to 1f, "far" to 100f, "tie" to 100f, "focus" to 0f)
        val schedule = pinchFadeSchedule(distances)
        assertEquals(listOf("far", "tie", "near", "focus"), schedule.keys.toList())
        assertEquals(emptyMap<String, PinchFadeWindow>(), pinchFadeSchedule(emptyMap<String, Float>()))
        for (step in 0..100) {
            val alphas = schedule.values.map { it.alpha(step / 100f) }
            alphas.zipWithNext().forEach { (far, near) -> assertTrue(far <= near) }
        }
    }

    @Test fun reversedProgressRetracesEveryAlphaAndArrivalsMirrorDepartures() {
        for (count in listOf(1, 2, 7)) for (rank in 0 until count) {
            val window = pinchFadeWindow(rank, count)
            val forward = (0..100).map { window.alpha(it / 100f) }
            val backward = (100 downTo 0).map { window.alpha(it / 100f) }
            assertEquals(forward, backward.reversed())
            forward.forEachIndexed { step, alpha ->
                assertEquals(alpha, window.alpha(1f - step / 100f, arriving = true), .00001f)
            }
            assertEquals(1f, window.alpha(-1f), 0f)
            assertEquals(0f, window.alpha(2f), 0f)
            assertEquals(0f, window.alpha(-1f, arriving = true), 0f)
            assertEquals(1f, window.alpha(2f, arriving = true), 0f)
        }
    }
}
