package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SoftwareBackdropTest {
    @get:Rule val compose = createComposeRule()

    @Test fun legacyCaptureSharesClearSoftwarePixelsAcrossControlsAndSelections() {
        assumeTrue(Build.VERSION.SDK_INT < 33)
        verifyCapture(GlassRendererMode.Automatic)
    }

    @Test fun forcedLegacyUsesRealSoftwareControlsOnModernAndroid() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        verifyCapture(GlassRendererMode.Legacy)
    }

    private fun verifyCapture(mode: GlassRendererMode) {
        lateinit var source: FrostedBackdrop
        lateinit var scope: CoroutineScope
        var value by mutableFloatStateOf(.5f)
        var checked by mutableStateOf(false)
        var selected by mutableStateOf(false)
        var keepLease by mutableStateOf(true)
        var sliderDraws = 0
        var switchDraws = 0
        var sliderBackend: GlassBackend? = null
        var switchBackend: GlassBackend? = null
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(renderer = mode)) {
            scope = rememberCoroutineScope()
            source = rememberFrostedBackdrop(Unit, glassBlur = 1f, whiteTint = 1f)
            if (keepLease) BackdropLease(source)
            Box(Modifier.size(300.dp, 260.dp).testTag("software-host")) {
                Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source)) {
                    drawRect(Color.Red)
                    drawRect(Color.Blue, Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
                }
                CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                    Column(Modifier.padding(16.dp)) {
                        CompositionLocalProvider(LocalControlLensDraw provides { sliderDraws++; sliderBackend = it }) {
                            InterfaceSlider(value, { value = it }, Modifier.testTag("software-slider"))
                        }
                        CompositionLocalProvider(LocalControlLensDraw provides { switchDraws++; switchBackend = it }) {
                            BooleanPreference("Software switch", checked) { checked = it }
                        }
                        ConnectedSelectionHost(Modifier.fillMaxWidth().height(100.dp)) {
                            Box(Modifier.padding(8.dp).fillMaxSize().selectionLens(selected, false))
                        }
                    }
                }
            }
        } } }
        compose.waitUntil(10000) { source.snapshot != null && source.lensSnapshot != null }
        if (Build.VERSION.SDK_INT >= 28) {
            var raw by mutableStateOf<ImageBitmap?>(null)
            compose.runOnIdle { scope.launch { raw = source.layer.toImageBitmap() } }
            compose.waitUntil(10000) { raw != null }
            assertEquals("Exercise the real AndroidX hardware capture path", Bitmap.Config.HARDWARE,
                raw!!.asAndroidBitmap().config)
            val softwareTarget = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                val failure = runCatching {
                    android.graphics.Canvas(softwareTarget).drawBitmap(raw!!.asAndroidBitmap(), 0f, 0f, null)
                }.exceptionOrNull()
                assertTrue("The unconverted capture must reproduce the software-Canvas rejection: $failure",
                    failure is IllegalArgumentException && failure.message.orEmpty().contains("hardware", ignoreCase = true))
            } finally { softwareTarget.recycle() }
        }
        // Allow the settled-scene recapture; subsequent consumers must reuse it.
        val deadline = SystemClock.uptimeMillis() + 800
        compose.waitUntil(5000) { SystemClock.uptimeMillis() >= deadline }
        val clear = source.snapshot!!
        assertNotEquals(Bitmap.Config.HARDWARE, clear.asAndroidBitmap().config)
        assertEquals("Legacy capture must not scale padded modern allocations", source.size.width, clear.width)
        assertEquals(source.size.height, clear.height)
        val pixels = clear.toPixelMap()
        val red = pixels[pixels.width / 4, pixels.height / 2]
        val blue = pixels[pixels.width * 3 / 4, pixels.height / 2]
        assertTrue("Clear capture must not inherit configured tint/blur", red.red > .98f && red.blue < .02f)
        assertTrue(blue.blue > .98f && blue.red < .02f)
        val target = Bitmap.createBitmap(clear.width, clear.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(target).drawBitmap(clear.asAndroidBitmap(), 0f, 0f, null)
        target.recycle()
        compose.onNodeWithTag("software-slider").performTouchInput { down(center) }
        compose.waitUntil(5000) { sliderDraws > 0 && sliderBackend == GlassBackend.Legacy }
        compose.onNodeWithTag("software-slider").performTouchInput { moveBy(Offset(30f, 0f)); up() }
        compose.onNodeWithTag("settings-switch", useUnmergedTree = true).performTouchInput { down(center) }
        compose.waitUntil(5000) { switchDraws > 0 && switchBackend == GlassBackend.Legacy }
        compose.onNodeWithTag("settings-switch", useUnmergedTree = true).performTouchInput { up() }
        compose.runOnIdle { selected = true }
        compose.onNodeWithTag("software-host").captureToImage()
        compose.runOnIdle { assertSame("Consumers share the clear capture", clear, source.snapshot) }
        compose.runOnIdle { selected = false; keepLease = false }
        compose.waitUntil(5000) { source.consumers == 0 }
        compose.runOnIdle { keepLease = true }
        compose.waitUntil(5000) { source.consumers > 0 }
        val reuseDeadline = SystemClock.uptimeMillis() + 700
        compose.waitUntil(5000) { SystemClock.uptimeMillis() >= reuseDeadline }
        compose.runOnIdle { assertSame("A new lease of unchanged content reuses its capture", clear, source.snapshot) }
        compose.runOnIdle { source.refresh() }
        compose.waitUntil(10000) { source.snapshot !== clear }
        assertNotEquals(Bitmap.Config.HARDWARE, source.snapshot!!.asAndroidBitmap().config)
    }

    @Test fun processingSmallSoftwareImagesNeverRecyclesTheSharedClearSource() = runBlocking {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val image = bitmap.asImageBitmap()
        assertSame(image, softwareGlassSnapshot(image))
        for (radius in listOf(1f, 16f)) {
            val blurred = blurGlassSnapshot(image, radius)
            assertFalse("A scaled bitmap can alias a tiny source", bitmap.isRecycled)
            assertEquals(android.graphics.Color.RED, bitmap.getPixel(0, 0))
            assertEquals(android.graphics.Color.RED, blurred.asAndroidBitmap().getPixel(0, 0))
        }
    }
}
