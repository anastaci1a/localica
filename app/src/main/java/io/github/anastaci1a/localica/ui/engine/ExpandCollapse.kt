package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.*
import kotlin.math.*
import kotlinx.coroutines.flow.first

private fun coordinates(r: Rect)=doubleArrayOf(r.left.toDouble(),r.top.toDouble(),r.right.toDouble(),r.bottom.toDouble())
@Immutable data class ExpansionBasis(val startBounds: Rect,val endBounds: Rect,val density: Float) {
    private val start=coordinates(startBounds); private val end=coordinates(endBounds); private val deltas=DoubleArray(4) { end[it]-start[it] }; val edgeIndex: Int; val edgeTravelPx: Float
    init { require(density.isFinite() && density>0 && (start+end).all(Double::isFinite) && startBounds.left<=startBounds.right && startBounds.top<=startBounds.bottom && endBounds.left<=endBounds.right && endBounds.top<=endBounds.bottom); edgeIndex=(0..3).maxBy { abs(deltas[it]) }; edgeTravelPx=abs(deltas[edgeIndex]).toFloat(); require(edgeTravelPx.isFinite()) }
    internal fun delta(i: Int)=deltas[i]
    internal fun at(p: Double)=DoubleArray(4) { (1-p)*start[it]+p*end[it] }
    internal fun rect(p: Double)=at(p).let { Rect(it[0].toFloat(),it[1].toFloat(),it[2].toFloat(),it[3].toFloat()) }
}
sealed interface BasisChange { data object Rescale: BasisChange; data object Rebase: BasisChange }
enum class ExpansionReason { User,Programmatic,Restore,Cancel }
@Immutable data class ReleaseDecision(val expanded: Boolean,val durationSeconds: Float,val handOffVelocity: Float)
@Stable interface ExpansionView { val phase: MotionValue; val expanded: Boolean; val inContact: Boolean; val canContact: Boolean }
internal fun expansionDuration(spec: ExpansionSpec,travel: Double,reference: Double,p: Double,v: Double,q: Double,sigma: Double,display: Double): Double { val full=spec.referenceDurationMs/1000.0; val objectTime=max(spec.floorMs/1000.0,full*(travel/reference).pow(spec.sizeExponent.toDouble())); val raw=sigma*objectTime*(spec.positionFloor+(1.0-spec.positionFloor)*abs(q-p))/max(1.0,abs(v*sigma*objectTime/full)/spec.velocityKnee).pow(spec.velocityExponent.toDouble()); return max(raw,2*display).also { require(it.isFinite() && it.toFloat().isFinite()) } }
// Each state is x,v,q,lower,upper. Residuals are Double analysis, not additional motion owners.
internal fun expansionPlan(stamp: FrameStamp,spec: ExpansionSpec,T: Double,sigma: Double,states: List<DoubleArray>)=boundedPlan(stamp,spec.core,T,sigma,states)
internal fun boundedPlan(stamp: FrameStamp,core: EasingSpec,T: Double,sigma: Double,states: List<DoubleArray>): EasedPlan {
    val curve=Bezier(core); val extrema=easingExtrema(core); var tin=min(sigma/120,T/4); var tout=min(T/8,sigma*.064)
    for(s in states) { val d=s[2]-s[0]; val B=abs(d)/T*core.maxSlope; val a=s[0]+d*extrema.start; val b=s[0]+d*extrema.endInclusive; require(min(a,b)>=s[3] && max(a,b)<=s[4]) { "Core is infeasible for the incoming bounded state" }; if(s[1]!=0.0) { val room=if(s[1]>0) s[4]-s[0] else s[0]-s[3]; require(room>0) { "Unreachable outward boundary state ${s.toList()}" }; tin=min(tin,room/(.149*(abs(s[1])+B)+if(s[1]*d>0) B else 0.0)) } }
    fun limits(s: DoubleArray,tau: Double,terminal: Boolean): ClosedFloatingPointRange<Double> { val u=if(terminal) 1-tau/T else tau/T; val x=s[0]+(s[2]-s[0])*curve.at(u); val v=(s[2]-s[0])*curve.at(u,true)/T; return if(terminal) hermiteExtrema(x,v,s[2],0.0,tau) else hermiteExtrema(s[0],s[1],x,v,tau) }
    while(states.any { val r=limits(it,tout,true); r.start<it[3] || r.endInclusive>it[4] }) { tout/=2; require(tout>0) { "Unrepresentable terminal bridge" } }
    require(tin>0 && tin.isFinite()); states.forEach { val r=limits(it,tin,false); require(r.start>=it[3] && r.endInclusive<=it[4]) { "Startup bridge escaped its proven band" } }; return EasedPlan(stamp,core,T,sigma,tin,tout)
}
@Stable class ExpandCollapse internal constructor(private val clock: UiClock,private var spec: ExpansionSpec,initiallyExpanded: Boolean,key: Any=Any()): ExpansionView {
    private val motion=MotionFloat(clock,if(initiallyExpanded) 1f else 0f,1f,key=key to "phase"); override val phase: MotionValue get()=motion
    private var logical by mutableStateOf(initiallyExpanded); override val expanded get()=logical; private var touching by mutableStateOf(false); override val inContact get()=touching
    private var held by mutableStateOf(false)
    private var basis by mutableStateOf<ExpansionBasis?>(null); override val canContact: Boolean get()=if(held || boundCurve!=null) false else basis?.let { b -> clock.context?.let { ctx -> b.edgeTravelPx>=1f && finiteExpansionFinish(capturedSpec(),b.edgeTravelPx.toDouble()/ctx.fullPlayerReferenceTravelPx,ctx.displayIntervalSeconds.toDouble()) } }?:false; private var bound: MotionRect?=null; private var boundCurve: Curve?=null; private var nativeBoundary: Float?=null; private var nativeSide=false; private var pendingNative by mutableStateOf<Boolean?>(null); internal var nativeGeneration=0L; private set; internal var pendingBasis: ExpansionBasis?=null; private set; internal var pendingTarget: Boolean?=null; private set
    private data class Contact(val basis: ExpansionBasis,val reference: Double,val p: Double,val edges: DoubleArray?,val previousTarget: Boolean,val spec: ExpansionSpec)
    internal val lifecycle=linkedSetOf<(Boolean)->Unit>(); internal fun owns(rect: MotionRectView)=bound===rect
    internal val ownerClock get()=clock
    private val physicalRest: Boolean get() { motion.epoch.intValue; bound?.channels?.forEach { it.epoch.intValue }; return motion.settled && bound?.channels?.all { it.settled }!=false }
    internal val awaitingNativeGate get()=!held && pendingNative!=null && physicalRest
    internal val retirementReady get()=!held && pendingNative==null && physicalRest
    internal val nativeOpening get()=pendingNative
    internal fun nativeGateCurrent(generation: Long,opening: Boolean) =
        !disposed && generation==nativeGeneration && pendingNative==opening && awaitingNativeGate
    /** A receipt can arrive while its home owns presentation. Resume only the retained current gate. */
    internal suspend fun awaitNativeGate(generation: Long,opening: Boolean): Boolean {
        clock.checkMutation()
        snapshotFlow { disposed || generation!=nativeGeneration || pendingNative!=opening || awaitingNativeGate }.first { it }
        clock.checkMutation()
        return nativeGateCurrent(generation,opening)
    }
    /** Native readiness is an intent barrier, never an elapsed-frame cutoff. The #608 trajectory rests at the seam before admission. */
    internal fun nativeGate(at: Float) { clock.checkMutation(); require(at.isFinite() && at in 0f..1f); check(nativeBoundary==null && !motion.presented || nativeBoundary==at); nativeBoundary=at }
    internal fun acknowledgeNativeGate(generation: Long): Boolean {
        clock.checkMutation(); if(disposed || generation!=nativeGeneration || !awaitingNativeGate) return false
        // A canceled, unadmitted opening returns without ever taking native ownership.
        if(pendingNative!=true || logical) nativeSide=requireNotNull(pendingNative)
        pendingNative=null; identity=null; target(logical); return true
    }
    private fun notifyLifecycle(intent: Boolean=false) { lifecycle.toList().forEach { it(intent) } }
    private fun basisEdges(value: ExpansionBasis,p: Double)=value.at(boundCurve?.at(p.toFloat())?.toDouble()?:p)
    private fun basisSlope(value: ExpansionBasis,i: Int,p: Double)=value.delta(i)*(boundCurve?.slope(p.toFloat())?.toDouble()?:1.0)
    private var flightSpec=spec; private fun capturedSpec()=contact?.spec?:if(motion.kind==1) flightSpec else spec
    private var contact: Contact?=null; private var lastStamp=clock.stampNow; private var identity: Any?=null; private var disposed by mutableStateOf(false)
    init { clock.checkMutation(); require(spec.band>=MinimumContactBand); motion.onRest={ if(touching && clock.context?.access==MotionAccess.Reduced) endReduced() else applyPendingNoop(); notifyLifecycle() } }
    internal fun updateSpec(value: ExpansionSpec) { clock.checkMutation(); require(value.band>=MinimumContactBand); spec=value }
    private fun context()=requireNotNull(clock.context) { "Publish real EngineContext through U11.00 before expansion intents" }
    private fun guard(stamp: FrameStamp) { clock.checkMutation(); check(!disposed); require(stamp.nanos>=lastStamp.nanos && stamp.nanos>=clock.presentedStamp.nanos) { "Stale expansion input" } }
    private fun endpointSame(old: ExpansionBasis,new: ExpansionBasis)=old.at(if(logical) 1.0 else 0.0).contentEquals(new.at(if(logical) 1.0 else 0.0))
    private fun applyPendingNoop() { val next=pendingBasis; val old=basis; if(!held && !touching && bound==null && motion.settled && next!=null && old!=null && endpointSame(old,next)) { basis=next; pendingBasis=null; identity=null } }
    private fun classify(next: ExpansionBasis,stamp: FrameStamp): BasisChange {
        val old=basis?:return BasisChange.Rescale; val p=motion.rawValue(stamp); val v=motion.rawVelocity(stamp); var c: Double?=null
        for(i in 0..3) { if(old.delta(i)==0.0) { if(next.delta(i)!=0.0) return BasisChange.Rebase } else { val ratio=next.delta(i)/old.delta(i); if(c!=null && ratio!=c) return BasisChange.Rebase; c=ratio } }; if(c!=null && c!=1.0 && v!=0.0) return BasisChange.Rebase
        val physical=bound?.channels; val newEdges=basisEdges(next,p); val oldEdges=basisEdges(old,p); for(i in 0..3) { val x=physical?.get(i)?.rawValue(stamp)?:oldEdges[i]; val w=physical?.get(i)?.rawVelocity(stamp)?:basisSlope(old,i,p)*v; val R=physical?.get(i)?.range?.toDouble()?:max(1.0,abs(old.delta(i))); if(abs(newEdges[i]-x)>1e-8*R || abs(basisSlope(next,i,p)*v-w)>1e-8*R) return BasisChange.Rebase }; return BasisChange.Rescale
    }
    fun bind(rect: MotionRect)=bindRect(rect,null)
    internal fun bind(rect: MotionRect,geometry: Curve)=bindRect(rect,geometry)
    private fun bindRect(rect: MotionRect,geometry: Curve?) { val stamp=clock.stampNow; guard(stamp); require(rect.clock===clock); check(bound==null || bound===rect); if(bound===rect) { require(boundCurve===geometry); return }; check(geometry==null || !touching); val oldCurve=boundCurve; boundCurve=geometry
        val next=pendingBasis?:basis; val prepared=try { next?.let { prepare(logical,stamp,it,rect) }.also { rect.claim(this) } } catch(failure: Throwable) { boundCurve=oldCurve; throw failure }; bound=rect; rect.channels.forEach { it.onRest={ notifyLifecycle() } }; if(prepared!=null) { pendingBasis=null; basis=next; apply(prepared,logical,stamp) } }

    fun setBasis(value: ExpansionBasis): BasisChange { val stamp=clock.stampNow; guard(stamp); if(value==basis) { pendingBasis=null; return BasisChange.Rescale }; val change=classify(value,stamp); if(touching || held) { pendingBasis=value; lastStamp=stamp; return change }; if(basis==null && bound==null) { basis=value; lastStamp=stamp; return change }; if(change==BasisChange.Rebase && bound==null) { pendingBasis=value; applyPendingNoop(); return change }; val prepared=prepare(logical,stamp,value,bound); basis=value; pendingBasis=null; apply(prepared,logical,stamp); return change }
    /** A new native window has no presented rectangle to preserve. At the ordinary
     * source gate only, seed its first measured home without reusing a dead window's edges. */
    internal fun initializeNativeHome(value: ExpansionBasis, host: ShadowPlane): Boolean {
        val stamp=clock.stampNow; guard(stamp)
        require(host.sameClock(clock)) { "A native host shares its expansion clock" }
        if(held || host.generation!=0L || nativeBoundary!=0f || nativeSide || !physicalRest || motion.rawValue(stamp)!=0.0 || motion.rawVelocity(stamp)!=0.0) return false
        val prepared=prepare(logical,stamp,value,bound,initialize=true)
        basis=value; pendingBasis=null; apply(prepared,logical,stamp)
        return true
    }
    private data class Prepared(val entries: List<Pair<MotionFloat,MotionIntent>>,val plan: EasedPlan,val duration: Double,val approvedTau: Double,val spec: ExpansionSpec,val gate: Boolean?)
    private fun prepare(q: Boolean,stamp: FrameStamp,next: ExpansionBasis,rect: MotionRect?,releaseVelocity: Double?=null,initialize: Boolean=false): Prepared {
        val law=contact?.spec?:spec; val ctx=context(); val sigma=if(ctx.access==MotionAccess.Faster) 2.0/3 else 1.0; val x=motion.rawValue(stamp); val v=releaseVelocity?:motion.rawVelocity(stamp); val waiting=if(nativeBoundary==null) null else if(awaitingNativeGate || held && pendingNative!=null) pendingNative else q.takeIf { it!=nativeSide }; val target=waiting?.let { requireNotNull(nativeBoundary).toDouble() }?:if(q) 1.0 else 0.0; val geometry=contact?.basis?:next; val reference=contact?.reference?:ctx.fullPlayerReferenceTravelPx.toDouble(); val T=if(ctx.access==MotionAccess.Reduced || initialize) 2*ctx.displayIntervalSeconds.toDouble() else expansionDuration(law,geometry.edgeTravelPx.toDouble(),reference,x,v,target,sigma,ctx.displayIntervalSeconds.toDouble())
        val edges=rect?.channels; val edgeV=if(releaseVelocity!=null && rect!=null) DoubleArray(4) { (contact?.basis?:basis?:next).delta(it)*releaseVelocity } else null
        require(finiteExpansionFinish(law,geometry.edgeTravelPx.toDouble()/reference,ctx.displayIntervalSeconds.toDouble())) { "Expansion duration is outside its admitted geometry domain" }; val states=mutableListOf(doubleArrayOf(x,v,target,-law.band.toDouble(),(1f+law.band).toDouble())); if(edges!=null) for(i in 0..3) { val residual=edges[i].rawValue(stamp)-basisEdges(next,x)[i]; val velocity=(edgeV?.get(i)?:edges[i].rawVelocity(stamp))-basisSlope(next,i,x)*v; val room=abs(residual)+law.band*edges[i].range.toDouble(); states.add(doubleArrayOf(residual,velocity,0.0,-room,room)) }
        val rest=x==target && v==0.0 && (edges==null || edges.indices.all { edges[it].rawValue(stamp)==basisEdges(next,target)[it] && (edgeV?.get(it)?:edges[it].rawVelocity(stamp))==0.0 }); val reduced=ctx.access==MotionAccess.Reduced; val instant=reduced || initialize
        val plan=if(instant || rest) EasedPlan(stamp,law.core,T,sigma,min(sigma/120,T/4)) else expansionPlan(stamp,law,T,sigma,states)
        val policy=if(reduced) "Reduced" else if(initialize) "Initial" else "Continuous"; val segment=if(instant || rest) Rest(target) else plan.segment(x,v,target,states.first()[3]..states.first()[4]); val intents=mutableListOf(motion to motion.prepareSegment(segment,target.toFloat(),stamp,plan,policy,releaseVelocity?.toFloat()))
        if(rect!=null) { val physical=if(instant || rest) rect.channels.mapIndexed { i,m -> m.prepareSegment(Rest(basisEdges(next,target)[i]),basisEdges(next,target)[i].toFloat(),stamp,plan,policy) } else if(boundCurve!=null) rect.prepareMapped(next,requireNotNull(boundCurve),segment,target,plan,this) else rect.prepare(next.rect(target),plan,this,edgeV); intents.addAll(rect.entries(physical)) }; return Prepared(intents,plan,if(instant || rest) 0.0 else T,min(sigma/120,T/4),law,waiting)
    }
    private fun apply(value: Prepared,q: Boolean,stamp: FrameStamp) { if(pendingNative!=value.gate && value.gate!=null) nativeGeneration++; pendingNative=value.gate; logical=q; flightSpec=value.spec; lastStamp=stamp; identity=listOf(q,spec,basis,context()); commitAtomic(value.entries); notifyLifecycle(true) }
    fun target(expanded: Boolean,reason: ExpansionReason=ExpansionReason.Programmatic) { val stamp=clock.stampNow; guard(stamp); if(held) { logical=expanded; identity=null; lastStamp=stamp; return }; if(touching) { pendingTarget=expanded; lastStamp=stamp; return }; val current=requireNotNull(basis) { "Measure an expansion basis before target" }; if(identity==listOf(expanded,spec,current,context()) && pendingBasis==null) return; val initialize=reason==ExpansionReason.Restore && !motion.presented && bound?.channels?.any { it.presented }!=true; val prepared=prepare(expanded,stamp,current,bound,initialize=initialize); apply(prepared,expanded,stamp) }
    /** The departing home owns presentation until disposal. Preserve the same phase and edges. */
    internal fun hold() {
        val stamp=clock.stampNow; guard(stamp); check(!touching) { "Contact and departing-home ownership cannot overlap" }
        if(held) return
        val channels=listOf(motion)+(bound?.channels?:emptyList())
        val entries=channels.map { channel ->
            val value=channel.rawValue(stamp)
            channel to channel.prepareSegment(Driven(value,0.0),value.toFloat(),stamp,policy="DrivenInput")
        }
        held=true; identity=null; lastStamp=stamp
        // Frozen destinations also preserve the frame when ordinary live reduction drains these channels.
        commitAtomic(entries,true)
    }
    internal fun release() {
        val stamp=clock.stampNow; guard(stamp)
        if(!held) return
        val next=if(bound!=null) pendingBasis?:basis else basis
        val prepared=requireNotNull(next).let { prepare(logical,stamp,it,bound) }
        held=false
        if(bound!=null) { basis=next; pendingBasis=null }
        apply(prepared,logical,stamp)
    }
    fun contactBegin(stamp: FrameStamp) { guard(stamp); check(!held && !touching); val current=requireNotNull(basis); check(canContact) { "Contact travel cannot normalize the admitted Float tracker domain" }; val p=motion.rawValue(stamp); val sample=bound?.channels?.map { it.rawValue(stamp) }?.toDoubleArray(); val phaseInput=motion.prepareSegment(Driven(p,motion.rawVelocity(stamp)),motion.target,stamp,policy="DrivenInput"); val entries=mutableListOf(motion to phaseInput); bound?.let { r -> val rect=Rect(sample!![0].toFloat(),sample[1].toFloat(),sample[2].toFloat(),sample[3].toFloat()); entries.addAll(r.entries(r.prepareDrive(rect,stamp,this,DoubleArray(4) { r.channels[it].rawVelocity(stamp) }))) }; contact=Contact(current,context().fullPlayerReferenceTravelPx.toDouble(),p,sample,logical,capturedSpec()); touching=true; pendingTarget=null; identity=null; lastStamp=stamp; commitAtomic(entries,true) }
    fun contactMove(edgeDisplacementPx: Float,stamp: FrameStamp) { guard(stamp); check(touching); require(edgeDisplacementPx.isFinite()); val frozen=contact!!; val p=(frozen.p+edgeDisplacementPx/frozen.basis.edgeTravelPx.toDouble()).coerceIn(0.0,1.0); val drive=motion.prepareDrive(p.toFloat(),stamp,if(p==0.0 || p==1.0) 0.0 else null); val entries=mutableListOf(motion to drive); bound?.let { r -> val x=DoubleArray(4) { frozen.edges!![it]+frozen.basis.delta(it)*(p-frozen.p) }; entries.addAll(r.entries(r.prepareDrive(Rect(x[0].toFloat(),x[1].toFloat(),x[2].toFloat(),x[3].toFloat()),stamp,this,DoubleArray(4) { frozen.basis.delta(it)*drive.next.velocity(0.0) }))) }; lastStamp=stamp; commitAtomic(entries,true) }
    fun contactRelease(edgeVelocityPxPerSecond: Float,stamp: FrameStamp): ReleaseDecision { guard(stamp); check(touching); require(edgeVelocityPxPerSecond.isFinite()); val frozen=contact!!; val law=frozen.spec; val v=edgeVelocityPxPerSecond/frozen.basis.edgeTravelPx.toDouble(); require(v.toFloat().isFinite()); val ctx=context(); val sigma=if(ctx.access==MotionAccess.Faster) 2.0/3 else 1.0; val objectTime=max(law.floorMs/1000.0,law.referenceDurationMs/1000.0*(frozen.basis.edgeTravelPx/frozen.reference).pow(law.sizeExponent.toDouble())); val referenceVelocity=v*sigma*objectTime/(law.referenceDurationMs/1000.0); val q=if(abs(referenceVelocity)<law.deadBand) motion.rawValue(stamp)>=law.stationaryOpenAt else referenceVelocity>0; return finishContact(q,stamp,v) }
    private fun finishContact(q: Boolean,stamp: FrameStamp,v: Double?): ReleaseDecision { val oldSpec=contact!!.spec; val next=if(bound!=null) pendingBasis?:basis!! else basis!!; val prepared=prepare(q,stamp,next,bound,v); touching=false; contact=null; pendingTarget=null; if(bound!=null) { basis=next; pendingBasis=null }; apply(prepared,q,stamp); if(oldSpec!=spec) identity=null; if(v!=null && prepared.duration>0) clock.events?.let { sink -> clock.observe { sink.releaseBridge(motion,prepared.plan.tauIn,prepared.approvedTau,context().displayIntervalSeconds.toDouble()) } }; return ReleaseDecision(q,prepared.duration.toFloat(),(v?:motion.rawVelocity(stamp)).toFloat()) }
    fun contactCancel() { val stamp=clock.stampNow; guard(stamp); if(!touching) return; finishContact(pendingTarget?:contact!!.previousTarget,stamp,null) }
    private fun endReduced() { val stamp=clock.stampNow; val next=pendingBasis?:basis; val prepared=next?.let { prepare(logical,stamp,it,bound) }; touching=false; contact=null; pendingTarget=null; if(next!=null) { basis=next; pendingBasis=null }; if(prepared!=null) apply(prepared,logical,stamp) }
    internal fun dispose() { clock.checkMutation(); if(disposed) return; try { check(pendingBasis==null || bound!=null) { "Unbound pending Rebase at owner disposal" } } finally { disposed=true; lifecycle.clear(); motion.onRest=null; motion.dispose(); bound?.dispose() } }
}
@Composable fun rememberExpandCollapse(key: Any,initiallyExpanded: Boolean): ExpandCollapse { val clock=ownerClock(); val spec=LocalUiSpec.current.spec.expansion; val owner=remember(clock,key) { ExpandCollapse(clock,spec,initiallyExpanded,key) }; SideEffect { owner.updateSpec(spec) }; DisposableEffect(owner) { onDispose { owner.dispose() } }; return owner }
/** U07.00 (D41–D43): a popup's one expansion, driven only by its components PopupState. The basis (presented home → envelope) comes with the presenter's measurement; an earlier intent waits for it. U04.02a: retained faces (MorphFaces, presence, panel) start the same owner at [initial]. */
@Stable internal class PopupMotion(private val clock: UiClock,spec: ExpansionSpec,key: Any,initial: Boolean=false) { internal val expansion=ExpandCollapse(clock,spec,initial,key to "popup"); private var placed=false; private var shown=initial
    internal var bounds by mutableStateOf<MotionRect?>(null); private set
    internal fun show(visible: Boolean) { clock.checkMutation(); if(placed) expansion.target(visible); shown=visible }
    internal fun place(basis: ExpansionBasis) {
        expansion.setBasis(basis)
        if(!placed) {
            val span=requireNotNull(clock.spec.geometry).maxViewportDiagonalPx
            val rect=MotionRect(clock,if(expansion.expanded) basis.endBounds else basis.startBounds,EdgeRanges(span,span,span,span),expansion)
            expansion.bind(rect); bounds=rect
        }
        placed=true; expansion.target(shown)
    } }
@Composable internal fun rememberPopupMotion(key: Any,initial: Boolean=false): PopupMotion { val clock=ownerClock(); val spec=LocalUiSpec.current.spec.expansion; val owner=remember(clock,key) { PopupMotion(clock,spec,key,initial) }; SideEffect { owner.expansion.updateSpec(spec) }; DisposableEffect(owner) { onDispose { owner.expansion.dispose() } }; return owner }
