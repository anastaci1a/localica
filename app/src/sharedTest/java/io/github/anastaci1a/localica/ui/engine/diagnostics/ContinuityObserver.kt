package io.github.anastaci1a.localica.ui.engine.diagnostics

import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.*
import kotlin.math.abs
import kotlin.math.max

/** Observes supplied analytic limits. It neither advances a clock nor mutates a presentation owner. */
internal class ContinuityObserver(
    sink: ViolationSink, private val owner: OwnerThread = OwnerThread(), detach: () -> Unit = {},
) : FrameObserver, AutoCloseable {
    private var sink: ViolationSink? = sink
    private var detach: (() -> Unit)? = detach
    private data class Entry(val registration: MotionRegistration, var epoch: Long = -1, var stamp: FrameStamp? = null,
        var presented: Boolean = false, var kind: SegmentKind? = null)
    private val entries = linkedMapOf<Long, Entry>()
    private var largestId = -1L
    private var lastFrame: FrameStamp? = null
    private var closed = false
    val registeredCount: Int get() { owner.check(); return entries.size }
    private fun checkOpen() { owner.check(); check(!closed) { "Observer closed" } }
    private fun bad(id: Long, stamp: FrameStamp?, code: String, invariant: String = "I1") {
        sink?.accept(Violation(invariant, id, stamp?.sequence ?: 0, code, "Observation rejected: $code"))
    }
    fun register(registration: MotionRegistration) {
        checkOpen()
        if (registration.id < 0 || registration.id <= largestId || !registration.value.target.isFinite() || registration.transitionKey.isBlank() || !registration.range.isFinite() || registration.range <= 0) {
            bad(registration.id, null, "InvalidRegistration", "I10"); return
        }
        if (entries.values.any { it.registration.transitionKey == registration.transitionKey }) {
            bad(registration.id, null, "DuplicateOwner", "I6"); return
        }
        entries[registration.id] = Entry(registration); largestId = registration.id
    }
    fun onJoin(join: MotionJoin) {
        checkOpen(); val entry = entries[join.id]
        if (entry == null) { bad(join.id, join.stamp, "UnknownMotion", "I10"); return }
        if (join.epoch <= entry.epoch || join.epoch < 0 || older(join.stamp, entry.stamp)) {
            bad(join.id, join.stamp, "InvalidEpochOrStamp", "I10"); return
        }
        if (!finite(join.before) || !finite(join.after)) { bad(join.id, join.stamp, "NonFiniteKinematics", "I10"); return }
        val range = entry.registration.range.toDouble()
        val valueError = abs(join.before.value.toDouble() - join.after.value) / range
        val velocityError = abs(join.before.velocity.toDouble() - join.after.velocity) / range
        when (join.policy) {
            JoinPolicy.Initial -> if (entry.presented) bad(join.id, join.stamp, "LateInitialExemption")
            JoinPolicy.DrivenInput -> if (join.afterKind != SegmentKind.Driven) bad(join.id, join.stamp, "InvalidDrivenInput")
            JoinPolicy.Reduced -> Unit // An explicit classified exemption; the real Engine must attest access at hookup.
            JoinPolicy.Continuous -> {
                if (valueError > 1e-4) bad(join.id, join.stamp, "ValueJump")
                if (join.afterKind != SegmentKind.Driven && velocityError > 1e-3) bad(join.id, join.stamp, "VelocityJump")
                if (join.beforeKind == SegmentKind.Driven && join.afterKind != SegmentKind.Driven) bad(join.id, join.stamp, "MissingReleaseEvidence")
            }
            JoinPolicy.DrivenRelease -> {
                val tracker = join.acceptedReleaseVelocity; val fd = join.finiteDifferenceVelocity
                if (join.beforeKind != SegmentKind.Driven || tracker == null || fd == null || !tracker.isFinite() || !fd.isFinite()) {
                    bad(join.id, join.stamp, "MissingReleaseEvidence")
                } else {
                    if (join.before.value != join.after.value) bad(join.id, join.stamp, "ValueJump")
                    if (abs(join.after.velocity.toDouble() - tracker) / range > 1e-3) bad(join.id, join.stamp, "ReleaseVelocityJump")
                    if (abs(tracker.toDouble() - fd) / range > max(.5 * abs(fd) / range, .5)) bad(join.id, join.stamp, "ReleaseDisagreement")
                }
            }
        }
        if (join.afterKind == SegmentKind.Rest && (join.after.velocity != 0f || join.after.value != entry.registration.value.target)) {
            bad(join.id, join.stamp, "InvalidRest")
        }
        entry.epoch = join.epoch; entry.stamp = join.stamp; entry.kind = join.afterKind
    }
    override fun onFrame(stamp: FrameStamp, active: List<ActiveMotion>) {
        checkOpen()
        if (lastFrame != null && (stamp.sequence <= lastFrame!!.sequence || stamp.nanos <= lastFrame!!.nanos)) {
            bad(0, stamp, "DuplicateOrReversedFrame", "I10"); return
        }
        lastFrame = stamp
        val ids = mutableSetOf<Long>()
        for (motion in active) {
            if (!ids.add(motion.id)) { bad(motion.id, stamp, "DuplicateActive", "I6"); continue }
            val entry = entries[motion.id]
            if (entry == null) { bad(motion.id, stamp, "UnknownMotion", "I10"); continue }
            if (entry.stamp != null && stamp.sequence < entry.stamp!!.sequence) { bad(motion.id, stamp, "StaleFrame", "I10"); continue }
            if (motion.presented) entry.presented = true
            if (!finite(entry.registration.value.sampleAt(stamp))) bad(motion.id, stamp, "NonFiniteKinematics", "I10")
        }
        // A frame alone never attests first presentation; fixture inputs must identify a real read.
    }
    fun retire(id: Long, stamp: FrameStamp) {
        checkOpen(); val entry = entries[id]
        if (entry == null) { bad(id, stamp, "UnbalancedRetirement", "I10"); return }
        if (older(stamp, entry.stamp)) { bad(id, stamp, "StaleRetirement", "I10"); return }
        val final = entry.registration.value.sampleAt(stamp)
        if (entry.kind != SegmentKind.Rest || !finite(final) || final.velocity != 0f || final.value != entry.registration.value.target) {
            bad(id, stamp, "MissingFinalRest"); return
        }
        entries.remove(id)
    }
    override fun close() {
        owner.check(); if (closed) return; closed = true
        try { detach?.invoke() } finally { entries.clear(); lastFrame = null; sink = null; detach = null }
    }
    private fun finite(k: Kinematic) = k.value.isFinite() && k.velocity.isFinite()
    private fun older(a: FrameStamp, b: FrameStamp?) = b != null && (a.sequence < b.sequence || a.nanos < b.nanos)
}
