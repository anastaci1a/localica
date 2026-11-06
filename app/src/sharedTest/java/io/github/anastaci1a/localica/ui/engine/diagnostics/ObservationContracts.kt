package io.github.anastaci1a.localica.ui.engine.diagnostics

import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.*

/** Frozen event shapes; the imports deliberately stay test-only until the A2 hook-adoption steps. */
internal enum class SegmentKind { Rest, Eased, Damped, Driven }
internal enum class JoinPolicy { Continuous, DrivenRelease, DrivenInput, Reduced, Initial }
internal data class MotionRegistration(val id: Long, val transitionKey: String, val range: Float, val value: MotionValue)
internal data class MotionJoin(
    val id: Long, val epoch: Long, val stamp: FrameStamp, val before: Kinematic, val after: Kinematic,
    val beforeKind: SegmentKind, val afterKind: SegmentKind, val policy: JoinPolicy,
    val acceptedReleaseVelocity: Float?, val finiteDifferenceVelocity: Float?,
)
internal data class Violation(val invariant: String, val subjectId: Long, val sequence: Long, val code: String, val detail: String)
internal fun interface ViolationSink { fun accept(violation: Violation) }
internal data class SurfaceObservation(
    val nodeId: Long, val shapeId: Long, val transitionKey: String, val host: HostKey,
    val generation: Long, val stamp: FrameStamp, val opacity: Float, val ownership: Float,
    val visibleLeaseCount: Int, val nativePresentedGeneration: Long?, val retired: Boolean,
)
internal data class StructuralSample(
    val stamp: FrameStamp, val pureMotionFrame: Boolean, val committedCompositions: Long,
    val paragraphMeasures: Long, val actualRenderNodes: Int?, val scalarReadAllocatedBytes: Long?,
    val activeMotions: Int, val clockAwaits: Long,
)
internal class BoundedEvidence(private val capacity: Int, private val owner: OwnerThread = OwnerThread()) : ViolationSink {
    init { require(capacity > 0) }
    private val records = mutableListOf<Violation>()
    var incomplete = false; private set
    val violations: List<Violation> get() { owner.check(); return records.toList() }
    override fun accept(violation: Violation) {
        owner.check(); if (incomplete) return
        if (records.size < capacity) records.add(violation) else {
            incomplete = true
            records[capacity - 1] = violation.copy(invariant = "Harness", code = "EvidenceOverflow", detail = "Run incomplete")
        }
    }
}
/** Disabled installation evaluates neither factory nor attach; no observer, worker, or clock is created. */
internal fun <T> installObservation(enabled: Boolean, factory: () -> T, attach: (T) -> Unit): T? =
    if (enabled) factory().also(attach) else null
