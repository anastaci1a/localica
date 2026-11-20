package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the shared material paths with real controls; content stays sharp. */
class GlassRenderModesTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun popupMaterialSamplesItsPhaseInDrawingAndMatchesStaticMaterials() {

        val phase = mutableFloatStateOf(0f)
        var compositions = 0
        lateinit var source: FrostedBackdrop
        compose.setContent {  LocalicaTheme {

                SideEffect { compositions++ }
                source = rememberFrostedBackdrop(Unit, dark = false)
                Box(Modifier.size(240.dp, 300.dp)) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        repeat(8) { x -> drawRect(if (x % 2 == 0) Color.Red else Color.Blue,
                            Offset(x * size.width / 8, 0f), Size(size.width / 8, size.height)) }
                    }
                    // Both lenses need the same real scene beyond their padded optical bounds.
                    Column(Modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PopupMaterialReference(source, phase)
                        LiquidGlassSurface(Modifier.fillMaxWidth().height(96.dp).testTag("phase-material"), source,
                            pressFeedback = false, shadow = false, presentedRadius = { (12 + 16 * phase.floatValue).dp }, presentation = {
                                val p = phase.floatValue
                                GlassSurfacePresentation(p, 1f - p, 0f, Color.Transparent, 1f)
                            })
                    }
                }

        }  }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        run {
            compose.waitForIdle()
            val settled = compositions
            for (p in listOf(0f, .4f, 1f, .4f, 0f)) {
                compose.runOnIdle { phase.floatValue = p }
                compose.waitForIdle()
                val reference = compose.onNodeWithTag("static-material").captureToImage().toPixelMap()
                val actual = compose.onNodeWithTag("phase-material").captureToImage().toPixelMap()
                assertEquals(reference.width, actual.width)
                assertEquals(reference.height, actual.height)
                for (y in 0 until actual.height step 7) for (x in 0 until actual.width step 7) {
                    val a = actual[x, y]; val b = reference[x, y]
                    assertEquals("Red default p=$p ($x,$y)", b.red, a.red, .025f)
                    assertEquals("Green default p=$p ($x,$y)", b.green, a.green, .025f)
                    assertEquals("Blue default p=$p ($x,$y)", b.blue, a.blue, .025f)
                    assertEquals("Alpha default p=$p ($x,$y)", b.alpha, a.alpha, .025f)
                }
                assertEquals("Draw-time phase must not recompose the material owner", settled, compositions)
            }
        }
    }

    @Test fun switchingAndReversingNeverDropsTheBackingOrReplacesTheSurface() {

        var stage by mutableIntStateOf(0)
        lateinit var view: android.view.View
        lateinit var source: FrostedBackdrop
        val owners = mutableSetOf<Int>()
        compose.setContent {  LocalicaTheme {
            CompositionLocalProvider( LocalGlassSurfaceDrawObserver provides { glassDrawProbe?.invoke(it); (it as? GlassSurfaceDrawObservation)?.let { o -> owners += o.surfaceIdentity } }) {
                view = androidx.compose.ui.platform.LocalView.current
                source = rememberFrostedBackdrop(Unit, dark = false)
                Box(Modifier.size(180.dp).testTag("switch-frame")) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        drawRect(Color(0xFFFF8080))
                        drawRect(Color(0xFF8080FF), Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
                    }
                    LiquidGlassSurface(Modifier.fillMaxSize(), source, cornerRadius = 12.dp,
                        pressFeedback = false, shadow = false)
                    PresentationMarker { stage }
                }
            }
        }  }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        compose.mainClock.autoAdvance = false
        try {
            repeat(3) {
                repeat(12) {
                    val token = compose.runOnIdle { stage = stage % 30 + 1; stage }
                    compose.mainClock.advanceTimeByFrame()
                    val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("switch-frame"), token) { view }
                    val c = pixels[pixels.width / 3, pixels.height / 2]
                    assertTrue("Backing vanished during mode redraw frame=$it color=$c", c.red > .35f && c.blue > .35f)
                    assertEquals("Recording follows the renderer: Default frame=$it", true,
                        compose.runOnIdle { source.recorded })
                }
            }
            compose.runOnIdle { assertEquals("Mode changes retain the existing surface", 1, owners.size) }
        } finally { compose.mainClock.autoAdvance = true }
    }
}

@Composable private fun PopupMaterialReference(source: FrostedBackdrop, phase: androidx.compose.runtime.State<Float>) {
    val p = phase.value
    LiquidGlassSurface(Modifier.fillMaxWidth().height(96.dp).testTag("static-material"), source,
        cornerRadius = (12 + 16 * p).dp, frostProgress = p, refraction = 1f - p, depthScale = 1f,
        pressFeedback = false, shadow = false)
}
