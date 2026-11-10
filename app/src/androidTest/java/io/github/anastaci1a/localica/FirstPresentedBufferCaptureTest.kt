package io.github.anastaci1a.localica

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.FrameStats
import android.view.View
import android.view.Window
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native primitive controls only. No full-player, source-generation or performance acceptance. */
class FirstPresentedBufferCaptureTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    private class PatchView(context: Context) : View(context) {
        var color = Color.BLUE
        var recordings = 0
        var recordedColor = Color.TRANSPARENT
        var afterDraw: (() -> Unit)? = null
        override fun onDraw(canvas: Canvas) {
            recordings++
            recordedColor = color
            canvas.drawColor(color)
            afterDraw?.also { afterDraw = null }?.invoke()
        }
    }
    private data class Receipt(val recordedColor: Int, val recordings: Int, val latePublished: Boolean)

    private fun fixture(test: (Window, PatchView, FirstPresentedBufferCapture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val original = automation.serviceInfo
        val originalFlags = original.flags
        val info = automation.serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        lateinit var window: Window
        lateinit var patch: PatchView
        val region = Rect()
        try {
            activity.scenario.onActivity { host ->
                host.title = "First-buffer fixture"
                window = host.window
                val root = FrameLayout(host).apply { setBackgroundColor(Color.BLACK) }
                patch = PatchView(host).apply { setLayerType(View.LAYER_TYPE_HARDWARE, null) }
                root.addView(patch, FrameLayout.LayoutParams(48, 48).apply { leftMargin = 32; topMargin = 32 })
                host.setContentView(root)
            }
            instrumentation.waitForIdleSync()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var windowId: Int? = null
            while (windowId == null && System.nanoTime() < deadline) {
                val matches = automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.root?.packageName?.toString() == instrumentation.targetContext.packageName }
                check(matches.size <= 1) { "Ambiguous application native window" }
                windowId = matches.singleOrNull()?.id
                if (windowId == null) Thread.sleep(2)
            }
            val id = checkNotNull(windowId) { "Missing application native window" }
            // Initialization may settle before the first-apply experiment is armed.
            var settled = false
            while (!settled && System.nanoTime() < deadline) {
                val stats = automation.getWindowContentFrameStats(id)
                settled = stats != null && stats.frameCount > 0 &&
                    (0 until stats.frameCount).all { stats.getFramePresentedTimeNano(it) != FrameStats.UNDEFINED_TIME_NANO }
                if (!settled) Thread.sleep(2)
            }
            check(settled) { "Native presentation statistics unavailable during fixture initialization" }
            activity.scenario.onActivity {
                check(patch.width == 48 && patch.height == 48)
                val location = IntArray(2).also(patch::getLocationInWindow)
                region.set(location[0], location[1], location[0] + 96, location[1] + 48)
            }
            test(window, patch, FirstPresentedBufferCapture(automation, id, window, region))
        } finally {
            val restore = automation.serviceInfo
            restore.flags = originalFlags
            automation.serviceInfo = restore
        }
    }
    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
    private fun expected(left: Int, right: Int): IntArray = IntArray(96 * 48) { if (it % 96 < 48) left else right }

    @Test fun copiesExactlyTheOnlyPresentedBufferWithoutAdditionalRedraw() = fixture { _, patch, capture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val frame = capture.capture(apply = {
            instrumentation.runOnMainSync { patch.color = Color.RED; patch.invalidate() }
        }, receiptOnMain = { Receipt(patch.recordedColor, patch.recordings, false) })
        assertArrayEquals(expected(Color.RED, Color.BLACK), frame.bitmap.pixels())
        assertEquals(Color.RED, frame.receipt.recordedColor)
        android.util.Log.i("PERF2_FIRST_NATIVE", "positive frame=${frame.frame} receipt=${frame.receipt}")
    }

    @Test fun rejectsRealLatePublicationEvenThoughLaterPixelsMatch() = fixture { _, patch, capture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val published = AtomicBoolean(false)
        val first = capture.capture(apply = {
            instrumentation.runOnMainSync {
                patch.color = Color.BLUE
                patch.afterDraw = {
                    Handler(Looper.getMainLooper()).post {
                        patch.color = Color.RED
                        published.set(true)
                        patch.invalidate()
                    }
                }
                patch.invalidate()
            }
        }, receiptOnMain = { Receipt(patch.recordedColor, patch.recordings, published.get()) })
        assertFalse("Publication must remain delayed through immutable first receipt", first.receipt.latePublished)
        assertArrayEquals(expected(Color.BLUE, Color.BLACK), first.bitmap.pixels())
        val required = expected(Color.RED, Color.BLACK)
        assertFalse("Actual initial wrong pixels must be rejected", required.contentEquals(first.bitmap.pixels()))
        instrumentation.waitForIdleSync()
        val later = capture.capture(apply = {
            instrumentation.runOnMainSync { check(published.get()); patch.invalidate() }
        }, receiptOnMain = { Receipt(patch.recordedColor, patch.recordings, published.get()) })
        assertTrue(later.receipt.latePublished)
        assertArrayEquals("Later equality must not replace rejected first pixels", required, later.bitmap.pixels())
        android.util.Log.i("PERF2_FIRST_NATIVE", "delayed first=${first.frame}/${first.receipt} later=${later.frame}/${later.receipt}")
    }

    @Test fun seesRetainedNativeMotionWithoutAnotherChildRecording() = fixture { _, patch, capture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val baseline = capture.capture(apply = {
            instrumentation.runOnMainSync { patch.color = Color.RED; patch.invalidate() }
        }, receiptOnMain = { Receipt(patch.recordedColor, patch.recordings, false) })
        val moved = capture.capture(apply = {
            instrumentation.runOnMainSync { patch.translationX = 48f }
        }, receiptOnMain = { Receipt(patch.recordedColor, patch.recordings, false) })
        assertArrayEquals(expected(Color.RED, Color.BLACK), baseline.bitmap.pixels())
        assertArrayEquals(expected(Color.BLACK, Color.RED), moved.bitmap.pixels())
        assertEquals("Native retention must not require a new child Canvas recording", baseline.receipt.recordings, moved.receipt.recordings)
        android.util.Log.i("PERF2_FIRST_NATIVE", "retained baseline=${baseline.frame}/${baseline.receipt} moved=${moved.frame}/${moved.receipt}")
    }
}
