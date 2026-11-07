package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.spec.PhaseWindow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Focus blur mask maths (#640 §2.1): β(x) = S · smoothstep(0, F, sdf(x) − ρ) with ρ = (1 − w)·Dmax, where w and S
 * are smoothstep windows of hold progress h. Pure and C1 in both h and space; the AGSL composite, the API 31–32
 * radial mask and the dim-only veil all evaluate these same functions.
 */
object FocusMask {
    /** Hermite smoothstep of x over [e0, e1]: C1 at both joins, slope 0 outside. */
    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        require(e0 < e1) { "smoothstep needs e0 < e1" }
        val u = ((x - e0) / (e1 - e0)).let { if (it <= 0f) 0f else if (it >= 1f) 1f else it }
        return u * u * (3f - 2f * u)
    }

    /** A Spec window evaluated on a phase. */
    fun window(window: PhaseWindow, p: Float): Float = smoothstep(window.start, window.end, p)

    /** Signed distance from (x, y) to the rounded rectangle [rect] with corner radius [radius]; negative inside. */
    fun roundedRectDistance(x: Float, y: Float, rect: Rect, radius: Float): Float {
        require(radius >= 0f && radius <= min(rect.width, rect.height) / 2f) { "radius must fit the rect" }
        val hx = rect.width / 2f - radius; val hy = rect.height / 2f - radius
        val qx = kotlin.math.abs(x - rect.center.x) - hx; val qy = kotlin.math.abs(y - rect.center.y) - hy
        val ox = max(qx, 0f); val oy = max(qy, 0f)
        return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - radius
    }

    /** Dmax: the farthest viewport corner's distance from the item, where the front starts at w = 0. */
    fun frontStart(item: Rect, viewport: Rect, radius: Float): Float = maxOf(
        roundedRectDistance(viewport.left, viewport.top, item, radius), roundedRectDistance(viewport.right, viewport.top, item, radius),
        roundedRectDistance(viewport.left, viewport.bottom, item, radius), roundedRectDistance(viewport.right, viewport.bottom, item, radius))

    /** ρ: the front's distance from the item for sweep progress [w] ∈ [0, 1]. */
    fun front(w: Float, dMax: Float): Float = (1f - w) * dMax

    /** β for a signed distance [sdf] given the front [rho], feather [feather] and strength [strength]. */
    fun level(sdf: Float, rho: Float, feather: Float, strength: Float): Float = strength * smoothstep(0f, feather, sdf - rho)

    /** β at a point for hold progress [h] with the Spec windows [frontWindow] and [strengthWindow] and the user [setting]. */
    fun at(x: Float, y: Float, item: Rect, radius: Float, viewport: Rect, h: Float, feather: Float,
        frontWindow: PhaseWindow, strengthWindow: PhaseWindow, setting: Float = 1f): Float {
        val dMax = frontStart(item, viewport, radius)
        val rho = front(window(frontWindow, h), dMax)
        return level(roundedRectDistance(x, y, item, radius), rho, feather, window(strengthWindow, h) * setting)
    }
}
