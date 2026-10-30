package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Two continuously retained coincident owners and a separate lit software oracle. */
class ControlCaptureSceneControlTest {
    @get:Rule val compose = createComposeRule()

    @Test fun frozenScenesMatchAtCoincidentPositionAcrossContentExtentFadeRestAndReactivation() {
        exerciseCoincidentControlScenes(compose, frozenActual = true)
    }

    @Test fun litMutableMeshMatchesFrozenPixelsAndKeepsEarlierOutputIndependent() {
        val shape = LensGeometry(Rect(0f, 0f, 32f, 24f), 8f)
        val input = ImageBitmap(32, 24)
        val renderer = LensMeshRenderer()
        fun paint(color: Color) {
            input.asAndroidBitmap().eraseColor(android.graphics.Color.TRANSPARENT)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(input), Size(32f, 24f)) {
                drawRect(color, size = Size(16f, 24f))
            }
        }
        fun render(mutable: Boolean, original: Boolean = false): ImageBitmap {
            val output = ImageBitmap(32, 24)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(output), Size(32f, 24f)) {
                if (original) with(OriginalControlLensMesh()) {
                    draw(shape, input, IntSize(32, 24), Offset.Zero, .8f, lighting = true)
                } else with(renderer) {
                    draw(shape, input, IntSize(32, 24), Offset.Zero, .8f, lighting = true,
                        mutableSoftwareSnapshot = mutable)
                }
            }
            return output
        }
        paint(Color.Red)
        if (Build.VERSION.SDK_INT >= 29) {
            try { render(true); fail("Hardware mesh must reject mutable routing") }
            catch (e: IllegalArgumentException) {
                assertEquals("Mutable snapshots require synchronous software rasterization", e.message)
            }
            equalPixels(render(false), render(false, original = true), "lit immutable API>=29")
            return
        }
        val first = render(true)
        equalPixels(first, render(false, original = true), "lit A fresh reference")
        val pixels = first.toPixelMap()
        val retained = IntArray(pixels.width * pixels.height) { index ->
            pixels[index % pixels.width, index / pixels.width].toArgb()
        }
        paint(Color.Blue)
        equalPixels(render(true), render(false, original = true), "lit B fresh reference")
        val previous = first.toPixelMap()
        for (index in retained.indices) {
            assertEquals("Lit published A stays independent", retained[index],
                previous[index % previous.width, index / previous.width].toArgb())
        }
    }

    private fun equalPixels(actual: ImageBitmap, expected: ImageBitmap, label: String) =
        assertControlCapturePixels(actual, expected, label, "frozen-control")
}
