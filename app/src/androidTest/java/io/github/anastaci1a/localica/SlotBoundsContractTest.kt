package io.github.anastaci1a.localica

import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.SlotBoundsNode
import io.github.anastaci1a.localica.ui.slotBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule

/**
 * Contract of [slotBounds], the browse bounds motion (#635).
 *
 * Intentional changes (resize, move, retarget, reversal and repeated retargets) must match the
 * library bounds animation frame for frame: the same fixture runs both side by side on a paused
 * clock, and every frame compares their bounds exactly. The deliberate differences have their
 * own cases: a disabled spec ends the motion at once, a null spec applies a change on its first
 * frame, and a recycled or re-attached node starts at its own item's bounds.
 */
class SlotBoundsContractTest {
    @get:Rule val compose = createComposeRule()

    private class Twin {
        var width by mutableIntStateOf(100)
        var height by mutableIntStateOf(60)
        var top by mutableIntStateOf(0)
        var enabled by mutableStateOf(true)
        var shown by mutableStateOf(true)
    }

    private fun twin(): Twin {
        val state = Twin()
        compose.setContent {
            LookaheadScope {
                Row {
                    Column(Modifier.width(200.dp).testTag("library-column")) {
                        Spacer(Modifier.height(state.top.dp))
                        Box(Modifier.animateBounds(this@LookaheadScope, boundsTransform = { _, _ -> tween(350) })
                            .size(state.width.dp, state.height.dp).testTag("library"))
                    }
                    Column(Modifier.width(200.dp).testTag("slot-column")) {
                        Spacer(Modifier.height(state.top.dp))
                        if (state.shown) Box(Modifier.slotBounds(this@LookaheadScope) { if (state.enabled) tween(350) else null }
                            .size(state.width.dp, state.height.dp).testTag("slot"))
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        return state
    }

    private fun bounds(tag: String, column: String): Rect {
        val box = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val parent = compose.onNodeWithTag(column).fetchSemanticsNode().boundsInRoot
        return box.translate(-parent.left, -parent.top)
    }
    private fun library() = bounds("library", "library-column")
    private fun slot() = bounds("slot", "slot-column")

    // Layout sizes and offsets are whole pixels.
    private fun px(dp: Int) = with(compose.density) { dp.dp.roundToPx().toFloat() }
    private fun expected(state: Twin) = Rect(0f, px(state.top), px(state.width), px(state.top) + px(state.height))

    // Reflection over node chains keeps production code free of test hooks.
    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name || it.name.startsWith("$name\$") }?.let { it.isAccessible = true; return it.get(o) }
            c = c.superclass
        }
        return null
    }
    private fun nodes(info: LayoutInfo): List<Any> {
        val result = mutableListOf<Any>()
        var node = field(field(info, "nodes") ?: return result, "head")
        while (node != null) { result += node; node = field(node, "child") }
        return result
    }
    private fun slotAnimating(tag: String = "slot"): Boolean =
        nodes(compose.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo).filterIsInstance<SlotBoundsNode>()
            .also { check(it.size == 2) { "Expected the outer and content bounds nodes, found ${it.size}" } }
            .any { it.isPlacementAnimating }
    private fun libraryAnimating(): Boolean = nodes(compose.onNodeWithTag("library").fetchSemanticsNode().layoutInfo)
        .filter { it.javaClass.simpleName == "BoundsAnimationModifierNode" }
        .any { node -> field(node, "boundsAnimation")?.let { a -> a.javaClass.getMethod("isIdle").invoke(a) == false } == true }

    /** Advance [frames] frames, comparing the two implementations exactly on each one. */
    private fun matchLibrary(label: String, frames: Int, log: MutableList<String>) {
        repeat(frames) { frame ->
            compose.mainClock.advanceTimeByFrame()
            val a = library()
            val b = slot()
            log += "$label frame $frame library=$a slot=$b"
            assertEquals("$label frame $frame: slotBounds must equal library animateBounds", a, b)
            assertEquals("$label frame $frame: both must report the same motion state", libraryAnimating(), slotAnimating())
        }
    }

    private fun finish(state: Twin, log: List<String>) {
        android.util.Log.i("SlotBounds635", log.joinToString("\n"))
        assertEquals(expected(state), slot())
        assertFalse("Motion must be idle once it reaches its target", slotAnimating())
    }

    @Test fun intentionalResizeMatchesLibrary() {
        val state = twin()
        val before = slot()
        val log = mutableListOf<String>()
        state.width = 160; state.height = 120
        compose.mainClock.advanceTimeByFrame()
        assertEquals("An intentional resize starts from the previous bounds", before, slot())
        assertEquals(library(), slot())
        assertTrue(slotAnimating())
        matchLibrary("resize", 30, log)
        finish(state, log)
    }

    @Test fun intentionalMoveMatchesLibrary() {
        val state = twin()
        val log = mutableListOf<String>()
        state.top = 90
        matchLibrary("move", 30, log)
        finish(state, log)
    }

    @Test fun retargetAndReversalMatchLibrary() {
        val state = twin()
        val log = mutableListOf<String>()
        state.width = 180; state.height = 140
        matchLibrary("resize", 6, log)
        // Retarget mid-flight: continue from the current value with its velocity.
        state.width = 60; state.top = 40
        matchLibrary("retarget", 5, log)
        // Reverse to the original geometry before the retarget settles.
        state.width = 100; state.height = 60; state.top = 0
        matchLibrary("reversal", 30, log)
        finish(state, log)
    }

    @Test fun repeatedRetargetsMatchLibrary() {
        val state = twin()
        val log = mutableListOf<String>()
        repeat(8) { i ->
            state.width = 80 + 15 * (i % 4); state.height = 50 + 20 * (i % 3); state.top = 10 * (i % 5)
            matchLibrary("retarget $i", 2, log)
        }
        matchLibrary("settle", 30, log)
        finish(state, log)
    }

    @Test fun disablingMotionMidwayEndsAtTheTargetOnTheNextFrame() {
        val state = twin()
        state.width = 180; state.height = 140; state.top = 30
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        assertTrue("Precondition: a nonzero motion is in flight", slotAnimating())
        assertTrue("Precondition: the motion has not reached its target", slot() != expected(state))
        // Reduced motion, reorder or a loading cell disables motion; the target does not change.
        state.enabled = false
        compose.mainClock.advanceTimeByFrame()
        assertEquals("Disabled motion must place the final bounds on the next frame", expected(state), slot())
        assertFalse("Disabled motion must leave the node idle", slotAnimating())
        compose.mainClock.advanceTimeByFrame()
        assertEquals(expected(state), slot())
    }

    @Test fun nullSpecAppliesAChangeOnItsFirstFrame() {
        val state = twin()
        state.enabled = false
        compose.mainClock.advanceTimeByFrame()
        state.width = 170; state.height = 30; state.top = 55
        compose.mainClock.advanceTimeByFrame()
        assertEquals("A change without motion has no stale frame", expected(state), slot())
        assertFalse(slotAnimating())
    }

    @Test fun reattachedNodeStartsAtItsOwnBounds() {
        val state = twin()
        state.width = 180; state.height = 140
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertTrue(slotAnimating())
        state.shown = false
        compose.mainClock.advanceTimeByFrame()
        state.width = 70; state.height = 90; state.top = 20
        compose.mainClock.advanceTimeByFrame()
        state.shown = true
        compose.mainClock.advanceTimeByFrame()
        assertEquals("A new node has no previous bounds to animate from", expected(state), slot())
        assertFalse(slotAnimating())
    }

    @Test fun recycledLazySlotStartsAtItsOwnItemBounds() {
        val list = LazyListState()
        lateinit var scope: CoroutineScope
        // Alternating heights with one content type: recycled slots always change size.
        fun height(i: Int) = if (i % 3 == 0) 40 else if (i % 3 == 1) 96 else 64
        compose.setContent {
            scope = rememberCoroutineScope()
            LookaheadScope {
                LazyColumn(Modifier.size(300.dp, 600.dp), state = list) {
                    items((0 until 400).toList(), key = { it }, contentType = { 0 }) { i ->
                        Box(Modifier.slotBounds(this@LookaheadScope) { tween(350) }
                            .size(200.dp, height(i).dp).testTag("item-$i"))
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val problems = mutableListOf<String>()
        var checked = 0
        listOf(37, 38, 120, 121, 260, 5, 390, 199).forEach { target ->
            compose.runOnIdle { scope.launch { list.scrollToItem(target) } }
            compose.mainClock.advanceTimeByFrame()
            val visible = compose.onAllNodes(SemanticsMatcher("item") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("item-") == true
            }).fetchSemanticsNodes()
            visible.forEach { node ->
                val i = node.config[SemanticsProperties.TestTag].removePrefix("item-").toInt()
                val b = node.boundsInRoot
                val animating = nodes(node.layoutInfo).filterIsInstance<SlotBoundsNode>().any { it.isPlacementAnimating }
                checked++
                if (kotlin.math.abs(b.height - px(height(i))) > .5f || animating)
                    problems += "jump to $target: item $i drawn ${b.height}px high (expected ${px(height(i))}), animating=$animating"
            }
        }
        assertTrue("Checked visible recycled items", checked > 40)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
