package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native lazy placement and actual host-drawn selection geometry, without screenshot-induced redraw. */
class ArtistSelectionPlacementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedAlbumGeometryTracksEachDragFlingReversalOffscreenReturnAndResizeFrame() {
        lateinit var scroll: LazyListState
        lateinit var scene: SelectionScene
        lateinit var scope: CoroutineScope
        var width by mutableStateOf(280.dp)
        var allSelected by mutableStateOf(false)
        var draws = 0
        compose.setContent { LocalicaTheme {
            scroll = androidx.compose.foundation.lazy.rememberLazyListState()
            scope = rememberCoroutineScope()
            SelectionViewport(Modifier.width(width).height(112.dp).background(Color.Black).testTag("album-viewport"),
                observePlacement = { scroll.layoutInfo; draws++; Unit }) { overscroll ->
                scene = requireNotNull(LocalSelectionScene.current)
                LazyRow(state = scroll, overscrollEffect = overscroll,
                    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items((0..19).toList(), key = { it }) { index ->
                        Box(Modifier.width(80.dp).height(96.dp).selectionLens(allSelected || index == 2, false).testTag("album-$index"))
                    }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        fun frame(): Rect? {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val viewport = compose.onNodeWithTag("album-viewport").fetchSemanticsNode().boundsInRoot
            val selected = compose.onAllNodesWithTag("album-2").fetchSemanticsNodes().singleOrNull()
                ?.boundsInRoot?.intersect(viewport)?.takeUnless { it.isEmpty }
            val drawn = compose.runOnIdle { scene.lastDrawnRegions.map { it.bounds.translate(viewport.topLeft).intersect(viewport) }
                .filterNot { it.isEmpty } }
            if (selected == null) {
                assertTrue("No stale glass remains in the viewport when selection leaves", drawn.isEmpty())
            } else {
                assertEquals("Exactly one live selected album", 1, drawn.size)
                val body = drawn.single()
                assertEquals("Drawn left follows this frame's placed item", selected.left, body.left, 1f)
                assertEquals("Drawn right follows this frame's placed item", selected.right, body.right, 1f)
                assertEquals("Drawn top follows this frame's placed item", selected.top, body.top, 1f)
                assertEquals("Drawn bottom follows this frame's placed item", selected.bottom, body.bottom, 1f)
            }
            return selected
        }
        try {
            val row = compose.onNodeWithTag("album-viewport")
            val start = requireNotNull(frame())
            row.performTouchInput { down(center); moveBy(Offset(-30f, 0f), delayMillis = 32) }
            val first = requireNotNull(frame())
            assertTrue("Real drag moves album and selected glass together", first.left < start.left)
            val drawBeforeReverse = compose.runOnIdle { draws }
            row.performTouchInput { moveBy(Offset(18f, 0f), delayMillis = 32) }
            val reverse = requireNotNull(frame())
            assertTrue("Reversal follows the finger rather than an eased old offset", reverse.left > first.left)
            assertTrue("Scroll invalidates the selection host drawing", compose.runOnIdle { draws } > drawBeforeReverse)
            row.performTouchInput {
                // Keep recent MOVE samples contiguous after frame() advances the event clock.
                // Compose 1.8.2 needs three velocity samples with gaps no greater than 40 ms.
                repeat(4) { moveBy(Offset(-20f, 0f), delayMillis = 16) }
                up()
            }
            val positions = mutableSetOf<Pair<Int, Int>>()
            var sawFling = false
            repeat(12) {
                frame()
                compose.runOnUiThread {
                    positions += scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset
                    sawFling = sawFling || scroll.isScrollInProgress
                }
            }
            assertTrue("Release exercises native fling progression", sawFling && positions.size > 1)
            fun jump(index: Int) {
                val completed = CompletableDeferred<Unit>()
                compose.runOnUiThread { scope.launch { try { scroll.scrollToItem(index); completed.complete(Unit) }
                    catch (failure: Throwable) { completed.completeExceptionally(failure) } } }
                runBlocking { withTimeout(5000) { completed.await() } }
                frame()
            }
            jump(12)
            assertTrue("Selected item is genuinely outside visible layout", compose.runOnIdle {
                scroll.layoutInfo.visibleItemsInfo.none { it.key == 2 } })
            jump(0)
            assertNotNull("Re-entering selected item gets its current geometry", frame())
            compose.runOnIdle { width = 220.dp }
            frame()
            compose.runOnUiThread { scope.launch { scroll.scrollBy(40f) } }
            frame()
            compose.runOnIdle { width = 280.dp }
            frame()
            // All mode uses every visible cover body; retain the same frame registration contract.
            compose.runOnIdle { allSelected = true }
            compose.mainClock.advanceTimeBy(500)
            fun allFrame() {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val viewport = compose.onNodeWithTag("album-viewport").fetchSemanticsNode().boundsInRoot
                val matcher = SemanticsMatcher("Album item") { node ->
                    node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                        ?.let { it.startsWith("album-") && it != "album-viewport" } == true
                }
                val expected = compose.onAllNodes(matcher).fetchSemanticsNodes().map { it.boundsInRoot.intersect(viewport) }
                    .filterNot { it.isEmpty }.sortedBy { it.left }
                val actual = compose.runOnIdle { scene.lastDrawnRegions.map { it.bounds.translate(viewport.topLeft).intersect(viewport) }
                    .filterNot { it.isEmpty }.sortedBy { it.left } }
                assertTrue("All mode exercises multiple visible album bodies", expected.size > 1)
                assertEquals("All-mode bodies at viewport=$viewport expected=$expected actual=$actual", expected.size, actual.size)
                expected.zip(actual).forEach { (item, glass) ->
                    assertEquals(item.left, glass.left, 1f); assertEquals(item.right, glass.right, 1f)
                    assertEquals(item.top, glass.top, 1f); assertEquals(item.bottom, glass.bottom, 1f)
                }
            }
            allFrame()
            row.performTouchInput { down(center); moveBy(Offset(-40f, 0f), delayMillis = 32) }
            allFrame()
            row.performTouchInput { moveBy(Offset(24f, 0f), delayMillis = 32) }
            allFrame()
            row.performTouchInput { advanceEventTime(128); up() }
            allFrame()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
