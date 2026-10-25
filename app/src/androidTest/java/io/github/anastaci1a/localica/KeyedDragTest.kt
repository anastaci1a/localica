package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** A non-song caller proves transaction and lifted rendering do not require browser models. */
class KeyedDragTest {
    @get:Rule val compose = createComposeRule()
    private data class Entry(val id: String, val name: String)
    private val entries = (0 until 6).map { Entry("queue-$it", "Queue $it") }
    private var published by mutableStateOf(entries)
    private lateinit var reorder: KeyedDragState<Entry, String>
    private var appearance = "at lift"

    private fun fixture(optimisticCommit: Boolean = true, reducedMotion: Boolean = true, startDrag: Boolean = true) {
        compose.setContent { LocalicaTheme {
            val grid = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            reorder = remember {
                object : KeyedDragState<Entry, String>(grid, scope, { it.id }, optimisticCommit) {
                    override fun capturePresentation() = appearance
                }.also { it.items = entries }
            }
            Box(Modifier.fillMaxWidth().height(420.dp).onSizeChanged {
                reorder.viewportWidth = it.width; reorder.viewportHeight = it.height
            }) {
                LazyVerticalGrid(columns = GridCells.Fixed(1), state = grid) {
                    items(reorder.rendered(published), key = { it?.id ?: DropGapKey }) { entry ->
                        Box(Modifier.fillMaxWidth().height(52.dp)) { entry?.let { Text(it.name) } }
                    }
                }
                KeyedDragOverlay(reorder, reducedMotion = reducedMotion, countDescription = { "Dragging $it queues" }) { entry, presentation ->
                    Text("${entry.name}: $presentation", Modifier.testTag("lifted-${entry.id}"))
                }
            }
        } }
        if (startDrag) compose.runOnIdle {
            val held = entries[1]
            assertTrue(reorder.begin(held, setOf(held.id), reorder.bounds().getValue(held.id).center))
        }
    }

    private fun moveDown(): List<Entry> {
        compose.runOnIdle {
            val target = reorder.bounds().getValue(entries[4].id)
            reorder.move(Offset(target.center.x, target.bottom - 1f))
        }
        return compose.runOnIdle { reorder.drag!!.ordered().also { assertNotEquals(entries, it) } }
    }

    @Test fun liftedContentUsesCapturedPresentationAndStableIdentity() {
        fixture()
        compose.runOnIdle { appearance = "changed after lift" }
        compose.onNodeWithTag("lifted-queue-1").assertTextEquals("Queue 1: at lift")
        compose.runOnIdle {
            assertEquals(setOf("queue-1"), reorder.drag!!.keys)
            reorder.finish(cancel = true, reducedMotion = true) { _, _, _ -> fail("Cancel cannot commit") }
        }
        compose.waitUntil(5000) { reorder.drag == null }
    }

    @Test fun successfulDropCommitsOnceAndReconcilesPublishedOrder() {
        fixture()
        val expected = moveDown()
        var commits = 0
        var moved = 0
        compose.runOnIdle {
            val anchor = reorder.drag!!.remaining.getOrNull(reorder.drag!!.insertion)?.id
            reorder.finish(cancel = false, reducedMotion = true, onMoved = { moved++ }) { keys, before, complete ->
                commits++
                assertEquals(setOf("queue-1"), keys)
                assertEquals(anchor, before)
                complete(true)
            }
        }
        compose.waitUntil(5000) { reorder.drag == null }
        compose.runOnIdle {
            assertEquals(1, commits); assertEquals(1, moved)
            assertEquals(expected, reorder.rendered(entries))
            published = expected
            reorder.reconcile(expected)
            assertNull(reorder.optimistic)
            assertEquals(expected, reorder.rendered(expected))
        }
    }

    @Test fun rejectedDropRestoresOrderWithoutReportingSuccess() {
        fixture()
        moveDown()
        var commits = 0
        compose.runOnIdle {
            reorder.finish(cancel = false, reducedMotion = true, onMoved = { fail("Rejected save cannot succeed") }) { _, _, complete ->
                commits++; complete(false)
            }
        }
        compose.waitUntil(5000) { reorder.drag == null }
        compose.runOnIdle { assertEquals(1, commits); assertEquals(entries, reorder.rendered(entries)); assertNull(reorder.optimistic) }
    }

    @Test fun canceledMoveRestoresOrderWithoutCommitting() {
        fixture()
        moveDown()
        compose.runOnIdle {
            reorder.finish(cancel = true, reducedMotion = true, onMoved = { fail("Cancel cannot succeed") }) { _, _, _ ->
                fail("Cancel cannot commit")
            }
        }
        compose.waitUntil(5000) { reorder.drag == null }
        compose.runOnIdle { assertEquals(entries, reorder.rendered(entries)); assertNull(reorder.optimistic) }
    }
    @Test fun authoritativeReceiptNeverHidesConcurrentIdentitiesOrMetadata() {
        fixture(optimisticCommit = false)
        val expected = moveDown()
        val authoritative = expected.filterNot { it.id == "queue-3" }.map {
            if (it.id == "queue-1") it.copy(name = "Renamed during save") else it
        } + Entry("new-queue", "New queue")
        compose.runOnIdle {
            reorder.finish(cancel = false, reducedMotion = true) { _, _, complete ->
                published = authoritative
                reorder.reconcile(authoritative)
                complete(true)
            }
        }
        compose.waitUntil(5000) { reorder.drag == null }
        compose.runOnIdle {
            assertNull(reorder.optimistic)
            assertEquals(authoritative, reorder.rendered(published))
        }
        compose.onNodeWithText("Renamed during save").assertIsDisplayed()
        compose.onNodeWithText("New queue").assertIsDisplayed()
        compose.onNodeWithText("Queue 3").assertDoesNotExist()
    }

    @Test fun standaloneCancelRetainsItsBottomMargin() {
        fixture()
        val margin = with(compose.density) { 20.dp.toPx() }
        val offset = (-margin).roundToInt()
        val drawn = compose.onNodeWithTag("cancel-drag").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertEquals(margin, reorder.cancelBottom, 0.01f)
            // Placement and hit testing share IntOffset, including negative half-pixel ties.
            assertEquals(offset, reorder.cancelOffsetY)
            assertEquals(reorder.viewportHeight + offset - reorder.cancelRadius, reorder.cancelCenter.y, 0.01f)
            assertEquals((reorder.viewportHeight + offset).toFloat(), drawn.bottom, 0.01f)
        }
    }

    @Test fun cancelHitGeometryReadsCurrentOccupancyBeforeRecomposition() {
        fixture()
        compose.runOnIdle {
            val oldCenter = reorder.cancelCenter
            reorder.obscuredBottom = 100f
            assertEquals(oldCenter.y - 100f, reorder.cancelCenter.y, 0.01f)
            reorder.move(reorder.cancelCenter)
            assertTrue(reorder.drag!!.overCancel)
            reorder.move(oldCenter)
            assertFalse(reorder.drag!!.overCancel)
        }
    }

    @Test fun cancelClearanceClampsInsideSmallViewport() {
        fixture()
        compose.runOnIdle {
            reorder.viewportHeight = 40
            reorder.cancelRadius = 20f
            reorder.cancelMargin = 20f
            reorder.obscuredBottom = 100f
            assertEquals(0f, reorder.cancelBottom, 0.01f)
            assertEquals(20f, reorder.cancelCenter.y, 0.01f)
            reorder.move(reorder.cancelCenter)
            assertTrue(reorder.drag!!.overCancel)
        }
    }

    @Test fun enteringTargetUsesVisibleGeometryAndTracksAStationaryPointer() {
        fixture(reducedMotion = false, startDrag = false)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            val held = entries[1]
            assertTrue(reorder.begin(held, setOf(held.id), reorder.bounds().getValue(held.id).center))
            assertFalse("No target from a prior gesture can be active", reorder.cancelInteractive)
        }
        compose.mainClock.advanceTimeBy(48)
        val drawn = compose.onNodeWithTag("cancel-drag").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val motion = reorder.drag!!.cancelMotion!!
            assertTrue(motion.progress in .001f.. .999f)
            assertEquals(drawn.center.y, reorder.cancelCenter.y, 1.1f)
            reorder.move(reorder.cancelCenter)
            assertTrue("The displayed moving center accepts cancellation", reorder.drag!!.overCancel)
            // Rise is smaller than the radius, so the two centers intentionally overlap.
            // The upper edge of the resting circle distinguishes moving from stale bounds.
            val restingTop = reorder.viewportHeight - reorder.cancelBottom - reorder.cancelRadius * 2f
            reorder.move(Offset(reorder.cancelCenter.x, restingTop + 1f))
            assertFalse("The not-yet-visible resting edge must not cancel", reorder.drag!!.overCancel)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertTrue("X rising under a stationary finger updates hover", reorder.drag!!.overCancel)
            reorder.obscuredBottom += reorder.cancelRadius * 3f
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertFalse("Changing occupancy removes hover without another move", reorder.drag!!.overCancel)
            reorder.move(reorder.cancelCenter)
            assertTrue(reorder.drag!!.overCancel)
            reorder.finish(true, true) { _, _, _ -> fail("Cancel cannot commit") }
            assertFalse("A landing/removed X is immediately inactive", reorder.cancelInteractive)
        }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5000) { reorder.drag == null }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            val held = entries[1]
            assertTrue(reorder.begin(held, setOf(held.id), reorder.bounds().getValue(held.id).center))
            reorder.move(reorder.cancelCenter)
            assertFalse("Fresh drag cannot inherit the old target", reorder.drag!!.overCancel)
        }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle {
            assertTrue(reorder.drag!!.cancelMotion!!.progress < 1f)
            reorder.move(reorder.cancelCenter)
            assertTrue(reorder.drag!!.overCancel)
            reorder.viewportHeight = 40
            reorder.cancelRadius = 20f
            reorder.obscuredBottom = 100f
            assertEquals("A tiny viewport cannot push the entering target below its clamp", 0f, reorder.cancelTravel, 0f)
            assertEquals(20f, reorder.cancelCenter.y, .01f)
            reorder.finish(true, true) { _, _, _ -> fail("Cancel cannot commit") }
        }
        compose.mainClock.autoAdvance = true
    }

}
