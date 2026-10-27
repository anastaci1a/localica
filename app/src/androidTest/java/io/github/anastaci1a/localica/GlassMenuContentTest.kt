package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Controlled viewport regression, not an optical model of Liquid Glass.
 * Both columns draw the same deliberate child overflow in the same captured frame;
 * only padding/scroll order differs. Real caller/material acceptance remains separate. */
class GlassMenuContentTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var sampleView: View
    private var density = 1f

    @Test fun fittingMenuPreservesGeometryAndTargetsWithoutInsetShadowCuts() {
        val activated = mutableListOf<String>()
        compose.setContent {
            val view = LocalView.current
            val currentDensity = LocalDensity.current.density
            SideEffect { sampleView = view; density = currentDensity }
            CompositionLocalProvider(LocalPopupScrollEdges provides true) {
                Box(Modifier.width(256.dp).background(Color.White).testTag("comparison")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.width(120.dp).clip(RoundedCornerShape(16.dp)).testTag("legacy")) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                .controlVerticalScroll(rememberScrollState())) {
                                TestRow("legacy-row") { activated += "legacy" }
                            }
                        }
                        Box(Modifier.width(120.dp).clip(RoundedCornerShape(16.dp)).testTag("shared")) {
                            GlassMenuContent(Modifier.fillMaxWidth()) {
                                TestRow("shared-row") { activated += "shared" }
                            }
                        }
                    }
                    PresentationMarker { 1 }
                }
            }
        }
        val legacy = compose.onNodeWithTag("legacy").fetchSemanticsNode().boundsInRoot
        val shared = compose.onNodeWithTag("shared").fetchSemanticsNode().boundsInRoot
        val legacyRow = compose.onNodeWithTag("legacy-row").fetchSemanticsNode().boundsInRoot
        val sharedRow = compose.onNodeWithTag("shared-row").fetchSemanticsNode().boundsInRoot
        assertEquals(legacy.size, shared.size)
        assertEquals(legacyRow.translate(-legacy.topLeft), sharedRow.translate(-shared.topLeft))

        val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("comparison"), 1) { sampleView }
        val oldX = (60f * density).roundToInt()
        val newX = (196f * density).roundToInt()
        val margin = (4f * density).roundToInt()
        for (y in listOf(margin, pixels.height - margin - 1)) {
            assertEquals("Old inset clip removes child overflow", 1f, pixels[oldX, y].green, .02f)
            assertEquals("Shared viewport retains the same overflow", 0f, pixels[newX, y].green, .02f)
            assertEquals("Retained child overflow is red", 1f, pixels[newX, y].red, .02f)
        }
        // Overflow drawing is not a new action target. The original 48dp target remains.
        compose.onNodeWithTag("shared").performTouchInput { click(Offset(center.x, 4f * density)) }
        compose.runOnIdle { assertTrue(activated.isEmpty()) }
        compose.onNodeWithTag("legacy-row").performTouchInput { click(center) }
        compose.onNodeWithTag("shared-row").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf("legacy", "shared"), activated) }
    }

    @Test fun constrainedMenuRetainsScrollRangeAndLastActionReachability() {
        val activated = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalPopupScrollEdges provides true) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.width(120.dp).height(120.dp).padding(vertical = 8.dp)
                        .controlVerticalScroll(rememberScrollState()).testTag("old-scroll")) {
                        repeat(6) { index -> TestRow("old-$index") { activated += "old-$index" } }
                    }
                    GlassMenuContent(Modifier.width(120.dp).height(120.dp).testTag("new-scroll")) {
                        repeat(6) { index -> TestRow("new-$index") { activated += "new-$index" } }
                    }
                }
            }
        }
        val oldRange = compose.onNodeWithTag("old-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        val newRange = compose.onNodeWithTag("new-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle {
            assertEquals(oldRange.maxValue(), newRange.maxValue(), 1f)
            assertTrue(newRange.maxValue() > 0f)
        }
        compose.onNodeWithTag("old-5").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("new-5").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("old-5", "new-5"), activated) }
        compose.onNodeWithTag("new-0").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("new-0", activated.last()) }
    }

    @Composable private fun TestRow(tag: String, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Box(Modifier.fillMaxWidth().height(48.dp).drawBehind {
                val reach = 10.dp.toPx()
                drawRect(Color.Red, Offset(0f, -reach), Size(size.width, size.height + 2f * reach))
            }.background(Color.Blue).testTag(tag).clickable(onClick = onClick))
        }
    }
}
