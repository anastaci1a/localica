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
import io.github.anastaci1a.localica.library.LibraryGroup
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.components.TriggerRole
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
    private var buttonTaps = 0
    private var buttonHolds = 0
    private var longPress = 0L
    private val transit = ArtworkTransit()
    private val state = LazyGridState()
    private var items by mutableStateOf(List(60) { BrowserItem(MediaRef(MediaKind.Album, "$it"), "Album $it", null, emptyList()) })

    private val albums = List(8) { LibraryGroup("album:$it", "Choice $it", null, emptyList()) }
    private val selections = mutableListOf<List<String>>()
    @OptIn(ExperimentalFoundationApi::class)
    private fun host(carousel: Boolean = false, all: Boolean = false) {
        compose.setContent { LocalicaTheme {
            longPress = LocalViewConfiguration.current.longPressTimeoutMillis
            CompositionLocalProvider(LocalArtworkTransit provides transit) {
                Box(Modifier.width(if (all) 128.dp else 300.dp).height(500.dp)) {
                    LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize().testTag("rows"), state = state) {
                        items(61) {
                            if (carousel && it == 15) ArtistAlbumChoices(albums, listOf(albums[0].id)) { selections += it }
                            else
                            Box(Modifier.fillMaxWidth().height(100.dp)
                                .combinedClickable(onClick = { rowTaps++ }, onLongClick = { rowHolds++ })) {
                                Text("Row $it")
                                // A row's own ⋯, as in browse rows: a shared button inside a long-pressable row.
                                // Rows near the top have none, so the thumb at rest has no button under it.
                                if (it >= 10) Box(Modifier.align(Alignment.CenterEnd)) {
                                    ActionIcon(AppIcon.ActionEllipsis, "Overflow $it", role = TriggerRole.PlainIcon,
                                        onLongClick = { buttonHolds++ }) { buttonTaps++ }
                                }
                            }
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

    /** #950: a quarter down, the revealed thumb lies over row 15's ⋯; returns that button's centre in the bar. */
    private fun thumbOverRowButton(): Offset {
        compose.runOnIdle { state.requestScrollToItem(14) }
        compose.revealScrollbar(state)
        val button = compose.onNodeWithContentDescription("Overflow 15").fetchSemanticsNode().boundsInRoot
        val strip = bar().fetchSemanticsNode().boundsInRoot
        assertTrue("Fixture: the thumb overlaps the button", thumb().top < button.bottom && thumb().bottom > button.top &&
            button.right > strip.left)
        return Offset(button.center.x - strip.left, button.center.y - strip.top)
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

    @Test fun aPlainTapOnARowButtonUnderTheThumbGoesToTheButton() {
        host()
        val at = thumbOverRowButton()
        val before = compose.runOnIdle { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
        val rest = thumbColour()
        bar().performTouchInput { down(at) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("A press over a button never selects the thumb", rest, thumbColour())
        bar().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals("The button takes the plain tap (#950)", 1, buttonTaps)
            assertEquals(0, rowTaps)
            assertEquals(before, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun aStillHoldOnARowButtonUnderTheThumbGoesToTheButton() {
        host()
        val at = thumbOverRowButton()
        val rest = thumbColour()
        bar().performTouchInput { down(at) }
        compose.mainClock.advanceTimeBy(longPress + 100)
        assertEquals(rest, thumbColour())
        bar().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals("The button keeps its hold", 1, buttonHolds)
            assertEquals(0, buttonTaps); assertEquals(0, rowHolds)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun verticalTravelFromARowButtonUnderTheThumbBecomesAScrollbarDrag() {
        host()
        val at = thumbOverRowButton()
        val index = compose.runOnIdle { state.firstVisibleItemIndex }
        bar().performTouchInput { down(at); moveBy(Offset(0f, 130f), delayMillis = 100) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnIdle { assertTrue("The scrollbar owns the drag", transit.active) }
        bar().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertTrue("The drag moved the list by the thumb", state.firstVisibleItemIndex > index + 5)
            assertEquals(0, buttonTaps); assertEquals(0, buttonHolds); assertEquals(0, rowTaps)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun aPressWithNoButtonActivatesAtOnceAndEveryPixelMovesTheThumb() {
        host()
        compose.revealScrollbar(state)
        val y = grab()
        bar().performTouchInput { down(Offset(centerX, y)) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertTrue("Touch-down activates: the drag's far journey starts", transit.active) }
        val top = thumb().top
        bar().performTouchInput { moveBy(Offset(0f, viewConfiguration.touchSlop / 2f)) }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        assertTrue("A sub-slop move already moves the thumb", thumb().top > top)
        bar().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertFalse(transit.active); assertEquals(0, rowTaps); assertEquals(0, rowHolds) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun republishedRowsDuringAHeldDragKeepTheThumbUnderTheFinger() {
        host()
        compose.revealScrollbar(state)
        bar().performTouchInput { down(Offset(centerX, grab())); moveBy(Offset(0f, 120f), delayMillis = 100) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val top = thumb().top
        val index = compose.runOnIdle { state.firstVisibleItemIndex }
        // #951: subviews republish rows (a refresh after the scroll stops) while the thumb is still held.
        compose.runOnIdle { items = items.mapIndexed { i, item -> if (i == 3) item.copy(title = "Renamed") else item } }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("The held thumb stays where the finger put it", top, thumb().top, 1f)
        compose.runOnIdle { assertTrue("The list does not jump to the top", state.firstVisibleItemIndex >= index) }
        bar().performTouchInput { moveBy(Offset(0f, 20f)) }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        assertTrue("The same drag continues", thumb().top > top)
        bar().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.autoAdvance = true
    }

    private fun thumbOverAlbumTile(all: Boolean = false): Offset {
        host(carousel = true, all = all)
        compose.runOnIdle { state.requestScrollToItem(14, with(compose.density) { 60.dp.roundToPx() }) }
        compose.revealScrollbar(state)
        val strip = bar().fetchSemanticsNode().boundsInRoot
        val tile = compose.onNodeWithText(if (all) "All albums" else "Choice 1").fetchSemanticsNode().boundsInRoot
        val grip = thumb()
        val left = maxOf(strip.left, tile.left); val right = minOf(strip.right, tile.right)
        val top = maxOf(grip.top, tile.top); val bottom = minOf(grip.bottom, tile.bottom)
        assertTrue("Fixture: the thumb overlaps the album tile", right > left && bottom > top)
        return Offset((left + right) / 2 - strip.left, (top + bottom) / 2 - strip.top)
    }

    @Test fun albumTapUnderTheThumbSelectsOnlyOnceWithoutActivatingTheBar() {
        val at = thumbOverAlbumTile()
        val before = compose.runOnIdle { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
        val rest = thumbColour()
        bar().performTouchInput { down(at) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(rest, thumbColour())
        bar().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {
            assertEquals(listOf(listOf(albums[1].id)), selections)
            assertEquals(before, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
            assertFalse(transit.active)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun albumHoldUnderTheThumbTogglesOnceWithoutActivatingTheBar() {
        val at = thumbOverAlbumTile()
        val rest = thumbColour()
        bar().performTouchInput { down(at) }
        compose.mainClock.advanceTimeBy(longPress + 100)
        assertEquals(rest, thumbColour())
        bar().performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(albums.take(2).map { it.id }), selections)
            assertFalse(transit.active)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun horizontalAlbumSwipeUnderTheThumbMovesOnlyTheCarousel() {
        val at = thumbOverAlbumTile()
        val before = compose.runOnIdle { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
        val row = compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange))
        val position = row.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange].value()
        val rest = thumbColour()
        bar().performTouchInput { down(at); moveBy(Offset(-100f, 0f), delayMillis = 100) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(rest, thumbColour())
        bar().performTouchInput { up() }
        compose.runOnIdle {
            assertTrue(row.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange].value() > position)
            assertEquals(before, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
            assertTrue(selections.isEmpty()); assertFalse(transit.active)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun verticalAlbumDragUnderTheThumbBelongsToTheBar() {
        val at = thumbOverAlbumTile()
        val index = compose.runOnIdle { state.firstVisibleItemIndex }
        val rest = thumbColour()
        bar().performTouchInput { down(at); moveBy(Offset(0f, 100f), delayMillis = 100) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertNotEquals(rest, thumbColour())
        compose.runOnIdle { assertTrue(transit.active); assertTrue(state.firstVisibleItemIndex > index); assertTrue(selections.isEmpty()) }
        bar().performTouchInput { up() }
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun allAlbumsTapUnderTheThumbClearsTheSelection() {
        val at = thumbOverAlbumTile(all = true)
        bar().performTouchInput { click(at) }
        compose.runOnIdle { assertEquals(listOf(emptyList<String>()), selections); assertFalse(transit.active) }
        compose.mainClock.autoAdvance = true
    }
}
