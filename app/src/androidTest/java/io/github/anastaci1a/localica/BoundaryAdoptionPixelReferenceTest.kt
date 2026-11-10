package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Identical test-only scheduling on independently frozen production arms.
 * These are declared live/retained settled fixtures, never a first-presented-frame claim. */
class BoundaryAdoptionPixelReferenceTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun NormalLive() = fixture(true, GlassRendererMode.Normal)
    @Test fun NormalRetained() = fixture(false, GlassRendererMode.Normal)
    @Test fun SingleToneBlurLive() = fixture(true, GlassRendererMode.SingleToneBlur)
    @Test fun SingleToneBlurRetained() = fixture(false, GlassRendererMode.SingleToneBlur)
    @Test fun FlatNoneLive() = fixture(true, GlassRendererMode.FlatNone)
    @Test fun FlatNoneRetained() = fixture(false, GlassRendererMode.FlatNone)

    private fun fixture(live: Boolean, mode: GlassRendererMode) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) =
                base.getSharedPreferences("legacy-controlled-740", mode)
        }
        val raw = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
        raw.edit().clear().putString("glass-renderer", "None").commit()
        val appearance = InterfacePreferences(context).read().glass.copy(renderer = mode)
        val pin = LiveShadowBranchPin()
        val shadows = ShadowStates()
        var dark by mutableStateOf(false)
        var opacity by mutableFloatStateOf(1f)
        var owner: LyricsBackdropScene? = null
        lateinit var source: FrostedBackdrop
        val branch = if (live) "live" else "retained"
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: base.getExternalFilesDir(null)!!
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                val tables = remember { mutableSetOf<CompositionData>() }
                shadows.bind(currentComposer.compositionData, tables)
                pin.bind(currentComposer.compositionData, tables)
                CompositionLocalProvider(LocalInspectionTables provides tables, LocalDensity provides Density(1f),
                    LocalGlassRendererRuntime provides remember { GlassRendererRuntime(appearance.renderer) },
                    LocalGlassAppearance provides appearance) {
                    LocalicaTheme { GlassPanelTheme(dark) {
                        source = rememberFrostedBackdrop(Unit, dark = dark)
                        val observe = Modifier.drawWithContent {
                            if (live) pin.pinApplied()
                            shadows.collect()
                            drawContent()
                            if (live) pin.assertLive()
                        }
                        Box(Modifier.size(320.dp, 400.dp).testTag("controlled-reference").then(observe)) {
                            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                                drawRect(Color(0xFFF4A020))
                                for (x in 0 until 32) drawRect(if (x % 2 == 0) Color(0xFF1030E0) else Color(0xFFD02050),
                                    Offset(x * 10f, 120f), Size(10f, 200f))
                            }
                            CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    LiquidGlassSurface(Modifier.size(200.dp, 56.dp), cornerRadius = 28.dp, pressFeedback = false) {}
                                    LyricsBackdrop("boundary", Modifier.size(240.dp, 220.dp), contentAlpha = { opacity }) {
                                        val scene = requireNotNull(LocalLyricsBackdropScene.current)
                                        SideEffect {
                                            check(owner == null || owner === scene) { "Lyrics owner changed during boundary updates" }
                                            owner = scene
                                        }
                                        Canvas(Modifier.fillMaxSize().recordLyricsLayer(scene, { opacity }, { dark })) {
                                            for (y in 0 until 22) drawRect(if (y % 2 == 0) Color.Green else Color.Red,
                                                Offset(0f, y * 10f), Size(240f, 5f))
                                        }
                                        CompositionLocalProvider(LocalFrostedBackdrop provides scene.source) {
                                            LiquidGlassSurface(Modifier.padding(top = 120.dp).size(180.dp, 56.dp),
                                                cornerRadius = 28.dp, pressFeedback = false) {}
                                        }
                                    }
                                }
                            }
                        }
                    } }
                }
            }
            fun frame() {
                if (live) advanceLiveShadowFrame(compose, pin)
                else { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                compose.runOnIdle { shadows.collect() }
            }
            // Identical bounded warm-up, independent of observed pixel values.
            repeat(64) { frame() }
            for (isDark in listOf(false, true)) {
              compose.runOnIdle { owner = null }
              for (alpha in listOf(1f, .5f, 0f, 1f)) {
                compose.runOnIdle { dark = isDark; opacity = alpha }
                repeat(8) { frame() }
                if (!live) compose.waitUntil(10_000) { compose.runOnIdle { shadows.prepared() } }
                compose.runOnIdle {
                    assertTrue(mode == GlassRendererMode.FlatNone || source.recorded)
                    if (mode != GlassRendererMode.FlatNone) assertEquals(source.rendererGeneration, source.recordedGeneration)
                    if (Build.VERSION.SDK_INT < 33 && mode != GlassRendererMode.FlatNone) assertNotNull(source.snapshot)
                    assertTrue("The fixture must contain actual production shadows", shadows.count >= 1)
                    if (live) pin.assertLive() else assertTrue(shadows.prepared())
                }
                val before = compose.runOnIdle { shadows.receipt(live) }
                val image = compose.onNodeWithTag("controlled-reference").captureToImage()
                val after = compose.runOnIdle { shadows.receipt(live) }
                assertEquals("Native copy cannot cross a shadow branch or key", before.toString(), after.toString())
                val prefix = "boundary-${mode.name}-$branch-$isDark-${compose.mainClock.currentTime}"
                File(root, "$prefix.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(root, "$prefix.json").writeText(JSONObject().put("api", Build.VERSION.SDK_INT)
                    .put("branch", branch).put("dark", isDark).put("alpha", alpha).put("mode", mode.name).put("clockMs", compose.mainClock.currentTime)
                    .put("rendererGeneration", source.rendererGeneration.toString())
                    .put("recordedGeneration", source.recordedGeneration.toString())
                    .put("sourceReady", source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null))
                    .put("shadowsBefore", before).put("shadowsAfter", after).toString(2))
              }
            }
        } finally {
            compose.mainClock.autoAdvance = true
            raw.edit().clear().commit()
            base.deleteSharedPreferences("legacy-controlled-740")
        }
    }

    /** Observe actual production owners/keys; no raster, geometry, shader or publication mutation. */
    private class ShadowStates {
        private lateinit var root: CompositionData
        private var tables: Set<CompositionData>? = null
        private val seen = mutableListOf<RetainedGlassShadow>()
        private val requested = RetainedGlassShadow::class.java.getDeclaredField("requested").apply { isAccessible = true }
        private val raster = RetainedGlassShadow::class.java.getDeclaredField("raster\$delegate").apply { isAccessible = true }
        val count get() = seen.size
        fun bind(root: CompositionData, tables: Set<CompositionData>?) { this.root = root; this.tables = tables }
        fun collect() {
            fun visit(table: CompositionData) {
                for (group in table.compositionGroups) {
                    for (value in group.data) if (value is RetainedGlassShadow && seen.none { it === value }) seen += value
                    visit(group)
                }
            }
            visit(root)
            tables?.toList()?.filter { it !== root }?.forEach(::visit)
        }
        private fun current(owner: RetainedGlassShadow): Any? = (raster.get(owner) as State<*>).value
        private fun matches(owner: RetainedGlassShadow): Boolean {
            val prepared = current(owner) ?: return false
            val key = prepared.javaClass.getDeclaredField("key").apply { isAccessible = true }.get(prepared)
            return requested.get(owner) == key
        }
        fun prepared() = seen.size >= 1 && seen.all { it.rasterizations > 0 && matches(it) }
        fun receipt(live: Boolean): JSONArray = JSONArray().apply {
            seen.forEachIndexed { index, owner ->
                if (live) check(owner.rasterizations == 0 && current(owner) == null)
                else check(owner.rasterizations > 0 && matches(owner))
                put(JSONObject().put("owner", index).put("branch", if (live) "live" else "retained")
                    .put("rasterizations", owner.rasterizations).put("requestedKey", requested.get(owner).toString())
                    .put("retainedKeyMatches", matches(owner)))
            }
        }
    }
}
