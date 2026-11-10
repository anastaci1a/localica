package io.github.anastaci1a.localica

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameStats
import android.view.PixelCopy
import android.view.ViewTreeObserver
import android.view.Window
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** EXPERIMENTAL test-only primitive. No redraw, publication delay, retry or later-buffer fallback.
 * It accepts only a cleared native window inventory containing exactly one PRESENTED buffer.
 * Support for WindowContentFrameStats on each emulator remains an explicit native prerequisite. */
internal class FirstPresentedBufferCapture(private val automation: UiAutomation, private val windowId: Int,
    private val window: Window, private val region: Rect) {
    data class NativeFrame(val posted: Long, val ready: Long, val presented: Long)
    data class Capture<T>(val bitmap: Bitmap, val frame: NativeFrame, val receipt: T)

    /** Must be invoked off Main. [apply] must not invoke a blocking Compose test API.
     * The caller drives its single known apply/frame inside [apply] or immediately after arming;
     * a traversal before the desired apply is a failed control, never silently skipped. */
    fun <T : Any> capture(apply: () -> Unit, receiptOnMain: () -> T): Capture<T> {
        check(Looper.myLooper() != Looper.getMainLooper())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val view = window.decorView
        check(view.isHardwareAccelerated && view.isAttachedToWindow && view.hasWindowFocus())
        check(region.width() > 0 && region.height() > 0)
        check(automation.clearWindowContentFrameStats(windowId)) { "Cannot clear native window frame inventory" }
        val before = checkNotNull(automation.getWindowContentFrameStats(windowId))
        check(before.frameCount == 0) { "Native frame inventory is not empty after clear" }
        val main = Handler(Looper.getMainLooper())
        val frozen = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val traversals = AtomicInteger()
        val receipt = AtomicReference<T>()
        val mainFailure = AtomicReference<Throwable>()
        val pixelThread = HandlerThread("first-buffer-copy").apply { start() }
        val pixelHandler = Handler(pixelThread.looper)
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (traversals.incrementAndGet() != 1) return
                main.postAtFrontOfQueue {
                    try {
                        view.viewTreeObserver.removeOnDrawListener(this)
                        receipt.set(receiptOnMain())
                        frozen.countDown()
                        check(release.await(4, TimeUnit.SECONDS)) { "First native-buffer Main barrier expired" }
                    } catch (failure: Throwable) {
                        mainFailure.set(failure)
                        frozen.countDown()
                    } finally { exited.countDown() }
                }
            }
        }
        var armed = false
        try {
            instrumentation.runOnMainSync {
                view.viewTreeObserver.addOnDrawListener(listener)
                armed = true
            }
            // No capture-induced invalidation. This is the caller's actual input/apply operation.
            apply()
            check(frozen.await(3, TimeUnit.SECONDS)) { "No native traversal for the requested apply" }
            mainFailure.get()?.let { throw it }
            check(traversals.get() == 1) { "Ambiguous native traversal inventory" }
            fun presented(): NativeFrame? {
                val stats = checkNotNull(automation.getWindowContentFrameStats(windowId))
                check(stats.frameCount <= 1) { "More than one queued native window buffer after clear" }
                if (stats.frameCount == 0) return null
                val row = NativeFrame(stats.getFramePostedTimeNano(0), stats.getFrameReadyTimeNano(0),
                    stats.getFramePresentedTimeNano(0))
                if (listOf(row.posted, row.ready, row.presented).any { it == FrameStats.UNDEFINED_TIME_NANO }) return null
                check(row.posted > 0 && row.ready > 0 && row.presented >= row.ready) { "Invalid native presentation times: $row" }
                return row
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var frame = presented()
            while (frame == null && System.nanoTime() < deadline) {
                Thread.sleep(2)
                frame = presented()
            }
            val first = checkNotNull(frame) { "No exactly-one presented native buffer; unsupported or incomplete frame stats" }
            val bitmap = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            val copyResult = AtomicInteger(-1)
            PixelCopy.request(window, region, bitmap, { result -> copyResult.set(result); copied.countDown() }, pixelHandler)
            check(copied.await(1, TimeUnit.SECONDS)) { "First-buffer PixelCopy timed out" }
            check(copyResult.get() == PixelCopy.SUCCESS) { "First-buffer PixelCopy failed: ${copyResult.get()}" }
            check(presented() == first) { "Native buffer inventory changed during copy" }
            mainFailure.get()?.let { throw it }
            check(exited.count == 1L) { "Main barrier ended before capture completed" }
            return Capture(bitmap, first, checkNotNull(receipt.get()))
        } finally {
            release.countDown()
            if (frozen.count == 0L) check(exited.await(1, TimeUnit.SECONDS)) { "Main barrier failed to release" }
            if (armed) instrumentation.runOnMainSync {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener)
            }
            pixelThread.quitSafely()
            pixelThread.join(500)
        }
    }
}
