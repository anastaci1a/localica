package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfacePreferences
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassActionRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chevronsPageByTheSameLogicalWidthAtEveryShadowSpread() = withOwnedInterfacePreferences { context, fixture ->
        val preferences = InterfacePreferences(context)
        val root = LocalicaUiRootState(InterfaceSettings(reducedMotion = true))
        var spread by mutableFloatStateOf(0f)
        var first: LayoutCoordinates? = null
        compose.setContent { LocalicaTheme {
            LocalicaUiRoot(root) {
                key(spread) {
                    Box(Modifier.width(180.dp)) {
                        GlassActionRow(Modifier.testTag("paging-strip")) {
                            repeat(7) { index ->
                                GlassActionButton(AppIcon.ActionRevert, "Action $index", Modifier.width(100.dp)
                                    .onGloballyPositioned { if (index == 0) first = it }) {}
                            }
                        }
                    }
                }
            }
        } }
        val expectedPage = with(compose.density) { 180.dp.roundToPx() + 2 * 22.8.dp.roundToPx() }
        val forward = compose.onNodeWithContentDescription("More actions")
        for (value in listOf(0f, 1f, 2f)) {
            compose.runOnIdle {
                check(fixture.edit().putFloat("glass-shadow-spread-multiplier", value).commit())
                root.settings.value = preferences.read().copy(reducedMotion = true)
                assertFalse(fixture.contains("glass-shadow-spread-multiplier"))
                spread = value
            }
            val before = compose.runOnIdle { requireNotNull(first).also { check(it.isAttached) }.positionInRoot().x }
            val size = compose.onNodeWithTag("paging-strip").fetchSemanticsNode().size
            forward.assertIsEnabled().performClick()
            val after = compose.runOnIdle { requireNotNull(first).also { check(it.isAttached) }.positionInRoot().x }
            assertEquals("Spread $value must preserve the stock page distance", expectedPage.toFloat(), before - after, 0f)
            assertEquals(size, compose.onNodeWithTag("paging-strip").fetchSemanticsNode().size)
            forward.assertIsEnabled()
        }
    }

    private fun assertWholeLabel(label: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals(1, results.single().lineCount)
        val layout = results.single()
        assertTrue("$label must receive a bounded paragraph width", layout.layoutInput.constraints.hasBoundedWidth)
        assertFalse("$label overflow: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
            "constraints=${layout.layoutInput.constraints}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}",
            layout.hasVisualOverflow)
    }

    @Test fun naturalWidthsKeepActionsBesideEachOtherAndReserveHiddenRevert() {
        var visible by mutableStateOf(false)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent { EngineTheme(reduced = true) { CompositionLocalProvider(
            LocalReducedMotion provides true, LocalLayoutDirection provides direction) {
            Box(Modifier.width(328.dp).padding(vertical = 30.dp)) {
                GlassActionRow(Modifier.testTag("actions")) {
                    GlassActionButton(AppIcon.ActionShuffle, "Randomize", onClick = { visible = true })
                    GlassPresence(visible) {
                        GlassActionButton(AppIcon.ActionRevert, "Revert", onClick = { visible = false })
                    }
                }
            }
        } } }
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { direction = layoutDirection }
            val hidden = compose.onNodeWithTag("actions").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithText("Revert").assertDoesNotExist()
            compose.onNodeWithText("Randomize").performClick()
            assertWholeLabel("Randomize"); assertWholeLabel("Revert")
            val randomize = compose.onNodeWithText("Randomize").fetchSemanticsNode().boundsInRoot
            val revert = compose.onNodeWithText("Revert").fetchSemanticsNode().boundsInRoot
            assertEquals(randomize.center.y, revert.center.y, 1f)
            if (layoutDirection == LayoutDirection.Ltr) assertTrue(randomize.right <= revert.left)
            else assertTrue(revert.right <= randomize.left)
            assertEquals(hidden, compose.onNodeWithTag("actions").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithContentDescription("More actions").assertDoesNotExist()
            compose.onNodeWithText("Revert").performClick()
            assertEquals(hidden, compose.onNodeWithTag("actions").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun narrowLargeFontActionsArbitrateDragsAndRemainReachableInShortWindow() {
        var visible by mutableStateOf(false)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var randomizations = 0
        var reversions = 0
        val pulses = mutableListOf<ControlHaptic>()
        compose.setContent { EngineTheme(reduced = true) {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f),
                LocalLayoutDirection provides direction, LocalReducedMotion provides true,
                LocalControlHaptics provides ControlHaptics { pulses += it }) {
                // A 360dp window minus 24dp outer margins, 48dp dialog padding
                // and default 45.6dp body shadow clearance leaves about 242dp.
                Box(Modifier.width(240.dp).height(112.dp).testTag("viewport")) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(48.dp))
                        GlassActionRow(Modifier.testTag("actions")) {
                            GlassActionButton(AppIcon.ActionShuffle, "Randomize", onClick = {
                                randomizations++; visible = true
                            })
                            GlassPresence(visible) {
                                GlassActionButton(AppIcon.ActionRevert, "Revert", onClick = {
                                    reversions++; visible = false
                                })
                            }
                        }
                    }
                }
            }
        } }
        for (layoutDirection in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { direction = layoutDirection }
            val randomize = compose.onNodeWithText("Randomize")
            randomize.performScrollTo().assertIsDisplayed()
            val beforeDrag = randomize.getUnclippedBoundsInRoot()
            val clicksBeforeDrag = randomizations
            val pulsesBeforeDrag = pulses.size
            val travel = if (layoutDirection == LayoutDirection.Ltr) -160f else 160f
            // A deliberate held drag keeps button ownership without activation.
            randomize.performTouchInput {
                down(center); advanceEventTime(ControlHoldLockMillis + 20)
                moveBy(Offset(travel, 0f), delayMillis = 100); up()
            }
            assertEquals(beforeDrag, randomize.getUnclippedBoundsInRoot())
            compose.runOnIdle {
                assertEquals(clicksBeforeDrag, randomizations); assertEquals(pulsesBeforeDrag, pulses.size)
            }
            // Once held, slow movement retains the same owner as a faster drag.
            randomize.performTouchInput {
                down(center)
                advanceEventTime(ControlHoldLockMillis + 20)
                repeat(10) { moveBy(Offset(travel / 10f, 0f), delayMillis = 100) }
                up()
            }
            assertEquals(beforeDrag, randomize.getUnclippedBoundsInRoot())
            compose.runOnIdle {
                assertEquals(clicksBeforeDrag, randomizations); assertEquals(pulsesBeforeDrag, pulses.size)
            }
            // Fast intent scrolls this overflowing row and never activates its origin.
            randomize.performTouchInput { swipe(center, center + Offset(travel, 0f), 120) }
            val afterScroll = randomize.getUnclippedBoundsInRoot()
            if (layoutDirection == LayoutDirection.Ltr) assertTrue(afterScroll.left < beforeDrag.left)
            else assertTrue(afterScroll.left > beforeDrag.left)
            compose.runOnIdle {
                assertEquals(clicksBeforeDrag, randomizations); assertEquals(pulsesBeforeDrag, pulses.size)
            }
            randomize.performScrollTo().assertIsDisplayed()
            randomize.performClick()
            compose.runOnIdle {
                assertEquals(clicksBeforeDrag + 1, randomizations)
                assertEquals(pulsesBeforeDrag + 1, pulses.size)
                assertEquals(ControlHaptic.Weak, pulses.last())
            }
            assertWholeLabel("Randomize"); assertWholeLabel("Revert")
            // Compare the native layout footprint, not two separately converted Dp
            // edges: scrolling changes their subtraction's floating-point rounding.
            val expandedSize = compose.onNodeWithTag("actions").fetchSemanticsNode().size
            compose.onNodeWithContentDescription("More actions").performScrollTo().assertIsEnabled()
                .performTouchInput { click(center) }
            val revert = compose.onNodeWithText("Revert")
            val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
            val button = revert.getUnclippedBoundsInRoot()
            assertTrue("The whole Revert action must be reachable", button.left >= viewport.left &&
                button.right <= viewport.right && button.top >= viewport.top && button.bottom <= viewport.bottom)
            revert.assertIsDisplayed().performClick()
            compose.onNodeWithText("Revert").assertDoesNotExist()
            assertEquals(expandedSize, compose.onNodeWithTag("actions").fetchSemanticsNode().size)
            compose.onNodeWithContentDescription("Previous actions").performScrollTo().assertIsEnabled()
                .performTouchInput { click(center) }
            compose.onNodeWithText("Randomize").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(2, randomizations); assertEquals(2, reversions) }
    }
}
