package io.github.anastaci1a.localica.ui.engine.diagnostics

import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.FrameStamp as EvidenceStamp
import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.Kinematic as EvidenceKinematic
import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.MotionValue as EvidenceValue
import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.ActiveMotion as EvidenceActive

/** U12.01b actual-engine hook. Installed by correctness fixtures only, never app timing runs. */
internal class EngineObservation(private val clock: UiClock,sink: ViolationSink): MotionEvents,AutoCloseable {
    val continuity=ContinuityObserver(sink)
    var subFrameReleases=0; private set
    var bandLimitedReleases=0; private set
    override fun releaseBridge(motion: MotionFloat,tau: Double,approvedTau: Double,displayInterval: Double) { if(tau<displayInterval) subFrameReleases++; if(tau<approvedTau) bandLimitedReleases++ }
    private val keys=linkedMapOf<Any,String>()
    private val previous=clock.events
    private val frames=FrameObserver { stamp,active -> continuity.onFrame(EvidenceStamp(stamp.nanos,stamp.sequence),active.map { EvidenceActive(it.id,it.presented) }) }
    init { require(clock.activeCount==0 && previous==null); clock.events=this; clock.observers.add(frames) }
    override fun created(motion: MotionFloat) {
        continuity.register(MotionRegistration(motion.id,keys.getOrPut(motion.key) { "owner-${motion.id}" },motion.range,object: EvidenceValue {
            override val target get()=motion.target
            override fun value()=motion.value()
            override fun sampleAt(stamp: EvidenceStamp)=motion.sampleAt(FrameStamp(stamp.nanos,stamp.sequence)).let { EvidenceKinematic(it.value,it.velocity) }
        }))
    }
    override fun joined(motion: MotionFloat,join: io.github.anastaci1a.localica.ui.engine.MotionJoin) {
        continuity.onJoin(MotionJoin(motion.id,join.epoch.toLong(),EvidenceStamp(join.stamp.nanos,join.stamp.sequence),EvidenceKinematic(join.before.value,join.before.velocity),EvidenceKinematic(join.after.value,join.after.velocity),SegmentKind.entries[join.beforeKind],SegmentKind.entries[join.afterKind],JoinPolicy.valueOf(join.policy),join.releaseVelocity,join.finiteDifferenceVelocity))
    }
    override fun retired(motion: MotionFloat,stamp: FrameStamp)=continuity.retire(motion.id,EvidenceStamp(stamp.nanos,stamp.sequence))
    override fun close() { clock.checkMutation(); clock.events=previous; clock.observers.remove(frames); continuity.close() }
}

/** Registers the actual closed-vocabulary factories; Float output rounding is explicit in the oracle. */
internal class EngineCurveObservation: AutoCloseable {
    val registry=CurveRegistry()
    private val previous=CurveHooks.created
    private var next=0L
    init { check(previous==null); CurveHooks.created={ curve,joins -> registry.register("curve-${++next}",object: io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.Curve {
        override fun at(p: Double)=curve.at(p.toFloat()).toDouble()
        override fun slope(p: Double)=curve.slope(p.toFloat()).toDouble()
    },joins,true) } }
    override fun close() { CurveHooks.created=previous }
}
