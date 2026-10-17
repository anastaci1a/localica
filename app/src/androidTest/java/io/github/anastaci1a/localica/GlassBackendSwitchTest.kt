package io.github.anastaci1a.localica

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassBackendSwitchTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(label: String) {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("captureGlassBackend") != "true") return
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("backend-captures"))
        assertTrue(androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            .takeScreenshot(java.io.File(directory, "$label.png")))
    }

    @Test fun rendererPickerExplainsUnsupportedRestoredChoices() {
        var value by mutableStateOf(GlassAppearance(renderer = GlassRendererMode.Modern))
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides value) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    GlassSettingsControls(value) { value = it }
                }
            }
        } }
        if (Build.VERSION.SDK_INT < 33) {
            compose.onNodeWithText("Modern").assertDoesNotExist()
            compose.onNodeWithText("Using Legacy. Modern requires Android 13 or newer.").assertIsDisplayed()
        } else compose.onNodeWithText("Modern").assertIsDisplayed()
        compose.onNodeWithText("Legacy").performClick()
        compose.runOnIdle { assertEquals(GlassRendererMode.Legacy, value.renderer) }
    }

    @Test fun rendererPreferencePersistsAndUnknownValuesUseAutomatic() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = InterfacePreferences(context)
        val original = preferences.read()
        try {
            for (mode in GlassRendererMode.entries) {
                preferences.write(original.copy(glass = original.glass.copy(renderer = mode)))
                assertEquals(mode, InterfacePreferences(context).read().glass.renderer)
            }
            context.getSharedPreferences("interface", Context.MODE_PRIVATE).edit()
                .putString("glass-renderer", "future-mode").commit()
            assertEquals(GlassRendererMode.Automatic, preferences.read().glass.renderer)
        } finally { preferences.write(original) }
    }

    @Test fun switchingUpdatesNestedCapturesControlsSelectionsAndRetainedPopups() {
        var mode by mutableStateOf(GlassRendererMode.Automatic)
        var background by mutableStateOf(Color.Red)
        var open by mutableStateOf(false)
        var slider by mutableFloatStateOf(.5f)
        var checked by mutableStateOf(false)
        var controlBackend: GlassBackend? = null
        lateinit var source: FrostedBackdrop
        lateinit var nested: FrostedBackdrop
        var popup: FrostedBackdrop? = null
        compose.setContent {
            val policy = rememberUpdatedState(effectiveGlassBackend(mode, Build.VERSION.SDK_INT))
            LocalicaTheme {
                CompositionLocalProvider(LocalGlassBackend provides policy,
                    LocalGlassAppearance provides GlassAppearance(blur = 0f, whiteTint = 0f, contrast = 0f,
                        renderer = mode, tabStyle = TabLensStyle.Overlay, overlayForeground = true)) {
                    source = rememberFrostedBackdrop(background, dark = false)
                    BackdropLease(source)
                    Box(Modifier.fillMaxSize()) {
                        Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(background) }
                        CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                            Column(Modifier.width(320.dp).padding(12.dp)) {
                                FrostedSurface(Modifier.fillMaxWidth().height(100.dp), shadow = false) {
                                    nested = LocalFrostedBackdrop.current!!
                                    LiquidGlassSurface(Modifier.padding(12.dp).size(180.dp, 52.dp)) {
                                        Text("Persistent foreground", color = Color.Black,
                                            modifier = Modifier.align(androidx.compose.ui.Alignment.Center).testTag("backend-ink"))
                                    }
                                }
                                Box(Modifier.fillMaxWidth().height(72.dp)) {
                                    SlidingTabs(listOf(MainTab.Library, MainTab.Albums), MainTab.Library, { 0f }) {}
                                }
                                CompositionLocalProvider(LocalControlLensDraw provides { controlBackend = it }) {
                                    InterfaceSlider(slider, { slider = it }, Modifier.testTag("backend-slider"))
                                    BooleanPreference("Backend switch", checked) { checked = it }
                                }
                                ConnectedSelectionHost(Modifier.fillMaxWidth().height(64.dp)) {
                                    Box(Modifier.fillMaxSize().selectionLens(true, false)) { Text("Selected row") }
                                }
                            }
                            if (open) GlassAlertDialog(onDismissRequest = { open = false },
                                title = { Text("Backend popup") },
                                text = {
                                    popup = LocalFrostedBackdrop.current
                                    Column {
                                        Text(glassBackend().name, Modifier.testTag("popup-backend"))
                                        LiquidGlassSurface(Modifier.size(96.dp, 40.dp)) {
                                            Text("Popup ink", Modifier.align(androidx.compose.ui.Alignment.Center))
                                        }
                                    }
                                }, confirmButton = { ActionTextButton(onClick = { open = false }) { Text("Done") } })
                        }
                    }
                }
            }
        }
        val sequence = listOf(GlassRendererMode.Automatic, GlassRendererMode.Legacy, GlassRendererMode.Legacy,
            GlassRendererMode.Modern, GlassRendererMode.Legacy, GlassRendererMode.Automatic)
        for ((index, next) in sequence.withIndex()) {
            val color = if (index % 2 == 0) Color.Red else Color.Green
            compose.runOnIdle { mode = next; background = color; controlBackend = null }
            val expected = effectiveGlassBackend(next, Build.VERSION.SDK_INT)
            if (expected == GlassBackend.Legacy) {
                compose.waitUntil(10000) {
                    source.legacyReady && nested.legacyReady && source.snapshot!!.toPixelMap().let {
                        it[it.width / 2, it.height / 2] == color
                    } && nested.snapshot!!.toPixelMap().let {
                        val pixel = it[it.width / 2, it.height / 2]
                        // A ready old capture is insufficient: the finished panel must
                        // follow a changed source even while Legacy remains selected.
                        if (color == Color.Red) pixel.red > pixel.green + .1f
                        else pixel.green > pixel.red + .1f
                    }
                }
                assertNotEquals(Bitmap.Config.HARDWARE, source.snapshot!!.asAndroidBitmap().config)
            } else compose.waitUntil(10000) { source.snapshot == null && nested.snapshot == null }
            compose.onNodeWithTag("backend-ink").assertIsDisplayed()
            val ink = compose.onNodeWithTag("backend-ink").captureToImage().toPixelMap()
            val darkPixels = (0 until ink.height).sumOf { y -> (0 until ink.width).count { x ->
                ink[x, y].let { it.red < .12f && it.green < .12f && it.blue < .12f }
            } }
            assertTrue("Foreground must remain visible through $next", darkPixels > 12)
            compose.onNodeWithText("Library").assertIsDisplayed()
            compose.onNodeWithText("Albums").assertIsDisplayed()
            compose.onNodeWithTag("backend-slider").performTouchInput { down(center) }
            compose.waitUntil(5000) { controlBackend == expected }
            capture("scene-$index-$next")
            compose.onNodeWithTag("backend-slider").performTouchInput { up() }
        }
        compose.runOnIdle { open = true }
        compose.onNodeWithText("Backend popup").assertIsDisplayed()
        for ((index, next) in sequence.withIndex()) {
            compose.runOnIdle { mode = next }
            val expected = effectiveGlassBackend(next, Build.VERSION.SDK_INT)
            compose.onNodeWithTag("popup-backend").assertTextEquals(expected.name)
            if (expected == GlassBackend.Legacy) compose.waitUntil(10000) { popup?.legacyReady == true }
            else compose.waitUntil(10000) { popup?.snapshot == null }
            compose.onNodeWithText("Popup ink").assertIsDisplayed()
            capture("popup-$index-$next")
        }
        compose.runOnIdle { open = false; mode = GlassRendererMode.Legacy }
        compose.waitUntil(10000) { source.legacyReady }
        compose.onNodeWithTag("backend-ink").assertIsDisplayed()
    }
}
