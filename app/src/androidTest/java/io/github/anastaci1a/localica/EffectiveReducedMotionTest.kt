package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Test

/** Controlled context coverage complements the separate real-window/system observer test. */
@OptIn(ExperimentalTestApi::class)
class EffectiveReducedMotionTest {
    @Test fun liveInheritedPolicyPreservesSelectionAndCancellation() {
        val scale = object : MotionDurationScale {
            override var scaleFactor by mutableFloatStateOf(1f)
        }
        runComposeUiTest(effectContext = scale) {
            var appReduced by mutableStateOf(false)
            var selected by mutableIntStateOf(0)
            var effective = true
            setContent {
                val reduced = rememberEffectiveReducedMotion(appReduced)
                SideEffect { effective = reduced }
                LocalicaTheme {
                    CompositionLocalProvider(LocalReducedMotion provides reduced) {
                        Box(Modifier.width(280.dp)) {
                            GlassChoices(listOf("First", "Second"), selected) { selected = it }
                        }
                    }
                }
            }
            runOnIdle { assertFalse(effective); scale.scaleFactor = 0f }
            runOnIdle { assertTrue(effective) }
            onNodeWithText("Second").performClick().assertIsSelected()
            runOnIdle { assertEquals(1, selected); appReduced = true; scale.scaleFactor = 1f }
            runOnIdle { assertTrue("App preference survives system re-enable", effective) }
            onNodeWithText("First").performClick().assertIsSelected()
            onNodeWithText("First").performTouchInput {
                down(center); moveBy(Offset(0f, height.toFloat())); cancel()
            }
            runOnIdle { assertEquals("Cancelled drag preserves selection", 0, selected); scale.scaleFactor = 0f }
            runOnIdle { assertTrue(effective); appReduced = false }
            runOnIdle { assertTrue("System preference survives app re-enable", effective) }
            onNodeWithText("First").performTouchInput {
                down(center); moveBy(Offset(0f, height.toFloat())); up()
            }
            runOnIdle { assertEquals("Reduced motion preserves drag selection", 1, selected); scale.scaleFactor = .5f }
            runOnIdle { assertFalse("Positive scale retains normal policy", effective) }
            onNodeWithText("Second").performClick().assertIsSelected()
        }
    }
}
