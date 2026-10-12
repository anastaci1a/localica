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

    @Test fun draggingOutsideTheLensScrollsOverflowTabsWithoutSelectingThem() {
        val tabs = MainTab.entries.take(8)
        val calls = mutableListOf<MainTab>()
        compose.setContent { LocalicaTheme { Box(Modifier.size(260.dp, 64.dp)) {
            SlidingTabs(tabs, tabs.first(), 0f) { calls += it }
        } } }
        val strip = compose.onNodeWithTag("tab-scroll")
        strip.performTouchInput { swipe(Offset(width * .8f, centerY), Offset(width * .25f, centerY), 400) }
        compose.waitForIdle()
        assertTrue("Scrolling the track cannot switch pages", calls.isEmpty())
        val range = strip.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("Extra tabs must be reachable by scrolling", range.value() > 0f)
    }
}
