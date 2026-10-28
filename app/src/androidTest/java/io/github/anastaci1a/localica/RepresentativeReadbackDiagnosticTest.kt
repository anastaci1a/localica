package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.os.HandlerCompat
import io.github.anastaci1a.localica.ui.ReadbackStage
import io.github.anastaci1a.localica.ui.RetainedLayerReadback
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/** Test-only dependency ladder. Not a product workaround or replacement for the player gate. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RepresentativeReadbackDiagnosticTest {
    @get:Rule val compose = createComposeRule()

    private val representative = IntSize(1066, 1731)

    private class Fixture {
        lateinit var context: GraphicsContext
        lateinit var density: Density
        lateinit var direction: LayoutDirection
        lateinit var scope: CoroutineScope
    }

    private fun fixture(): Fixture {
        val fixture = Fixture()
        compose.setContent {
            val context = LocalGraphicsContext.current
            val density = LocalDensity.current
            val direction = LocalLayoutDirection.current
            val scope = rememberCoroutineScope()
            SideEffect {
                fixture.context = context; fixture.density = density
                fixture.direction = direction; fixture.scope = scope
            }
            Box { }
        }
        compose.waitForIdle()
        return fixture
    }

    private class Trace(private val case: String) {
        private var sequence = 0
        var completedCopies = 0; private set
        var verifiedCopies = 0; private set

        fun emit(event: String, fields: JSONObject.() -> Unit = {}) {
            val record = JSONObject().put("schema", 1).put("case", case)
                .put("sequence", sequence++).put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
                .put("api", Build.VERSION.SDK_INT).put("thread", Thread.currentThread().name)
                .put("event", event)
            record.fields()
            Log.i("RepresentativeReadback", record.toString())
        }

        fun begin(route: String, size: IntSize) = emit("begin") {
            put("route", route); put("width", size.width); put("height", size.height); put("copies", 2)
        }

        fun copied(generation: Int, image: ImageBitmap, size: IntSize) {
            assertEquals(size.width, image.width); assertEquals(size.height, image.height)
            completedCopies++
            emit("copied") { put("generation", generation); put("width", image.width); put("height", image.height) }
        }

        fun verify(generation: Int, image: ImageBitmap, size: IntSize, color: Color) {
            assertEquals(size.width, image.width); assertEquals(size.height, image.height)
            val pixels = IntArray(size.width * size.height)
            image.readPixels(pixels)
            val expected = color.toArgb()
            val mismatch = pixels.indexOfFirst { it != expected }
            assertEquals("Every pixel must match opaque $color; first mismatch at $mismatch", -1, mismatch)
            verifiedCopies++
            emit("verified") {
                put("generation", generation); put("width", image.width); put("height", image.height)
                put("pixel_count", pixels.size); put("argb", expected)
            }
        }

        fun end(cancelled: Boolean = false) = emit("end") {
            put("completed_copies", completedCopies); put("verified_copies", verifiedCopies)
            put("owned_resources_released", true); put("failures", 0)
            if (cancelled) { put("first_caller_cancelled", true); put("old_publications", 0) }
        }
    }

    /** Mirrors the V22 ImageReader transport without GraphicsLayer or app ownership helpers. */
    private suspend fun directImageReader(size: IntSize, color: Color, trace: Trace, generation: Int): ImageBitmap = withContext(NonCancellable) {
        check(Looper.myLooper() == Looper.getMainLooper())
        ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 1).use { reader ->
            val image = suspendCancellableCoroutine<Image> { continuation ->
                reader.setOnImageAvailableListener({ available ->
                    val result = runCatching { requireNotNull(available.acquireLatestImage()) }
                    result.fold(onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(it) })
                }, HandlerCompat.createAsync(Looper.getMainLooper()))
                val surface = reader.surface
                val canvas = surface.lockHardwareCanvas()
                try { canvas.drawColor(color.toArgb(), PorterDuff.Mode.SRC) }
                finally { surface.unlockCanvasAndPost(canvas) }
            }
            // Match Compose 1.8.2 V22 plane copying, including its contiguous RGBA assumption.
            // No stride workaround or alternate renderer is silently substituted by this control.
            val colors = IntArray(image.width * image.height)
            val plane = image.planes[0]
            val buffer = plane.buffer
            trace.emit("plane") {
                put("generation", generation); put("width", image.width); put("height", image.height)
                put("row_stride", plane.rowStride); put("pixel_stride", plane.pixelStride)
                put("remaining_bytes", buffer.remaining())
            }
            buffer.asIntBuffer().get(colors)
            for (index in colors.indices) {
                val rgba = colors[index]
                colors[index] = (rgba and -0x1000000) or ((rgba and 0xff) shl 16) or
                    (rgba and 0xff00) or ((rgba ushr 16) and 0xff)
            }
            Bitmap.createBitmap(colors, image.width, image.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            // reader.use closes its acquired image only after the complete pixel copy.
        }
    }

    private fun stableCase(case: String, route: String, size: IntSize) {
        val f = fixture()
        val trace = Trace(case)
        trace.begin(route, size)
        var layer: GraphicsLayer? = null
        var job: Job? = null
        var failure: Throwable? = null
        var releasedLeases = 0
        val owner = RetainedLayerReadback { stage ->
            trace.emit("lease") { put("generation", releasedLeases); put("stage", stage.name) }
            if (stage == ReadbackStage.RELEASED) releasedLeases++
        }
        try {
            compose.runOnIdle {
                if (route != "android-imagereader") layer = f.context.createGraphicsLayer()
                job = f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        for ((generation, color) in listOf(Color.Red, Color.Blue).withIndex()) {
                            val source = layer
                            source?.record(f.density, f.direction, size) { drawRect(color) }
                            val image = when (route) {
                                "android-imagereader" -> directImageReader(size, color, trace, generation).also { trace.copied(generation, it, size) }
                                "compose-layer" -> withContext(NonCancellable) { requireNotNull(source).toImageBitmap() }
                                    .also { trace.copied(generation, it, size) }
                                "retained-layer" -> owner.capture(requireNotNull(source), f.context, f.density, f.direction) { parent ->
                                    parent.toImageBitmap().also { trace.copied(generation, it, size) }
                                }
                                else -> error("Unknown diagnostic route: $route")
                            }
                            trace.verify(generation, image, size, color)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Throwable) { failure = error }
                }
            }
            compose.waitUntil(15000) { job?.isCompleted == true }
            compose.runOnIdle {
                failure?.let { throw it }
                assertFalse(requireNotNull(job).isCancelled)
                assertEquals(2, trace.completedCopies); assertEquals(2, trace.verifiedCopies)
                assertEquals(if (route == "retained-layer") 2 else 0, releasedLeases)
                layer?.let { assertFalse(it.isReleased) }
            }
        } finally {
            compose.runOnUiThread { job?.cancel() }
            compose.waitUntil(15000) { job?.isCompleted != false }
            compose.runOnUiThread { layer?.let { f.context.releaseGraphicsLayer(it); assertTrue(it.isReleased) } }
        }
        trace.end()
    }

    @Test fun a_directImageReaderSmallControl() =
        stableCase("a_directImageReaderSmallControl", "android-imagereader", IntSize(64, 64))

    @Test fun b_directImageReaderRepresentativeControl() =
        stableCase("b_directImageReaderRepresentativeControl", "android-imagereader", representative)

    @Test fun c_composeLayerRepresentativeControl() =
        stableCase("c_composeLayerRepresentativeControl", "compose-layer", representative)

    @Test fun d_retainedLayerRepresentativeControl() =
        stableCase("d_retainedLayerRepresentativeControl", "retained-layer", representative)

    @Test fun e_retainedLayerRepresentativeCancellationResize() {
        val f = fixture()
        val trace = Trace("e_retainedLayerRepresentativeCancellationResize")
        val initial = IntSize(1067, 1731)
        trace.begin("retained-mutation", initial)
        val parents = mutableListOf<GraphicsLayer>()
        val publications = mutableListOf<ImageBitmap>()
        val failures = mutableListOf<Throwable>()
        var releasedLeases = 0
        val stages = mutableListOf<ReadbackStage>()
        val owner = RetainedLayerReadback { stage ->
            stages += stage
            trace.emit("lease") { put("generation", releasedLeases); put("stage", stage.name) }
            if (stage == ReadbackStage.RELEASED) releasedLeases++
        }
        var layer: GraphicsLayer? = null
        var first: Job? = null
        var second: Job? = null
        var secondEntered = false
        try {
            compose.runOnIdle {
                val live = f.context.createGraphicsLayer().also { layer = it }
                live.record(f.density, f.direction, initial) { drawRect(Color.Red) }
                first = f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val caller = currentCoroutineContext().job
                    try {
                        publications += owner.capture(live, f.context, f.density, f.direction) { parent ->
                            parents += parent
                            coroutineScope {
                                val native = async(start = CoroutineStart.UNDISPATCHED) { parent.toImageBitmap() }
                                try {
                                    assertFalse("API26 callback must still be pending before same-source mutation", native.isCompleted)
                                    trace.emit("native_pending") { put("generation", 0); put("pending", true) }
                                    caller.cancel()
                                    live.record(f.density, f.direction, representative) { drawRect(Color.Blue) }
                                    assertFalse(live.isReleased); assertFalse(parent.isReleased)
                                    second = f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        try {
                                            publications += owner.capture(live, f.context, f.density, f.direction) { next ->
                                                secondEntered = true; parents += next
                                                next.toImageBitmap().also { trace.copied(1, it, representative) }
                                            }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (error: Throwable) { failures += error }
                                    }
                                    assertFalse(secondEntered); assertFalse(requireNotNull(second).isCompleted)
                                    assertEquals(listOf(ReadbackStage.ACQUIRED), stages)
                                    assertTrue(publications.isEmpty()); assertEquals(1, parents.size)
                                    trace.emit("successor_blocked") {
                                        put("blocked", true); put("source_live", true); put("parent_live", true)
                                    }
                                    native.await().also {
                                        assertFalse(parent.isReleased); assertFalse(live.isReleased)
                                        trace.copied(0, it, initial)
                                        // Deliberately no first-copy color assertion across live-source mutation.
                                    }
                                } finally { native.await() }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Throwable) { failures += error }
                }
            }
            compose.waitUntil(15000) { first?.isCompleted == true && second?.isCompleted != false }
            compose.runOnIdle {
                assertTrue("Unexpected failures: $failures", failures.isEmpty())
                assertTrue(requireNotNull(first).isCancelled); assertFalse(requireNotNull(second).isCancelled)
                assertTrue(secondEntered); assertEquals(2, releasedLeases)
                assertEquals(2, parents.size); assertTrue(parents.all { it.isReleased })
                assertFalse(requireNotNull(layer).isReleased)
                assertEquals(listOf(ReadbackStage.ACQUIRED, ReadbackStage.COPIED, ReadbackStage.RELEASED,
                    ReadbackStage.ACQUIRED, ReadbackStage.COPIED, ReadbackStage.RELEASED), stages)
                assertEquals(1, publications.size); assertEquals(2, trace.completedCopies)
                trace.verify(1, publications.single(), representative, Color.Blue)
                assertEquals(1, trace.verifiedCopies)
            }
        } finally {
            compose.runOnUiThread { first?.cancel(); second?.cancel() }
            compose.waitUntil(15000) { first?.isCompleted != false && second?.isCompleted != false }
            compose.runOnUiThread { layer?.let { f.context.releaseGraphicsLayer(it); assertTrue(it.isReleased) } }
        }
        trace.end(cancelled = true)
    }
}
