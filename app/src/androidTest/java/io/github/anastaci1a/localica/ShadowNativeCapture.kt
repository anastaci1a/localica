package io.github.anastaci1a.localica

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.IntOffset
import androidx.test.platform.graphics.HardwareRendererCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** The original image, rather than a clock, callback or copied metadata, attests this generation. */
internal fun checkNativeShadowMarker(capture: PresentedCapture) {
    if (capture.nativeWindows.isNotEmpty()) {
        check(capture.nativeWindows.map { it.windowId }.sorted() == capture.windowIds.sorted() &&
            capture.windowIds.distinct().size == capture.windowIds.size) { "Incomplete: missing or duplicate native window receipts" }
        check(capture.nativeWindows.map { it.state }.distinct().size == 1) { "Incomplete: incoherent native geometry receipts" }
        capture.nativeWindows.forEach { receipt ->
            check(receipt.state.sequence == capture.clockSequence && receipt.state.epoch == capture.hostEpoch &&
                receipt.generation.drawVsyncNanos != null) { "Incomplete: foreign native draw receipt" }
            checkNativeShadowMarker(capture.copy(windowIds = listOf(receipt.windowId),
                nativeGeneration = receipt.generation, nativeWindows = emptyList()))
        }
        return
    }
    check(capture.windowIds.size == 1) { "Incomplete: every native window needs a draw receipt" }
    val native = checkNotNull(capture.nativeGeneration) { "Incomplete: no native generation" }
    val p = native.marker
    check(p.x >= 0 && p.y >= 0 && p.x + 4 < capture.image.width && p.y < capture.image.height)
    val pixels = capture.image.toPixelMap()
    check(native.token in 1..0xfffffe && pixels[p.x, p.y].toArgb() == (Color.BLACK or native.token) &&
        pixels[p.x + 4, p.y].toArgb() == Color.MAGENTA) {
        "Incomplete: the original native pixels do not contain generation ${native.token}"
    }
}

/** One system-composited display screenshot per sample; never polls or retries pixels.
 * The diagnostic overlay is outside the tested field. It adds no Compose/layout/rendering wrapper.
 * Its counter advances only when Android draws it, independently of the frozen Engine clock.
 * A native traversal permits the request; only the token IN the original display image proves correlation.
 */
internal data class ShadowDrawState(val sequence: Long, val epoch: Long,
    val bodies: List<Pair<androidx.compose.ui.geometry.RoundRect, Float>> = emptyList(),
    val background: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White,
    val scaleX: Float = 1f, val scaleY: Float = 1f, val strength: Float = 1f, val spread: Float = 1f,
    val receiver: io.github.anastaci1a.localica.ui.engine.GlassShadowReceiver? = null,
    val exclusion: ShadowExclusionState? = null)
internal data class ShadowExclusionState(val bodies: List<androidx.compose.ui.geometry.RoundRect>,
    val origin: androidx.compose.ui.geometry.Offset, val size: androidx.compose.ui.geometry.Size,
    val scaleX: Float, val scaleY: Float, val shift: Float, val weight: Float) {
    fun renderer() = io.github.anastaci1a.localica.ui.engine.GlassShadowExclusion(bodies, origin, size, scaleX, scaleY, shift, weight)
}
internal data class ShadowNativeWindow(val window: Window, val view: android.view.View,
    val marker: IntOffset, val state: (androidx.compose.ui.geometry.Rect) -> ShadowDrawState)
internal data class ShadowWindowReceipt(val windowId: String, val generation: NativeShadowGeneration, val state: ShadowDrawState)

@OptIn(ExperimentalTestApi::class)
internal class ShadowNativeCapture(private val rule: ComposeContentTestRule,
    node: SemanticsNodeInteraction, additionalWindows: List<ShadowNativeWindow> = emptyList(),
    private val clockSequence: () -> Long) : AutoCloseable {
    private val root = node.fetchSemanticsNode()
    private val view = (root.root as ViewRootForTest).view
    private val bounds = root.boundsInRoot
    private val drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
    private class Source(val window: Window, val view: android.view.View, val point: IntOffset,
        val state: ((androidx.compose.ui.geometry.Rect) -> ShadowDrawState)?) {
        val marker = Marker()
        val id = synchronized(windows) { "window-${windows.getOrPut(window) { windows.size + 1 }}" }
    }
    private val sources = listOf(Source(view.context.shadowActivityWindow(), view,
        IntOffset(bounds.left.roundToInt() + 1, bounds.top.roundToInt() + 1), null)) +
        additionalWindows.map { Source(it.window, it.view, it.marker, it.state) }
    init {
        require(sources.map { it.window }.distinct().size == sources.size)
        if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
        rule.runOnUiThread { sources.forEach {
            check(it.view.rootView === it.window.decorView.rootView && it.view.matrix.isIdentity) { "Incomplete: foreign or transformed native root" }
            it.marker.setBounds(it.point.x, it.point.y, it.point.x + 8, it.point.y + 8)
            it.view.overlay.add(it.marker)
        } }
    }

    fun capture(hostEpoch: Long = 0,
        field: ((androidx.compose.ui.geometry.Rect) -> ShadowDrawState)? = null): PresentedCapture {
        val drawn = CountDownLatch(sources.size)
        val ledgers = sources.map { linkedMapOf<Int, Pair<ShadowDrawState, Long>>() }
        val submitted = BooleanArray(sources.size)
        val metricsVsyncs = sources.map { mutableListOf<Long>() }
        var overflow = false
        var nativeVsync = 0L
        val choreographer = rule.runOnUiThread { Choreographer.getInstance() }
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                nativeVsync = frameTimeNanos; choreographer.postFrameCallback(this)
            }
        }
        val listeners = sources.mapIndexed { index, _ -> Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            val vsync = frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
            if (metricsVsyncs[index].size < 32) metricsVsyncs[index] += vsync
            if (!submitted[index] && ledgers[index].size >= 2 &&
                ledgers[index].values.drop(1).any { it.second == vsync }) {
                submitted[index] = true; drawn.countDown()
            }
        } }
        val points = mutableListOf<IntOffset>()
        val scene = rule.runOnUiThread {
            check(root.boundsInRoot == bounds && view.matrix.isIdentity) { "Capture transform changed" }
            checkNativeRoots(sources.map { it.view })
            val origin = IntArray(2).also(view::getLocationOnScreen)
            bounds.translate(androidx.compose.ui.geometry.Offset(origin[0].toFloat(), origin[1].toFloat()))
        }
        try {
            rule.runOnUiThread {
                choreographer.postFrameCallback(callback)
                sources.forEachIndexed { index, source ->
                    val origin = IntArray(2).also(source.view::getLocationOnScreen)
                    points += IntOffset(origin[0] + source.point.x + 1, origin[1] + source.point.y + 1)
                    source.window.addOnFrameMetricsAvailableListener(listeners[index], Handler(Looper.getMainLooper()))
                    source.marker.afterDraw = {
                        check(nativeVsync > 0L) { "Incomplete: no native VSYNC before marker draw" }
                        val state = (source.state ?: field)?.invoke(scene) ?: ShadowDrawState(clockSequence(), hostEpoch)
                        if (ledgers[index].size == 32) overflow = true else
                            ledgers[index][source.marker.token] = state.copy(bodies = state.bodies.toList(),
                                exclusion = state.exclusion?.let { it.copy(bodies = it.bodies.toList()) }) to nativeVsync
                        // Complete a second native traversal so the first request buffer can be latched.
                        // This is submission readiness, never screenshot polling or presentation evidence.
                        if (ledgers[index].size == 1) source.view.postOnAnimation {
                            source.marker.invalidateSelf(); source.view.invalidate()
                        }
                    }
                    source.marker.invalidateSelf(); source.view.invalidate()
                }
            }
            check(drawn.await(5, TimeUnit.SECONDS)) {
                "Incomplete: not every window completed both native marker traversals; " +
                    rule.runOnUiThread { sources.indices.map { "${sources[it].id}: ledger=${ledgers[it]} metrics=${metricsVsyncs[it]}" } }
            }
            // Submission readiness only. The original pixels, never these callbacks, grant correlation.
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 5000)
            val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
                "Incomplete: single system-composited screenshot failed"
            }
            var result = PresentedCapture(bitmap.asImageBitmap(), sources.map { it.id }, hostEpoch, -1, -1,
                null, System.nanoTime(), sceneBounds = scene, origin = "original-system-composited-display")
            try {
                check(scene.left >= 0 && scene.top >= 0 && scene.right <= bitmap.width && scene.bottom <= bitmap.height) {
                    "Incomplete: native scene is clipped by the display"
                }
                val receipts = rule.runOnUiThread {
                    checkNativeRoots(sources.map { it.view })
                    check(root.boundsInRoot == bounds) { "Incomplete: native scene moved during capture" }
                    check(!overflow) { "Incomplete: native draw ledger overflow" }
                    sources.mapIndexed { index, source ->
                        val point = points[index]
                        val origin = IntArray(2).also(source.view::getLocationOnScreen)
                        check(source.view.matrix.isIdentity && point == IntOffset(origin[0] + source.point.x + 1, origin[1] + source.point.y + 1)) {
                            "Incomplete: native root moved during capture"
                        }
                        check(point.x >= 0 && point.y >= 0 && point.x + 4 < bitmap.width && point.y < bitmap.height)
                        val token = bitmap.getPixel(point.x, point.y) and 0xffffff
                        val entry = checkNotNull(ledgers[index][token]) {
                            "Incomplete: stale, occluded or dimmed display for ${source.id}; token=$token ledger=${ledgers[index]}"
                        }
                        check(entry.first.sequence == clockSequence()) { "Incomplete: Engine changed during native capture" }
                        ShadowWindowReceipt(source.id, NativeShadowGeneration(token, point, entry.second), entry.first)
                    }
                }
                check(receipts.map { it.state }.distinct().size == 1) { "Incomplete: native windows describe different reference states" }
                val first = receipts.first()
                result = result.copy(drawGeneration = first.generation.token.toLong(), clockSequence = first.state.sequence,
                    hostEpoch = first.state.epoch, nativeGeneration = first.generation, nativeWindows = receipts)
                checkNativeShadowMarker(result)
            } catch (failure: Throwable) {
                shadowOracleEvidence("incomplete-${System.nanoTime()}", result, failure = failure.toString()); throw failure
            }
            return result
        } finally { rule.runOnUiThread {
            choreographer.removeFrameCallback(callback)
            sources.forEachIndexed { index, source ->
                source.marker.afterDraw = null; source.window.removeOnFrameMetricsAvailableListener(listeners[index])
            }
        } }
    }

    override fun close() {
        rule.runOnUiThread { sources.forEach { it.marker.afterDraw = null; it.view.overlay.remove(it.marker) } }
        if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false)
    }

    private class Marker : Drawable() {
        var token = 0; private set
        var afterDraw: (() -> Unit)? = null
        private val paint = Paint()
        override fun draw(canvas: Canvas) {
            token = generations.incrementAndGet(); check(token <= 0xfffffe) { "Native generation token exhausted" }
            paint.color = Color.BLACK or token; canvas.drawRect(bounds, paint)
            paint.color = Color.MAGENTA
            canvas.drawRect((bounds.left + 4).toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
            afterDraw?.invoke()
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
    }
    private companion object { val generations = AtomicInteger(); val windows = IdentityHashMap<Window, Int>() }
}

private fun checkNativeRoots(expected: List<android.view.View>) {
    val actual = if (android.os.Build.VERSION.SDK_INT >= 29) android.view.inspector.WindowInspector.getGlobalWindowViews()
    else {
        val type = Class.forName("android.view.WindowManagerGlobal")
        val manager = type.getDeclaredMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        (type.getDeclaredField("mViews").apply { isAccessible = true }.get(manager) as List<android.view.View>).toList()
    }
    check(expected.all { it.isAttachedToWindow } && actual.filter { it.isAttachedToWindow }.toSet() == expected.map { it.rootView }.toSet()) {
        "Incomplete: missing, detached or unexpected native roots"
    }
}

private fun Context.shadowActivityWindow(): Window = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.shadowActivityWindow()
    else -> error("Native shadow capture requires the original Activity window")
}

internal fun shadowOracleEvidence(label: String, actual: PresentedCapture, reference: PresentedCapture? = null,
    comparison: ShadowComparison? = null, failure: String? = null) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = nativeEvidenceDirectory("shadow-field-oracle", context.getExternalFilesDir("shadow-field-oracle"))
    check(directory.isDirectory || directory.mkdirs())
    fun image(name: String, capture: PresentedCapture) = File(directory, "$label-$name.png").outputStream().use {
        check(capture.image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
    }
    image("actual", actual); reference?.let { image("reference", it) }
    File(directory, "$label.json").writeText(JSONObject().put("windows", actual.windowIds)
        .put("captureSource", actual.origin)
        .put("sceneBounds", actual.sceneBounds?.toString())
        .put("hostEpoch", actual.hostEpoch).put("drawGeneration", actual.drawGeneration)
        .put("clockSequence", actual.clockSequence).put("nativeGeneration", actual.nativeGeneration?.token)
        .put("nativeDrawVsyncNanos", actual.nativeGeneration?.drawVsyncNanos ?: JSONObject.NULL)
        .put("nativeWindows", org.json.JSONArray(actual.nativeWindows.map { JSONObject()
            .put("window", it.windowId).put("token", it.generation.token).put("marker", it.generation.marker.toString())
            .put("drawVsyncNanos", it.generation.drawVsyncNanos).put("engineSequence", it.state.sequence)
            .put("hostEpoch", it.state.epoch).put("bodies", it.state.bodies.toString())
            .put("background", it.state.background.toString()).put("scaleX", it.state.scaleX).put("scaleY", it.state.scaleY) }))
        .put("referenceState", actual.nativeWindows.firstOrNull()?.state?.toString())
        .put("nativePresentedNanos", actual.nativePresentedNanos ?: JSONObject.NULL)
        .put("captureNanos", actual.captureNanos).put("comparison", comparison?.toString())
        .put("failure", failure ?: JSONObject.NULL).toString(2))
}
