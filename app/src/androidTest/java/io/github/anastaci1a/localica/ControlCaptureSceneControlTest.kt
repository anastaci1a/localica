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

/** Two frozen physical positions and a separate lit software oracle; no causal waiver. */
class ControlCaptureSceneControlTest {
    @get:Rule val compose = createComposeRule()

    @Test fun frozenScenesMatchAcrossVerticalPositionsContentExtentFadeRestAndReactivation() {
        var stage by mutableIntStateOf(0)
        var captures = 0
        var referenceCaptures = 0
        var draws = 0
        val runtime = GlassRendererRuntime(GlassRendererMode.Legacy)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalGlassRendererRuntime provides runtime) {
                Column {
                    // The same closures feed frozen scenes in the original two physical positions.
                    val geometry: androidx.compose.ui.graphics.drawscope.DrawScope.() -> LensGeometry = {
                        LensGeometry(Rect(24f, 12f, if (stage == 3) 92f else 88f, 52f), 20f)
                    }
                    val active = { when (stage) { 0, 6 -> 0f; 4 -> .4f; else -> 1f } }
                    val body = { GlassShape(depth = if (stage == 4) -.08f else 0f) }
                    val scene: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
                        if (stage != 2) drawRect(if (stage % 2 == 0) Color.Red else Color.Blue,
                            topLeft = Offset(0f, 22f), size = Size(120f, 12f))
                    }
                    Box(Modifier.size(120.dp, 64.dp).background(Color.White)) {
                        CompositionLocalProvider(LocalOriginalControlLensDraw provides { draws++ }) {
                            OriginalControlCaptureScene(Modifier.fillMaxSize().testTag("candidate"), source = null,
                                geometry = geometry, body = body, active = active,
                                onCapture = { captures++ }, scene = scene)
                        }
                    }
                    Box(Modifier.size(120.dp, 64.dp).background(Color.White)) {
                        OriginalControlCaptureScene(Modifier.fillMaxSize().testTag("reference"), source = null,
                            geometry = geometry, body = body, active = active,
                            onCapture = { referenceCaptures++ }, scene = scene)
                    }
                }
            }
        }
        var stableCaptures = 0
        var stableDraws = 0
        var stableReferences = 0
        for (next in 0..7) {
            compose.runOnIdle { stage = next }
            val actual = compose.onNodeWithTag("candidate").captureToImage()
            val reference = compose.onNodeWithTag("reference").captureToImage()
            equalPixels(actual, reference, "stage $next")
            compose.runOnIdle {
                when (next) {
                    0 -> assertEquals(0, captures)
                    1 -> { assertTrue(draws > 0); stableCaptures = captures; stableDraws = draws; stableReferences = referenceCaptures }
                    2 -> {
                        assertTrue(draws > stableDraws)
                        assertTrue(referenceCaptures > stableReferences)
                        assertTrue("Frozen actual slot retains fresh capture", captures > stableCaptures)
                    }
                    3 -> assertTrue("Extent change allocates", captures > stableCaptures)
                    6 -> { stableCaptures = captures; stableDraws = draws }
                    7 -> { assertTrue("Reactivation draws", draws > stableDraws); assertTrue("Reactivation owns a fresh capture", captures > stableCaptures) }
                }
                android.util.Log.i("ControlCaptureWork", "control=frozen-spatial stage=$next api=${Build.VERSION.SDK_INT} captures=$captures referenceCaptures=$referenceCaptures draws=$draws")
            }
        }
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
