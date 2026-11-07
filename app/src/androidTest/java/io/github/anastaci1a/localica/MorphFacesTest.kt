package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.components.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** U04.02a part A (D09, B4, lead 11:35 AM (1)): a shared element is one instance placed in the destination face and drawn at the eased frame
 * between the faces' slots; its measure-only endpoint copies are never placed, drawn or in semantics. Semantics are queried as accessibility
 * sees them: the copies sit under clearAndSetSemantics, whose replaced nodes the unmerged test tree still lists. */
class MorphFacesTest {
    @get:Rule val compose = createComposeRule()
    private var expanded by mutableStateOf(true)
    private val instances = mutableMapOf<Any, MutableSet<Any>>()

    private fun content() = compose.setContent { EngineTheme(clear = true) {
        Box(Modifier.size(320.dp, 120.dp).background(Color.White).testTag("scene"), contentAlignment = Alignment.Center) {
            MorphFaces(expanded, shared = { key ->
                if (key == "label") {
                    val id = remember { Any() }; val scope = this; SideEffect { instances.getOrPut(scope) { mutableSetOf() } += id }
                    MorphText("3 selected", MaterialTheme.typography.labelLarge, MaterialTheme.typography.titleMedium)
                } else Box(Modifier.size(20.dp).background(Color.Red))
            }, compact = { Row(Modifier.testTag("compact")) { Shared("mark"); Shared("label") } }) {
                Row(Modifier.width(280.dp).testTag("expanded")) { Shared("label", Modifier.weight(1f)); Shared("mark", Modifier.size(32.dp)) }
            }
        }
    } }

    /** Red pixels of the shared mark: its drawn frame, wherever the one instance is drawn. */
    private fun mark(): Pair<Float, Int> {
        val pixels = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        var sum = 0f; var count = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) { val c = pixels[x, y]; if (c.red > .8f && c.green < .2f && c.blue < .2f) { sum += x; count++ } }
        return (if (count == 0) Float.NaN else sum / count) to count
    }

    @Test fun sharedChildKeepsOneInstanceAcrossInterruptedMorphs() {
        content(); compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            repeat(3) { round ->
                compose.runOnIdle { expanded = !expanded }; repeat(3 + round) { compose.mainClock.advanceTimeByFrame() }
                compose.onAllNodesWithText("3 selected").assertCountEquals(1)
            }
            compose.mainClock.advanceTimeBy(2000)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        // One live scope plus the two measure-only endpoint copies; none of them ever remounted.
        assertEquals("Scopes: $instances", 3, instances.size)
        instances.values.forEach { assertEquals("A shared child remounted: $instances", 1, it.size) }
        compose.onAllNodesWithText("3 selected").assertCountEquals(1)
    }

    @Test fun sharedFrameEasesBetweenTheFaceSlotsWithOneDrawnCopy() {
        content(); compose.waitForIdle()
        val (open, openArea) = mark()
        compose.runOnIdle { expanded = false }; compose.waitForIdle()
        val (closed, closedArea) = mark()
        assertTrue("The mark travels between slots: $open -> $closed", open - closed > 40f)
        assertEquals("Compact endpoint records the 20 dp mark, not stale expanded ink", 20f * 20f / (32f * 32f), closedArea.toFloat() / openArea, .03f)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { expanded = true }
            val frames = mutableListOf(closed to closedArea)
            repeat(40) { compose.mainClock.advanceTimeByFrame(); frames += mark() }
            val xs = frames.map { it.first }; val span = open - closed
            xs.zipWithNext().forEach { (a, b) ->
                assertTrue("Monotone travel: $xs; closedArea=$closedArea openArea=$openArea", b >= a - .75f)
                assertTrue("No frame jump: $xs", b - a <= span * .4f)
            }
            assertTrue("Intermediate frames: $xs", xs.count { it > closed + span * .1f && it < open - span * .1f } >= 3)
            // Only the destination's one instance draws: the area eases from 20 dp to 32 dp, never doubles.
            frames.forEach { (_, area) -> assertTrue("One drawn copy: $frames", area <= openArea * 1.1f) }
            assertEquals(open, xs.last(), 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
