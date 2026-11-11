package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.LocalUiClock
import io.github.anastaci1a.localica.ui.engine.UiClock
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The accepted live-Reduced change must retire the actual native component's Engine work. */
class SettingsSearchEntryReductionTest {
    @get:Rule val compose = localicaComposeRule()
    private var reduced by mutableStateOf(false)
    private var mounted by mutableStateOf(true)
    private lateinit var focus: SettingsSearchFocus
    private lateinit var clock: UiClock
    private fun alpha() = compose.onNodeWithTag("setting:Glass renderer", useUnmergedTree = true)
        .fetchSemanticsNode().config[SettingsHighlightAlpha]
    private fun transition(atMillis: Long) {
        compose.setContent { EngineTheme(reduced = reduced) {
            clock = LocalUiClock.current
            val scroll = rememberScrollState()
            focus = remember { SettingsSearchFocus("Glass", scroll) }
            CompositionLocalProvider(LocalSettingsSearchFocus provides focus, LocalReducedMotion provides reduced) {
                if (mounted) SettingsEntry("Glass renderer") { Box(Modifier.width(300.dp).height(80.dp)) }
            }
        } }
        compose.waitForIdle()
        assertEquals(0, clock.activeCount)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { focus.highlighted = "Glass renderer" }
        compose.mainClock.advanceTimeBy(atMillis)
        assertTrue("The requested phase must be visible before reduction", alpha() > 0f)
        assertEquals(1, clock.activeCount)
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(0f, alpha(), 0f)
        assertEquals(0, clock.activeCount)
        compose.runOnIdle { reduced = false }
        compose.mainClock.advanceTimeBy(1600)
        assertEquals("Leaving Reduced never replays the retired pulse", 0f, alpha(), 0f)
        assertEquals(0, clock.activeCount)
        compose.runOnIdle { focus.highlighted = null }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { focus.highlighted = "Glass renderer" }
        compose.mainClock.advanceTimeBy(80)
        assertTrue(alpha() > 0f)
        compose.runOnIdle { focus.highlighted = null }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(0, clock.activeCount)
        compose.runOnIdle { focus.highlighted = "Glass renderer" }
        compose.mainClock.advanceTimeBy(80)
        assertEquals(1, clock.activeCount)
        compose.runOnIdle { mounted = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(0, clock.activeCount)
        assertTrue(focus.anchors.isEmpty())
        compose.mainClock.autoAdvance = true
    }
    @Test fun enablingAndDisablingReducedDuringOnsetRetiresWithoutReplay() = transition(80)
    @Test fun enablingAndDisablingReducedDuringHoldRetiresWithoutReplay() = transition(320)
    @Test fun enablingAndDisablingReducedDuringFadeRetiresWithoutReplay() = transition(800)
}
