package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
                        GlassActionButton(R.drawable.ic_seek_back, "Revert", onClick = { visible = false })
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

    @Test fun narrowLargeFontActionsRemainReachableWithoutDraggingButtonsInShortWindow() {
        var visible by mutableStateOf(false)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var randomizations = 0
        var reversions = 0
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f),
                LocalLayoutDirection provides direction, LocalReducedMotion provides true) {
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
                                GlassActionButton(R.drawable.ic_seek_back, "Revert", onClick = {
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
            randomize.performTouchInput {
                if (layoutDirection == LayoutDirection.Ltr) swipeLeft() else swipeRight()
            }
            assertEquals(beforeDrag, randomize.getUnclippedBoundsInRoot())
            compose.runOnIdle { assertEquals(clicksBeforeDrag, randomizations) }
            randomize.performClick()
            assertWholeLabel("Randomize"); assertWholeLabel("Revert")
            val expandedHeight = compose.onNodeWithTag("actions").getUnclippedBoundsInRoot().let { it.bottom - it.top }
            compose.onNodeWithContentDescription("More actions").performScrollTo().assertIsEnabled()
                .performTouchInput { click(center) }
            val revert = compose.onNodeWithText("Revert")
            val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
            val button = revert.getUnclippedBoundsInRoot()
            assertTrue("The whole Revert action must be reachable", button.left >= viewport.left &&
                button.right <= viewport.right && button.top >= viewport.top && button.bottom <= viewport.bottom)
            revert.assertIsDisplayed().performClick()
            compose.onNodeWithText("Revert").assertDoesNotExist()
            assertEquals(expandedHeight, compose.onNodeWithTag("actions").getUnclippedBoundsInRoot().let { it.bottom - it.top })
            compose.onNodeWithContentDescription("Previous actions").performScrollTo().assertIsEnabled()
                .performTouchInput { click(center) }
            compose.onNodeWithText("Randomize").assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(2, randomizations); assertEquals(2, reversions) }
    }
}
