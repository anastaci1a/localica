package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.OccurrenceMoves
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * #735/#736: the queue selector uses the shared list drag exactly like a mutable song list.
 * Live numbering, one instance of the playing (chosen) queue per frame, displaced chosen rows
 * that follow their slot, and the shared release/cancel haptics.
 */
class QueueSelectorUnifiedDragTest {
    @get:Rule val compose = createComposeRule()
    private var queues by mutableStateOf((0 until 6).map { ListeningQueue(id = "q$it", name = "Queue $it", entries = emptyList()) })
    private val pulses = mutableListOf<ControlHaptic>()
    private var source: QueueSelectionDrawSource? = null

    private fun fixture(chosen: String = "q2") {
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalControlHaptics provides ControlHaptics { pulses += it },
                LocalQueueSelectionDrawObserver provides { source = it }) {
                Box(Modifier.width(380.dp).height(440.dp)) {
                    QueueSelectorList(queues, chosen, chosen, true, false, true, Modifier.fillMaxSize(),
                        onSelect = {}, onRename = {}, onDelete = {}, onOffset = { _, _ -> },
                        onMove = { id, before, done -> queues = OccurrenceMoves.before(queues, setOf(id), before) { it.id }; done(true) })
                }
            }
        } }
        compose.waitUntil(5000) { source != null }
    }

    private fun list() = compose.onNodeWithTag("queue-selector-list")
    private fun origin() = list().fetchSemanticsNode().boundsInRoot.topLeft
    private fun body(id: String) = compose.onNodeWithTag("queue-select-$id", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.center - origin()
    private fun rowBounds(id: String) = compose.onNodeWithTag("queue-row-$id").fetchSemanticsNode().boundsInRoot
    private fun hold(id: String): Offset {
        val start = body(id)
        list().performTouchInput { down(start); advanceEventTime(650); moveTo(start + Offset(0f, 30f)); advanceEventTime(32) }
        compose.onNodeWithTag("drag-stack").assertExists()
        return start
    }
    /** The number in "N. Name" for every composed node carrying [id]'s select tag (row or card). */
    private fun numbers(id: String) = compose.onAllNodesWithTag("queue-select-$id").fetchSemanticsNodes().map { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        requireNotNull(Regex("""^(\d+)\. """).find(text)) { "No number in '$text'" }.groupValues[1].toInt()
    }
    /** One composition frame, then a real display draw of only what is invalidated. Capturing an
     * image would invalidate every layer and hide a host that was never asked to redraw. */
    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(34)
        instrumentation.waitForIdleSync()
    }
    private fun awaitLanding() {
        var frames = 0
        while (compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isNotEmpty()) {
            frame(); assertTrue("Landing finishes", ++frames < 200)
        }
    }

    @Test fun numbersFollowTheLiveInsertionInEveryFrame() {
        fixture()
        val start = hold("q4")
        compose.mainClock.autoAdvance = false
        try {
            val target = body("q0").y - 10f
            var y = start.y + 30f
            var checked = 0
            while (y > target) {
                y -= 12f
                list().performTouchInput { moveTo(Offset(start.x, y)); advanceEventTime(16) }
                frame()
                // The grid's own keyed order (the gap is the held queue's slot) is the expected numbering.
                val rendered = compose.runOnUiThread { requireNotNull(source).state.layoutInfo.visibleItemsInfo
                    .sortedBy { it.index }.map { it.key.toString() } }
                rendered.forEachIndexed { index, key ->
                    val id = if (key == DropGapKey) "q4" else key
                    assertEquals("Live number of $id at y=$y in $rendered", listOf(index + 1), numbers(id))
                }
                checked++
            }
            assertTrue("Crossed every row above", checked > 10)
            list().performTouchInput { up() }
            awaitLanding()
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        assertEquals(listOf("q4", "q0", "q1", "q2", "q3", "q5"), queues.map { it.id })
        queues.forEachIndexed { index, queue -> assertEquals(listOf(index + 1), numbers(queue.id)) }
    }

    /** The chosen (playing) row is painted by the selection host; it must never be a second instance. */
    @Test fun draggingThePlayingQueueShowsOneInstanceInEveryFrame() {
        fixture()
        val start = hold("q2")
        compose.mainClock.autoAdvance = false
        try {
            frame()
            assertEquals("Once acquired, the host paints no lifted copy of the held row",
                emptyList<SelectionRegion>(), compose.runOnUiThread { requireNotNull(source).scene.lastDrawnRegions })
            val target = rowBounds("q4").bottom - origin().y - 4f
            list().performTouchInput { moveTo(Offset(start.x, target)); advanceEventTime(16) }
            repeat(30) { frame() }
            list().performTouchInput { up() }
            var elapsed = 0L
            while (compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isNotEmpty()) {
                frame(); elapsed += 16
                val drawn = compose.runOnUiThread {
                    requireNotNull(source).scene.regions.values.firstOrNull {
                        it.itemKey == "q2" && it.coordinates?.isAttached == true && it.visibility() > 0f
                    }?.alpha?.invoke()
                }
                // The card owns the item while it lands (350 ms); the resting row only fades in with the handoff.
                if (elapsed < 320 && drawn != null) assertTrue(
                    "Frame ${elapsed}ms: the destination's lifted row is drawn at alpha $drawn under the landing card", drawn < .02f)
                assertTrue("Landing finishes", elapsed < 3000)
            }
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        assertEquals(listOf("q0", "q1", "q3", "q4", "q2", "q5"), queues.map { it.id })
    }

    private fun assertChosenFollowsItsSlot(moving: String, to: String, below: Boolean) {
        fixture()
        val start = hold(moving)
        compose.mainClock.autoAdvance = false
        try {
            val target = rowBounds(to).let { if (below) it.bottom - 4f else it.top + 4f } - origin().y
            var y = start.y + 30f
            val step = if (target > y) 10f else -10f
            var frames = 0
            fun check(label: String) {
                val (drawn, row) = compose.runOnUiThread {
                    val probe = requireNotNull(source)
                    val rowCoordinates = requireNotNull(probe.rows["q2"]) { "Chosen row coordinates" }
                    probe.scene.lastDrawnRegions.map { it.bounds } to probe.viewport.localBoundingBoxOf(rowCoordinates, clipBounds = false)
                }
                val lifted = drawn.minByOrNull { abs(it.center.y - row.center.y) }
                assertNotNull("$label: the chosen row is painted lifted", lifted)
                assertEquals("$label: the lifted chosen row is drawn at its live slot (drawn=$drawn row=$row)",
                    row.center.y, lifted!!.center.y, 6f)
                frames++
            }
            while ((step > 0 && y < target) || (step < 0 && y > target)) {
                y += step
                list().performTouchInput { moveTo(Offset(start.x, y)); advanceEventTime(16) }
                frame(); check("drag y=$y")
            }
            // Let every displacement tween finish while the finger rests, sampling each frame.
            repeat(30) { frame(); check("settle $it") }
            list().performTouchInput { up() }
            var landing = 0
            while (compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isNotEmpty() && landing++ < 200) {
                frame(); check("landing $landing")
            }
            assertTrue("Sampled every frame", frames > 40)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
    }

    @Test fun draggingAnotherQueueDownPastThePlayingQueueMovesIt() {
        assertChosenFollowsItsSlot("q0", "q4", below = true)
        assertEquals(listOf("q1", "q2", "q3", "q4", "q0", "q5"), queues.map { it.id })
    }

    @Test fun draggingAnotherQueueUpPastThePlayingQueueMovesIt() {
        assertChosenFollowsItsSlot("q5", "q1", below = false)
        assertEquals(listOf("q0", "q5", "q1", "q2", "q3", "q4"), queues.map { it.id })
    }

    @Test fun pickupAndReleaseAreStrongAndSystemCancelIsSilent() {
        fixture()
        val start = hold("q1")
        compose.runOnIdle { assertEquals("Pickup without a row long-press owner", listOf(ControlHaptic.Strong), pulses) }
        list().performTouchInput { moveTo(start + Offset(0f, 90f)); advanceEventTime(32); up() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals("Release is Strong", listOf(ControlHaptic.Strong, ControlHaptic.Strong), pulses); pulses.clear() }
        val again = hold("q3")
        compose.runOnIdle { assertEquals(listOf(ControlHaptic.Strong), pulses); pulses.clear() }
        list().performTouchInput { moveTo(again + Offset(0f, 90f)); advanceEventTime(32); cancel() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals("A system cancel stays silent", emptyList<ControlHaptic>(), pulses) }
    }

    @Test fun releaseOnTheCancelTargetIsStillAStrongRelease() {
        fixture()
        hold("q1")
        val target = compose.onNodeWithTag("cancel-drag").fetchSemanticsNode().boundsInRoot.center - origin()
        list().performTouchInput { moveTo(target); advanceEventTime(32) }
        compose.runOnIdle { pulses.clear() }
        list().performTouchInput { up() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(listOf(ControlHaptic.Strong), pulses) }
        assertEquals((0 until 6).map { "q$it" }, queues.map { it.id })
    }
}
