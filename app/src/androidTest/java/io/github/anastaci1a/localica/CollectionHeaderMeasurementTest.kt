package io.github.anastaci1a.localica

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.BeyondBoundsLayout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ModifierLocalBeyondBoundsLayout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Public lazy-layout experiment only. Success is required before using this in production.
 * Returned evidence contains only copied rectangles, with no guessed pitch or pixels. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
class CollectionHeaderMeasurementTest {
    @get:Rule val compose = createComposeRule()
    private val header = "browser-notice"
    private val anchor = "row-17"
    private val count = 60

    private class Placed(val owner: Any, val epoch: Int, val request: Int?, val coordinates: LayoutCoordinates)
    private class Fixture {
        var columns by mutableIntStateOf(6)
        var epoch by mutableIntStateOf(0)
        lateinit var grid: LazyGridState
        lateinit var scope: CoroutineScope
        var viewport: LayoutCoordinates? = null
        var beyond: BeyondBoundsLayout? = null
        var activeRequest: Int? = null
        var requestSerial = 0
        val placed = mutableMapOf<String, Placed>()
    }
    private data class Scroll(val index: Int, val offset: Int, val anchor: Rect)
    private data class Measured(val epoch: Int, val request: Int, val callbacks: Int,
        val header: Rect, val rows: Map<String, Rect>)

    @Test fun publicBeyondBoundsCopiesCurrentOffscreenHeaderWithoutMovingTheCorrectedAnchor() {
        val fixture = Fixture()
        compose.setContent {
            val grid = rememberLazyGridState()
            val scope = rememberCoroutineScope()
            val columns = fixture.columns
            val epoch = fixture.epoch
            SideEffect { fixture.grid = grid; fixture.scope = scope }
            LazyVerticalGrid(GridCells.Fixed(columns), state = grid,
                modifier = Modifier.fillMaxSize().onPlaced { fixture.viewport = it }) {
                item(key = header, span = { GridItemSpan(maxLineSpan) }) {
                    MeasuredItem(fixture, header, epoch, Modifier.fillMaxWidth().height(79.dp))
                }
                items(count, key = { "row-$it" }) { index ->
                    // Deliberately nonuniform: an index multiplied by any single row
                    // height cannot establish the offscreen header's coordinate.
                    val height = if (columns == 6) 32 + index % 3 * 7 else 56 + index % 5 * 11
                    MeasuredItem(fixture, "row-$index", epoch, Modifier.fillMaxWidth().height(height.dp))
                }
            }
        }
        compose.waitUntil(5000) { compose.runOnUiThread {
            fixture.placed[header]?.epoch == 0 && fixture.placed[anchor]?.epoch == 0 && fixture.beyond != null
        } }
        val source = compose.runOnUiThread { rectangle(fixture, header) }
        val sourceAnchor = compose.runOnUiThread { rectangle(fixture, anchor) }
        assertEquals(0f, source.top, 1.1f)
        assertTrue("Source header is actually mounted in the viewport", source.bottom > 0f)

        reflowAndCorrect(fixture, 1, sourceAnchor.top)
        compose.runOnIdle {
            assertFalse("Destination must really virtualize the header",
                fixture.grid.layoutInfo.visibleItemsInfo.any { it.key == header })
        }
        val first = measureBeyond(fixture)
        assertTrue("Copied destination is genuinely above the viewport", first.header.bottom <= 0f)
        assertMeasuredChain(first, anchor)

        reflowAndCorrect(fixture, 6, sourceAnchor.top)
        compose.runOnIdle {
            assertTrue("Reverse reentry mounts the real header again",
                fixture.grid.layoutInfo.visibleItemsInfo.any { it.key == header })
            assertRect(source, rectangle(fixture, header))
            assertRect(sourceAnchor, rectangle(fixture, anchor))
        }

        reflowAndCorrect(fixture, 1, sourceAnchor.top)
        val second = measureBeyond(fixture)
        assertTrue("A later request cannot reuse the old placement stamp", second.request > first.request)
        assertTrue("Reverse/reentry must produce a new measurement generation", second.epoch > first.epoch)
        assertRect(first.header, second.header)
        assertMeasuredChain(second, anchor)
    }

    @Composable
    private fun MeasuredItem(fixture: Fixture, key: String, epoch: Int, modifier: Modifier) {
        val owner = remember(key, epoch) { Any() }
        DisposableEffect(owner) { onDispose {
            if (fixture.placed[key]?.owner === owner) fixture.placed.remove(key)
        } }
        Box(modifier.modifierLocalConsumer {
            ModifierLocalBeyondBoundsLayout.current?.let { fixture.beyond = it }
        }.onPlaced {
            fixture.placed[key] = Placed(owner, epoch, fixture.activeRequest, it)
        })
    }

    private fun reflowAndCorrect(fixture: Fixture, columns: Int, anchorTop: Float) {
        compose.runOnIdle { fixture.columns = columns; fixture.epoch++ }
        val done = CompletableDeferred<Unit>()
        compose.runOnIdle {
            fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    // Same real item-key/measurement correction used by the browser;
                    // the target is measured before applying the residual correction.
                    fixture.grid.scrollToItem(18, -anchorTop.roundToInt())
                    fixture.grid.scroll {
                        val item = requireNotNull(fixture.grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor })
                        scrollBy(item.offset.y - anchorTop)
                    }
                    done.complete(Unit)
                } catch (failure: Throwable) { done.completeExceptionally(failure) }
            }
        }
        compose.waitUntil(5000) { compose.mainClock.advanceTimeByFrame(); done.isCompleted }
        runBlocking { done.await() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Actual anchor correction precedes the measurement request", anchorTop, rectangle(fixture, anchor).top, 1.1f)
        }
    }

    private fun measureBeyond(fixture: Fixture): Measured {
        lateinit var before: Scroll
        lateinit var result: Measured
        compose.runOnIdle {
            before = scroll(fixture)
            val epoch = fixture.epoch
            val request = ++fixture.requestSerial
            fixture.activeRequest = request
            var callbacks = 0
            try {
                result = requireNotNull(fixture.beyond) { "No public BeyondBoundsLayout supplied by LazyVerticalGrid" }
                    .layout(BeyondBoundsLayout.LayoutDirection.Before) {
                        callbacks++
                        check(callbacks <= count + 1) { "Beyond-bounds traversal exceeded the fixture's actual item count" }
                        val placed = fixture.placed[header]
                        if (placed?.epoch == epoch && placed.request == request && placed.coordinates.isAttached) {
                            // Copy geometry NOW: layout() may dispose extras on return.
                            val rectangles = fixture.placed.filterValues { it.epoch == epoch && it.coordinates.isAttached }
                                .mapValues { (_, item) -> rectangle(fixture, item.coordinates) }
                            val measured = Measured(epoch, request, callbacks, rectangles.getValue(header), rectangles)
                            assertScroll(before, scroll(fixture))
                            android.util.Log.i("HeaderBeyondBounds", "epoch=$epoch request=$request callbacks=$callbacks " +
                                "header=${measured.header} first=${fixture.grid.firstVisibleItemIndex}:${fixture.grid.firstVisibleItemScrollOffset} " +
                                "anchor=${measured.rows[anchor]} measuredKeys=${measured.rows.keys}")
                            measured
                        } else {
                            check(hasMoreContent) {
                                "Public beyond-bounds exhausted WITHOUT current onPlaced header geometry: " +
                                    "epoch=$epoch request=$request callbacks=$callbacks " +
                                    "headerEpoch=${placed?.epoch} headerRequest=${placed?.request} attached=${placed?.coordinates?.isAttached}"
                            }
                            null
                        }
                    } ?: throw AssertionError("Public beyond-bounds returned no copied header rectangle")
            } finally { fixture.activeRequest = null }
            assertScroll(before, scroll(fixture))
        }
        // Also verify the ordinary layout after beyond-bounds extras can disappear.
        compose.waitForIdle()
        compose.runOnIdle { assertScroll(before, scroll(fixture)) }
        return result
    }

    private fun rectangle(fixture: Fixture, key: String): Rect {
        val placed = requireNotNull(fixture.placed[key]) { "No actual placement for $key" }
        assertEquals("Placement belongs to current density generation", fixture.epoch, placed.epoch)
        return rectangle(fixture, placed.coordinates)
    }
    private fun rectangle(fixture: Fixture, coordinates: LayoutCoordinates): Rect {
        assertTrue("Read only attached public coordinates", coordinates.isAttached)
        val viewport = requireNotNull(fixture.viewport)
        val origin = viewport.localPositionOf(coordinates, Offset.Zero)
        return Rect(origin, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    }
    private fun scroll(fixture: Fixture) = Scroll(fixture.grid.firstVisibleItemIndex,
        fixture.grid.firstVisibleItemScrollOffset, rectangle(fixture, anchor))
    private fun assertScroll(expected: Scroll, actual: Scroll) {
        assertEquals("Beyond-bounds must not navigate", expected.index, actual.index)
        assertEquals("Beyond-bounds must not alter scroll offset", expected.offset, actual.offset)
        assertRect(expected.anchor, actual.anchor)
    }
    private fun assertMeasuredChain(measured: Measured, anchorKey: String) {
        // Independent geometric constraint: measured single-column items must join
        // continuously from header to the unchanged, actually corrected anchor.
        var bottom = measured.header.bottom
        for (index in 0..17) {
            val rectangle = requireNotNull(measured.rows["row-$index"]) {
                "Beyond-bounds did not expose the actual intervening row $index in epoch ${measured.epoch}"
            }
            assertEquals("Measured header/row chain at row-$index", bottom, rectangle.top, 1.1f)
            bottom = rectangle.bottom
        }
        assertTrue("The anchor itself is part of the copied callback geometry", anchorKey in measured.rows)
    }
    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1.1f)
        assertEquals(expected.top, actual.top, 1.1f)
        assertEquals(expected.right, actual.right, 1.1f)
        assertEquals(expected.bottom, actual.bottom, 1.1f)
    }
}
