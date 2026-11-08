package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

/** #559: departures fade in the order they leave the viewport, and finish as they leave. */
class PinchFadeScheduleTest {
    private val viewport = Rect(0f, 0f, 1000f, 2000f)

    private fun lerp(source: Rect, target: Rect, p: Float) = Rect(
        source.left + (target.left - source.left) * p, source.top + (target.top - source.top) * p,
        source.right + (target.right - source.right) * p, source.bottom + (target.bottom - source.bottom) * p)

    private fun Rect.visibleIn(viewport: Rect) =
        left < viewport.right && right > viewport.left && top < viewport.bottom && bottom > viewport.top

    @Test fun exitProgressIsTheFirstFrameOutsideTheViewport() {
        val rows = listOf(
            Rect(0f, 100f, 200f, 300f) to Rect(0f, -900f, 1000f, -500f),   // up through the top
            Rect(800f, 1800f, 1000f, 1990f) to Rect(0f, 2400f, 1000f, 2800f), // down through the bottom
            Rect(800f, 500f, 1000f, 700f) to Rect(1100f, 500f, 2100f, 900f),  // out of the right edge
            Rect(0f, 500f, 200f, 700f) to Rect(-1200f, 400f, -200f, 800f))    // out of the left edge
        for ((source, target) in rows) {
            val exit = requireNotNull(pinchExitProgress(source, target, viewport))
            assertTrue(exit in 0f..1f)
            assertTrue("Visible just before $exit", lerp(source, target, exit - .001f).visibleIn(viewport))
            assertFalse("Gone at $exit", lerp(source, target, exit + .0001f).visibleIn(viewport))
        }
        assertNull("A path ending on screen never leaves",
            pinchExitProgress(Rect(0f, 0f, 100f, 100f), Rect(500f, 500f, 900f, 900f), viewport))
        assertEquals("A path starting off screen is never shown", 0f,
            pinchExitProgress(Rect(0f, -300f, 100f, -100f), Rect(0f, -900f, 500f, -500f), viewport))
    }

    @Test fun fadesFollowExitOrderWithEvenStartsAndEndAsEachItemLeaves() {
        val exits = linkedMapOf("late" to .9f, "first" to .2f, "second" to .35f, "third" to .62f)
        val schedule = pinchExitSchedule(exits)
        val order = listOf("first", "second", "third", "late")
        val starts = order.map { schedule.getValue(it).start }
        assertEquals("Ordered by exit, never by input order", starts.sorted(), starts)
        val steps = starts.zipWithNext { a, b -> b - a }
        steps.forEach { assertEquals("Fade starts are evenly spaced: $starts", steps.first(), it, .0001f) }
        assertEquals(0f, starts.first(), 0f)
        for (key in order) {
            val window = schedule.getValue(key)
            assertEquals("$key finishes fading exactly as it leaves", exits.getValue(key), window.start + window.width, .0001f)
            assertTrue("$key fades for at least one step", window.width >= steps.first() - .0001f)
            assertEquals(0f, window.alpha(exits.getValue(key)), .0001f)
            assertTrue("$key is still partly drawn shortly before leaving",
                window.alpha(exits.getValue(key) - window.width / 2f) in .05f.. .95f)
        }
    }

    @Test fun topAndBottomRowsLeavingTogetherFadeTogether() {
        // The top row exits upward and the bottom row downward at the same progress.
        val top = pinchExitProgress(Rect(0f, 100f, 1000f, 300f), Rect(0f, -500f, 1000f, -100f), viewport)!!
        val bottom = pinchExitProgress(Rect(0f, 1700f, 1000f, 1900f), Rect(0f, 2100f, 1000f, 2500f), viewport)!!
        val middle = pinchExitProgress(Rect(0f, 1000f, 1000f, 1200f), Rect(0f, 2900f, 1000f, 3300f), viewport)!!
        assertEquals(top, bottom, .02f)
        val schedule = pinchExitSchedule(mapOf("top" to top, "bottom" to bottom, "middle" to middle,
            "nearTie" to top + PinchExitTie * .9f))
        assertEquals(schedule.getValue("top"), schedule.getValue("bottom"))
        assertEquals(schedule.getValue("top"), schedule.getValue("nearTie"))
        assertNotEquals(schedule.getValue("top"), schedule.getValue("middle"))
    }

    @Test fun itemsAlreadyAtTheEdgeFadeUntilTheyLeaveWithoutCompressingTheCascade() {
        val schedule = pinchExitSchedule(mapOf("edge" to .01f, "a" to .3f, "b" to .6f, "c" to .9f))
        assertEquals(PinchFadeWindow(0f, .01f), schedule.getValue("edge"))
        listOf(0f, .3f, .6f).zip(listOf("a", "b", "c")).forEach { (start, key) -> assertEquals(start, schedule.getValue(key).start, .0001f) }
        assertEquals(emptyMap<String, PinchFadeWindow>(), pinchExitSchedule(emptyMap<String, Float>()))
        assertEquals(PinchFadeWindow(0f, 1f), pinchExitSchedule(mapOf("only" to 1f)).getValue("only"))
    }

    @Test fun noDepartureStopsWhileOpaqueAndArrivalsRetraceDepartures() {
        // Departures follow their whole path: whenever one is still drawn it is on screen, and
        // it is invisible from the frame it leaves. Reversal plays the same alphas backward.
        val paths = (0 until 12).associate { index ->
            val column = index % 4; val row = index / 4
            "item$index" to (Rect(column * 250f, row * 600f + 50f, column * 250f + 250f, row * 600f + 550f) to
                Rect(column * 1000f - 1500f, row * 2400f - 2400f, column * 1000f - 500f, row * 2400f - 400f))
        }
        val exits = paths.mapValues { (_, path) -> pinchExitProgress(path.first, path.second, viewport) ?: 1f }
        val schedule = pinchExitSchedule(exits.filterValues { it > 0f })
        for ((key, path) in paths) {
            val window = schedule[key] ?: continue
            for (step in 0..1000) {
                val p = step / 1000f
                val alpha = window.alpha(p)
                if (!lerp(path.first, path.second, p).visibleIn(viewport))
                    assertEquals("$key is drawn off screen at $p", 0f, alpha, .0001f)
                assertEquals("Arrival mirrors departure", alpha, window.alpha(1f - p, arriving = true), .0001f)
            }
        }
    }
}
