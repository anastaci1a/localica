package io.github.anastaci1a.localica.ui.engine.diagnostics

import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.OwnerThread
import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.HostKey

/** Synthetic receipt checks only; native presentation/transfer-ledger attestation is U12.01c. */
internal class IdentityObserver(sink: ViolationSink, private val owner: OwnerThread = OwnerThread()) {
    private var sink: ViolationSink? = sink
    private data class Key(val transition: String, val host: HostKey, val generation: Long)
    private val observations = linkedMapOf<Key, SurfaceObservation>()
    private var closed = false
    fun observe(observation: SurfaceObservation) {
        owner.check(); check(!closed); val o = observation
        fun bad(code: String, invariant: String) = sink?.accept(Violation(invariant, o.nodeId, o.stamp.sequence, code, "Synthetic identity receipt: $code"))
        if (o.nodeId < 0 || o.shapeId < 0 || o.transitionKey.isBlank() || o.host.id.isBlank() || o.host.epoch < 0 || o.generation < 0 ||
            !o.opacity.isFinite() || o.opacity !in 0f..1f || !o.ownership.isFinite() || o.ownership !in 0f..1f || o.visibleLeaseCount < 0 ||
            o.nativePresentedGeneration?.let { it < 0 } == true) { bad("InvalidSurfaceObservation", "I10"); return }
        val key = Key(o.transitionKey,o.host,o.generation)
        val previous = observations[key]
        if (previous?.retired == true && !o.retired) { bad("RetiredGenerationResurrected", "I5"); return }
        if (previous != null && (o.stamp.sequence < previous.stamp.sequence || o.stamp.nanos < previous.stamp.nanos)) { bad("StaleObservation", "I10"); return }
        val live = observations.values.filter { it.transitionKey == o.transitionKey && !it.retired }
        if (live.any { it.nodeId != o.nodeId }) { bad("DuplicateOwner", "I6"); return }
        if (live.any { it.shapeId != o.shapeId }) { bad("ShapeIdentityChanged", "I4"); return }
        if (observations.values.any { it.transitionKey == o.transitionKey && it.host == o.host && it.generation > o.generation }) {
            bad("StaleGeneration", "I5"); return
        }
        if (o.retired && (o.opacity != 0f || o.ownership != 0f || o.visibleLeaseCount != 0 ||
                o.nativePresentedGeneration == null || o.nativePresentedGeneration < o.generation)) { bad("EarlyRetirement", "I5"); return }
        observations[key] = o
    }
    fun close() { owner.check(); if (closed) return; closed = true; observations.clear(); sink = null }
}
