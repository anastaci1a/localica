package io.github.anastaci1a.localica

import android.os.Build
import io.github.anastaci1a.localica.ui.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ordinary bounded metadata; no Bitmap, Compose State, source owner or Throwable retained. */
internal class GlassPreparationWorkRecorder : GlassPreparationWorkObserver {
    private data class Entry(val phase: String, val receivedNs: Long, val event: GlassPreparationWork)
    private val records = ArrayList<Entry>()
    private val totals = linkedMapOf<String, Int>()
    private val phases = ArrayList<Pair<String, Long>>()
    private val failures = ArrayList<String>()
    private var failureCount = 0
    private val rendererRequests = ArrayList<JSONObject>()
    private var phase = "initial"
    private var dropped = 0
    private var sealed = false
    @Synchronized fun phase(name: String) {
        check(!sealed)
        phase = name
        phases += name to System.nanoTime()
    }
    @Synchronized override fun record(event: GlassPreparationWork) {
        if (sealed) return
        val key = "$phase/${event.kind}"
        totals[key] = (totals[key] ?: 0) + 1
        if (records.size < 8192) records += Entry(phase, System.nanoTime(), event) else dropped++
    }
    @Synchronized override fun observationFailed(kind: String, failure: String) {
        if (!sealed) {
            failureCount++
            if (failures.size < 64) failures += "$phase/$kind/$failure"
        }
    }
    @Synchronized fun renderer(requested: String, backend: String, epoch: Long) {
        check(!sealed)
        rendererRequests += JSONObject().put("phase", phase).put("requested", requested)
            .put("backend", backend).put("epoch", epoch).put("receivedNs", System.nanoTime())
    }
    @Synchronized fun prepared(name: String, snapshotIdentity: Int, tint: Float): Boolean =
        records.any { it.phase == name && it.event.kind == "appearance-completed" &&
            it.event.parameter == 1f && it.event.images.firstOrNull()?.image == snapshotIdentity } &&
        records.any { it.phase == name && it.event.kind == "tint-clear" && it.event.parameter == tint }
    @Synchronized fun has(name: String, kind: String): Boolean = records.any { it.phase == name && it.event.kind == kind }
    @Synchronized fun seal(bodyFailure: Throwable?): JSONObject {
        check(!sealed)
        sealed = true
        return JSONObject().put("schema", 1).put("api", Build.VERSION.SDK_INT)
            .put("testId", "io.github.anastaci1a.localica.GlassPreparationProfileTest#actualLegacyStagesReportSizesAliasesCacheAndWorkAcrossChanges")
            .put("bodyOutcome", if (bodyFailure == null) "returned" else "threw")
            .put("endedNs", System.nanoTime()).put("droppedRecords", dropped)
            .put("observationFailureCount", failureCount).put("droppedFailureRecords", (failureCount - failures.size).coerceAtLeast(0))
            .put("rendererRequests", JSONArray(rendererRequests))
            .put("fixtureToneWeight", 2f).put("fixtureLightInk", true)
            .put("phaseAssociation", "observer delivery; not operation-start causality")
            .put("scopeComplete", false).put("tailComplete", false)
            .put("rawRecordsComplete", dropped == 0).put("observationFailures", JSONArray(failures))
            .put("aggregateCounts", JSONObject(totals as Map<*, *>))
            .put("phases", JSONArray().also { a -> phases.forEach { (name, ns) -> a.put(JSONObject().put("name", name).put("ns", ns)) } })
            .put("records", JSONArray().also { a -> records.forEach { entry ->
                val e = entry.event
                a.put(JSONObject().put("phase", entry.phase).put("receivedNs", entry.receivedNs).put("kind", e.kind).put("ownerHint", e.owner).put("epoch", e.epoch)
                    .put("startedNs", e.startedNs).put("endedNs", e.endedNs)
                    .put("parameter", if (e.parameter.isFinite()) e.parameter else JSONObject.NULL)
                    .put("cacheOutputBytes", e.cacheBytes).put("cacheHits", e.cacheHits).put("cacheMisses", e.cacheMisses)
                    .put("cacheEvictions", e.cacheEvictions).put("reportedCopyBytes", e.reportedCopyBytes)
                    .put("failure", e.failure ?: JSONObject.NULL).put("sameImage", JSONArray(e.sameImage))
                    .put("sameBitmap", JSONArray(e.sameBitmap)).put("images", JSONArray().also { images ->
                        e.images.forEach { image -> images.put(if (image == null) JSONObject.NULL else JSONObject()
                            .put("imageHint", image.image).put("bitmapHint", image.bitmap).put("width", image.width)
                            .put("height", image.height).put("config", image.config).put("mutable", image.mutable)
                            .put("reportedAllocationBytes", image.reportedBytes)) }
                    }))
            } })
            .put("limits", JSONArray(listOf("Instrumented stage wall time includes nested read-copy observer work; no uninstrumented CPU, queue or frame timing.",
                "Output/cache accounting and explicit normalization-copy reports are not unique, retained, peak or total heap.",
                "Identity hashes are hints; only same-event reference alias matrices are exact.",
                "Composition lookup/endpoint events are executions, not committed composition or native presentation.",
                "Cache size/hit/miss/eviction fields are sequential samples, not an atomic cache snapshot; recorder order is delivery order.",
                "One synthetic scene and bounded settings/source/renderer transitions; no full UI, hardware or global performance claim.",
                "Recording seals after fixture disposal; asynchronous post-seal worker completions and full tail are not observed.")))
    }
    fun export(document: JSONObject, directory: File) {
        check(directory.exists() || directory.mkdirs())
        val stem = "glass-preparation-${Build.VERSION.SDK_INT}-${System.nanoTime()}"
        val partial = File(directory, "$stem.partial")
        partial.writeText(document.toString(2) + "\n")
        check(partial.renameTo(File(directory, "$stem.json")))
    }
}
