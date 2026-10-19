package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassActionRowTest {
    @get:Rule val compose = createComposeRule()

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
        compose.setContent { LocalicaTheme { CompositionLocalProvider(
            LocalReducedMotion provides true, LocalLayoutDirection provides direction) {
            Box(Modifier.width(328.dp).padding(vertical = 30.dp)) {
                GlassActionRow(Modifier.testTag("actions")) {
                    GlassActionButton(R.drawable.ic_shuffle, "Randomize", onClick = { visible = true })
                    GlassPresence(visible) {
                        GlassActionButton(R.drawable.ic_revert, "Revert", onClick = { visible = false })
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
        compose.setContent { LocalicaTheme {
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
                            GlassActionButton(R.drawable.ic_shuffle, "Randomize", onClick = {
                                randomizations++; visible = true
                            })
                            GlassPresence(visible) {
                                GlassActionButton(R.drawable.ic_revert, "Revert", onClick = {
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
                down(center); advanceEventTime(200)
                moveBy(Offset(travel, 0f), delayMillis = 100); up()
            }
            assertEquals(beforeDrag, randomize.getUnclippedBoundsInRoot())
            compose.runOnIdle {
                assertEquals(clicksBeforeDrag, randomizations); assertEquals(pulsesBeforeDrag, pulses.size)
            }
            // Slow movement must also retain ownership without a stationary hold.
            randomize.performTouchInput {
                down(center)
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
