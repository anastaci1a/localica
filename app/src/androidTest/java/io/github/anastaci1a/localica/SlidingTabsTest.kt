package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.TabLensStyle
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SlidingTabsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun optionalForegroundRefractionChangesOnlyTheSelectedInk() {
        var foreground by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(tabStyle = TabLensStyle.Overlay,
                overlayForeground = foreground, whiteTint = 0f, blur = 0f, contrast = 0f, shadowStrength = 0f)) {
                val source = rememberFrostedBackdrop(Unit, dark = false)
                Box(Modifier.size(300.dp, 64.dp).testTag("foreground-tabs")) {
                    Box(Modifier.fillMaxSize().recordFrostedBackdrop(source).background(Color.White))
                    SlidingTabs(listOf(MainTab.Home, MainTab.Library), MainTab.Home, { 0f }, source = source) {}
                }
            }
        } }
        val node = compose.onNodeWithTag("foreground-tabs")
        val before = node.captureToImage().toPixelMap()
        compose.runOnIdle { foreground = true }
        // The legacy renderer prepares its shared scene off the UI thread.
        // Compose idleness alone does not mean that readback has finished.
        var after = node.captureToImage().toPixelMap()
        compose.waitUntil(8000) {
            after = node.captureToImage().toPixelMap()
            var refracted = 0
            for (y in 0 until before.height) for (x in 0 until before.width / 2)
                if (kotlin.math.abs(before[x, y].green - after[x, y].green) > .1f) refracted++
            refracted > 40
        }
        var changed = 0; var outside = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val error = kotlin.math.abs(before[x, y].green - after[x, y].green)
            if (error > .1f) { if (x < before.width / 2) changed++ else outside++ }
        }
        assertTrue("Opting in must visibly refract the selected foreground", changed > 40)
        assertTrue("Other tab labels stay untouched: $outside", outside < 10)
        compose.onAllNodesWithContentDescription("Home").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Library").assertCountEquals(1)
    }
    @Test fun overlayLensDoesNotRefractOrDuplicateItsForegroundLabels() {
        var style by mutableStateOf(TabLensStyle.Classic)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(tabStyle = style, whiteTint = 0f,
                blur = 0f, contrast = 0f, shadowStrength = 0f)) {
                val source = rememberFrostedBackdrop(Unit)
                Box(Modifier.size(300.dp, 64.dp).testTag("tabs")) {
                    Box(Modifier.fillMaxSize().recordFrostedBackdrop(source).background(Color.White))
                    CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                        SlidingTabs(listOf(MainTab.Home, MainTab.Library), MainTab.Home, { 0f }) {}
                    }
                }
            }
        } }
        val node = compose.onNodeWithTag("tabs")
        val before = node.captureToImage().toPixelMap()
        compose.runOnIdle { style = TabLensStyle.Overlay }
        val after = node.captureToImage().toPixelMap()
        fun accent(c: Color) = c.red - c.green > .2f && c.red - c.blue > .15f
        var ink = 0
        var disagreement = 0
        for (y in 0 until before.height) for (x in 0 until before.width / 2) {
            val old = accent(before[x, y]); val next = accent(after[x, y])
            if (old || next) ink++
            if (old != next) disagreement++
        }
        assertTrue("Fixture must contain a visible selected icon and label", ink > 100)
        assertTrue("Glass must not move or duplicate accent ink: $disagreement/$ink", disagreement < ink * .03f)
    }
    @Test fun onlyTappingTheSettledCurrentTabRequestsReselection() {
        val tabs = listOf(MainTab.Home, MainTab.Library)
        var active by mutableStateOf(MainTab.Home)
        val selections = mutableListOf<MainTab>()
        val reselections = mutableListOf<MainTab>()
        compose.setContent { LocalicaTheme { Box(Modifier.size(300.dp, 64.dp)) {
            SlidingTabs(tabs, active, { tabs.indexOf(active).toFloat() }, onReselect = { reselections += it }) {
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
            SlidingTabs(tabs, active, { tabs.indexOf(active).toFloat() }) { active = it; calls += it }
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

    @Test fun overflowLensOwnsBoundaryDragWhileOtherOriginsScrollTabs() {
        val tabs = MainTab.entries.take(8)
        val calls = mutableListOf<MainTab>()
        compose.setContent { LocalicaTheme { Box(Modifier.size(260.dp, 64.dp)) {
            SlidingTabs(tabs, tabs.first(), { 0f }) { calls += it }
        } } }
        val strip = compose.onNodeWithTag("tab-scroll")
        strip.performTouchInput { swipe(Offset(width * .18f, centerY), Offset(width * .02f, centerY), 400) }
        compose.waitForIdle()
        // #366 makes the actual lens origin own adjustment. At the first boundary
        // this outward drag selects no new page and must not scroll the strip.
        assertTrue("Outward lens drag stays at the first page", calls.isEmpty())
        assertEquals(0f, strip.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value(), 0f)
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
            SlidingTabs(tabs, active, { tabs.indexOf(active).toFloat() }) { active = it }
        } } }
        compose.onNodeWithTag("tabs").performTouchInput {
            swipe(Offset(width * .5f, centerY), Offset(width * .88f, centerY), 400)
        }
        compose.runOnIdle { assertEquals("#366 picks up the touched Library option and continues to Songs", MainTab.Songs, active) }
    }

    @Test fun longestLabelFitsInsideItsSelectorWithRoundedEndPadding() {
        val tabs = listOf(MainTab.Home, MainTab.AlbumArtists, MainTab.Albums)
        compose.setContent { LocalicaTheme { Box(Modifier.size(300.dp, 64.dp)) {
            SlidingTabs(tabs, MainTab.AlbumArtists, { 1f }) {}
        } } }
        val label = compose.onNode(hasText(MainTab.AlbumArtists.label) and hasAnyAncestor(isSelected()), useUnmergedTree = true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        label.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Cell width follows the full label", layouts.single().isLineEllipsized(0))
        val text = label.fetchSemanticsNode().boundsInRoot
        val lens = compose.onNodeWithTag("tab-selector", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The label stays inside the curved selector", text.left > lens.left && text.right < lens.right)
    }

    @Test fun delayedPageSamplesRespectTapRequestAndPendingContactThenCancelToLatestPage() {
        val tabs = listOf(MainTab.Home, MainTab.Library, MainTab.Songs)
        var active by mutableStateOf(MainTab.Home)
        var page by mutableFloatStateOf(0f)
        var pageDragging by mutableStateOf(false)
        val selections = mutableListOf<MainTab>()
        val reselections = mutableListOf<MainTab>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true,
                LocalGlassAppearance provides GlassAppearance(tabStyle = TabLensStyle.Classic)) {
                Box(Modifier.size(300.dp, 64.dp).testTag("page-ownership-tabs")) {
                    SlidingTabs(tabs, active, { page }, pageDragging = pageDragging,
                        onReselect = { reselections += it }) { selections += it; active = it }
                }
            }
        } }
        fun assertAt(index: Int) {
            val selector = compose.onNodeWithTag("tab-selector").fetchSemanticsNode().boundsInRoot
            val option = compose.onNodeWithTag("tab-option-$index").fetchSemanticsNode().boundsInRoot
            assertEquals("Selector follows the intended option", option.center.x, selector.center.x, 1.1f)
        }
        fun assertOnlyOriginalSelection() = compose.runOnIdle {
            assertEquals(listOf(MainTab.Songs), selections)
            assertTrue(reselections.isEmpty())
        }
        var held = false
        var failure: Throwable? = null
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("tab-option-2").performClick()
            compose.mainClock.advanceTimeBy(64)
            assertAt(2)
            assertOnlyOriginalSelection()
            // Target-page selection and fractional pager delivery are independent.
            for (sample in listOf(.25f, 1.5f)) {
                compose.runOnIdle { page = sample }
                compose.mainClock.advanceTimeByFrame()
                assertAt(2)
                assertOnlyOriginalSelection()
            }
            compose.onNodeWithTag("tab-selector").performTouchInput { down(center); held = true }
            compose.mainClock.advanceTimeByFrame()
            assertAt(2)
            compose.runOnIdle { page = 1.25f }
            compose.mainClock.advanceTimeByFrame()
            assertAt(2)
            // A real pager takeover retires the old tap request while this contact
            // remains pending. Advance only frames, not the long-press pickup delay.
            compose.runOnIdle { active = MainTab.Library; pageDragging = true; page = .75f }
            compose.mainClock.advanceTimeByFrame()
            assertAt(2)
            assertOnlyOriginalSelection()
            compose.runOnIdle { page = 1f; pageDragging = false }
            compose.mainClock.advanceTimeByFrame()
            assertAt(2)
            compose.onNodeWithTag("page-ownership-tabs").performTouchInput { cancel(); held = false }
            compose.mainClock.advanceTimeBy(64)
            compose.waitUntil(5000) {
                val selector = compose.onNodeWithTag("tab-selector").fetchSemanticsNode().boundsInRoot
                val latest = compose.onNodeWithTag("tab-option-1").fetchSemanticsNode().boundsInRoot
                kotlin.math.abs(selector.center.x - latest.center.x) <= 1.1f
            }
            assertAt(1)
            compose.onNodeWithTag("tab-option-1").assertIsSelected()
            assertOnlyOriginalSelection()
            compose.onNodeWithTag("tab-option-1").performClick()
            compose.runOnIdle {
                assertEquals(listOf(MainTab.Songs), selections)
                assertEquals(listOf(MainTab.Library), reselections)
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                if (held) try {
                    compose.onNodeWithTag("page-ownership-tabs").performTouchInput { cancel() }
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

}
