package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        fun equal(kind: String, phase: String) = compose.runOnIdle {
            val expected = requireNotNull(geometry["normal-$kind"])
            val actual = requireNotNull(geometry["flat-$kind"])
            assertEquals("$kind $phase bounds", expected.bounds, actual.bounds)
            assertEquals("$kind $phase radius", expected.corner, actual.corner, 0f)
        }
        try {
            for (kind in listOf("slider", "switch")) {
                equal(kind, "rest")
                for (prefix in listOf("normal", "flat")) compose.onNodeWithTag("$prefix-$kind").performTouchInput { down(center) }
                repeat(12) { compose.mainClock.advanceTimeByFrame(); equal(kind, "press frame $it") }
                compose.runOnIdle { assertTrue("Flat $kind must expand on contact", requireNotNull(geometry["flat-$kind"]).bounds.height > 28f) }
                for (prefix in listOf("normal", "flat")) compose.onNodeWithTag("$prefix-$kind").performTouchInput { moveTo(Offset(width * .8f, centerY)) }
                repeat(8) { compose.mainClock.advanceTimeByFrame(); equal(kind, "drag frame $it") }
                for (prefix in listOf("normal", "flat")) compose.onNodeWithTag("$prefix-$kind").performTouchInput { up() }
                repeat(40) { compose.mainClock.advanceTimeByFrame(); equal(kind, "release frame $it") }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun disablingTheWindowAtlasKeepsUnsavedContentMounted() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        var mounts = 0
        compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) {
            io.github.anastaci1a.localica.ui.components.NoneReductionAtlasHost(GlassColorShader) {
                LocalicaTheme {
                    remember { mounts++; Any() }
                    var draft by remember { mutableStateOf("") }
                    androidx.compose.material3.OutlinedTextField(draft, { draft = it }, Modifier.testTag("atlas-draft"))
                }
            }
        } }
        compose.onNodeWithTag("atlas-draft").performTextInput("Unsaved mode change")
        for (mode in listOf(GlassRendererMode.FlatNone, GlassRendererMode.SingleToneBlur, GlassRendererMode.FlatNone, GlassRendererMode.Normal)) {
            compose.runOnIdle { runtime.request(mode) }
            compose.onNodeWithTag("atlas-draft").assertTextContains("Unsaved mode change")
            compose.runOnIdle { assertEquals("Mode changes cannot recreate window content", 1, mounts) }
        }
    }

    @Test fun opaqueThemePixelsAndModeTransitionsDoNoGlassWork() {
        var dark by mutableStateOf(false)
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
                        assertEquals(if (dark) Color(0xFF242528) else Color.White, reference)
                        Box(Modifier.size(160.dp, 280.dp).testTag("flat-stage")) {
                            // Adversarial scene must never be sampled, recorded, or blended into the fill.
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.Magenta) }
                            LiquidGlassSurface(Modifier.size(120.dp).testTag("flat"), source = source,
                                cornerRadius = 16.dp, shadow = false, pressFeedback = false) {}
                            Canvas(Modifier.offset(y = 140.dp).size(120.dp)) { drawRect(reference) }
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
                // One native frame compares the material against an independently drawn opaque
                // theme reference on the same adversarial backing; no color tolerance.
                for (y in 24 until 96) for (x in 24 until 96)
                    assertEquals("Opaque theme interior at $x,$y", pixels[x, y + 140].toArgb(), pixels[x, y].toArgb())
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

    @Test fun migratedLegacyNoneAndCanonicalSingleToneBlurHaveIdenticalPixels() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "flat-none-migration-pixels"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        val raw = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
        try {
            raw.edit().clear().putString("glass-renderer", "None").commit()
            val legacy = InterfacePreferences(context).read().glass
            assertEquals(GlassRendererMode.SingleToneBlur, legacy.renderer)
            assertEquals("SingleToneBlur", raw.getString("glass-renderer", null))
            var appearance by mutableStateOf(legacy)
            lateinit var source: FrostedBackdrop
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f),
                    LocalGlassRendererRuntime provides remember { GlassRendererRuntime(legacy.renderer) },
                    LocalGlassAppearance provides appearance) {
                    source = rememberFrostedBackdrop(Unit, dark = false)
                    Box(Modifier.size(180.dp)) {
                        Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                            drawRect(Color(0xFFE02060))
                            drawRect(Color(0xFF1040E0), Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
                        }
                        LiquidGlassSurface(Modifier.padding(20.dp).size(140.dp).testTag("legacy"), source,
                            cornerRadius = 24.dp, shadow = false, pressFeedback = false) {}
                    }
                }
            } }
            compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
            val before = compose.onNodeWithTag("legacy").captureToImage()
            raw.edit().putString("glass-renderer", "SingleToneBlur").commit()
            compose.runOnIdle { appearance = InterfacePreferences(context).read().glass }
            val after = compose.onNodeWithTag("legacy").captureToImage()
            assertTrue("Legacy None migration must be byte-identical", before.asAndroidBitmap().sameAs(after.asAndroidBitmap()))
            val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: base.getExternalFilesDir(null)!!
            File(root, "legacy-none-migrated.png").outputStream().use { before.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { raw.edit().clear().commit(); base.deleteSharedPreferences(name) }
    }
}
