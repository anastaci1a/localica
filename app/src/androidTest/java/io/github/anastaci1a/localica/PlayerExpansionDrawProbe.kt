package io.github.anastaci1a.localica

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.GlassSurfaceDrawObservation
import io.github.anastaci1a.localica.ui.GlassSurfaceSourceDraw
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in test evidence from the existing coating observer: no capture, invalidation,
 * resource retention or native-completion claim. Logging can still perturb timing.
 * Production calls already invoke this observer without snapshot read tracking.
 */
internal class PlayerExpansionDrawProbe private constructor(
    private val contentTag: String,
    private val progress: () -> Float,
    private val layout: () -> String,
) {
    private var sequence = 0L
    private var observations = 0L
    private var failed = false

    init { emit("begin") }

    val observer: (GlassSurfaceDrawObservation) -> Unit = { draw ->
        // Do not store draw, source objects, Views, layers, images or Jobs.
        observations++
        emit("draw") {
            put("observed_uptime_ms", draw.uptime)
            put("surface_id", draw.surfaceIdentity)
            put("view_id", draw.viewIdentity)
            put("attached", draw.attached)
            put("visible", draw.visible)
            put("width", draw.width)
            put("height", draw.height)
            put("root_corners", draw.rootCorners.json())
            put("silhouette", draw.silhouettePresent)
            put("shadow_enabled", draw.shadowEnabled)
            put("source", draw.source.json())
            put("home", draw.home.json())
        }
    }

    fun disposed() { emit("disposed") { put("observations", observations); put("failed", failed) } }

    private fun emit(event: String, fields: JSONObject.() -> Unit = {}) {
        val ordinal = sequence++
        try {
            val record = JSONObject()
                .put("schema", 1)
                .put("event", event)
                .put("sequence", ordinal)
                .put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
                .put("thread", Thread.currentThread().name)
                .put("content", contentTag)
                .put("layout", layout())
                .put("progress", progress())
            record.fields()
            Log.i(TAG, record.toString())
        } catch (_: Throwable) {
            // The diagnostic cannot change rendering. Its evidence fails closed instead.
            failed = true
            Log.e(TAG, "{\"schema\":1,\"event\":\"observation_error\",\"sequence\":$ordinal}")
        }
    }

    private fun List<Float>?.json(): Any = if (this == null) JSONObject.NULL else JSONArray(this)

    private fun GlassSurfaceSourceDraw?.json(): Any {
        val source = this ?: return JSONObject.NULL
        return JSONObject()
            .put("source_id", source.identity)
            .put("parent_source_id", source.parentIdentity ?: JSONObject.NULL)
            .put("layer_id", source.layerIdentity)
            .put("parent_layer_id", source.parentLayerIdentity ?: JSONObject.NULL)
            .put("recorded", source.recorded)
            .put("released", source.released)
            .put("parent_recorded", source.parentRecorded ?: JSONObject.NULL)
            .put("parent_released", source.parentReleased ?: JSONObject.NULL)
            .put("width", source.width)
            .put("height", source.height)
            .put("layer_width", source.layerWidth)
            .put("layer_height", source.layerHeight)
            .put("revision", source.revision)
            .put("requested_readback", source.requestedReadback)
            .put("published_readback", source.publishedReadback)
            .put("published_draw_request", source.publishedDrawRequest ?: JSONObject.NULL)
            .put("published_composite", source.publishedComposite ?: JSONObject.NULL)
            .put("snapshot_id", source.snapshotIdentity ?: JSONObject.NULL)
            .put("frost_id", source.frostIdentity ?: JSONObject.NULL)
            .put("lens_id", source.lensIdentity ?: JSONObject.NULL)
            .put("source_to_consumer", source.sourceToConsumer.json())
    }

    companion object {
        private const val TAG = "PlayerExpansionDraw"

        fun create(contentTag: String, progress: () -> Float, layout: () -> String): PlayerExpansionDrawProbe? {
            val enabled = InstrumentationRegistry.getArguments().getString("playerExpansionDrawProbe")
            require(enabled == null || enabled == "false" || enabled == "true") { "Invalid playerExpansionDrawProbe argument" }
            return if (enabled == "true" && contentTag == "empty-lyrics") PlayerExpansionDrawProbe(contentTag, progress, layout) else null
        }
    }
}
