package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.navigation.TabLensStyle
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Actual tab/utility siblings deliberately overlap to expose their draw order. */
class SelectorStackingTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var activity: SelectorTrackActivity
    private lateinit var view: View
    private lateinit var inputMode: InputModeManager
    private var backing: FrostedBackdrop? = null
    private var stage by mutableIntStateOf(0)
    private var showTabs by mutableStateOf(true)

    private fun fixture() {
        compose.setContent { LocalicaTheme {
            view = LocalView.current
            inputMode = LocalInputModeManager.current
            activity = rememberSelectorTrackActivity()
            val source = rememberFrostedBackdrop(Unit, glassBlur = 0f, whiteTint = 0f)
            backing = source
            var selected by remember { mutableStateOf(MainTab.Songs) }
            val tabs = listOf(MainTab.Home, MainTab.Library, MainTab.Songs)
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(
                tabStyle = TabLensStyle.Classic, classicOpacity = 1f,
                blur = 0f, whiteTint = 0f, contrast = 0f, shadowStrength = 0f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(308.dp, 112.dp).testTag("stacking-scene")) {
                        Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source)) { drawRect(Color.Blue) }
                        Canvas(Modifier.matchParentSize()) { drawRect(Color.White) }
                        // Same sibling-boundary modifier as LocalicaScreen. The smaller
                        // gap makes overlap deterministic without altering either control.
                        if (showTabs) Box(Modifier.offset(y = 24.dp).size(220.dp, 64.dp)
                            .selectorTrackStacking(activity).testTag("stacked-tabs")) {
                            SlidingTabs(tabs, selected, { tabs.indexOf(selected).toFloat() }, source,
                                activity = activity) { selected = it }
                        }
                        NavigationUtilityPill(rememberGlassAnchor(), source,
                            Modifier.offset(x = 204.dp, y = 24.dp).size(104.dp, 64.dp),
                            onSearch = {}, onMore = {})
                        PresentationMarker { stage }
                    }
                }
            }
        } }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { backing?.lensSnapshot != null }
        compose.waitForIdle()
    }

    private fun overlapPixel(): Color {
        compose.runOnIdle { stage = (stage + 1) % 32 }
        val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("stacking-scene"), stage) { view }
        val x = with(compose.density) { 212.dp.toPx() }.roundToInt()
        val y = with(compose.density) { 56.dp.toPx() }.roundToInt()
        return pixels[x, y]
    }

    private fun assertUtilityAbove() {
        val color = overlapPixel()
        assertTrue("Blue utility surface should cover the resting tab: $color", color.blue > color.red + .2f)
    }

    private fun assertTabsAbove() {
        val color = overlapPixel()
        assertTrue("Opaque Classic selector should cover the utility surface: $color", color.red < .15f && color.blue < .15f)
    }

    @Test fun contactCancelAndSettlementRaiseTheWholeTabSiblingWithoutMovingItsHost() {
        fixture()
        assertUtilityAbove()
        val host = compose.onNodeWithTag("tab-track-host")
        val restingHost = host.getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        var held = false
        try {
            host.performTouchInput { down(Offset(width * 5f / 6f, centerY)); held = true }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle { assertTrue("Pending contact raises the complete pill", activity.raised) }
            assertTabsAbove()
            host.performTouchInput { moveBy(Offset(140f, 0f), 100) }
            compose.mainClock.advanceTimeBy(500)
            compose.runOnIdle { assertTrue(activity.raised) }
            assertEquals(restingHost, host.getUnclippedBoundsInRoot())
            assertTabsAbove()
            host.performTouchInput { cancel(); held = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertTrue("Cancelled pressure retains stacking while settling", activity.raised) }
            assertTabsAbove()
            host.performTouchInput { down(Offset(width * 5f / 6f, centerY)); held = true; moveBy(Offset(140f, 0f), 100) }
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle { assertTrue("Regrab retains stacking", activity.raised) }
            assertTabsAbove()
            host.performTouchInput { cancel(); held = false }
            compose.mainClock.advanceTimeBy(2500)
            compose.runOnIdle { assertFalse(activity.raised) }
            assertUtilityAbove()
            host.performTouchInput { down(Offset(width * 5f / 6f, centerY)); held = true; moveBy(Offset(140f, 0f), 100) }
            compose.mainClock.advanceTimeBy(300)
            host.performTouchInput { up(); held = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertTrue("Released pressure retains stacking while settling", activity.raised) }
            compose.mainClock.advanceTimeBy(2500)
            compose.runOnIdle { assertFalse(activity.raised) }
            assertEquals(restingHost, host.getUnclippedBoundsInRoot())
        } finally {
            if (held) host.performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun keyboardFocusRaisesTabsAndDisposalClearsItsPresentationOwnership() {
        fixture()
        assertUtilityAbove()
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithTag("tab-option-2").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.onNodeWithTag("tab-option-2").assertIsFocused()
        compose.runOnIdle { assertTrue(activity.raised) }
        assertTabsAbove()
        compose.onNodeWithContentDescription("Search library").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.runOnIdle { assertFalse(activity.raised) }
        assertUtilityAbove()
        compose.onNodeWithTag("tab-option-2").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.runOnIdle { showTabs = false }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("Removed tracks must not leave stale activity", activity.raised) }
    }
}
