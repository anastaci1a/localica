package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.Immutable
import io.github.anastaci1a.localica.ui.spec.*
import kotlin.math.*

@Immutable data class Kinematic(val value: Float,val velocity: Float)
internal sealed interface Trajectory { val kind: Int; val duration: Double; fun value(t: Double): Double; fun velocity(t: Double): Double; fun at(t: Float)=Kinematic(value(t.toDouble()).toFloat(),velocity(t.toDouble()).toFloat()) }
internal class Rest(private val x: Double): Trajectory { override val kind=0; override val duration=Double.POSITIVE_INFINITY; override fun value(t: Double)=x; override fun velocity(t: Double)=0.0 }
internal class Driven(private val x: Double,private val v: Double): Trajectory { override val kind=3; override val duration=Double.POSITIVE_INFINITY; override fun value(t: Double)=x; override fun velocity(t: Double)=v }
internal class Bezier(private val e: EasingSpec) {
    init { require(e.maxSlope.isFinite() && e.x1 in 0f..1f && e.x2 in 0f..1f) }
    fun maxSlope(from: Double,to: Double): Double { val x=doubleArrayOf(e.x1.toDouble(),2.0*(e.x2-2.0*e.x1),1-3.0*e.x2+3.0*e.x1); val y=doubleArrayOf(e.y1.toDouble(),2.0*(e.y2-2.0*e.y1),1-3.0*e.y2+3.0*e.y1); val roots=quadraticRoots(y[2]*x[1]-y[1]*x[2],2*(y[2]*x[0]-y[0]*x[2]),y[1]*x[0]-y[0]*x[1]); return (listOf(abs(at(from,true)),abs(at(to,true)))+roots.filter { it>0 && it<1 }.map { t -> val a=1-t; 3*a*a*t*e.x1+3*a*t*t*e.x2+t*t*t }.filter { it in from..to }.map { abs(at(it,true)) }).max() }
    fun at(u: Double,derivative: Boolean=false): Double {
        val x1=e.x1.toDouble(); val x2=e.x2.toDouble(); val y1=e.y1.toDouble(); val y2=e.y2.toDouble()
        if(u<=0) return if(!derivative) 0.0 else if(x1!=0.0) y1/x1 else if(x2!=0.0) y2/x2 else 1.0
        if(u>=1) return if(!derivative) 1.0 else if(x2!=1.0) (1-y2)/(1-x2) else if(x1!=1.0) (1-y1)/(1-x1) else 1.0
        var lo=0.0; var hi=1.0; var t=u
        for(i in 0..63) { val s=1-t; val x=3*s*s*t*x1+3*s*t*t*x2+t*t*t; val d=3*s*s*x1+6*s*t*(x2-x1)+3*t*t*(1-x2); if(abs(x-u)<=maxOf(Math.ulp(u)*2,1e-14*minOf(u,1-u))) break; if(x<u) lo=t else hi=t; val next=t-(x-u)/d; t=if(next>lo && next<hi) next else (lo+hi)/2 }
        val s=1-t; if(!derivative) return 3*s*s*t*y1+3*s*t*t*y2+t*t*t
        val dx=s*s*x1+2*s*t*(x2-x1)+t*t*(1-x2); val dy=s*s*y1+2*s*t*(y2-y1)+t*t*(1-y2); if(dx!=0.0) return dy/dx
        val ddx=2*(s*(x2-2*x1)+t*(1-2*x2+x1)); val ddy=2*(s*(y2-2*y1)+t*(1-2*y2+y1)); return if(ddx!=0.0) ddy/ddx else (1-3*y2+3*y1)/(1-3*x2+3*x1)
    }
}
internal fun hermite(x: Double,v: Double,q: Double,w: Double,tau: Double,t: Double,derivative: Boolean): Double {
    if(t<=0) return if(derivative) v else x; if(t>=tau) return if(derivative) w else q; val u=t/tau; val s=1-u
    return if(derivative) (3*u*u-4*u+1)*v+(3*u*u-2*u)*w+6*u*s*(q-x)/tau else (1+2*u)*s*s*x+u*u*(3-2*u)*q+tau*(u*s*s*v-u*u*s*w)
}
internal fun hermiteExtrema(x: Double,v: Double,q: Double,w: Double,tau: Double): ClosedFloatingPointRange<Double> {
    val c=tau*v; val b=3*(q-x)-2*c-tau*w; val a=-2*(q-x)+c+tau*w; val values=listOf(x,q)+quadraticRoots(3*a,2*b,c).filter { it>0 && it<1 }.map { u -> hermite(x,v,q,w,tau,u*tau,false) }; return values.min()..values.max()
}
internal fun hermiteVelocityPeak(x: Double,v: Double,q: Double,w: Double,tau: Double): Double { val c=tau*v; val b=3*(q-x)-2*c-tau*w; val a=-2*(q-x)+c+tau*w; val u=if(a==0.0) -1.0 else -b/(3*a); return maxOf(abs(v),abs(w),if(u>0 && u<1) abs(hermite(x,v,q,w,tau,u*tau,true)) else 0.0) }
internal class Eased(internal val x: Double,internal val v: Double,internal val q: Double,private val easing: EasingSpec,override val duration: Double,sigma: Double=1.0,bounds: ClosedFloatingPointRange<Double>?=null,maxTauIn: Double=Double.POSITIVE_INFINITY,internal val plan: EasedPlan?=null,maxTauOut: Double=Double.POSITIVE_INFINITY): Trajectory {
    override val kind=1; private val core=Bezier(easing); private val delta=q-x; internal val tauOut=minOf(duration/8,sigma*.064,maxTauOut)
    internal val extrema: ClosedFloatingPointRange<Double>; internal val tauIn: Double; private val b: Double; private val bv: Double; private val tailX: Double; private val tailV: Double
    init {
        require(duration.isFinite() && duration>0 && tauOut.isFinite() && tauOut>0 && listOf(x,v,q,sigma).all { it.isFinite() } && sigma>0)
        var tau=minOf(sigma/120,duration/4,maxTauIn); val B=abs(delta)/duration*easing.maxSlope
        if(bounds!=null && v!=0.0) { val room=if(v>0) bounds.endInclusive-x else x-bounds.start; require(room>0) { "Unreachable outward boundary state" }; tau=min(tau,room/(.149*(abs(v)+B)+if(v*delta>0) B else 0.0)) }
        require(tau>0 && tau.isFinite()); tauIn=tau; b=x+delta*core.at(tau/duration); bv=delta*core.at(tau/duration,true)/duration; tailX=x+delta*core.at(1-tauOut/duration); tailV=delta*core.at(1-tauOut/duration,true)/duration
        val extrema=easingExtrema(easing); val a=x+delta*extrema.start; val z=x+delta*extrema.endInclusive; val start=hermiteExtrema(x,v,b,bv,tauIn); val end=hermiteExtrema(tailX,tailV,q,0.0,tauOut); val lo=minOf(a,z,start.start,end.start); val hi=maxOf(a,z,start.endInclusive,end.endInclusive)
        this.extrema=lo..hi; require(bounds==null || lo>=bounds.start && hi<=bounds.endInclusive) { "Easing or Hermite bridge leaves channel bounds" }; require(lo.toFloat().isFinite() && hi.toFloat().isFinite() && maxOf(abs(delta)/duration*core.maxSlope(tauIn/duration,1-tauOut/duration),hermiteVelocityPeak(x,v,b,bv,tauIn),hermiteVelocityPeak(tailX,tailV,q,0.0,tauOut)).toFloat().isFinite()) { "Trajectory exceeds Float kinematic capacity" }
    }
    private fun sample(t: Double,d: Boolean): Double = when { t<=0 -> if(d) v else x; t>=duration -> if(d) 0.0 else q; t<tauIn -> hermite(x,v,b,bv,tauIn,t,d); t>=duration-tauOut -> hermite(tailX,tailV,q,0.0,tauOut,t-duration+tauOut,d); else -> if(d) delta*core.at(t/duration,true)/duration else x+delta*core.at(t/duration) }
    override fun value(t: Double)=sample(t,false)
    override fun velocity(t: Double)=sample(t,true)
}
internal class Damped(private val x: Double,private val v: Double,private val q: Double,private val omega: Double,private val zeta: Double,range: Double,displayInterval: Double): Trajectory {
    override val kind=2; private val offset=x-q; private val a=zeta*omega; private val wd=omega*sqrt(abs(1-zeta*zeta)); private val slow=if(zeta>1) omega/(zeta+sqrt(zeta*zeta-1)) else a; private val fast=if(zeta>1) omega*(zeta+sqrt(zeta*zeta-1)) else omega
    internal val envelopeSeconds=settleEnvelope(offset,v,omega,zeta,range); override val duration=if(offset==0.0 && v==0.0) 0.0 else max(envelopeSeconds,2*displayInterval); internal val tauOut=minOf(duration/8,.064,1/slow); private val tailStart=duration-tauOut; private val tailX=raw(tailStart,false); private val tailV=raw(tailStart,true)
    init { require(listOf(x,v,q,omega,zeta,range,displayInterval).all { it.isFinite() } && omega>0 && zeta>0 && range>0 && displayInterval>0 && duration.isFinite()) }
    internal fun raw(t: Double,d: Boolean): Double {
        val response=when { zeta<1 -> { val c=(v+a*offset)/wd; exp(-a*t)*(if(d) v*cos(wd*t)-(a*c+offset*wd)*sin(wd*t) else offset*cos(wd*t)+c*sin(wd*t)) }; zeta==1.0 -> exp(-omega*t)*(if(d) v-omega*(v+omega*offset)*t else offset+(v+omega*offset)*t); else -> { val difference=fast-slow; val b=-(v+slow*offset)/difference; val extra=expm1(-difference*t); exp(-slow*t)*(if(d) -slow*offset+b*(-slow*extra-difference*exp(-difference*t)) else offset+b*extra) } }
        return response+if(d) 0.0 else q
    }
    private fun sample(t: Double,d: Boolean)=when { t<=0 -> if(d) v else x; t>=duration -> if(d) 0.0 else q; t>=tailStart -> hermite(tailX,tailV,q,0.0,tauOut,t-tailStart,d); else -> raw(t,d) }
    override fun value(t: Double)=sample(t,false)
    override fun velocity(t: Double)=sample(t,true)
}
internal fun settleEnvelope(x: Double,v: Double,w: Double,z: Double,range: Double): Double {
    require(listOf(x,v,w,z,range).all { it.isFinite() } && w>0 && z>0 && range>0); val a=z*w; val critical=abs(v+w*x); val slow=if(z>1) w/(z+sqrt(z*z-1)) else a; val fast=if(z>1) w*(z+sqrt(z*z-1)) else w
    fun bound(t: Double,d: Boolean): Double = when { z<1 -> hypot(x,(v+a*x)/(w*sqrt(1-z*z)))*exp(-a*t)*if(d) w else 1.0; z==1.0 -> (if(d) abs(v)+w*critical*t else abs(x)+critical*t)*exp(-w*t); else -> { val A=(v+fast*x)/(fast-slow); val B=-(v+slow*x)/(fast-slow); (if(d) abs(A)*slow+abs(B)*fast else abs(A)+abs(B))*exp(-slow*t) } }
    fun time(d: Boolean): Double { val limit=range*if(d) SettleVelocityThreshold else SettleThreshold; var lo=if(z==1.0 && critical>0) max(0.0,1/w-if(d) abs(v)/(w*critical) else abs(x)/critical) else 0.0; if(bound(lo,d)<=limit) return 0.0; var hi=max(2.0,lo); while(bound(hi,d)>limit) { hi*=2; require(hi.isFinite()) }; repeat(80) { val t=(lo+hi)/2; if(bound(t,d)>limit) lo=t else hi=t }; return hi }
    return max(time(false),time(true))
}

/** A phase projection with its physical edge's rebase residual, sharing the same finite plan. */
internal class MappedEdge(private val phase: Trajectory,private val curve: Curve,private val start: Double,private val delta: Double,private val residual: Trajectory): Trajectory {
    override val kind=1; override val duration=residual.duration
    override fun value(t: Double)=start+delta*curve.at(phase.value(t).toFloat())+residual.value(t)
    override fun velocity(t: Double)=delta*curve.slope(phase.value(t).toFloat())*phase.velocity(t)+residual.velocity(t)
}
