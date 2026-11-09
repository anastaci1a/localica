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
                        Box(Modifier.size(160.dp)) {
                            // Adversarial scene must never be sampled, recorded, or blended into the fill.
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.Magenta) }
                            LiquidGlassSurface(Modifier.size(120.dp).testTag("flat"), source = source,
                                cornerRadius = 16.dp, shadow = false, pressFeedback = false) {}
                        }
                    }
                }
            } }
            fun assertFill(expected: Color) {
                val pixels = compose.onNodeWithTag("flat").captureToImage().toPixelMap()
                for (y in 24 until pixels.height - 24) for (x in 24 until pixels.width - 24)
                    assertEquals("Opaque theme interior at $x,$y", expected.toArgb(), pixels[x, y].toArgb())
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
