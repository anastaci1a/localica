package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class FlatNoneMaterialTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun flatAndNormalControlsHaveIdenticalPressDragAndReleaseGeometry() {
        val normal = GlassRendererRuntime(GlassRendererMode.Normal)
        val flat = GlassRendererRuntime(GlassRendererMode.FlatNone)
        val geometry = mutableMapOf<String, LensGeometry>()
        var normalValue by mutableFloatStateOf(.5f)
        var flatValue by mutableFloatStateOf(.5f)
        var normalChecked by mutableStateOf(false)
        var flatChecked by mutableStateOf(false)
        lateinit var source: FrostedBackdrop
        compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides normal, LocalDensity provides Density(1f)) {
            LocalicaTheme {
                source = rememberFrostedBackdrop(Unit, dark = false)
                BackdropLease(source)
                Box(Modifier.size(300.dp, 240.dp)) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color(0xFFE0E0E0)) }
                    Column {
                        CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                            CompositionLocalProvider(LocalControlGeometryDraw provides { geometry["normal-slider"] = it }) {
                                InterfaceSlider(normalValue, { normalValue = it }, Modifier.width(280.dp).testTag("normal-slider"))
                            }
                            CompositionLocalProvider(LocalControlGeometryDraw provides { geometry["normal-switch"] = it }) {
                                Box(Modifier.size(60.dp, 48.dp).testTag("normal-switch")) { InterfaceSwitch(normalChecked) { normalChecked = it } }
                            }
                        }
                        CompositionLocalProvider(LocalGlassRendererRuntime provides flat, LocalFrostedBackdrop provides null) {
                            CompositionLocalProvider(LocalControlGeometryDraw provides { geometry["flat-slider"] = it }) {
                                InterfaceSlider(flatValue, { flatValue = it }, Modifier.width(280.dp).testTag("flat-slider"))
                            }
                            CompositionLocalProvider(LocalControlGeometryDraw provides { geometry["flat-switch"] = it }) {
                                Box(Modifier.size(60.dp, 48.dp).testTag("flat-switch")) { InterfaceSwitch(flatChecked) { flatChecked = it } }
                            }
                        }
                    }
                }
            }
        } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            for (kind in listOf("slider", "switch")) {
                fun trace(prefix: String): List<LensGeometry> {
                    compose.mainClock.advanceTimeBy(1200)
                    val frames = mutableListOf<LensGeometry>()
                    fun receipt() = compose.runOnIdle { frames.add(requireNotNull(geometry["$prefix-$kind"])) }
                    receipt()
                    compose.onNodeWithTag("$prefix-$kind").performTouchInput { down(center) }
                    repeat(12) { compose.mainClock.advanceTimeByFrame(); receipt() }
                    compose.runOnIdle { assertTrue("$prefix $kind must expand on contact", frames.last().bounds.height > 28f) }
                    compose.onNodeWithTag("$prefix-$kind").performTouchInput { moveTo(Offset(width * .8f, centerY)) }
                    repeat(8) { compose.mainClock.advanceTimeByFrame(); receipt() }
                    compose.onNodeWithTag("$prefix-$kind").performTouchInput { up() }
                    repeat(40) { compose.mainClock.advanceTimeByFrame(); receipt() }
                    return frames
                }
                // Two simultaneous pointers trigger the controls' multi-touch cancellation.
                // Replay one identical single-pointer contact per control instead.
                val expected = trace("normal")
                val actual = trace("flat")
                expected.zip(actual).forEachIndexed { frame, (normalFrame, flatFrame) ->
                    assertEquals("$kind frame $frame bounds", normalFrame.bounds, flatFrame.bounds)
                    assertEquals("$kind frame $frame radius", normalFrame.corner, flatFrame.corner, 0f)
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun rendererChangesKeepUnsavedContentMounted() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        var mounts = 0
        compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) {
                LocalicaTheme {
                    remember { mounts++; Any() }
                    var draft by remember { mutableStateOf("") }
                    androidx.compose.material3.OutlinedTextField(draft, { draft = it }, Modifier.testTag("atlas-draft"))
                }
        } }
        compose.onNodeWithTag("atlas-draft").performTextInput("Unsaved mode change")
        for (mode in listOf(GlassRendererMode.FlatNone, GlassRendererMode.Normal)) {
            compose.runOnIdle { runtime.request(mode) }
            compose.onNodeWithTag("atlas-draft").assertTextContains("Unsaved mode change")
            compose.runOnIdle { assertEquals("Mode changes cannot recreate window content", 1, mounts) }
        }
    }

    @Test fun opaqueThemePixelsAndModeTransitionsDoNoGlassWork() {
        var dark by mutableStateOf(false)
        var referenceOnly by mutableStateOf(false)
        val runtime = GlassRendererRuntime(GlassRendererMode.FlatNone)
        val events = mutableListOf<String>()
        lateinit var source: FrostedBackdrop
        compose.runOnUiThread { GlassWork.observer = { events.add(it) } }
        try {
            compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime, LocalDensity provides Density(1f)) {
                LocalicaTheme {
                    GlassPanelTheme(dark) {
                        source = rememberFrostedBackdrop(Unit)
                        val reference = flatGlassColor()
                        val appearance = LocalGlassAppearance.current.normalized()
                        val referenceTone = GlassTone.mixed(appearance.contrast, appearance.contrastWeight, source.lightMix)
                        assertEquals(if (dark) Color(0xFF242528) else Color.White, reference)
                        Box(Modifier.size(160.dp, 420.dp).testTag("flat-stage")) {
                            // Adversarial scene must never be sampled, recorded, or blended into the fill.
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.Magenta) }
                            if (!referenceOnly) LiquidGlassSurface(Modifier.size(120.dp).testTag("flat"), source = source,
                                cornerRadius = 16.dp, shadow = false, pressFeedback = false) {}
                            else Canvas(Modifier.size(120.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
                                drawRect(reference)
                                drawGlassRim(androidx.compose.ui.geometry.Rect(Offset.Zero, size), 16.dp.toPx(), tone = referenceTone)
                            }
                            // The required structural rim remains part of the material.
                            // Compare against an opaque theme draw plus that same finish.
                            Canvas(Modifier.offset(y = 140.dp).size(120.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
                                drawRect(reference)
                                drawGlassRim(androidx.compose.ui.geometry.Rect(Offset.Zero, size), 16.dp.toPx(), tone = referenceTone)
                            }
                            Canvas(Modifier.offset(y = 280.dp).size(120.dp)) { drawRect(reference) }
                        }
                    }
                }
            } }
            fun assertFill(expected: Color) {
                val image = compose.onNodeWithTag("flat-stage").captureToImage()
                val pixels = image.toPixelMap()
                val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                    ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
                File(root, "flat-fill-" + expected.toArgb() + ".png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                // Keep the independent finish at the identical physical coordinates.
                // The translated same-frame reference remains in both saved images.
                compose.runOnIdle { referenceOnly = true }
                val referenceImage = compose.onNodeWithTag("flat-stage").captureToImage()
                val referencePixels = referenceImage.toPixelMap()
                File(root, "flat-fill-" + expected.toArgb() + "-reference.png").outputStream().use {
                    referenceImage.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                compose.runOnIdle { referenceOnly = false }
                for (y in 24 until 96) for (x in 24 until 96)
                    assertEquals("Opaque theme interior at $x,$y", referencePixels[x, y].toArgb(), pixels[x, y].toArgb())
                assertEquals("The uncoated reference is the specified opaque theme", expected.toArgb(), pixels[60, 340].toArgb())
                assertEquals("The material center retains the opaque theme", pixels[60, 340].toArgb(), pixels[60, 60].toArgb())
                compose.runOnIdle {
                    assertFalse(source.hasGlassLayer)
                    assertEquals(0, source.consumers)
                    assertFalse(source.recorded)
                    assertNull(source.capture)
                    assertEquals(emptyList<String>(), events)
                }
            }
            assertFill(Color.White)
            compose.runOnIdle { dark = true }
            assertFill(Color(0xFF242528))
            compose.runOnIdle { runtime.request(GlassRendererMode.Normal) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue("Positive control must allocate glass", events.contains("layer"))
                runtime.request(GlassRendererMode.FlatNone)
            }
            compose.waitForIdle()
            compose.runOnIdle { events.clear() }
            assertFill(Color(0xFF242528))
        } finally { compose.runOnUiThread { GlassWork.observer = null } }
    }

}
