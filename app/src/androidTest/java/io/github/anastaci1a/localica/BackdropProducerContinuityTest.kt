package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** A real pending ownership interval, independent of native copy timing or optical polling. */
class BackdropProducerContinuityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun producerAdvancesWhileItsSamplingRecordingIsPinned() = inspect(false)
    @Test fun liveProducerPreservesBackingAndDisplayCutoutWhilePinned() = inspect(true)

    private fun inspect(backed: Boolean) {
        lateinit var source: FrostedBackdrop
        lateinit var graphics: GraphicsContext
        lateinit var density: Density
        lateinit var direction: LayoutDirection
        lateinit var scope: CoroutineScope
        lateinit var view: View
        var color by mutableStateOf(Color.Red)
        var stage by mutableIntStateOf(0)
        val release = CompletableDeferred<Unit>()
        var owned: Deferred<ImageBitmap?>? = null
        var sampled: ImageBitmap? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(context.getExternalFilesDir(null))
        val output = File(root, "backdrop-producer/$backed").also { it.mkdirs() }
        try {
            compose.setContent { LocalicaTheme {
                source = rememberFrostedBackdrop("producer", enabled = false, dark = false)
                val backing = rememberFrostedBackdrop("backing", enabled = false, dark = false)
                graphics = LocalGraphicsContext.current
                density = LocalDensity.current
                direction = LocalLayoutDirection.current
                scope = rememberCoroutineScope()
                view = LocalView.current
                Box(Modifier.size(120.dp).background(Color.Yellow).testTag("producer-scene")) {
                    if (backed) Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(backing,
                        displayCutout = { Path().apply { addRect(Rect(Offset.Zero, size)) } })) { drawRect(Color.Blue) }
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source, backing.takeIf { backed },
                        displayCutout = if (backed) ({ Path().apply { addRect(Rect(0f, 0f, size.width / 8f, size.height)) } }) else null)) {
                        if (backed) drawRect(color, Offset(size.width / 4f, size.height / 4f), Size(size.width / 2f, size.height / 2f))
                        else drawRect(color)
                    }
                    PresentationMarker { stage }
                }
            } }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            val scene = compose.onNodeWithTag("producer-scene")
            fun frame(label: String): PixelMap {
                compose.runOnUiThread { stage++ }
                compose.mainClock.advanceTimeByFrame()
                return capturePresentedPopupPixels(compose, scene, stage) { view }.also {
                    export(it, File(output, "$label.png"))
                }
            }
            fun assertColor(expected: Color, pixels: PixelMap, x: Float = .5f, y: Float = .5f) {
                val actual = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
                assertEquals("Producer red channel", expected.red, actual.red, .01f)
                assertEquals("Producer green channel", expected.green, actual.green, .01f)
                assertEquals("Producer blue channel", expected.blue, actual.blue, .01f)
            }
            assertColor(Color.Red, frame("source"))
            val recordings = compose.runOnUiThread { source.recordings }
            val size = compose.runOnUiThread { source.layer.size }
            compose.runOnUiThread {
                owned = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    source.withPinnedRecording({ true }) {
                        release.await()
                        RetainedLayerReadback().capture(source.layer, graphics, density, direction)
                    }
                }
                assertTrue(source.readbackPending)
                color = Color.Green
            }
            val held = frame("held")
            assertColor(Color.Green, held)
            if (backed) {
                assertColor(Color.Blue, held, .9f)
                assertColor(Color.Yellow, held, .06f)
            }
            compose.runOnUiThread {
                assertTrue("The sampler still owns its immutable recording", source.readbackPending)
                assertEquals(recordings, source.recordings)
                assertEquals(size, source.layer.size)
                release.complete(Unit)
            }
            sampled = requireNotNull(runBlocking { withTimeout(5000) { requireNotNull(owned).await() } })
            val pixels = sampled.toPixelMap()
            export(pixels, File(output, "sampler.png"))
            assertColor(Color.Red, pixels)
            compose.runOnUiThread { assertFalse(source.readbackPending) }
            assertColor(Color.Green, frame("released"))
            compose.runOnUiThread { assertTrue(source.recordings > recordings) }
        } finally {
            compose.runOnUiThread { release.complete(Unit) }
            compose.mainClock.autoAdvance = true
            if (sampled == null && owned != null) sampled = runBlocking { withTimeout(5000) { owned?.await() } }
            sampled?.asAndroidBitmap()?.recycle()
        }
    }

    private fun export(pixels: PixelMap, file: File) {
        val bitmap = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(IntArray(pixels.width * pixels.height) { pixels[it % pixels.width, it / pixels.width].toArgb() },
                0, pixels.width, 0, 0, pixels.width, pixels.height)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
