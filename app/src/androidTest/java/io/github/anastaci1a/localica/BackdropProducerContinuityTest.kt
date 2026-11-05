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
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** A real pending ownership interval, independent of native copy timing or optical polling. */
class BackdropProducerContinuityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun producerAdvancesWhileItsSamplingRecordingIsPinned() = inspect(false)
    @Test fun liveProducerPreservesBackingAndDisplayCutoutWhilePinned() = inspect(true)
    @Test fun nestedProducerKeepsItsGeometryAcrossNativeCopyCompletion() = inspect(false, true)
    @Test fun nestedProducerKeepsItsGeometryAcrossQueuedCopyCompletion() = inspect(false, true, false)

    private fun inspect(backed: Boolean, nested: Boolean = false, inlineCompletion: Boolean = true) {
        lateinit var source: FrostedBackdrop
        lateinit var graphics: GraphicsContext
        lateinit var density: Density
        lateinit var direction: LayoutDirection
        lateinit var scope: CoroutineScope
        lateinit var view: View
        var color by mutableStateOf(Color.Red)
        var stage by mutableIntStateOf(0)
        var edge by mutableIntStateOf(40)
        val release = CompletableDeferred<Unit>()
        val copied = CompletableDeferred<Unit>()
        var pinnedParent: GraphicsLayer? = null
        var owned: Deferred<ImageBitmap?>? = null
        var sampled: ImageBitmap? = null
        var failure: Throwable? = null
        val nativeContext = if (nested && inlineCompletion) android.os.Handler(android.os.Looper.getMainLooper())
            .asCoroutineDispatcher().immediate else kotlin.coroutines.EmptyCoroutineContext
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(context.getExternalFilesDir(null))
        val output = File(root, "backdrop-producer/${if (nested) "nested-$inlineCompletion" else backed}").also { it.mkdirs() }
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
                    if (nested) Box(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        Canvas(Modifier.align(Alignment.Center).size(edge.dp).testTag("producer-art").graphicsLayer {
                            clip = true; shape = RoundedCornerShape(8.dp)
                        }) { drawRect(color) }
                    } else Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source, backing.takeIf { backed },
                        displayCutout = if (backed) ({ Path().apply { addRect(Rect(0f, 0f, size.width / 8f, size.height)) } }) else null)) {
                        if (backed) drawRect(color, Offset(size.width / 4f, size.height / 4f), Size(size.width / 2f, size.height / 2f))
                        else drawRect(color)
                    }
                    PresentationMarker(composeStage = true) { stage }
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
            fun assertGeometry(pixels: PixelMap) {
                val x = pixels.width / 2; val y = pixels.height / 2
                fun green(x: Int, y: Int) = pixels[x, y].let { it.green > .9f && it.red < .1f && it.blue < .1f }
                assertTrue("The nested producer is visible", green(x, y))
                var left = x; var right = x; var top = y; var bottom = y
                while (left > 0 && green(left - 1, y)) left--
                while (right + 1 < pixels.width && green(right + 1, y)) right++
                while (top > 0 && green(x, top - 1)) top--
                while (bottom + 1 < pixels.height && green(x, bottom + 1)) bottom++
                val measured = compose.onNodeWithTag("producer-art").fetchSemanticsNode().boundsInRoot
                assertEquals("Nested width follows layout; pending=${source.readbackPending}", measured.width, (right - left + 1).toFloat(), 1.25f)
                assertEquals("Nested height follows layout; pending=${source.readbackPending}", measured.height, (bottom - top + 1).toFloat(), 1.25f)
            }
            assertColor(Color.Red, frame("source"))
            val recordings = compose.runOnUiThread { source.recordings }
            val size = compose.runOnUiThread { source.layer.size }
            compose.runOnUiThread {
                owned = scope.async(context = nativeContext, start = CoroutineStart.UNDISPATCHED) {
                    source.withPinnedRecording({ true }) {
                        source.readback.capture(source.layer, graphics, density, direction, copy = { parent ->
                            pinnedParent = parent
                            val image = RetainedLayerReadback().capture(parent, graphics, density, direction)
                            copied.complete(Unit)
                            release.await()
                            image
                        })
                    }
                }
                assertTrue(source.readbackPending)
            }
            // Hold the real retained parent after native pixels exist, before ownership is released.
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5000) { copied.isCompleted || owned?.isCompleted == true }
            if (!copied.isCompleted) runBlocking { requireNotNull(owned).await() }
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                assertFalse(requireNotNull(pinnedParent).isReleased)
                color = Color.Green
                if (nested) edge = 45
            }
            val held = frame("held")
            assertColor(Color.Green, held)
            if (nested) {
                assertGeometry(held)
                for (size in listOf(50, 55, 60, 65)) {
                    compose.runOnUiThread { edge = size }
                    assertGeometry(frame("pinned-$size"))
                }
            }
            if (backed) {
                assertColor(Color.Blue, held, .9f)
                assertColor(Color.Yellow, held, .06f)
            }
            compose.runOnUiThread {
                assertTrue("The sampler still owns its immutable recording", source.readbackPending)
                assertEquals(recordings, source.recordings)
                assertEquals(size, source.layer.size)
                release.complete(Unit)
                if (nested) {
                    if (inlineCompletion) assertFalse("Native completion precedes the resumed geometry frame", source.readbackPending)
                    edge = 70
                }
            }
            if (nested) {
                val completed = frame("copy-completion")
                compose.runOnUiThread { assertFalse("The sampled frame crosses native ownership completion", source.readbackPending) }
                assertGeometry(completed)
            }
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5000) { owned?.isCompleted == true }
            sampled = requireNotNull(runBlocking { requireNotNull(owned).await() })
            compose.mainClock.autoAdvance = false
            val pixels = sampled.toPixelMap()
            export(pixels, File(output, "sampler.png"))
            assertColor(Color.Red, pixels)
            compose.runOnUiThread {
                assertFalse(source.readbackPending)
                assertTrue(requireNotNull(pinnedParent).isReleased)
            }
            if (nested) compose.runOnUiThread { edge = 75 }
            val released = frame("released")
            assertColor(Color.Green, released)
            if (nested) assertGeometry(released)
            compose.runOnUiThread { assertTrue(source.recordings > recordings) }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            try {
                compose.runOnUiThread { release.complete(Unit) }
                compose.mainClock.autoAdvance = true
                if (owned != null) {
                    compose.waitUntil(5000) { owned?.isCompleted == true }
                    if (sampled == null) sampled = runBlocking { owned?.await() }
                }
                sampled?.asAndroidBitmap()?.recycle()
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
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
