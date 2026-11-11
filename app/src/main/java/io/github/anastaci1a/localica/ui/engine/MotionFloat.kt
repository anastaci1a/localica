package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.ui.spec.*
import kotlin.math.*

@Immutable @ConsistentCopyVisibility data class MotionProfileId internal constructor(val name: String)
@Immutable @ConsistentCopyVisibility data class SpringProfileId internal constructor(val name: String)
internal fun motionProfileId(profile: MotionProfile)=MotionProfileId(profile.name)
internal fun springProfileId(profile: SpringProfile)=SpringProfileId(profile.name)
@Stable interface MotionValue { fun value(): Float; fun velocity(): Float; fun sample(): Kinematic; fun valueAt(stamp: FrameStamp): Float; fun velocityAt(stamp: FrameStamp): Float; fun sampleAt(stamp: FrameStamp): Kinematic; val target: Float; val settled: Boolean }
internal data class MotionJoin(val epoch: Int,val stamp: FrameStamp,val before: Kinematic,val after: Kinematic,val beforeKind: Int,val afterKind: Int,val policy: String,val releaseVelocity: Float?=null,val finiteDifferenceVelocity: Float?=null,val envelopeSeconds: Double?=null,val finishSeconds: Double?=null)
internal interface MotionEvents { fun created(motion: MotionFloat); fun joined(motion: MotionFloat,join: MotionJoin); fun driven(motion: MotionFloat,stamp: FrameStamp,sample: Kinematic) {}; fun retired(motion: MotionFloat,stamp: FrameStamp); fun releaseBridge(motion: MotionFloat,tau: Double,approvedTau: Double,displayInterval: Double) {} }
internal data class MotionIntent(val next: Trajectory,val stamp: FrameStamp,val destination: Float,val descriptor: Any?,val previous: Trajectory,val before: Kinematic,val releaseVelocity: Float?=null,val finiteDifferenceVelocity: Float?=null,val policy: String="Continuous")
@Stable class MotionFloat internal constructor(internal val clock: UiClock,initial: Float,internal val range: Float,internal val bounds: ClosedFloatingPointRange<Double>?=null,internal val key: Any=Any(),born: FrameStamp?=null): MotionValue,ActiveMotion {
    private data class Past(val trajectory: Trajectory,val started: FrameStamp,val previous: Past?)
    private val destination=mutableFloatStateOf(initial); private val epochState=mutableIntStateOf(0); private var segment: Trajectory=Rest(initial.toDouble()); private var started=born?:clock.stampNow; private var descriptor: Any?=null; private var drivenStamp: FrameStamp?=null; private var disposed=false; private var past: Past?=null
    override val id: Long
    override var presented=false; private set
    internal var onRest: (()->Unit)?=null
    internal val epoch: IntState get()=epochState
    internal val kind: Int get()=segment.kind
    override val target: Float get()=destination.floatValue
    override val settled: Boolean get()=segment.kind==0
    init { clock.checkMutation(); require(initial.isFinite() && range.isFinite() && range>0 && (bounds==null || initial.toDouble() in bounds)); id=clock.allocateId(); clock.events?.let { sink -> clock.observe { sink.created(this); sink.joined(this,MotionJoin(0,started,Kinematic(initial,0f),Kinematic(initial,0f),0,0,"Initial")) } } }
    // Compose keeps evaluator velocity in play-time units; only timed elapsed is scaled.
    // Driven values and their input velocities always retain real timestamp units.
    private fun elapsed(trajectory: Trajectory,start: FrameStamp,stamp: FrameStamp): Double {
        val seconds=(stamp.nanos-start.nanos)/1e9
        if(trajectory.kind !in 1..2) return seconds
        val scale=clock.context?.durationScale?:1f
        return when(scale) { 1f -> seconds; 0f -> trajectory.duration; else -> seconds/scale }
    }
    private fun raw(stamp: FrameStamp,derivative: Boolean): Double { var selected=segment; var start=started; var old=past; while(stamp.nanos<start.nanos && old!=null) { selected=old.trajectory; start=old.started; old=old.previous }; val t=elapsed(selected,start,stamp); return if(derivative) selected.velocity(t) else selected.value(t) }
    internal fun rawValue(stamp: FrameStamp)=raw(stamp,false)
    internal fun rawVelocity(stamp: FrameStamp)=raw(stamp,true)
    private fun presentedRead() { if(!presented && !clock.isObserving) presented=true }
    override fun valueAt(stamp: FrameStamp): Float { presentedRead(); return rawValue(stamp).toFloat() }
    override fun velocityAt(stamp: FrameStamp): Float { presentedRead(); return rawVelocity(stamp).toFloat() }
    override fun sampleAt(stamp: FrameStamp): Kinematic { presentedRead(); return Kinematic(rawValue(stamp).toFloat(),rawVelocity(stamp).toFloat()) }
    override fun value(): Float { epochState.intValue; return if(settled) { presentedRead(); segment.value(0.0).toFloat() } else valueAt(clock.stamp) }
    override fun velocity(): Float { epochState.intValue; return if(settled) { presentedRead(); 0f } else velocityAt(clock.stamp) }
    override fun sample(): Kinematic { epochState.intValue; return if(settled) { presentedRead(); Kinematic(segment.value(0.0).toFloat(),0f) } else sampleAt(clock.stamp) }
    private fun context()=requireNotNull(clock.context) { "Publish real EngineContext through U11.00 before a motion intent" }
    internal fun prepare(target: Float,profile: MotionProfileId,stamp: FrameStamp=clock.stampNow,maxTauIn: Double=Double.POSITIVE_INFINITY,force: Boolean=false): MotionIntent? {
        clock.checkMutation(); check(!disposed); require(target.isFinite()); val data=clock.spec.motion[MotionProfile.valueOf(profile.name)] as? MotionProfileSpec?:throw IllegalArgumentException("Scalar intent requires a scalar profile"); val access=context().access; val sigma=if(access==MotionAccess.Faster) 2.0/3 else 1.0; val identity=listOf(profile,data,sigma)
        val x=rawValue(stamp); val v=rawVelocity(stamp); if(!force && target==this.target && segment.kind!=3 && (target.toDouble()==x && v==0.0 || descriptor==identity)) return null
        val next=if(access==MotionAccess.Reduced || target.toDouble()==x && v==0.0) Rest(target.toDouble()) else Eased(x,v,target.toDouble(),data.easing,data.durationMs*sigma/1000,sigma,bounds,maxTauIn)
        return prepareSegment(next,target,stamp,identity,if(access==MotionAccess.Reduced) "Reduced" else "Continuous")
    }
    internal fun prepareSegment(next: Trajectory,target: Float,stamp: FrameStamp,identity: Any?=null,policy: String="Continuous",acceptedVelocity: Float?=null): MotionIntent {
        clock.checkMutation(); check(!disposed); require(target.isFinite() && (bounds==null || target.toDouble() in bounds)); require(stamp.nanos>=started.nanos) { "Intent predates the current trajectory" }; val fd=if(segment.kind==3) rawVelocity(stamp).toFloat() else null; val accepted=acceptedVelocity?:fd
        return MotionIntent(next,stamp,target,identity,segment,Kinematic(rawValue(stamp).toFloat(),accepted?:rawVelocity(stamp).toFloat()),accepted,fd,if(policy=="Continuous" && fd!=null) "DrivenRelease" else policy)
    }
    internal fun validate(intent: MotionIntent) { clock.checkMutation(); check(!disposed && segment===intent.previous) { "Stale atomic motion intent" } }
    internal fun commit(intent: MotionIntent,notify: Boolean=true) {
        clock.checkMutation(); check(!disposed); check(segment===intent.previous) { "Stale atomic motion intent" }; past=if(intent.policy in setOf("Reduced","Initial","DrivenInput")) null else Past(segment,started,past); segment=intent.next; started=intent.stamp; descriptor=intent.descriptor; destination.floatValue=intent.destination; epochState.intValue++; if(segment.kind!=3) drivenStamp=null; if(segment.kind==0) clock.unregister(this) else clock.register(this); if(notify) emit(intent)
    }
    internal fun emit(intent: MotionIntent) { clock.events?.let { sink -> clock.observe { sink.joined(this,MotionJoin(epochState.intValue,intent.stamp,intent.before,Kinematic(intent.next.value(0.0).toFloat(),intent.next.velocity(0.0).toFloat()),intent.previous.kind,intent.next.kind,intent.policy,intent.releaseVelocity,intent.finiteDifferenceVelocity,(intent.next as? Damped)?.envelopeSeconds,intent.next.duration)) } }; if(intent.next.kind==0) onRest?.invoke() }
    fun retarget(target: Float,profile: MotionProfileId) { prepare(target,profile)?.let { commit(it) } }
    fun retargetSpring(target: Float,spring: SpringProfileId) {
        clock.checkMutation(); check(!disposed); require(target.isFinite()); val spec=clock.spec.spring.getValue(SpringProfile.valueOf(spring.name)); val context=context(); val sigma=if(context.access==MotionAccess.Faster) 2.0/3 else 1.0; val speed=(if(spec.speedExponent==2) 4.0/3 else 1.0)/sigma; val identity=listOf(spring,spec,sigma); val stamp=clock.stampNow; val x=rawValue(stamp); val v=rawVelocity(stamp)
        if(target==this.target && segment.kind!=3 && (target.toDouble()==x && v==0.0 || descriptor==identity)) return
        require(context.access==MotionAccess.Reduced || spring.name==SpringProfile.Strain.name || abs(x-target)<=1.05*range && abs(v)<=vMax.toDouble()*range) { "Spring state exceeds its declared admitted range" }
        val next=if(context.access==MotionAccess.Reduced || target.toDouble()==x && v==0.0) Rest(target.toDouble()) else Damped(x,v,target.toDouble(),sqrt(spec.stiffness.toDouble())*speed,spec.dampingRatio.toDouble(),range.toDouble(),context.displayIntervalSeconds.toDouble()); commit(prepareSegment(next,target,stamp,identity,if(context.access==MotionAccess.Reduced) "Reduced" else "Continuous"))
    }
    fun drive(value: Float,stamp: FrameStamp) { commitDrive(prepareDrive(value,stamp)) }
    internal fun prepareDrive(value: Float,stamp: FrameStamp,velocityOverride: Double?=null): MotionIntent {
        clock.checkMutation(); check(!disposed); require(value.isFinite() && (bounds==null || value.toDouble() in bounds)); require(stamp.nanos>=clock.presentedStamp.nanos && stamp.nanos>=(drivenStamp?.nanos?:Long.MIN_VALUE)) { "Stale drive sample" }; val previous=drivenStamp; val velocity=if(previous==null || stamp.nanos==previous.nanos) rawVelocity(stamp) else (value-rawValue(previous))/(stamp.nanos-previous.nanos)*1e9; val v=if(bounds!=null && (value.toDouble()==bounds.start || value.toDouble()==bounds.endInclusive)) 0.0 else velocityOverride?:velocity; require(v.toFloat().isFinite())
        return prepareSegment(Driven(value.toDouble(),v),target,stamp,policy="DrivenInput")
    }
    internal fun commitDrive(intent: MotionIntent,notify: Boolean=true) { clock.checkMutation(); check(segment===intent.previous); if(segment.kind!=3) commit(intent,false) else { segment=intent.next; started=intent.stamp; past=null; clock.register(this) }; drivenStamp=intent.stamp; if(notify) emitDrive(intent) }
    internal fun emitDrive(intent: MotionIntent) { if(intent.previous.kind!=3) emit(intent); clock.events?.let { sink -> clock.observe { sink.driven(this,intent.stamp,Kinematic(intent.next.value(0.0).toFloat(),intent.next.velocity(0.0).toFloat())) } } }
    fun release(target: Float,profile: MotionProfileId,releaseVelocity: Float) {
        clock.checkMutation(); check(segment.kind==3); require(releaseVelocity.isFinite()); val stamp=clock.stampNow; val data=clock.spec.motion[MotionProfile.valueOf(profile.name)] as? MotionProfileSpec?:throw IllegalArgumentException("Scalar release profile required"); val context=context(); val sigma=if(context.access==MotionAccess.Faster) 2.0/3 else 1.0; val next=if(context.access==MotionAccess.Reduced || rawValue(stamp)==target.toDouble() && releaseVelocity==0f) Rest(target.toDouble()) else Eased(rawValue(stamp),releaseVelocity.toDouble(),target.toDouble(),data.easing,data.durationMs*sigma/1000,sigma,bounds); commit(prepareSegment(next,target,stamp,listOf(profile,data,sigma),if(context.access==MotionAccess.Reduced) "Reduced" else "DrivenRelease",releaseVelocity)); drivenStamp=null
    }
    internal fun initialize(value: Float) { clock.checkMutation(); require(value.isFinite()); check(!presented || context().access==MotionAccess.Reduced); commit(prepareSegment(Rest(value.toDouble()),value,clock.stampNow,policy=if(context().access==MotionAccess.Reduced) "Reduced" else "Initial")) }
    // An intent stamped from the system clock can postdate the frame being dispatched; with a zero duration scale
    // elapsed() ignores time, so a segment that has not started yet must not finish at that earlier frame.
    override fun endsAt(stamp: FrameStamp): Boolean { if(stamp.nanos<started.nanos) return false; past=null; return segment.kind in 1..2 && elapsed(segment,started,stamp)>=segment.duration }
    override fun finish(stamp: FrameStamp) { val intent=prepareSegment(Rest(target.toDouble()),target,stamp,descriptor); segment=intent.next; started=stamp; past=null; epochState.intValue++; emit(intent) }
    override fun reduce(stamp: FrameStamp) { commit(prepareSegment(Rest(target.toDouble()),target,stamp,policy="Reduced")) }
    internal fun dispose() { clock.checkMutation(); if(disposed) return; clock.unregister(this); clock.events?.let { sink -> clock.observe { sink.retired(this,clock.stampNow) } }; disposed=true }
}
@Composable fun rememberMotionFloat(key: Any,initial: Float,range: Float): MotionFloat { val clock=ownerClock(); val result=remember(clock,key) { MotionFloat(clock,initial,range,key=key) }; require(result.range==range) { "Owner range is immutable" }; DisposableEffect(result) { onDispose { result.dispose() } }; return result }
@Composable fun rememberNormalizedMotionFloat(key: Any,initial: Float)=rememberMotionFloat(key,initial,1f)

internal fun commitAtomic(entries: List<Pair<MotionFloat,MotionIntent>>,drive: Boolean=false) { require(entries.isNotEmpty() && entries.map { it.first }.distinct().size==entries.size); val clock=entries.first().first.clock; val stamp=entries.first().second.stamp; require(entries.all { it.first.clock===clock && it.second.stamp===stamp }); val plans=entries.mapNotNull { (it.second.next as? Eased)?.plan }; require(plans.isEmpty() || plans.first().stamp===stamp && plans.all { it===plans.first() } && entries.all { it.second.descriptor===plans.first() }) { "Atomic channels require one source plan" }; entries.forEach { (m,i) -> m.validate(i) }; entries.forEach { (m,i) -> if(drive) m.commitDrive(i,false) else m.commit(i,false) }; entries.forEach { (m,i) -> if(drive) m.emitDrive(i) else m.emit(i) } }
