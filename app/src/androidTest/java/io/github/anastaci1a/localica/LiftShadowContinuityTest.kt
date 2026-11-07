package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Canvas as UiCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Single-owner lift only; neighbour deselection masks belong to #556. */
class LiftShadowContinuityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun selectionLiftShadowIsContinuousThroughBothEndpoints() {
        var selected by mutableStateOf(false)
        lateinit var selection: SelectionScene
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.Black,
                LocalGlassAppearance provides GlassAppearance(shadowStrength = 1f, shadowSpread = 1f)) {
                Box(Modifier.size(300.dp, 320.dp).background(Color.White).testTag("lift-shadow-scene")) {
                    ConnectedSelectionHost(Modifier.fillMaxSize()) {
                        selection = checkNotNull(LocalSelectionScene.current)
                        Box(Modifier.padding(horizontal = 40.dp, vertical = 80.dp)) {
                            Box(Modifier.fillMaxWidth().height(72.dp).selectionLens(selected, false, itemKey = "single"))
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val d = compose.density.density
        val probe = ShadowFrameProbe(Rect(130*d, 160*d, 170*d, 164*d))
        val reference = ShadowFrameProbe(Rect(130*d, 160*d, 170*d, 164*d))
        reference.observeLuminance(1f)
        val scene = compose.onNodeWithTag("lift-shadow-scene")
        probe.sample(scene.captureNativeFrame())
        for (target in listOf(true, false)) {
            compose.runOnIdle { selected = target }
            repeat(60) {
                compose.mainClock.advanceTimeByFrame()
                val image = scene.captureNativeFrame()
                val actual = probe.sample(image)
                val expectedImage = ImageBitmap(image.width, image.height)
                compose.runOnIdle {
                    CanvasDrawScope().draw(Density(d), LayoutDirection.Ltr, UiCanvas(expectedImage),
                        Size(image.width.toFloat(), image.height.toFloat())) {
                        drawRect(Color.White)
                        // The reference writes the complete alpha field directly,
                        // avoiding the software renderer's per-ring truncation.
                        drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
                        val body = selection.lastDrawnRegions.firstOrNull()
                        if (body != null) glassShadows(listOf(RoundRect(body.bounds, CornerRadius(body.corner ?: 0f)) to 1f),
                            selection.regions.values.single().visibility(), 1f, GlassShadowGeometry(), rasterizing = true)
                        drawContext.canvas.restore()
                    }
                }
                assertEquals("Lift matches its uninterrupted direct shadow", reference.sample(expectedImage), actual, 2f/255f)
                ShadowFrameProbe.assertSpreadEdge(image,
                    Rect(130*d, 186*d, 170*d, 188*d), Rect(130*d, 188*d, 170*d, 190*d), .02f, "single selection")
            }
        }
        assertTrue("The probe must observe the lift shadow", probe.luminances.min() < .97f)
        probe.assertContinuous(reference.maximumDelta + 2f/255f, "single selection lift")
    }
}
