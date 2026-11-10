package io.github.anastaci1a.localica

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #704: only the visible thumb takes contact, and it takes it exclusively. Every other edge touch, and every
 * touch while the scrollbar is faded, still reaches the rows and the list beneath.
 */
class ScrollbarRowInputTest {
    @get:Rule val compose = localicaComposeRule()
    private var taps = 0
    private var rowTaps = 0
    private var rowHolds = 0
    private var longPress = 0L
    private val transit = ArtworkTransit()
    private val state = LazyGridState()

    @OptIn(ExperimentalFoundationApi::class)
    private fun host() {
        val items = List(60) { BrowserItem(MediaRef(MediaKind.Album, "$it"), "Album $it", null, emptyList()) }
        compose.setContent { LocalicaTheme {
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            CompositionLocalProvider(LocalArtworkTransit provides transit) {
                Box(Modifier.width(300.dp).height(500.dp)) {
                    LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize().testTag("rows"), state = state) {
                        items(61) {
                            Box(Modifier.fillMaxWidth().height(100.dp)
                                .combinedClickable(onClick = { rowTaps++ }, onLongClick = { rowHolds++ })) { Text("Row $it") }
                        }
                    }
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                        ActionIcon(AppIcon.ActionEllipsis, "Row overflow", onClick = { taps++ })
                    }
                    BrowseScrollbar(state, items, TrackSort.Title, Modifier.align(Alignment.CenterEnd))
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun bar() = compose.onNodeWithTag("browse-scrollbar")
    private fun thumb() = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot
    private fun grab() = thumb().center.y - bar().fetchSemanticsNode().boundsInRoot.top
    private fun thumbColour() = compose.onNodeWithTag("browse-scrollbar-thumb").captureToImage().toPixelMap()
        .let { it[it.width / 2, it.height / 2] }

    @Test fun aFadedScrollbarHasNoHitAreaSoTheRowTakesTheTap() {
        host()
        val y = grab()
        bar().performTouchInput { click(Offset(centerX, y)) }
        compose.runOnIdle { assertEquals("Faded scrollbar must pass the tap to the row", 1, rowTaps) }
    }

    @Test fun edgeTapsAwayFromTheThumbReachTheRowAction() {
        host()
        compose.revealScrollbar(state)
        compose.onNodeWithContentDescription("Row overflow").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithContentDescription("Row overflow").performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals("An edge tap off the thumb must reach the row action", 1, taps) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun thumbContactSelectsAtOnceAndCapturesExclusivelyOverALongPressableRow() {
        host()
        compose.revealScrollbar(state)
        val before = compose.runOnIdle { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
        val rest = thumbColour()
        val y = grab()
        bar().performTouchInput { down(Offset(centerX, y)) }
        // Contact recomposes, the grip admits time zero, then the first eased frame presents.
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertNotEquals("Touch-down selects without a drag", rest, thumbColour())
        compose.mainClock.advanceTimeBy(longPress + 300)
        compose.runOnIdle {
            assertEquals("No row long-press under the held scrollbar", 0, rowHolds)
            assertEquals("Holding the thumb never moves the list", before, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
        }
        bar().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals("Release without moving never taps the row", 0, rowTaps)
            assertEquals(0, rowHolds)
            assertEquals(0, taps)
        }
        assertEquals("Release deselects", rest, thumbColour())
        compose.mainClock.autoAdvance = true
    }

    @Test fun thumbDragScrollsTheListAndContentDragsStillScroll() {
        host()
        compose.revealScrollbar(state)
        bar().performTouchInput { down(Offset(centerX, grab())); moveBy(Offset(0f, 130f), delayMillis = 150) }
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertTrue("Scrollbar keeps a real drag", transit.active) }
        bar().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertFalse(transit.active)
            assertTrue("The drag moved the list", state.firstVisibleItemIndex > 0)
            assertEquals(0, rowTaps); assertEquals(0, rowHolds); assertEquals(0, taps)
        }
        compose.mainClock.autoAdvance = true
        val index = compose.runOnIdle { state.firstVisibleItemIndex }
        compose.onNodeWithTag("rows").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue("Ordinary list drags still scroll", state.firstVisibleItemIndex > index) }
    }
}
