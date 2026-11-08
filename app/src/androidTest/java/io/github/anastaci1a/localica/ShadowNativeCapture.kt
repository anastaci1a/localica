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
@OptIn(ExperimentalTestApi::class)
internal class ShadowNativeCapture(private val rule: ComposeContentTestRule,
    node: SemanticsNodeInteraction, private val clockSequence: () -> Long) : AutoCloseable {
    private val root = node.fetchSemanticsNode()
    private val view = (root.root as ViewRootForTest).view
    private val window = view.context.shadowActivityWindow()
    private val bounds = root.boundsInRoot
    private val marker = Marker()
    private val drawingWasEnabled = HardwareRendererCompat.isDrawingEnabled()
    private val windowId = synchronized(windows) { windows.getOrPut(window) { windows.size + 1 } }
    init {
        if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(true)
        rule.runOnUiThread {
            marker.setBounds(bounds.left.roundToInt() + 1, bounds.top.roundToInt() + 1,
                bounds.left.roundToInt() + 9, bounds.top.roundToInt() + 9)
            view.overlay.add(marker)
        }
    }

    fun capture(hostEpoch: Long = 0): PresentedCapture {
        val drawn = CountDownLatch(1)
        val screenBounds = android.graphics.Rect(bounds.left.roundToInt(), bounds.top.roundToInt(),
            bounds.right.roundToInt(), bounds.bottom.roundToInt())
        val drawings = linkedMapOf<Int, Pair<Long, Long>>() // Engine sequence, native draw VSYNC
        var overflow = false
        val metrics = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            val vsync = frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
            if (drawings.values.any { it.second == vsync }) drawn.countDown()
        }
        rule.runOnUiThread {
            check(root.boundsInRoot == bounds && view.isAttachedToWindow && view.matrix.isIdentity) { "Capture transform or native host changed" }
            val origin = IntArray(2).also(view::getLocationOnScreen)
            screenBounds.offset(origin[0], origin[1])
            window.addOnFrameMetricsAvailableListener(metrics, Handler(Looper.getMainLooper()))
            marker.afterDraw = {
                if (drawings.size == 32) overflow = true else drawings[marker.token] =
                    clockSequence() to Choreographer.getInstance().frameTimeNanos
            }
            marker.invalidateSelf()
            view.invalidate()
        }
        try {
            check(drawn.await(5, TimeUnit.SECONDS)) { "Incomplete: no completed native frame matches the marker traversal" }
        } finally { rule.runOnUiThread { window.removeOnFrameMetricsAvailableListener(metrics) } }
        // Accessibility quiet is only a submission wait. It cannot grant an optical/native pass.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 5000)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "Incomplete: single system-composited screenshot failed"
        }
        val point = IntOffset(screenBounds.left + 2, screenBounds.top + 2)
        val token = if (point.x >= 0 && point.y >= 0 && point.x + 4 < bitmap.width && point.y < bitmap.height)
            bitmap.getPixel(point.x, point.y) and 0xffffff else -1
        var result = PresentedCapture(bitmap.asImageBitmap(), listOf("activity-window-$windowId"), hostEpoch,
            token.toLong(), -1, null, System.nanoTime(), NativeShadowGeneration(token, point),
            sceneBounds = androidx.compose.ui.geometry.Rect(screenBounds.left.toFloat(), screenBounds.top.toFloat(),
                screenBounds.right.toFloat(), screenBounds.bottom.toFloat()), origin = "original-system-composited-display")
        try {
            check(screenBounds.left >= 0 && screenBounds.top >= 0 && screenBounds.right <= bitmap.width && screenBounds.bottom <= bitmap.height) {
                "Incomplete: native scene is clipped by the display"
            }
            val nativeDraw = rule.runOnUiThread {
                marker.afterDraw = null
                check(!overflow) { "Incomplete: native draw ledger overflow" }
                checkNotNull(drawings[token]) { "Incomplete: display image is stale or belongs to a different native traversal" }
                    .also { check(it.first == clockSequence()) { "Incomplete: Engine state changed during native capture" } }
            }
            result = result.copy(clockSequence = nativeDraw.first,
                nativeGeneration = NativeShadowGeneration(token, point, nativeDraw.second))
            checkNativeShadowMarker(result)
        } catch (failure: Throwable) {
            shadowOracleEvidence("incomplete-$token", result, failure = failure.toString())
            throw failure
        }
        return result
    }

    override fun close() {
        rule.runOnUiThread { marker.afterDraw = null; view.overlay.remove(marker) }
        if (!drawingWasEnabled) HardwareRendererCompat.setDrawingEnabled(false)
    }

    private class Marker : Drawable() {
        var token = 0; private set
        var afterDraw: (() -> Unit)? = null
        private val paint = Paint()
        override fun draw(canvas: Canvas) {
            token = generations.incrementAndGet()
            check(token <= 0xfffffe) { "Native generation token exhausted" }
            paint.color = Color.BLACK or token; canvas.drawRect(bounds, paint)
            paint.color = Color.MAGENTA
            canvas.drawRect((bounds.left + 4).toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
            afterDraw?.invoke()
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
    }
    private companion object {
        val generations = AtomicInteger()
        val windows = IdentityHashMap<Window, Int>()
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
        .put("nativePresentedNanos", actual.nativePresentedNanos ?: JSONObject.NULL)
        .put("captureNanos", actual.captureNanos).put("comparison", comparison?.toString())
        .put("failure", failure ?: JSONObject.NULL).toString(2))
}
