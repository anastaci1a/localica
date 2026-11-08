package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.anastaci1a.localica.ui.engine.GlassShadowGeometry
import io.github.anastaci1a.localica.ui.engine.LocalUiClock
import io.github.anastaci1a.localica.ui.engine.UiClock
import io.github.anastaci1a.localica.ui.engine.glassShadows
import org.junit.Rule
import org.junit.Test

/** HardwareRenderer exists from API29; API26's original software oracle is not changed. */
class NativeShadowFieldConformanceTest {
    @get:Rule val compose = localicaComposeRule()

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun independentUncutFieldMatchesBareNativeAtBothOrigins() {
        var delta by mutableFloatStateOf(0f)
        var union by mutableStateOf(false)
        var background by mutableStateOf(Color.White)
        var statusPixels by mutableIntStateOf(0)
        lateinit var bodies: List<Pair<RoundRect, Float>>
        lateinit var clock: UiClock
        lateinit var view: android.view.View
        compose.setContent {
            val currentClock = LocalUiClock.current
            val currentView = LocalView.current
            SideEffect { clock = currentClock; view = currentView }
            val top = with(LocalDensity.current) { statusPixels.toDp() }
            Box(Modifier.padding(top = top)) {
                Box(Modifier.size(240.dp).background(background).testTag("uncut-field-control").drawBehind {
                    bodies = (if (union) listOf(60f, 110f) else listOf(60f)).map { y ->
                        RoundRect(60.dp.toPx() + .25f + delta, y.dp.toPx() + .75f + delta,
                            160.dp.toPx() + .25f + delta, (y + 40).dp.toPx() + .75f + delta,
                            CornerRadius(20.dp.toPx())) to 1f
                    }
                    // Bare on-screen control only. The independent reference never calls this kernel.
                    glassShadows(bodies, 1f, 1f, GlassShadowGeometry(), excludeInterior = false)
                })
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { statusPixels = checkNotNull(ViewCompat.getRootWindowInsets(view))
            .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            ShadowNativeCapture(compose, compose.onNodeWithTag("uncut-field-control")) { clock.stampNow.sequence }.use { native ->
                for (multiple in listOf(false, true)) for (shift in listOf(0f, .5f))
                    for (level in listOf(1f, .5f, .25f)) {
                        compose.runOnIdle { union = multiple; delta = shift; background = Color(level, level, level) }
                        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                        val actual = native.capture { bounds -> ShadowDrawState(clock.stampNow.sequence, 0,
                            shadowScreenBodies(bodies, bounds.topLeft), background) }
                        val state = actual.nativeWindows.single().state
                        val expected = nativeShadowFieldReference(actual.image.width, actual.image.height,
                            state.bodies, compose.density.density, state.strength, state.spread, state.background.toArgb())
                        val blank = ImageBitmap(expected.width, expected.height)
                        CanvasDrawScope().draw(compose.density, LayoutDirection.Ltr, Canvas(blank),
                            Size(blank.width.toFloat(), blank.height.toFloat())) { drawRect(state.background) }
                        val scene = checkNotNull(actual.sceneBounds)
                        val probe = ShadowFieldProbe(listOf(Rect(scene.left, scene.top + 10f, scene.right, scene.bottom)), 1f / 255f)
                        val reference = actual.copy(image = expected, offscreen = true)
                        val result = probe.compare(actual, reference, actual.copy(image = blank, offscreen = true))
                        val label = "uncut-field-${if (multiple) "union" else "single"}-$shift-$level"
                        shadowOracleEvidence(label, actual, reference, result)
                        // Declared BEFORE execution: <=1/255 over the ENTIRE existing scene ROI, zero missing coverage.
                        probe.assertWithin(result, 1f / 255f, label)
                    }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
