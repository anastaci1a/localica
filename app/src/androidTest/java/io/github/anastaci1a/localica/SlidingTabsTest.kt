package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SlidingTabsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun onlyTappingTheSettledCurrentTabRequestsReselection() {
        val tabs = listOf(MainTab.Home, MainTab.Library)
        var active by mutableStateOf(MainTab.Home)
        val selections = mutableListOf<MainTab>()
        val reselections = mutableListOf<MainTab>()
        compose.setContent { LocalicaTheme { Box(Modifier.size(300.dp, 64.dp)) {
            SlidingTabs(tabs, active, tabs.indexOf(active).toFloat(), onReselect = { reselections += it }) {
                active = it; selections += it
            }
        } } }
        compose.onNodeWithContentDescription("Library").performClick()
        compose.runOnIdle { assertEquals(listOf(MainTab.Library), selections); assertTrue(reselections.isEmpty()) }
        compose.onNodeWithContentDescription("Library").performClick()
        compose.runOnIdle { assertEquals(listOf(MainTab.Library), reselections) }
        compose.onNodeWithContentDescription("Home").performClick()
        compose.runOnIdle { assertEquals(listOf(MainTab.Library, MainTab.Home), selections); assertEquals(1, reselections.size) }
    }
    @Test fun draggingSwitchesOnlyAfterTheLensCrossesAndKeepsTheContainerFixed() {
        val tabs = listOf(MainTab.Home, MainTab.Library, MainTab.Songs)
        var active by mutableStateOf(MainTab.Home)
        val calls = mutableListOf<MainTab>()
        compose.setContent { LocalicaTheme { Box(Modifier.size(300.dp, 64.dp).testTag("tabs")) {
            SlidingTabs(tabs, active, tabs.indexOf(active).toFloat()) { active = it; calls += it }
        } } }
        val target = compose.onNodeWithTag("tabs")
        val bounds = target.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        target.performTouchInput { down(Offset(width / 6f, centerY)); moveBy(Offset(width / 3f * .4f, 0f)) }
        compose.mainClock.advanceTimeBy(64)
        assertTrue(calls.isEmpty())
        assertEquals(bounds, target.fetchSemanticsNode().boundsInRoot)
        target.performTouchInput { moveBy(Offset(width / 3f * .3f, 0f)) }
        compose.mainClock.advanceTimeBy(64)
        assertEquals(listOf(MainTab.Library), calls)
        target.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.onAllNodesWithContentDescription("Library").assertCountEquals(1)
    }

    @Test fun everyOverflowDragScrollsTabsIncludingADragFromTheLens() {
        val tabs = MainTab.entries.take(8)
        val calls = mutableListOf<MainTab>()
        compose.setContent { LocalicaTheme { Box(Modifier.size(260.dp, 64.dp)) {
            SlidingTabs(tabs, tabs.first(), 0f) { calls += it }
        } } }
        val strip = compose.onNodeWithTag("tab-scroll")
        strip.performTouchInput { swipe(Offset(width * .18f, centerY), Offset(width * .02f, centerY), 400) }
        compose.waitForIdle()
        assertTrue("Dragging the selected overflow tab cannot move its lens", calls.isEmpty())
        assertTrue(strip.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        strip.performTouchInput { swipe(Offset(width * .8f, centerY), Offset(width * .25f, centerY), 400) }
        compose.waitForIdle()
        assertTrue("Scrolling the track cannot switch pages", calls.isEmpty())
        val range = strip.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("Extra tabs must be reachable by scrolling", range.value() > 0f)
    }

    @Test fun fittedTabsAcceptSelectorDragsBeginningAwayFromTheLens() {
        val tabs = listOf(MainTab.Home, MainTab.Library, MainTab.Songs)
        var active by mutableStateOf(MainTab.Home)
        compose.setContent { LocalicaTheme { Box(Modifier.size(300.dp, 64.dp).testTag("tabs")) {
            SlidingTabs(tabs, active, tabs.indexOf(active).toFloat()) { active = it }
        } } }
        compose.onNodeWithTag("tabs").performTouchInput {
            swipe(Offset(width * .5f, centerY), Offset(width * .88f, centerY), 400)
        }
        compose.runOnIdle { assertEquals("A track drag moves the lens relatively without teleporting it", MainTab.Library, active) }
    }

    @Test fun longestLabelFitsInsideItsSelectorWithRoundedEndPadding() {
        val tabs = listOf(MainTab.Home, MainTab.AlbumArtists, MainTab.Albums)
        compose.setContent { LocalicaTheme { Box(Modifier.size(300.dp, 64.dp)) {
            SlidingTabs(tabs, MainTab.AlbumArtists, 1f) {}
        } } }
        val label = compose.onNode(hasText(MainTab.AlbumArtists.label) and hasAnyAncestor(isSelected()), useUnmergedTree = true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        label.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Cell width follows the full label", layouts.single().isLineEllipsized(0))
        val text = label.fetchSemanticsNode().boundsInRoot
        val lens = compose.onNodeWithTag("tab-selector", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The label stays inside the curved selector", text.left > lens.left && text.right < lens.right)
    }
}
