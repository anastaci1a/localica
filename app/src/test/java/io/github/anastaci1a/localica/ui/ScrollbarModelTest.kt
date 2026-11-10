package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Frame-sequence tests of the shared scrollbar position model against a simulated lazy grid. */
class ScrollbarModelTest {
    /** Item heights with span 1..columns (a full-span item owns its line); viewport in px. */
    private class SimGrid(val heights: List<Int>, val fullSpan: Set<Int>, val columns: Int, val viewport: Int, val spacing: Int = 0) {
        class Line(val first: Int, val items: List<Int>, val top: Int, val height: Int)
        val lines: List<Line>
        val content: Int
        init {
            val out = mutableListOf<Line>()
            var i = 0; var y = 0
            while (i < heights.size) {
                val n = if (i in fullSpan) 1 else (1..columns).takeWhile { i + it - 1 < heights.size && (it == 1 || (i + it - 1) !in fullSpan) }.last()
                val items = (i until i + n).toList()
                val h = items.maxOf { heights[it] }
                out += Line(i, items, y, h); y += h + spacing; i += n
            }
            lines = out
            content = y - spacing
        }
        val maxScroll get() = (content - viewport).coerceAtLeast(0)
        fun frame(scroll: Int): ScrollbarFrame {
            val s = scroll.coerceIn(0, maxScroll)
            val visible = lines.filter { it.top + it.height > s && it.top < s + viewport }
            val items = visible.flatMap { line -> line.items.map { ScrollbarItem(it, line.top - s, heights[it]) } }
            val first = visible.first()
            return ScrollbarFrame(items, heights.size, s - first.top, viewport, viewport, s >= maxScroll)
        }
    }

    private fun queue(n: Int) = SimGrid(listOf(220) + List(n) { 64 + (it % 3) * 4 } + listOf(96), setOf(0, n + 1), 1, 800)
    private fun sections(n: Int): SimGrid {
        val h = mutableListOf<Int>(); val full = mutableSetOf<Int>()
        repeat(n / 10) { s -> full += h.size; h += 150; repeat(10) { h += 90 + (it % 4) * 6 } }
        return SimGrid(h, full, 1, 700)
    }
    private fun gallery(n: Int): SimGrid {
        val h = mutableListOf(260); val full = mutableSetOf(0)
        repeat(n) { h += 180 + (it % 5) }
        return SimGrid(h, full, 3, 900, 8)
    }
    private fun lists() = mapOf("queue" to queue(300), "sections" to sections(400), "gallery" to gallery(900), "longQueue" to queue(20000))

    private fun run(grid: SimGrid, model: ScrollbarModel, from: Int, to: Int, step: Int): List<Float> {
        val out = mutableListOf<Float>()
        var s = from
        while (true) {
            out += model.observe(grid.frame(s))
            if (s == to) break
            s = if (to > from) minOf(to, s + step) else maxOf(to, s - step)
        }
        return out
    }

    private fun assertContinuous(name: String, fractions: List<Float>, grid: SimGrid, step: Int, increasing: Boolean) {
        val tolerance = step.toFloat() / grid.maxScroll * 2.2f + .0025f
        for (k in 1 until fractions.size) {
            val d = if (increasing) fractions[k] - fractions[k - 1] else fractions[k - 1] - fractions[k]
            assertTrue("$name step $step frame $k not monotonic: $d", d >= -5e-4f)
            assertTrue("$name step $step frame $k jumped $d (> $tolerance)", d <= tolerance)
        }
        assertEquals(if (increasing) 1f else 0f, fractions.last(), 0f)
    }

    @Test fun slowAndFastScrollsToBothEndsAreMonotonicAndContinuous() {
        for ((name, grid) in lists()) for (step in listOf(3, 40, 400, 1500)) if (step > 3 || grid.maxScroll < 200000) {
            val model = ScrollbarModel()
            assertContinuous(name, run(grid, model, 0, grid.maxScroll, step), grid, step, true)
            assertContinuous(name, run(grid, model, grid.maxScroll, 0, step), grid, step, false)
        }
    }

    @Test fun endOfListConvergesWithoutAClampJump() {
        for ((name, grid) in lists()) {
            val model = ScrollbarModel()
            val f = run(grid, model, 0, grid.maxScroll, 7)
            val lastDeltas = f.takeLast(60).zipWithNext { a, b -> b - a }
            assertTrue("$name last-frames max delta ${lastDeltas.max()}", lastDeltas.max() <= 7f / grid.maxScroll * 2.2f + .0025f)
            assertTrue(f.last() == 1f && f[f.size - 2] < 1f)
        }
    }

    @Test fun smallScrollsAreProportionalAtTopMiddleAndBottom() {
        for ((name, grid) in lists()) for (x in listOf(2, 6, 25)) {
            val model = ScrollbarModel()
            val max = grid.maxScroll
            // Walk to each place (so item sizes are measured as in real use), then probe x px.
            for (at in listOf(0, max / 2, max - x)) {
                run(grid, model, if (at == 0) 0 else maxOf(0, at - 3000), at, 40)
                val before = model.observe(grid.frame(at))
                val after = model.observe(grid.frame(at + x))
                val expected = x.toFloat() / model.scrollable
                // Very long lists carry sub-pixel average noise (< .0005 of the track) from newly measured items.
                assertTrue("$name at $at: $x px moved ${after - before}", after > before || (grid.maxScroll > 200000 && after - before > -5e-4f))
                assertEquals("$name at $at x=$x", expected, after - before, expected * .35f + 5e-4f)
            }
        }
    }

    @Test fun topOfQueueRespondsBeforeTheFirstSongIsReached() {
        val grid = queue(300); val model = ScrollbarModel()
        assertEquals(0f, model.observe(grid.frame(0)), 0f)
        var last = 0f
        for (px in 1..60) { val f = model.observe(grid.frame(px)); assertTrue("px $px", f > last); last = f }
    }

    @Test fun thumbSizeAndTargetsUseTheSameModel() {
        val grid = sections(400); val model = ScrollbarModel()
        run(grid, model, 0, 5000, 50)
        assertEquals(1000f * model.viewport / (model.scrollable + model.viewport), model.thumbSize(1000f, 0f, 1000f), 0.01f)
        assertEquals(48f, model.thumbSize(1000f, 48f, 333f), 0.01f)
        assertEquals(333f, ScrollbarModel().also { it.observe(queue(3).frame(0)) }.thumbSize(1000f, 48f, 333f), 0.01f)
    }

    @Test fun dragTargetsAreTheInverseOfTheFractionAndContinuous() {
        for ((name, grid) in lists()) {
            val model = ScrollbarModel()
            run(grid, model, 0, minOf(grid.maxScroll, 4000), 60)
            var previousPx = -1
            var previousFinger = -1f
            for (k in 0..400) {
                val finger = k / 400f
                val (index, offset) = model.target(finger)
                val line = grid.lines.last { it.first <= index }
                val px = (line.top + offset).coerceAtMost(grid.maxScroll)
                assertTrue("$name finger $finger px $px < $previousPx", px >= previousPx)
                // Landing there, then settling, the true thumb sits at the finger within model error.
                                val f = ScrollbarModel().also { fresh -> run(grid, fresh, 0, minOf(grid.maxScroll, 1500), 60) }.observe(grid.frame(px))
                assertEquals("$name finger $finger", finger, f, .06f)
                previousPx = px; previousFinger = finger
            }
            assertEquals(grid.maxScroll, model.target(1f).let { (i, o) -> (grid.lines.last { it.first <= i }.top + o).coerceAtMost(grid.maxScroll) })
        }
    }


    @Test fun gripScalesUniformlyAndStaysInsideTheTrackAtEveryScale() {
        val track = 800f
        for (f in listOf(0f, .5f, 1f)) for (step in 0..20) {
            val scale = 1f + (ScrollbarGrip.HeldScale - 1f) * step / 20f
            val height = ScrollbarGrip.RestHeightDp * scale
            val top = ScrollbarGrip.top(f, track, height)
            assertTrue("f=$f scale $scale", top >= 0f && top + height <= track + 1e-3f)
            if (f == 0f) assertEquals(0f, top, 0f)
            if (f == 1f) assertEquals(track, top + height, 1e-3f)
            // The point at fraction f of the thumb stays at one track position: no jump when the size changes.
            assertEquals(f * track, top + f * height, 1e-3f)
        }
        assertEquals(1.5f, ScrollbarGrip.HeldScale, 0f)
        assertEquals(0, ScrollbarGrip.durationMillis(4f / 3f, true))
        assertEquals(180, ScrollbarGrip.durationMillis(4f / 3f, false))
        assertTrue(ScrollbarGrip.durationMillis(2f, false) < 180)
        assertTrue(ScrollbarGrip.HitWidthDp >= 32f)
    }

    /** Scroll offset of [index] plus [offset] in [grid]: what scrollToItem lands on. */
    private fun scrollOf(grid: SimGrid, index: Int, offset: Int): Int {
        val line = grid.lines.last { it.first <= index }
        return (line.top + offset).coerceIn(0, grid.maxScroll)
    }

    /** One relative drag over [grid] along [finger] (thumb-fraction deltas); returns every scroll offset. */
    private fun drag(grid: SimGrid, start: Int, finger: List<Float>): List<Int> {
        val model = ScrollbarModel()
        val drag = ScrollbarDrag()
        var s = start
        model.observe(grid.frame(s))
        drag.begin(model.fraction)
        val out = mutableListOf(s)
        for (delta in finger) {
            drag.move(delta)
            val step = drag.take(model.position, model.remaining)
            s = when {
                step == Float.POSITIVE_INFINITY -> grid.maxScroll
                step == Float.NEGATIVE_INFINITY -> 0
                step == 0f -> s
                abs(step) <= model.viewport -> (s + step.toInt()).coerceIn(0, grid.maxScroll)
                else -> model.targetBy(step).let { (i, o) -> scrollOf(grid, i, o) }
            }
            model.observe(grid.frame(s))
            out += s
        }
        return out
    }

    /** Rows that change height partway, so estimates for unmeasured rows are wrong until reached (#704). */
    private fun coldGrid(n: Int) = SimGrid(List(n) { if (it < n / 3) 56 else 140 + (it % 5) * 9 }, emptySet(), 1, 800)

    @Test fun relativeDragNeverMovesTheListAgainstTheFingerAndArrivesAtBothEnds() {
        for ((name, grid) in lists() + mapOf("cold" to coldGrid(20000), "coldOperator" to coldGrid(13400))) {
            for (rate in listOf(.001f, .005f, .02f)) {
                val down = drag(grid, 0, List((1.2f / rate).toInt()) { rate })
                assertTrue("$name $rate forward reversal", down.zipWithNext().all { (a, b) -> b >= a })
                assertEquals("$name $rate reaches the end", grid.maxScroll, down.last())
                val up = drag(grid, grid.maxScroll, List((1.2f / rate).toInt()) { -rate })
                assertTrue("$name $rate backward reversal", up.zipWithNext().all { (a, b) -> b <= a })
                assertEquals("$name $rate reaches the top", 0, up.last())
            }
        }
    }

    @Test fun grabbingWithoutMovingNeverMovesTheList() {
        for ((name, grid) in lists() + mapOf("cold" to coldGrid(20000))) for (start in listOf(0, grid.maxScroll / 3, grid.maxScroll)) {
            val moved = drag(grid, start, List(20) { 0f })
            assertTrue("$name grab at $start moved the list: ${moved.distinct()}", moved.all { it == start })
        }
    }

    @Test fun aMonotonicFingerWithJitterFreePauseGivesMonotonicListMotion() {
        val grid = coldGrid(20000)
        val finger = List(400) { k -> if (k % 50 in 20..29) 0f else .0007f * (1 + k % 3) }
        val path = drag(grid, grid.maxScroll / 5, finger)
        assertTrue(path.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(path.last() > path.first())
    }

    @Test fun heldGrowthIsAConstantWidthAndAProportionalCappedLength() {
        val cap = 16f
        assertEquals(8f, io.github.anastaci1a.localica.ui.spec.ScrollbarSpec.heldExtraLength(32f, cap), 0f)
        assertEquals(12.5f, io.github.anastaci1a.localica.ui.spec.ScrollbarSpec.heldExtraLength(50f, cap), 0f)
        assertEquals(cap, io.github.anastaci1a.localica.ui.spec.ScrollbarSpec.heldExtraLength(240f, cap), 0f)
        assertTrue(io.github.anastaci1a.localica.ui.spec.ScrollbarSpec.heldExtraWidth.value > 0f)
    }
}
