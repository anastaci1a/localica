package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

/** #533: hover-lift geometry is pure; these pin the operator's spacing and continuity rules. */
class HoverLiftTest {
    private val cap = 8f // 4 dp at density 2

    @Test fun anUnselectedNeighbourKeepsAboutThreeQuartersOfItsGap() {
        // List rows 16 px apart (8 dp at density 2), viewport edges far away.
        val rest = Rect(20f, 100f, 700f, 246f)
        val above = Rect(20f, -62f, 700f, 84f)
        val below = Rect(20f, 262f, 700f, 408f)
        val room = hoverRoom(rest, listOf(above, rest, below), Rect(0f, 0f, 720f, 1280f), LiftEdges.Horizontal, cap)
        assertEquals(4f, room.top, .001f)
        assertEquals(4f, room.bottom, .001f)
        // Across the scroll axis the viewport edge limits growth (half of 20 px, capped).
        assertEquals(8f, room.left, .001f)
        assertEquals(8f, room.right, .001f)
        val lifted = hoverGeometry(MediaItemOutline(rest, 20f), room, 1f).lifted.bounds
        assertTrue("Never overlaps a neighbour", lifted.top > above.bottom && lifted.bottom < below.top)
        assertEquals("Remaining gap is 0.75x the normal gap", .75f, (below.top - lifted.bottom) / (below.top - rest.bottom), .001f)
    }

    @Test fun growthIsCappedAndScrollAxisEdgesDoNotShrinkItWhileScrolling() {
        val rest = Rect(20f, -40f, 700f, 106f) // Partly scrolled off the top.
        val room = hoverRoom(rest, listOf(rest), Rect(0f, 0f, 720f, 1280f), LiftEdges.Horizontal, cap)
        assertEquals(cap, room.top, 0f)
        assertEquals(cap, room.bottom, 0f)
        // A horizontal carousel limits only its vertical edges.
        val tile = Rect(32f, 16f, 304f, 380f)
        val carousel = hoverRoom(tile, listOf(tile, Rect(328f, 16f, 600f, 380f)), Rect(0f, 0f, 720f, 396f), LiftEdges.Vertical, cap)
        assertEquals(6f, carousel.right, .001f)
        assertEquals(8f, carousel.top, .001f)
        assertEquals(8f, carousel.bottom, .001f)
        assertEquals(cap, carousel.left, 0f)
    }

    @Test fun outlineScaleCornersAndContentMoveTogetherAndMonotonically() {
        val rest = MediaItemOutline(Rect(20f, 100f, 700f, 246f), 20f)
        val room = Rect(8f, 4f, 8f, 4f)
        var previous = hoverGeometry(rest, room, 0f)
        assertEquals("No lift at rest", rest, previous.lifted)
        assertEquals(1f, previous.scale, 0f)
        for (step in 1..20) {
            val next = hoverGeometry(rest, room, step / 20f)
            assertTrue(next.lifted.bounds.width > previous.lifted.bounds.width)
            assertTrue(next.lifted.bounds.height > previous.lifted.bounds.height)
            assertTrue(next.scale > previous.scale)
            // Concentric: the corner grows by the smaller inset growth.
            val inset = minOf(rest.bounds.top - next.lifted.bounds.top, rest.bounds.left - next.lifted.bounds.left)
            assertEquals(rest.corner + inset, next.lifted.corner, .001f)
            // Content scaled about the outline centre never leaves the lifted outline.
            val scaledWidth = rest.bounds.width * next.scale
            val scaledHeight = rest.bounds.height * next.scale
            assertTrue(scaledWidth <= next.lifted.bounds.width + .001f && scaledHeight <= next.lifted.bounds.height + .001f)
            assertEquals(rest.bounds.center, next.pivot)
            previous = next
        }
        assertEquals(Rect(12f, 96f, 708f, 250f), previous.lifted.bounds)
    }

    @Test fun selectAndDeselectShareOneMonotoneEaseOut() {
        // One progress drives scale, shadow and blur, so they are simultaneous by construction.
        var previous = 0f
        var previousStep = Float.MAX_VALUE
        for (frame in 1..13) {
            val t = frame / 13f
            val eased = LinearOutSlowInEasing.transform(t)
            assertTrue("Monotone", eased > previous)
            val step = eased - previous
            assertTrue("Ease-out: the first frame moves furthest, then it settles ($frame)", step <= previousStep + 1e-4f)
            previous = eased; previousStep = step
        }
        assertEquals(1f, previous, 1e-4f)
    }

    @Test fun magnificationShowsOnlyTheRestRegion() {
        val rest = Rect(20f, 100f, 700f, 246f)
        val lifted = Rect(12f, 96f, 708f, 250f)
        val (scale, translation) = hoverMagnification(rest, lifted)
        fun map(point: Offset) = Offset(translation.x + scale.x * point.x, translation.y + scale.y * point.y)
        assertEquals(lifted.left, map(rest.topLeft).x, .001f)
        assertEquals(lifted.top, map(rest.topLeft).y, .001f)
        assertEquals(lifted.bottomRight.x, map(rest.bottomRight).x, .001f)
        assertEquals(lifted.bottomRight.y, map(rest.bottomRight).y, .001f)
        // Nothing outside the rest region reaches the lifted surface.
        assertTrue(scale.x > 1f && scale.y > 1f)
    }

    @Test fun groupsJoinByRestGeometryAndKeepDiagonalNeighboursApart() {
        val rows = listOf(Rect(0f, 0f, 100f, 60f), Rect(0f, 68f, 100f, 128f), Rect(0f, 300f, 100f, 360f))
        assertEquals(listOf(listOf(0, 1), listOf(2)), hoverComponents(rows, 16f))
        val diagonal = listOf(Rect(0f, 0f, 80f, 80f), Rect(88f, 88f, 168f, 168f))
        assertEquals(2, hoverComponents(diagonal, 16f).size)
    }

    @Test fun dragBridgesDetachWhileFlyingAndRejoinWhileLandingWithoutJumps() {
        // Lift: 0 -> 1 flight with no landing. Then landing: 0 -> 1 at the stack.
        val frames = (0..30).map { it / 30f }
        val lifting = frames.map { hoverBridgeStrength(true, true, it to it, 0f to 0f, 1f to 1f) }
        val landing = frames.map { hoverBridgeStrength(true, true, 1f to 1f, it to it, 1f to 1f) }
        assertEquals("Merged when the drag starts", 1f, lifting.first(), 0f)
        assertEquals("Split once both reach the stack", 0f, lifting.last(), 0f)
        assertEquals("Still split when the landing starts", 0f, landing.first(), 0f)
        assertEquals("Rejoined at the destination", 1f, landing.last(), 0f)
        (lifting + landing).zipWithNext().forEach { (a, b) ->
            assertTrue("No single-frame jump: $a -> $b", kotlin.math.abs(b - a) <= 1f / 30f + 1e-4f)
        }
        assertTrue(lifting.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(landing.zipWithNext().all { (a, b) -> b >= a })
        // Items that were not neighbours never bridge; deselection narrows a bridge with its hover.
        assertEquals(0f, hoverBridgeStrength(false, false, 0f to 0f, 0f to 0f, 1f to 1f), 0f)
        assertEquals(.4f, hoverBridgeStrength(true, false, 0f to 0f, 0f to 0f, 1f to .4f), 1e-4f)
    }
}
