package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.Immutable
import kotlin.math.*

internal object CurveHooks { var created: ((Curve,FloatArray)->Unit)?=null }
@Immutable sealed interface Curve {
    fun at(p: Float): Float
    fun slope(p: Float): Float
    fun compose(inner: Curve): Curve=CurveNode(5,left=this,right=inner)
    operator fun plus(other: Curve): Curve=CurveNode(3,left=this,right=other)
    operator fun times(other: Curve): Curve=CurveNode(4,left=this,right=other)
    fun scaled(k: Float): Curve=this*constant(k)
    fun mix(other: Curve,weight: Curve): Curve=this*(constant(1f)+weight.scaled(-1f))+other*weight
    companion object {
        fun linear(a: Float,b: Float): Curve=CurveNode(0,a,b)
        fun window(p0: Float,p1: Float): Curve { require(p0.isFinite() && p1.isFinite() && p0<p1); return CurveNode(1,p0,p1) }
        fun softWindow(p0: Float,p1: Float): Curve { require(p0.isFinite() && p1.isFinite() && p0<p1); return CurveNode(2,p0,p1) }
        fun smooth(a: Float,b: Float)=linear(a,b).compose(window(0f,1f))
        fun constant(v: Float): Curve=CurveNode(0,v,v)
        /** #854 prepared-origin geometry: 0 up to [p0], a C1 quadratic blend over [b], then affine with value 1 at p = 1 and no clamp above 1. */
        fun ramp(p0: Float,b: Float): Curve { require(p0.isFinite() && b.isFinite() && b>0 && p0>=0 && p0+b<1); return CurveNode(6,p0,b) }
    }
}
@Immutable private class CurveNode(val kind: Int,val a: Float=0f,val b: Float=0f,val left: Curve?=null,val right: Curve?=null): Curve {
    init { require(a.isFinite() && b.isFinite()); val limits=limits(-.05,1.05); require(limits.first.toFloat().isFinite() && limits.second.toFloat().isFinite() && limits.third.toFloat().isFinite()) { "Curve exceeds Float capacity" }; CurveHooks.created?.invoke(this,joins()) }
    private fun normalized(p: Float)=((p.toDouble()-a)/(b.toDouble()-a)).coerceIn(0.0,1.0)
    override fun at(p: Float): Float=when(kind) {
        0 -> (a.toDouble()+(b.toDouble()-a)*p).toFloat()
        1,2 -> normalized(p).let { u -> (if(kind==1) u*u*(3-2*u) else u*u*u*(10+u*(-15+6*u))).toFloat() }
        6 -> ramp(p.toDouble(),false).toFloat()
        3 -> left!!.at(p)+right!!.at(p); 4 -> left!!.at(p)*right!!.at(p); else -> left!!.at(right!!.at(p))
    }
    override fun slope(p: Float): Float=when(kind) {
        0 -> b-a
        1,2 -> if(p<=a || p>=b) 0f else normalized(p).let { u -> ((if(kind==1) 6*u*(1-u) else 30*u*u*(1-u)*(1-u))/(b.toDouble()-a)).toFloat() }
        6 -> ramp(p.toDouble(),true).toFloat()
        3 -> left!!.slope(p)+right!!.slope(p); 4 -> left!!.slope(p)*right!!.at(p)+left.at(p)*right.slope(p); else -> left!!.slope(right!!.at(p))*right.slope(p)
    }
    private fun ramp(p: Double,derivative: Boolean): Double { val p0=a.toDouble(); val blend=b.toDouble(); val span=1-p0-blend/2; val u=p-p0
        return when { u<=0 -> 0.0; u<=blend -> if(derivative) u/(blend*span) else u*u/(2*blend*span); else -> if(derivative) 1/span else (u-blend/2)/span } }
    private fun limits(lo: Double,hi: Double): Triple<Double,Double,Double> {
        if(kind==6) return Triple(ramp(lo,false),ramp(hi,false),if(hi<=a) 0.0 else 1/(1-a.toDouble()-b.toDouble()/2))
        if(kind==0) { val x=a+(b.toDouble()-a)*lo; val y=a+(b.toDouble()-a)*hi; return Triple(min(x,y),max(x,y),abs(b.toDouble()-a)) }
        if(kind in 1..2) return Triple(at(lo.toFloat()).toDouble(),at(hi.toFloat()).toDouble(),if(hi<=a || lo>=b) 0.0 else (if(kind==1) 1.5 else 1.875)/(b.toDouble()-a))
        val l=(left as CurveNode).limits(lo,hi); val r=(right as CurveNode).limits(lo,hi); if(kind==3) return Triple(l.first+r.first,l.second+r.second,l.third+r.third)
        if(kind==4) { val values=doubleArrayOf(l.first*r.first,l.first*r.second,l.second*r.first,l.second*r.second); return Triple(values.min(),values.max(),l.third*max(abs(r.first),abs(r.second))+r.third*max(abs(l.first),abs(l.second))) }
        val outer=left.limits(r.first,r.second); return Triple(outer.first,outer.second,outer.third*r.third)
    }
    private fun joins(): FloatArray { if(kind in 1..2) return floatArrayOf(a,b); if(kind==6) return floatArrayOf(a,a+b); val l=(left as? CurveNode)?.joins()?:floatArrayOf(); val r=(right as? CurveNode)?.joins()?:floatArrayOf(); return if(kind==5 && right is CurveNode && right.kind==0 && right.a!=right.b) r+l.map { ((it-right.a)/(right.b-right.a)) }.filter { it in -.05f..1.05f }.toFloatArray() else l+r }
}
