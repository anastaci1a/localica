package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.*

/** A rounded rectangle with continuous corners. The weights blend each axis toward a
 * circular arc as that side approaches the corner's reach; a capsule's short axis is round. */
internal data class LensGeometry(val bounds: Rect, val corner: Float) {
    val span get() = min(bounds.width, bounds.height) / 2f
    val radius get() = corner.coerceIn(.01f, max(span, .01f))
    val weights get() = Offset(cornerWeight(bounds.width), cornerWeight(bounds.height))
    private fun cornerWeight(side: Float) =
        (side.toDouble() * -1.44577851758675 / (radius * CornerReach) + 2.8915570351735).coerceIn(0.0, 1.0).toFloat()

}
private const val CornerReach = 1.52866495f

/** Background refraction, in dp. Each profile displaces along the inward gradient by
 * amount * (1 - sqrt(t(2 - t))), t = inside / height. The outer sample mixes in only
 * within the outermost dp of the edge. */
internal object GlassRefraction {
    const val InnerAmount = -38.4f
    const val InnerHeight = 12f
    const val OuterAmount = 9.6f
    const val OuterHeight = 6f
    const val OuterOpacity = .3f
    const val OuterDistance = -1f
    const val OuterDistanceEnd = -.5f
}

/** Precision noise in coordinate subtraction must not rebuild a silhouette while
 * its content simply translates. The origin retains full subpixel precision. */
internal fun localLensRegions(regions: List<SelectionRegion>, origin: Offset,
    previous: List<SelectionRegion>? = null): List<SelectionRegion> {
    var result: MutableList<SelectionRegion>? = if(previous?.size==regions.size)null else ArrayList(regions.size)
    fun stable(value: Float) = round(value * 64f) / 64f
    for(i in regions.indices) {
        val region=regions[i];val bounds=region.bounds
        val l=stable(bounds.left-origin.x);val t=stable(bounds.top-origin.y)
        val r=stable(bounds.right-origin.x);val b=stable(bounds.bottom-origin.y)
        val old=previous?.getOrNull(i)
        if(old!=null && old.bounds.left.toBits()==l.toBits() && old.bounds.top.toBits()==t.toBits() &&
            old.bounds.right.toBits()==r.toBits() && old.bounds.bottom.toBits()==b.toBits() && old.corner==region.corner) {
            result?.add(old)
        } else {
            if(result==null)result=ArrayList<SelectionRegion>(regions.size).also { next -> for(j in 0 until i)next.add(previous!![j]) }
            result.add(SelectionRegion(Rect(l,t,r,b),region.corner))
        }
    }
    return result ?: previous ?: emptyList()
}
