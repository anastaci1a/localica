package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsSelected
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QueueSelectionPlacementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realQueueSelectorTracksNativeDragReversalReentryAndBothDepartingSelections() {
        val frames = SelectorPlacementFrames(compose)
        lateinit var source: QueueSelectionDrawSource
        lateinit var scope: CoroutineScope
        var selected by mutableStateOf<String?>("q2")
        val queues = (0..39).map { ListeningQueue(id = "q$it", name = "Queue $it", entries = emptyList()) }
        var unexpectedActions = 0
        var failure: Throwable? = null
        try {
            compose.setContent { LocalicaTheme {
                scope = rememberCoroutineScope()
                CompositionLocalProvider(LocalGlassPanelReady provides true,
                    LocalQueueSelectionDrawObserver provides { next ->
                        source = next
                        frames.observe(next.view) { queuePlacement(next) }
                    }) {
                    Box(Modifier.width(320.dp).height(320.dp)) {
                        QueueSelectorList(queues, selected, "q0", false, false, true,
                            onSelect = { selected = it }, onRename = { unexpectedActions++ }, onDelete = { unexpectedActions++ },
                            onOffset = { _, _ -> unexpectedActions++ },
                            onMove = { _, _, complete -> unexpectedActions++; complete(false) })
                    }
                }
            } }
            compose.waitForIdle()
            frames.awaitReady()
            compose.mainClock.autoAdvance = false
            frames.settle()
            frames.requireChosen(setOf("q2"))
            compose.onNodeWithTag("queue-select-q2", useUnmergedTree = true).assertIsSelected()
            frames.checking = true
            frames.exerciseMotion("q2") { index ->
                val done = CompletableDeferred<Unit>()
                compose.runOnUiThread { scope.launch {
                    try { source.state.scrollToItem(index); done.complete(Unit) }
                    catch (caught: Throwable) { done.completeExceptionally(caught) }
                } }
                runBlocking { withTimeout(5000) { done.await() } }
            }
            frames.departing = setOf("q2")
            frames.tap("q1")
            val departure = frames.frame()
            assertEquals(setOf("q1"), departure.layout.chosen)
            val before = requireNotNull(departure.visibility["q2"])
            assertTrue("Old visible selected queue keeps its departure", before > 0f)
            repeat(3) { frames.frame() }
            val outgoing = requireNotNull(frames.frame().visibility["q2"])
            assertTrue(outgoing > 0f && outgoing < before)
            frames.departing = setOf("q1", "q2")
            frames.tap("q2")
            val reversal = frames.frame()
            assertEquals(setOf("q2"), reversal.layout.chosen)
            val reversed = requireNotNull(reversal.visibility["q2"])
            assertTrue(reversed > 0f && reversed < 1f)
            assertTrue("Newly deselected queue also retains its visible lens", requireNotNull(reversal.visibility["q1"]) > 0f)
            repeat(3) { frames.frame() }
            assertTrue(requireNotNull(frames.frame().visibility["q2"]) > reversed)
            frames.settle()
            frames.requireChosen(setOf("q2"))
            assertEquals("Other queue departure finishes", 0f, requireNotNull(frames.frame().visibility["q1"]), 0f)
            assertEquals("Ordinary scrolling and row taps do not dispatch reorder/trailing actions", 0, unexpectedActions)
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            val cleanup = runCatching { frames.close() }.exceptionOrNull()
            if (cleanup != null) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }
}

private fun queuePlacement(source: QueueSelectionDrawSource): SelectorDraw = Snapshot.withoutReadObservation {
    val info = source.state.layoutInfo
    val origin = source.content.positionInRoot()
    // Surface owns the existing two-dp vertical padding inside each native grid item.
    val inset = 2f * source.view.resources.displayMetrics.density
    val layout = SelectorLayout(source.viewport.boundsInRoot(),
        source.state.firstVisibleItemIndex to source.state.firstVisibleItemScrollOffset,
        setOfNotNull(source.browsedId), info.visibleItemsInfo.map { item ->
            val key = item.key as String
            val top = origin.y + info.beforeContentPadding + item.offset.y
            val left = origin.x + item.offset.x
            SelectorPlacedItem(key, Rect(left, top + inset, left + item.size.width, top + item.size.height - inset),
                source.rows[key]?.takeIf { it.isAttached }?.let { Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())) })
        }, source.state.isScrollInProgress)
    selectorDraw(source.scene, source.viewport, layout, source.hostDraws())
}
