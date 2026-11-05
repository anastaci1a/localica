package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.math.*

/** Compact, continuous field shared by selection masks and shadows. Distances are in pixels.
 * A polynomial pair blend has zero support outside the connection band. Its maximum
 * displacement is the fillet cap; parallel faces never inflate coincident drag cards.
 * Pair support requires a shared side, so diagonally separated cells cannot bridge.
 */
internal class SelectionOutlineField(
    regions: List<SelectionRegion>, gap: Float, private val clip: Rect? = null,
    strength: (Int, Int) -> Float = { _, _ -> 1f },
) {
    private val n = regions.size
    private val boxes = regions.map { it.bounds }
    private val radii = FloatArray(n) { regions[it].radius(gap) }
    private val centersX = FloatArray(n) { boxes[it].center.x }
    private val centersY = FloatArray(n) { boxes[it].center.y }
    private val coresX = FloatArray(n) { boxes[it].width*.5f-radii[it] }
    private val coresY = FloatArray(n) { boxes[it].height*.5f-radii[it] }
    private val band = gap * .5f
    private val cap = gap * .25f
    private val distances = FloatArray(n)
    private val nx = FloatArray(n)
    private val ny = FloatArray(n)
    private data class PairSupport(val a: Int, val b: Int, val overlap: Float,
        val vertical: Boolean, val start: Float, val end: Float, val weight: Float)
    private val pairs = buildList {
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = boxes[i]; val b = boxes[j]
            val dx = max(a.left-b.right,b.left-a.right)
            val dy = max(a.top-b.bottom,b.top-a.bottom)
            val overlap = dx < 0f && dy < 0f
            val vertical = dx < dy
            val across = if (vertical) -dx else -dy
            val separation = max(dx,dy)
            if (across <= 0f || separation >= band) continue
            val coreX = abs(centersX[i]-centersX[j])-coresX[i]-coresX[j]
            val coreY = abs(centersY[i]-centersY[j])-coresY[i]-coresY[j]
            val physicalGap = hypot(max(coreX,0f),max(coreY,0f))+min(max(coreX,coreY),0f)-radii[i]-radii[j]
            val overlapAdmission = if (overlap) smooth((-physicalGap/cap).coerceIn(0f,1f)) else 0f
            val sharedSide = smooth(((if (vertical) -coreX else -coreY)/cap).coerceIn(0f,1f))
            val s = max(overlapAdmission,strength(i,j).coerceIn(0f,1f))
            val weight = s * max(sharedSide,overlapAdmission) * smooth((across / cap).coerceIn(0f,1f)) *
                (1f-smooth((separation/band).coerceIn(0f,1f)))
            if (weight == 0f) continue
            add(PairSupport(i,j,overlapAdmission,vertical,
                if (vertical) max(a.left,b.left) else max(a.top,b.top),
                if (vertical) min(a.right,b.right) else min(a.bottom,b.bottom),weight))
        }
    }
    val bounds: Rect = clip ?: if (boxes.isEmpty()) Rect.Zero else boxes.reduce { a,b ->
        Rect(min(a.left,b.left),min(a.top,b.top),max(a.right,b.right),max(a.bottom,b.bottom)) }
    val isolated: Boolean get() = pairs.isEmpty()
    /** Only these compact contact patches can differ from the exact item contours. */
    val patches: List<Rect> get() = pairs.map { boxes[it.a].inflate(band).intersect(boxes[it.b].inflate(band)) }
    private fun clipDistance(x: Float,y: Float): Float {
        val b = clip ?: return Float.NEGATIVE_INFINITY
        return max(max(b.left-x,x-b.right),max(b.top-y,y-b.bottom))
    }
    private fun distance(i: Int, x: Float, y: Float, normal: Boolean): Float {
        val r = radii[i]
        val dx = x-centersX[i]; val dy = y-centersY[i]
        val qx = abs(dx)-coresX[i]; val qy = abs(dy)-coresY[i]
        val ax = max(qx,0f); val ay = max(qy,0f)
        val length = if (ax == 0f || ay == 0f) max(ax,ay) else sqrt(ax*ax+ay*ay)
        if (normal) {
            if (length > 0f) { nx[i] = ax/length * sign(dx); ny[i] = ay/length * sign(dy) }
            else { nx[i] = if (qx > qy) sign(dx) else 0f; ny[i] = if (qx > qy) 0f else sign(dy) }
        }
        return length + min(max(qx,qy),0f)-r
    }
    /** Exact distance bounds permit contour subdivision to skip all uniform interior/exterior. */
    fun uniform(x: Float, y: Float, radius: Float): Boolean {
        val boundary = clipDistance(x,y)
        if (boundary > radius) return true
        var nearest = Float.POSITIVE_INFINITY
        for (i in 0 until n) {
            val d = distance(i,x,y,false)
            distances[i] = d
            if (d < -radius && boundary < -radius) return true
            nearest = min(nearest,d)
        }
        if (nearest > radius+cap) return true
        var lower = nearest-radius
        for (index in pairs.indices) {
            val p = pairs[index]
            val a = distances[p.a]-radius; val b = distances[p.b]-radius
            val support = 1f-smooth((max(a,b)/band).coerceIn(0f,1f))
            val k = 6f*cap*p.weight*support
            if (k <= 0f) continue
            val delta = max(abs(distances[p.a]-distances[p.b])-radius*2f,0f)
            val h = max(1f-delta/k,0f)
            lower = min(lower,min(a,b)-k*h*h*h/6f)
        }
        return lower > 0f
    }
    fun covered(left: Float,top: Float,right: Float,bottom: Float): Boolean {
        if (clip != null && (left<clip.left || top<clip.top || right>clip.right || bottom>clip.bottom)) return false
        for (i in 0 until n) if (distance(i,left,top,false)<=0f && distance(i,right,top,false)<=0f &&
            distance(i,right,bottom,false)<=0f && distance(i,left,bottom,false)<=0f) return true
        return false
    }
    /** Axis on which this complete cell's field varies, or -1 near curved/tapered patches. */
    fun straightAxis(left: Float, top: Float, right: Float, bottom: Float): Int {
        if (clip != null && (left < clip.left || top < clip.top || right > clip.right || bottom > clip.bottom)) return -1
        val x = (left+right)*.5f; val y = (top+bottom)*.5f
        val reach = hypot(right-left,bottom-top)*.5f+cap
        var axis = -1
        for (i in 0 until n) {
            if (distance(i,x,y,false) > reach) continue
            val b = boxes[i]; val r = radii[i]
            val next = if (top >= b.top+r && bottom <= b.bottom-r &&
                (right <= b.left+r || left >= b.right-r)) 0
            else if (left >= b.left+r && right <= b.right-r &&
                (bottom <= b.top+r || top >= b.bottom-r)) 1 else return -1
            if (axis >= 0 && next != axis) return -1
            axis = next
        }
        if (axis < 0) return -1
        for (index in pairs.indices) {
            val p = pairs[index]
            if (p.overlap >= 1f || p.vertical != (axis == 1)) continue
            val lo = if (axis == 0) top else left
            val hi = if (axis == 0) bottom else right
            if (hi > p.start-cap && lo < p.end+cap) return -1
        }
        return axis
    }
    fun value(x: Float, y: Float): Float {
        var field = Float.POSITIVE_INFINITY
        for (i in 0 until n) {
            distances[i] = distance(i,x,y,true)
            field = min(field,distances[i])
        }
        for (index in pairs.indices) {
            val pair = pairs[index]
            val a = pair.a; val b = pair.b
            val da = distances[a]; val db = distances[b]
            if (da >= band || db >= band) continue
            val cross = if (pair.vertical) x else y
            val taper = (1f-abs(2f*cross-pair.start-pair.end)/(pair.end-pair.start+2f*cap)).coerceIn(0f,1f)
            val port = pair.overlap+(1f-pair.overlap)*smooth(taper)
            val facing = ((1f-nx[a]*nx[b]-ny[a]*ny[b])*.5f).coerceIn(0f,1f)
            val support = (1f-smooth((max(da,db)/band).coerceIn(0f,1f)))
            val k = 6f*cap*pair.weight*port*facing*support
            if (k <= 0f) continue
            val h = max(1f-abs(da-db)/k,0f)
            field = min(field,min(da,db)-k*h*h*h/6f)
        }
        return max(field,clipDistance(x,y))
    }
    private fun smooth(t: Float): Float = t*t*t*(t*(t*6f-15f)+10f)
}

/** Adaptive marching squares, with shared edge vertices and an asymptotic saddle decision.
 * Only cells near the surface are visited. Interior spans and distant space have no grid
 * evaluation cost. Storage is reused by the cache; no allocations occur per field sample.
 */
internal class SelectionOutlineProfile {
    var evaluationNanos = 0L
    var pathNanos = 0L
}

internal class SelectionOutlineContour(private val profile: SelectionOutlineProfile? = null) {
    private var previousPatches = mutableMapOf<Any,Path>()
    private var currentPatches = mutableMapOf<Any,Path>()
    fun beginPatches() { currentPatches.clear() }
    fun endPatches() {
        val old = previousPatches; previousPatches = currentPatches; currentPatches = old
    }
    fun patch(regions: List<SelectionRegion>,gap: Float,clip: Rect,strength: (Int,Int) -> Float): Path {
        val local = localLensRegions(regions,clip.topLeft)
        val strengths = regions.indices.flatMap { a -> (a+1 until regions.size).map { b -> strength(a,b) } }
        val key = Triple(local,clip.size,strengths) to gap
        val cached = previousPatches[key] ?: path(SelectionOutlineField(local,gap,
            Rect(0f,0f,clip.width,clip.height),strength),gap/8f)
        currentPatches[key] = cached
        return Path().apply { addPath(cached,clip.topLeft) }
    }
    /** Sample only the moving contact patches. Large linear spans stay single segments;
     * curved segments refine against the field, with subpixel cells at a saddle. */
    fun path(field: SelectionOutlineField,resolution: Float): Path {
        val started = if (profile != null) System.nanoTime() else 0L
        val path = Path()
        val minimum = resolution*.125f
        val tolerance = resolution*.05f
        fun packed(x: Float,y: Float) = (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xffffffffL)
        fun px(p: Long) = Float.fromBits((p shr 32).toInt())
        fun py(p: Long) = Float.fromBits(p.toInt())
        fun root(x1: Float,y1: Float,x2: Float,y2: Float,a: Float,b: Float): Long {
            if (a == 0f) return packed(x1,y1)
            if (b == 0f) return packed(x2,y2)
            var lo = 0f; var hi = 1f; var va = a; var vb = b
            var t = a/(a-b)
            repeat(10) {
                t = (lo+(hi-lo)*va/(va-vb)).coerceIn(lo+(hi-lo)*.025f,hi-(hi-lo)*.025f)
                val v = field.value(x1+(x2-x1)*t,y1+(y2-y1)*t)
                if (abs(v) < .000001f) return packed(x1+(x2-x1)*t,y1+(y2-y1)*t)
                if ((v <= 0f) == (va <= 0f)) { lo = t; va = v } else { hi = t; vb = v }
            }
            return packed(x1+(x2-x1)*t,y1+(y2-y1)*t)
        }
        fun triangle(x1: Float,y1: Float,a: Float,x2: Float,y2: Float,b: Float,x3: Float,y3: Float,c: Float) {
            var first = true
            fun add(p: Long) { if (first) { path.moveTo(px(p),py(p)); first = false } else path.lineTo(px(p),py(p)) }
            if (a <= 0f) add(packed(x1,y1))
            if ((a<=0f)!=(b<=0f)) add(root(x1,y1,x2,y2,a,b))
            if (b <= 0f) add(packed(x2,y2))
            if ((b<=0f)!=(c<=0f)) add(root(x2,y2,x3,y3,b,c))
            if (c <= 0f) add(packed(x3,y3))
            if ((c<=0f)!=(a<=0f)) add(root(x3,y3,x1,y1,c,a))
            if (!first) path.close()
        }
        fun straight(a: Long,b: Long): Boolean {
            val dx = px(b)-px(a); val dy = py(b)-py(a)
            val length = hypot(dx,dy)
            if (length <= tolerance) return true
            val nx = -dy/length; val ny = dx/length
            val probe = resolution*.25f
            for (i in 1..3) {
                val x = px(a)+dx*i*.25f; val y = py(a)+dy*i*.25f
                val v = field.value(x,y)
                val derivative = abs(field.value(x+nx*probe,y+ny*probe)-field.value(x-nx*probe,y-ny*probe))/(2f*probe)
                if (derivative < .05f || abs(v) > derivative*tolerance) return false
            }
            return true
        }
        fun cell(left: Float,top: Float,right: Float,bottom: Float,a: Float,b: Float,c: Float,d: Float) {
            val x = (left+right)*.5f; val y = (top+bottom)*.5f
            val w = right-left; val h = bottom-top
            // Exact item contours already cover these spans. Only added contact pixels
            // belong in this path; retaining interior cells needlessly complicates its union.
            if (field.covered(left,top,right,bottom) || field.uniform(x,y,hypot(w,h)*.5f)) return
            if (w > h*2f) {
                val e = field.value(x,top); val g = field.value(x,bottom)
                cell(left,top,x,bottom,a,e,g,d); cell(x,top,right,bottom,e,b,c,g)
                return
            }
            if (h > w*2f) {
                val k = field.value(left,y); val f = field.value(right,y)
                cell(left,top,right,y,a,b,f,k); cell(left,y,right,bottom,k,f,c,d)
                return
            }
            val e = field.value(x,top); val f = field.value(right,y)
            val g = field.value(x,bottom); val k = field.value(left,y); val m = field.value(x,y)
            val code = (if(a<=0f)1 else 0) or (if(b<=0f)2 else 0) or (if(c<=0f)4 else 0) or (if(d<=0f)8 else 0)
            val allInside = code == 15 && e<=0f && f<=0f && g<=0f && k<=0f && m<=0f
            if (allInside && (max(w,h)<=resolution || field.covered(left,top,right,bottom))) {
                path.addRect(Rect(left,top,right,bottom),Path.Direction.Clockwise); return
            }
            if (max(w,h)<=minimum) {
                triangle(left,top,a,right,top,b,x,y,m)
                triangle(right,top,b,right,bottom,c,x,y,m)
                triangle(right,bottom,c,left,bottom,d,x,y,m)
                triangle(left,bottom,d,left,top,a,x,y,m)
                return
            }
            if (code != 0 && code != 15 && code != 5 && code != 10) {
                val r0 = if ((a<=0f)!=(b<=0f)) root(left,top,right,top,a,b) else 0L
                val r1 = if ((b<=0f)!=(c<=0f)) root(right,top,right,bottom,b,c) else 0L
                val r2 = if ((c<=0f)!=(d<=0f)) root(right,bottom,left,bottom,c,d) else 0L
                val r3 = if ((d<=0f)!=(a<=0f)) root(left,bottom,left,top,d,a) else 0L
                val start = when(code) { 1,2,6,9,13,14 -> r0; 3,4,11,12 -> r1; else -> r2 }
                val end = when(code) { 1,3,7,8,12,14 -> r3; 2,13 -> r1; else -> r2 }
                if (straight(start,end)) {
                    var first = true
                    fun add(p: Long) { if (first) { path.moveTo(px(p),py(p)); first=false } else path.lineTo(px(p),py(p)) }
                    if (a<=0f) add(packed(left,top))
                    if ((a<=0f)!=(b<=0f)) add(r0)
                    if (b<=0f) add(packed(right,top))
                    if ((b<=0f)!=(c<=0f)) add(r1)
                    if (c<=0f) add(packed(right,bottom))
                    if ((c<=0f)!=(d<=0f)) add(r2)
                    if (d<=0f) add(packed(left,bottom))
                    if ((d<=0f)!=(a<=0f)) add(r3)
                    path.close(); return
                }
            }
            cell(left,top,x,y,a,e,m,k)
            cell(x,top,right,y,e,b,f,m)
            cell(x,y,right,bottom,m,f,c,g)
            cell(left,y,x,bottom,k,m,g,d)
        }
        val bounds = field.bounds
        cell(bounds.left,bounds.top,bounds.right,bounds.bottom,
            field.value(bounds.left,bounds.top),field.value(bounds.right,bounds.top),
            field.value(bounds.right,bounds.bottom),field.value(bounds.left,bounds.bottom))
        profile?.let { it.evaluationNanos += System.nanoTime()-started }
        return path
    }
}
