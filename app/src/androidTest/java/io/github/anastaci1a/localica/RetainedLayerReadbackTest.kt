package io.github.anastaci1a.localica

import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.ReadbackStage
import io.github.anastaci1a.localica.ui.RetainedLayerReadback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Genuine layer recording, retained ownership and pixel readback across caller cancellation. */
class RetainedLayerReadbackTest {
    @get:Rule val compose = createComposeRule()

    private class Fixture {
        var redVisible by mutableStateOf(true)
        lateinit var red: GraphicsLayer
        lateinit var blue: GraphicsLayer
        lateinit var context: GraphicsContext
        lateinit var density: Density
        lateinit var direction: LayoutDirection
        lateinit var scope: CoroutineScope
    }

    @Composable private fun PixelSource(color: Color, onLayer: (GraphicsLayer) -> Unit) {
        val layer = rememberGraphicsLayer()
        val density = LocalDensity.current
        SideEffect { onLayer(layer) }
        androidx.compose.foundation.layout.Box(Modifier.size(with(density) { 64.toDp() }).drawWithContent {
            layer.record(this, layoutDirection, IntSize(64, 64)) { drawRect(color) }
            drawLayer(layer)
        })
    }

    private fun fixture(): Fixture {
        val f = Fixture()
        compose.setContent {
            val context = LocalGraphicsContext.current
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            val scope = rememberCoroutineScope()
            SideEffect { f.context = context; f.density = density; f.direction = direction; f.scope = scope }
            Row {
                if (f.redVisible) PixelSource(Color.Red) { f.red = it }
                PixelSource(Color.Blue) { f.blue = it }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(IntSize(64, 64), f.red.size)
            assertEquals(IntSize(64, 64), f.blue.size)
            assertFalse(f.red.isReleased)
            assertFalse(f.blue.isReleased)
        }
        return f
    }

    private fun assertOpaquePixels(image: ImageBitmap, expected: Color) {
        assertEquals(64, image.width)
        assertEquals(64, image.height)
        val pixels = image.toPixelMap()
        Log.i("ReadbackFixture", "expected=$expected first=${pixels[0, 0]} size=${image.width}x${image.height}")
        for (y in 0 until 64) for (x in 0 until 64) {
            val actual = pixels[x, y]
            assertEquals("red at $x,$y", expected.red, actual.red, 0f)
            assertEquals("green at $x,$y", expected.green, actual.green, 0f)
            assertEquals("blue at $x,$y", expected.blue, actual.blue, 0f)
            assertEquals("alpha at $x,$y", 1f, actual.alpha, 0f)
        }
    }

    private class OwnedSource(val layer: GraphicsLayer, private val context: GraphicsContext) {
        private var released = false
        fun release() {
            if (!released) {
                released = true
                context.releaseGraphicsLayer(layer)
            }
        }
    }

    private fun ownedSource(f: Fixture, color: Color): OwnedSource {
        val layer = f.context.createGraphicsLayer()
        try { layer.record(f.density, f.direction, IntSize(64, 64)) { drawRect(color) } }
        catch (error: Throwable) { f.context.releaseGraphicsLayer(layer); throw error }
        return OwnedSource(layer, f.context)
    }

    private fun assertNativeRoute(completed: Boolean) {
        Log.i("ReadbackFixture", "api=" + Build.VERSION.SDK_INT + " nativeCompletedBeforeOwnerRelease=" + completed)
        if (Build.VERSION.SDK_INT < 28) assertFalse("V22 producer is posted and its callback is still pending", completed)
        else assertTrue("V28 bitmap copy completes synchronously before owner release", completed)
    }

    @Test fun cancelledCopyRetainsDisposedSourceUntilRealPixelsCompleteWithoutPublishing() {
        val f = fixture()
        val stages = mutableListOf<ReadbackStage>()
        val owner = RetainedLayerReadback { stages += it }
        var red: OwnedSource? = null
        var parent: GraphicsLayer? = null
        var copied: ImageBitmap? = null
        var published = false
        var failure: Throwable? = null
        var job: Job? = null
        try {
            compose.runOnIdle {
                val source = ownedSource(f, Color.Red).also { red = it }
                job = f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val originalCaller = currentCoroutineContext().job
                    try {
                        owner.capture(source.layer, f.context, f.density, f.direction) { retained ->
                            parent = retained
                            // This scope belongs to the helper's protected read, not originalCaller.
                            coroutineScope {
                                val native = async(start = CoroutineStart.UNDISPATCHED) { retained.toImageBitmap() }
                                try {
                                    assertNativeRoute(native.isCompleted)
                                    // No Main yield occurs between native submission and owner release.
                                    originalCaller.cancel()
                                    source.release()
                                    assertTrue("Only the caller-owned source was released", source.layer.isReleased)
                                    assertFalse("Parent survives through actual copy completion", retained.isReleased)
                                    assertEquals(listOf(ReadbackStage.ACQUIRED), stages)
                                    native.await().also { copied = it }
                                } finally { native.await() }
                            }
                        }
                        published = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Throwable) { failure = error }
                }
            }
            compose.waitUntil(5000) { job?.isCompleted == true }
            compose.runOnIdle {
                assertNull(failure)
                assertOpaquePixels(requireNotNull(copied), Color.Red)
                assertFalse(published)
                assertTrue(requireNotNull(job).isCancelled)
                assertTrue(requireNotNull(parent).isReleased)
                assertEquals(listOf(ReadbackStage.ACQUIRED, ReadbackStage.COPIED, ReadbackStage.RELEASED), stages)
            }
        } finally {
            compose.runOnUiThread { job?.cancel() }
            compose.waitUntil(5000) { job?.isCompleted != false }
            compose.runOnUiThread { red?.release() }
        }
    }

    @Test fun supersedingBlueCaptureWaitsForCancelledRedLeaseAndOnlyBluePublishes() {
        val f = fixture()
        val stages = mutableListOf<ReadbackStage>()
        val parents = mutableListOf<GraphicsLayer>()
        val owner = RetainedLayerReadback { stages += it }
        val publications = mutableListOf<ImageBitmap>()
        var red: OwnedSource? = null
        var blue: OwnedSource? = null
        var redCopy: ImageBitmap? = null
        var secondEntered = false
        var failure: Throwable? = null
        var first: Job? = null
        var second: Job? = null
        try {
            compose.runOnIdle {
                val redSource = ownedSource(f, Color.Red).also { red = it }
                val blueSource = ownedSource(f, Color.Blue).also { blue = it }
                first = f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val originalCaller = currentCoroutineContext().job
                    try {
                        publications += owner.capture(redSource.layer, f.context, f.density, f.direction) { parent ->
                            parents += parent
                            coroutineScope {
                                val native = async(start = CoroutineStart.UNDISPATCHED) { parent.toImageBitmap() }
                                try {
                                    assertNativeRoute(native.isCompleted)
                                    originalCaller.cancel()
                                    redSource.release()
                                    assertTrue(redSource.layer.isReleased)
                                    assertFalse(parent.isReleased)
                                    second = f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        try {
                                            publications += owner.capture(blueSource.layer, f.context, f.density, f.direction) { next ->
                                                secondEntered = true
                                                parents += next
                                                coroutineScope {
                                                    val blueNative = async(start = CoroutineStart.UNDISPATCHED) { next.toImageBitmap() }
                                                    try {
                                                        assertNativeRoute(blueNative.isCompleted)
                                                        blueNative.await()
                                                    } finally { blueNative.await() }
                                                }
                                            }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (error: Throwable) { failure = error }
                                    }
                                    assertFalse("Second request waits for the first lease", secondEntered)
                                    assertFalse(requireNotNull(second).isCompleted)
                                    assertEquals(1, parents.size)
                                    assertEquals(listOf(ReadbackStage.ACQUIRED), stages)
                                    assertTrue(publications.isEmpty())
                                    native.await().also { redCopy = it }
                                } finally { native.await() }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Throwable) { failure = error }
                }
            }
            compose.waitUntil(5000) { first?.isCompleted == true && second?.isCompleted != false }
            compose.runOnIdle {
                assertNull(failure)
                assertOpaquePixels(requireNotNull(redCopy), Color.Red)
                assertTrue(secondEntered)
                assertEquals(1, publications.size)
                assertOpaquePixels(publications.single(), Color.Blue)
                assertTrue(requireNotNull(first).isCancelled)
                assertFalse(requireNotNull(second).isCancelled)
                assertEquals(2, parents.size)
                assertTrue(parents.all { it.isReleased })
                assertEquals(listOf(ReadbackStage.ACQUIRED, ReadbackStage.COPIED, ReadbackStage.RELEASED,
                    ReadbackStage.ACQUIRED, ReadbackStage.COPIED, ReadbackStage.RELEASED), stages)
            }
        } finally {
            compose.runOnUiThread { first?.cancel(); second?.cancel() }
            compose.waitUntil(5000) { first?.isCompleted != false && second?.isCompleted != false }
            compose.runOnUiThread { red?.release(); blue?.release() }
        }
    }

    @Test fun liveRedAndBlueSourcesCopyEveryPixelWithoutCancellationOrDisposal() {
        val f = fixture()
        val stages = mutableListOf<ReadbackStage>()
        val parents = mutableListOf<GraphicsLayer>()
        val owner = RetainedLayerReadback { stages += it }
        val images = mutableListOf<ImageBitmap>()
        var failure: Throwable? = null
        lateinit var job: Job
        compose.runOnIdle {
            job = f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    for (source in listOf(f.red, f.blue)) {
                        images += owner.capture(source, f.context, f.density, f.direction) { parent ->
                            parents += parent
                            parent.toImageBitmap()
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { failure = error }
            }
        }
        try {
            compose.waitUntil(5000) { job.isCompleted }
            compose.runOnIdle {
                assertNull(failure)
                assertFalse(job.isCancelled)
                assertFalse(f.red.isReleased)
                assertFalse(f.blue.isReleased)
                assertEquals(2, images.size)
                assertOpaquePixels(images[0], Color.Red)
                assertOpaquePixels(images[1], Color.Blue)
                assertEquals(2, parents.size)
                assertTrue(parents.all { it.isReleased })
                assertEquals(listOf(ReadbackStage.ACQUIRED, ReadbackStage.COPIED, ReadbackStage.RELEASED,
                    ReadbackStage.ACQUIRED, ReadbackStage.COPIED, ReadbackStage.RELEASED), stages)
            }
        } finally {
            compose.runOnUiThread { job.cancel() }
            compose.waitUntil(5000) { job.isCompleted }
        }
    }
}
