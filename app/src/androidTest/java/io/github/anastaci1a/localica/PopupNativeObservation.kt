package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Test-only observation. The overlay token is outside the retained field recording;
 * a token match does not certify field freshness or physical presentation ownership. */
internal class PopupNativeObservation {
    private val receipts = ArrayList<JSONObject>() // UI thread only, frozen before finish.
    private val beforeDrawCallbacks = ArrayList<JSONObject>()
    private val beforeDrawCaptures = ArrayList<JSONObject>()
    private val captures = ArrayList<JSONObject>() // Single worker; read after finish.
    private val worker = Executors.newSingleThreadExecutor { work ->
        Thread(work, "popup-native-observation").apply { isDaemon = true }
    }
    private val busy = AtomicBoolean(false)
    private var sequence = 0
    private var attempts = 0 // UI thread only.
    private var closed = false
    var armed = false

    @Synchronized
    fun draw(canvas: Canvas, originX: Int, originY: Int, markerX: Int, markerY: Int,
        state: JSONObject, restored: Boolean, output: File) {
        if (closed || receipts.size >= 4096) return
        val token = ++sequence
        val color = Color.rgb(token and 255, (token shr 8) and 255, 173)
        // Stay inside the existing overlay token's damage bounds, away from its
        // synchronization sample. No new invalidation or enlarged damage region.
        canvas.drawRect(markerX - 1f, markerY - 1f, markerX + 1f, markerY + 1f,
            Paint().apply { this.color = color })
        receipts += state.put("sequence", token).put("drawNs", SystemClock.elapsedRealtimeNanos())
            .put("markerScreenX", originX + markerX).put("markerScreenY", originY + markerY)
        if (!armed || !restored || attempts >= 40 || !busy.compareAndSet(false, true)) return
        val attempt = attempts++
        // This task does not wait for the app thread or acknowledge any native gate.
        worker.execute {
            val record = JSONObject().put("attempt", attempt).put("requestedSequence", token)
                .put("captureStartNs", SystemClock.elapsedRealtimeNanos())
            var bitmap: Bitmap? = null
            try {
                val captured = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                bitmap = captured
                record.put("captureEndNs", SystemClock.elapsedRealtimeNanos())
                val pixel = captured.getPixel(originX + markerX, originY + markerY)
                record.put("markerPixel", pixel).put("markerBlue", Color.blue(pixel))
                    .put("observedSequence", Color.red(pixel) + (Color.green(pixel) shl 8))
                    .put("requestedMarkerMatched", pixel == color)
                val file = File(output, "native-observation-$attempt.png")
                file.outputStream().use { check(captured.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                record.put("png", file.name)
            } catch (failure: Throwable) {
                record.put("error", failure.toString())
            } finally {
                bitmap?.recycle()
                captures += record
                busy.set(false)
            }
        }
    }


    /** ViewRoot callback observes intervals the separately retained overlay may not redraw.
     * State is sampled BEFORE native drawing; neither it nor an old overlay token certifies presentation. */
    @Synchronized
    fun beforeDraw(state: JSONObject, restored: Boolean, output: File) {
        if (closed || beforeDrawCallbacks.size >= 4096) return
        val callback = beforeDrawCallbacks.size + 1
        val requestedNs = SystemClock.elapsedRealtimeNanos()
        beforeDrawCallbacks += state.put("callback", callback).put("beforeDrawNs", requestedNs)
        if (!armed || !restored || attempts >= 40 || !busy.compareAndSet(false, true)) return
        val attempt = attempts++
        val priorOverlaySequence = sequence
        worker.execute {
            val record = JSONObject().put("attempt", attempt).put("callback", callback)
                .put("beforeDrawNs", requestedNs).put("priorOverlaySequence", priorOverlaySequence)
                .put("captureStartNs", SystemClock.elapsedRealtimeNanos())
            var bitmap: Bitmap? = null
            try {
                val captured = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                bitmap = captured
                record.put("captureEndNs", SystemClock.elapsedRealtimeNanos())
                val file = File(output, "before-draw-native-$attempt.png")
                file.outputStream().use { check(captured.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                record.put("png", file.name)
            } catch (failure: Throwable) { record.put("error", failure.toString()) }
            finally { bitmap?.recycle(); beforeDrawCaptures += record; busy.set(false) }
        }
    }

    fun finish(output: File) {
        synchronized(this) { closed = true; armed = false; worker.shutdown() }
        check(worker.awaitTermination(20, TimeUnit.SECONDS)) { "Native observation worker did not finish" }
        File(output, "native-observation.json").writeText(JSONObject()
            .put("scope", "Overlay draw observation and asynchronous native screenshots only. Cached physical field geometry is not a field-recording token; no optical assertion is waived.")
            .put("receipts", JSONArray(receipts)).put("captures", JSONArray(captures))
            .put("beforeDrawCallbacks", JSONArray(beforeDrawCallbacks)).put("beforeDrawCaptures", JSONArray(beforeDrawCaptures)).toString(2))
    }
}
