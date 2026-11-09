package io.github.anastaci1a.localica.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import org.json.JSONArray
import org.json.JSONObject

/** NEVER MERGE. Passive visual receipts only, enabled explicitly; timed arms never collect. */
internal object NoneSamplingReceipts {
    private val events = ArrayList<String>()
    private var dropped = 0
    private var failures = 0
    fun reset() { events.clear(); dropped = 0; failures = 0 }
    private fun receipt(block: () -> JSONObject) {
        if (!NoneSamplingDiagnostic.witness) return
        try { Snapshot.withoutReadObservation {
            if (events.size < 20_000) events.add(block().put("elapsedNs", SystemClock.elapsedRealtimeNanos()).toString())
            else dropped++
        } } catch (_: Throwable) { failures++ }
    }
    private fun source(backing: FrostedBackdrop?) = if (backing == null) JSONObject.NULL else JSONObject()
        .put("identity", System.identityHashCode(backing)).put("parent", backing.parent?.let(System::identityHashCode))
        .put("width", backing.size.width).put("height", backing.size.height)
        .put("recordings", backing.recordings).put("revision", backing.revision)
        .put("rendererEpoch", backing.rendererGeneration.epoch).put("recordedGeneration", backing.recordedGeneration.toString())
        .put("consumers", backing.consumers).put("lightMix", backing.lightMix)
    fun sample(backing: FrostedBackdrop, consumer: BackdropLocation, region: Rect,
        registration: BackdropRegistration, weight: Float, home: Boolean, side: Int?) = receipt {
        val corners = registration.inverse()?.corners(region)
        JSONObject().put("kind", "regional-reduction").put("mode", NoneSamplingDiagnostic.mode)
            .put("consumer", System.identityHashCode(consumer)).put("source", source(backing))
            .put("region", JSONArray(listOf(region.left, region.top, region.right, region.bottom)))
            .put("sourceCorners", JSONArray(corners?.map { listOf(it.x, it.y) }))
            .put("transform", JSONArray(listOf(registration.xx, registration.xy, registration.yx,
                registration.yy, registration.tx, registration.ty)))
            .put("home", home).put("weight", weight).put("sampleSide", side ?: JSONObject.NULL)
    }
    fun control(shape: LensGeometry, backing: FrostedBackdrop?, optical: Boolean) = receipt {
        JSONObject().put("kind", "control").put("mode", NoneSamplingDiagnostic.mode)
            .put("bounds", JSONArray(listOf(shape.bounds.left, shape.bounds.top, shape.bounds.right, shape.bounds.bottom)))
            .put("optical", optical).put("source", source(backing))
    }
    fun export(context: Context) {
        if (!NoneSamplingDiagnostic.witness) return
        val file = java.io.File(context.getExternalFilesDir("none-sampling"), "receipt.json")
        file.parentFile?.mkdirs()
        file.writeText("{\"mode\":\"${NoneSamplingDiagnostic.mode}\",\"dropped\":$dropped,\"failures\":$failures," +
            "\"exportElapsedNs\":${SystemClock.elapsedRealtimeNanos()},\"events\":[" + events.joinToString(",") + "]}")
        android.util.Log.i("NoneSamplingExport", "${file.absolutePath} events=${events.size} dropped=$dropped failures=$failures")
    }
}
