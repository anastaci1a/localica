package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import io.github.anastaci1a.localica.ui.spec.*
import kotlin.math.*

@Immutable data class EdgeRanges(val left: Float,val top: Float,val right: Float,val bottom: Float) { init { require(listOf(left,top,right,bottom).all { it.isFinite() && it>0 }) }; internal fun values()=floatArrayOf(left,top,right,bottom) }
@Stable interface MotionRectView { val left: MotionValue; val top: MotionValue; val right: MotionValue; val bottom: MotionValue; fun value(): Rect; fun valueAt(stamp: FrameStamp): Rect }
@Stable interface MotionColorView { fun value(): Color; fun valueAt(stamp: FrameStamp): Color }
internal data class EasedPlan(val stamp: FrameStamp,val core: UnitCurveSpec,val duration: Double,val sigma: Double,val tauIn: Double,val tauOut: Double=min(duration/8,sigma*.064)) { fun segment(x: Double,v: Double,q: Double,bounds: ClosedFloatingPointRange<Double>?=null)=Eased(x,v,q,core,duration,sigma,bounds,tauIn,this,tauOut).also { require(it.tauIn==tauIn && it.tauOut==tauOut) { "All bound channels must use the source plan" } } }
/** #854: one spring intent's shared finish frame and terminal bridge (U05 A2.2a), the analog of [EasedPlan]. */
internal class SpringPlan(val stamp: FrameStamp,val finish: Double,val tauOut: Double,val sigma: Double) {
    init { require(finish.isFinite() && finish>0 && tauOut.isFinite() && tauOut>0 && tauOut<=finish && sigma.isFinite() && sigma>0) }
    fun segment(x: Double,v: Double,q: Double,law: SpringLaw,range: Double,display: Double)=Damped(x,v,q,law.omega/sigma,law.zeta.toDouble(),range,display,finish,tauOut,this)
    companion object {
        /** finish = max(2 frames, every channel's settle envelope); tauOut = min(finish/8, .064 sigma, 1/slowest pole). Each state is x, v, q, omega_eff, zeta, range. */
        fun of(stamp: FrameStamp,sigma: Double,display: Double,states: List<DoubleArray>): SpringPlan {
            val finish=states.maxOf { settleEnvelope(it[0]-it[2],it[1],it[3],it[4],it[5]) }.coerceAtLeast(2*display)
            val slowest=states.minOf { val w=it[3]; val z=it[4]; if(z>1) w/(z+sqrt(z*z-1)) else z*w }
            return SpringPlan(stamp,finish,minOf(finish/8,.064*sigma,1/slowest),sigma)
        }
    }
}
private fun edges(rect: Rect)=floatArrayOf(rect.left,rect.top,rect.right,rect.bottom).also { require(it.all(Float::isFinite)) }
@Stable class MotionRect internal constructor(internal val clock: UiClock,initial: Rect,internal val ranges: EdgeRanges,key: Any=Any()): MotionRectView {
    private val initialEdges=edges(initial); internal val channels=Array(4) { MotionFloat(clock,initialEdges[it],ranges.values()[it],key=key to "edge$it") }; private var binding: Any?=null; private var last: Any?=null
    override val left: MotionValue get()=channels[0]; override val top: MotionValue get()=channels[1]; override val right: MotionValue get()=channels[2]; override val bottom: MotionValue get()=channels[3]
    override fun value()=Rect(left.value(),top.value(),right.value(),bottom.value())
    override fun valueAt(stamp: FrameStamp)=Rect(left.valueAt(stamp),top.valueAt(stamp),right.valueAt(stamp),bottom.valueAt(stamp))
    internal fun claim(owner: Any) { clock.checkMutation(); require(binding==null || binding===owner) { "Rectangle already has an expansion owner" }; binding=owner }
    internal fun prepare(target: Rect,plan: EasedPlan,owner: Any,velocity: DoubleArray?=null): List<MotionIntent> { clock.checkMutation(); require(binding==null || binding===owner); val values=edges(target); require(velocity==null || velocity.size==4 && velocity.all(Double::isFinite)); return channels.mapIndexed { i,m -> val x=m.rawValue(plan.stamp); val v=velocity?.get(i)?:m.rawVelocity(plan.stamp); val next=if(clock.context!!.access==MotionAccess.Reduced) Rest(values[i].toDouble()) else plan.segment(x,v,values[i].toDouble()); m.prepareSegment(next,values[i],plan.stamp,plan,if(clock.context!!.access==MotionAccess.Reduced) "Reduced" else "Continuous",velocity?.get(i)?.toFloat()) } }
    /** One analytic phase plus C1 edge residuals; the physical channels remain the expansion's bound rectangle. */
    /** #854: each edge maps its own axis phase (the main or the cross-axis snap phase) through [curve]; its rebase residual is a spring on that axis law. */
    internal fun prepareMapped(basis: ExpansionBasis,curve: Curve,phases: List<Trajectory>,target: DoubleArray,residuals: List<Trajectory>,plan: SpringPlan,owner: Any): List<MotionIntent> {
        clock.checkMutation(); require(binding==null || binding===owner)
        return channels.mapIndexed { i,m -> val start=basis.at(0.0)[i]; val delta=basis.delta(i)
            m.prepareSegment(MappedEdge(phases[i],curve,start,delta,residuals[i]),(start+delta*curve.at(target[i].toFloat())).toFloat(),plan.stamp,plan) }
    }
    internal fun prepareMapped(basis: ExpansionBasis,curve: Curve,phase: Trajectory,target: Double,plan: EasedPlan,owner: Any): List<MotionIntent> {
        clock.checkMutation(); require(binding==null || binding===owner)
        return channels.mapIndexed { i,m ->
            val start=basis.at(0.0)[i]; val delta=basis.delta(i); val p=phase.value(0.0); val v=phase.velocity(0.0)
            val residual=plan.segment(m.rawValue(plan.stamp)-start-delta*curve.at(p.toFloat()),m.rawVelocity(plan.stamp)-delta*curve.slope(p.toFloat())*v,0.0)
            m.prepareSegment(MappedEdge(phase,curve,start,delta,residual), (start+delta*curve.at(target.toFloat())).toFloat(),plan.stamp,plan)
        }
    }
    internal fun prepareDrive(target: Rect,stamp: FrameStamp,owner: Any?=null,velocity: DoubleArray?=null): List<MotionIntent> { clock.checkMutation(); require(binding==null && owner==null || binding===owner); val values=edges(target); require(velocity==null || velocity.size==4 && velocity.all(Double::isFinite)); return channels.mapIndexed { i,m -> m.prepareDrive(values[i],stamp,velocity?.get(i)) } }
    internal fun entries(intents: List<MotionIntent>)=channels.zip(intents)
    fun retarget(target: Rect,profile: MotionProfileId) { clock.checkMutation(); check(binding==null) { "Bound rectangle intents belong to ExpandCollapse" }; val values=edges(target); val identity=listOf(target,profile,clock.spec.motion[MotionProfile.valueOf(profile.name)],clock.context?.access); if(last==identity && channels.none { it.kind==3 }) return; val stamp=clock.stampNow; val intents=channels.mapIndexed { i,m -> m.prepare(values[i],profile,stamp,force=true)!! }; commitAtomic(entries(intents)); last=identity }
    fun drive(rect: Rect,stamp: FrameStamp) { clock.checkMutation(); check(binding==null) { "Bound rectangle intents belong to ExpandCollapse" }; commitAtomic(entries(prepareDrive(rect,stamp)),true); last=null }
    internal fun dispose() { channels.forEach { it.dispose() } }
}
@Stable class MotionColor internal constructor(private val clock: UiClock,initial: Color,key: Any=Any()): MotionColorView {
    private val initialLab=toOklab(initial).also { requireColorCapacity(it.take(3).map { v -> v.toDouble()..v.toDouble() }) }; private val spans=floatArrayOf(1f,.51010436f,.5100979f,1f); private val channels=Array(4) { MotionFloat(clock,initialLab[it],spans[it],if(it==3) 0.0..1.0 else null,key to "color$it") }; private var last: Any?=null
    override fun value()=linearColor(channels[0].value(),channels[1].value(),channels[2].value(),channels[3].value())
    override fun valueAt(stamp: FrameStamp)=linearColor(channels[0].valueAt(stamp),channels[1].valueAt(stamp),channels[2].valueAt(stamp),channels[3].valueAt(stamp))
    fun retarget(target: Color,profile: MotionProfileId) { clock.checkMutation(); val values=toOklab(target); val identity=listOf(target,profile,clock.spec.motion[MotionProfile.valueOf(profile.name)],clock.context?.access); if(last==identity && channels.none { it.kind==3 }) return; val stamp=clock.stampNow; val intents=channels.mapIndexed { i,m -> m.prepare(values[i],profile,stamp,force=true)!! }; requireColorCapacity(intents.take(3).map { val t=it.next; if(t is Eased) t.extrema else t.value(0.0)..t.value(0.0) }); commitAtomic(channels.zip(intents)); last=identity }
    fun drive(color: Color,stamp: FrameStamp) { clock.checkMutation(); val values=toOklab(color); requireColorCapacity(values.take(3).map { it.toDouble()..it.toDouble() }); val intents=channels.mapIndexed { i,m -> m.prepareDrive(values[i],stamp) }; commitAtomic(channels.zip(intents),true); last=null }
    internal fun dispose() { channels.forEach { it.dispose() } }
}
// Ottosson's 2021 matrices: https://bottosson.github.io/posts/oklab/ ; output avoids Compose's Oklab clamps.
internal fun oklabToLinear(l: Double,a: Double,b: Double,index: Int): Double { val x=l+.3963377774*a+.2158037573*b; val y=l-.1055613458*a-.0638541728*b; val z=l-.0894841775*a-1.291485548*b; return when(index) { 0 -> 4.0767416621*x*x*x-3.3077115913*y*y*y+.2309699292*z*z*z; 1 -> -1.2684380046*x*x*x+2.6097574011*y*y*y-.3413193965*z*z*z; else -> -.0041960863*x*x*x-.7034186147*y*y*y+1.707614701*z*z*z } }
private fun linearColor(l: Float,a: Float,b: Float,alpha: Float)=Color(oklabToLinear(l.toDouble(),a.toDouble(),b.toDouble(),0).toFloat(),oklabToLinear(l.toDouble(),a.toDouble(),b.toDouble(),1).toFloat(),oklabToLinear(l.toDouble(),a.toDouble(),b.toDouble(),2).toFloat(),alpha,ColorSpaces.LinearExtendedSrgb)
internal fun toOklab(color: Color): FloatArray { require(color!=Color.Unspecified); fun gamma(v: Float): Double { val x=v.toDouble(); return if(x<=.04045) x/12.92 else ((x+.055)/1.055).pow(2.4) }; val c=if(color.colorSpace==ColorSpaces.Srgb) color else color.convert(ColorSpaces.LinearExtendedSrgb); val r=if(color.colorSpace==ColorSpaces.Srgb) gamma(c.red) else c.red.toDouble(); val g=if(color.colorSpace==ColorSpaces.Srgb) gamma(c.green) else c.green.toDouble(); val b=if(color.colorSpace==ColorSpaces.Srgb) gamma(c.blue) else c.blue.toDouble(); val x=Math.cbrt(.4122214708*r+.5363325363*g+.0514459929*b); val y=Math.cbrt(.2119034982*r+.6806995451*g+.1073969566*b); val z=Math.cbrt(.0883024619*r+.2817188376*g+.6299787005*b); return floatArrayOf((.2104542553*x+.793617785*y-.0040720468*z).toFloat(),(1.9779984951*x-2.428592205*y+.4505937099*z).toFloat(),(.0259040371*x+.7827717662*y-.808675766*z).toFloat(),color.alpha).also { require(it.all(Float::isFinite)) } }
private fun requireColorCapacity(values: List<ClosedFloatingPointRange<Double>>) {
    fun combine(weights: DoubleArray,ranges: List<ClosedFloatingPointRange<Double>>): ClosedFloatingPointRange<Double> { var lo=0.0; var hi=0.0; weights.forEachIndexed { i,w -> lo+=w*if(w>=0) ranges[i].start else ranges[i].endInclusive; hi+=w*if(w>=0) ranges[i].endInclusive else ranges[i].start }; return lo..hi }
    val cones=listOf(doubleArrayOf(1.0,.3963377774,.2158037573),doubleArrayOf(1.0,-.1055613458,-.0638541728),doubleArrayOf(1.0,-.0894841775,-1.291485548)).map { w -> val r=combine(w,values); r.start.pow(3)..r.endInclusive.pow(3) }; for(w in listOf(doubleArrayOf(4.0767416621,-3.3077115913,.2309699292),doubleArrayOf(-1.2684380046,2.6097574011,-.3413193965),doubleArrayOf(-.0041960863,-.7034186147,1.707614701))) { val r=combine(w,cones); require(r.start>=-65504 && r.endInclusive<=65504) { "Color trajectory exceeds Extended-sRGB capacity" } }
}
@Composable fun rememberMotionRect(key: Any,initial: Rect,ranges: EdgeRanges): MotionRect { val clock=ownerClock(); val value=remember(clock,key) { MotionRect(clock,initial,ranges,key) }; require(value.ranges==ranges) { "Owner ranges are immutable" }; DisposableEffect(value) { onDispose { value.dispose() } }; return value }
@Composable fun rememberMotionColor(key: Any,initial: Color): MotionColor { val clock=ownerClock(); val value=remember(clock,key) { MotionColor(clock,initial,key) }; DisposableEffect(value) { onDispose { value.dispose() } }; return value }
