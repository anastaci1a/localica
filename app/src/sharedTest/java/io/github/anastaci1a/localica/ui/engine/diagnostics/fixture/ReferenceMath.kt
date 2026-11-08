package io.github.anastaci1a.localica.ui.engine.diagnostics.fixture

import kotlin.math.*

/** Independent Double equations; never calls a production trajectory or its evaluator. */
internal data class Hermite(val start: Double, val end: Double, val v0: Double, val v1: Double, val seconds: Double) {
    init { require(listOf(start,end,v0,v1,seconds).all { it.isFinite() } && seconds > 0) }
    private val a = 2*start-2*end+seconds*(v0+v1)
    private val b = -3*start+3*end-seconds*(2*v0+v1)
    private val c = seconds*v0
    fun value(u: Double) = ((a*u+b)*u+c)*u+start
    fun velocity(u: Double) = (3*a*u*u+2*b*u+c)/seconds
    fun extrema(): List<Double> {
        val roots = if (abs(a) < 1e-14) {
            if (abs(b) < 1e-14) emptyList() else listOf(-c/(2*b))
        } else {
            val discriminant = 4*b*b-12*a*c
            if (discriminant < 0) emptyList() else listOf((-2*b-sqrt(discriminant))/(6*a),(-2*b+sqrt(discriminant))/(6*a))
        }
        return roots.filter { it in 0.0..1.0 } + listOf(0.0,1.0)
    }
}
internal fun dampedDisplacement(zeta: Double, omega: Double, x0: Double, v0: Double, seconds: Double): Double {
    require(listOf(zeta,omega,x0,v0,seconds).all { it.isFinite() } && zeta >= 0 && omega > 0 && seconds >= 0)
    return when {
        zeta == 1.0 -> (x0+(v0+omega*x0)*seconds)*exp(-omega*seconds)
        zeta < 1 -> {
            val wd = omega*sqrt(1-zeta*zeta)
            exp(-zeta*omega*seconds)*(x0*cos(wd*seconds)+(v0+zeta*omega*x0)/wd*sin(wd*seconds))
        }
        else -> {
            val slow = omega/(zeta+sqrt(zeta*zeta-1)); val fast = omega*(zeta+sqrt(zeta*zeta-1))
            val a = (v0+fast*x0)/(fast-slow); val b = -(v0+slow*x0)/(fast-slow)
            a*exp(-slow*seconds)+b*exp(-fast*seconds)
        }
    }
}
internal fun overdampedBounds(zeta: Double, omega: Double, x0: Double, v0: Double, seconds: Double): Pair<Double,Double> {
    require(listOf(zeta,omega,x0,v0,seconds).all { it.isFinite() } && zeta > 1 && omega > 0 && seconds >= 0)
    val slow = omega/(zeta+sqrt(zeta*zeta-1)); val fast = omega*(zeta+sqrt(zeta*zeta-1))
    val a = (v0+fast*x0)/(fast-slow); val b = -(v0+slow*x0)/(fast-slow)
    return (abs(a)+abs(b))*exp(-slow*seconds) to (abs(a)*slow+abs(b)*fast)*exp(-slow*seconds)
}
