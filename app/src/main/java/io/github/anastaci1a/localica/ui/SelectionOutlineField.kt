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
    private val normalLengths = FloatArray(n)
    private val normalErrors = FloatArray(n)
    private val active = IntArray(n)
    private data class PairSupport(val a: Int, val b: Int, val overlap: Float,
        val vertical: Boolean, val start: Float, val end: Float, val weight: Float, val separation: Float)
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
            val requested = strength(i,j).coerceIn(0f,1f)
            val s = requested+(1f-requested)*overlapAdmission
            val interaction = sharedSide+(1f-sharedSide)*overlapAdmission
            val weight = s * interaction * smooth((across / cap).coerceIn(0f,1f)) *
                (1f-smooth((separation/band).coerceIn(0f,1f)))
            if (weight == 0f) continue
            add(PairSupport(i,j,overlapAdmission,vertical,
                if (vertical) max(a.left,b.left) else max(a.top,b.top),
                if (vertical) min(a.right,b.right) else min(a.bottom,b.bottom),weight,physicalGap))
        }
    }
    private val pairAt = arrayOfNulls<PairSupport>(n*n).also { matrix ->
        for (p in pairs) { matrix[p.a*n+p.b] = p; matrix[p.b*n+p.a] = p }
    }
    val bounds: Rect = clip ?: if (boxes.isEmpty()) Rect.Zero else boxes.reduce { a,b ->
        Rect(min(a.left,b.left),min(a.top,b.top),max(a.right,b.right),max(a.bottom,b.bottom)) }
    val isolated: Boolean get() = pairs.isEmpty()
    /** Only these compact contact patches can differ from the exact item contours. */
    val patches: List<Rect> get() {
        val patches = pairs.map { boxes[it.a].inflate(band).intersect(boxes[it.b].inflate(band)) }.toMutableList()
        var i = 0
        while (i < patches.size) {
            var j = i+1
            while (j < patches.size) {
                val a = patches[i]; val b = patches[j]
                val hull = Rect(min(a.left,b.left),min(a.top,b.top),max(a.right,b.right),max(a.bottom,b.bottom))
                if (a.overlaps(b) && hull.width*hull.height <= (a.width*a.height+b.width*b.height)*1.25f) {
                    patches[i] = hull; patches.removeAt(j); j = i+1
                } else j++
            }
            i++
        }
        return patches
    }
    private fun clipDistance(x: Float,y: Float): Float {
        val b = clip ?: return Float.NEGATIVE_INFINITY
        return max(max(b.left-x,x-b.right),max(b.top-y,y-b.bottom))
    }
    private fun near(i: Int,x: Float,y: Float,padding: Float): Boolean {
        val b = boxes[i]
        return x>=b.left-padding && x<=b.right+padding && y>=b.top-padding && y<=b.bottom+padding
    }
    private fun distance(i: Int, x: Float, y: Float, normal: Boolean, forceNormal: Boolean = false): Float {
        val r = radii[i]
        val dx = x-centersX[i]; val dy = y-centersY[i]
        val qx = abs(dx)-coresX[i]; val qy = abs(dy)-coresY[i]
        val ax = max(qx,0f); val ay = max(qy,0f)
        val length = if (ax == 0f || ay == 0f) max(ax,ay) else sqrt(ax*ax+ay*ay)
        val result = length + min(max(qx,qy),0f)-r
        if (normal && (forceNormal || result < band)) {
            normalLengths[i] = length
            if (length > 0f) { nx[i] = ax/length * sign(dx); ny[i] = ay/length * sign(dy) }
            else { nx[i] = if (qx > qy) sign(dx) else 0f; ny[i] = if (qx > qy) 0f else sign(dy) }
        }
        return result
    }
    /** Exact distance bounds permit contour subdivision to skip all uniform interior/exterior. */
    fun uniform(x: Float, y: Float, radius: Float): Boolean {
        val boundary = clipDistance(x,y)
        if (boundary > radius) return true
        var nearest = Float.POSITIVE_INFINITY
        for (i in 0 until n) {
            if (!near(i,x,y,radius+band)) { distances[i] = Float.POSITIVE_INFINITY; continue }
            val d = distance(i,x,y,false)
            distances[i] = d
            if (d < -radius && boundary < -radius) return true
            nearest = min(nearest,d)
        }
        if (nearest > radius+cap) return true
        var lower = nearest-radius
        for (index in pairs.indices) {
            val p = pairs[index]
            if (!distances[p.a].isFinite() || !distances[p.b].isFinite()) continue
            val a = distances[p.a]-radius; val b = distances[p.b]-radius
            val support = (1f-smooth((a/band).coerceIn(0f,1f)))*(1f-smooth((b/band).coerceIn(0f,1f)))
            val k = kernel(6f*cap*p.weight*support)
            if (k <= 0f) continue
            val delta = max(abs(distances[p.a]-distances[p.b])-radius*2f,0f)
            val h = max(1f-delta/k,0f)
            lower = min(lower,min(a,b)-k*h*h*h/6f)
        }
        return lower > 0f
    }
    fun covered(left: Float,top: Float,right: Float,bottom: Float): Boolean {
        if (clip != null && (left<clip.left || top<clip.top || right>clip.right || bottom>clip.bottom)) return false
        for (i in 0 until n) {
            val b = boxes[i]
            if (left<b.left || top<b.top || right>b.right || bottom>b.bottom) continue
            if (distance(i,left,top,false)<=0f && distance(i,right,top,false)<=0f &&
                distance(i,right,bottom,false)<=0f && distance(i,left,bottom,false)<=0f) return true
        }
        return false
    }
    /** Outside both bodies their distances sum to at least the bodies' separation.
     * If the maximum blend support cannot exceed that sum, no new pixels can exist.
     * This also skips original rounded edges, which are already drawn analytically. */
    fun changesOutline(left: Float,top: Float,right: Float,bottom: Float): Boolean {
        val x = (left+right)*.5f; val y = (top+bottom)*.5f
        val radius = hypot(right-left,bottom-top)*.5f
        for (i in 0 until n) {
            if (!near(i,x,y,radius+band)) { distances[i] = Float.POSITIVE_INFINITY; continue }
            distances[i] = distance(i,x,y,true,true)
            val b = boxes[i]; val r = radii[i]
            val straight = top>=b.top+r && bottom<=b.bottom-r && (left>=b.right-r || right<=b.left+r) ||
                left>=b.left+r && right<=b.right-r && (top>=b.bottom-r || bottom<=b.top+r)
            // Projection onto the rounded rectangle's core is non-expansive. For an
            // outside point its residual length is at least the corner radius.
            normalErrors[i] = if (straight) 0f else if (r+normalLengths[i] > 0f)
                min(2f,2f*radius/(r+normalLengths[i])) else 2f
        }
        for (index in pairs.indices) {
            val p = pairs[index]
            if (!distances[p.a].isFinite() || !distances[p.b].isFinite()) continue
            val lo = if (p.vertical) left else top
            val hi = if (p.vertical) right else bottom
            val center = (p.start+p.end)*.5f
            val port = port(p,center.coerceIn(lo,hi))
            val a = distances[p.a]; val b = distances[p.b]
            val support = (1f-smooth(((a-radius)/band).coerceIn(0f,1f)))*
                (1f-smooth(((b-radius)/band).coerceIn(0f,1f)))
            val facing = ((1f-nx[p.a]*nx[p.b]-ny[p.a]*ny[p.b]+normalErrors[p.a]+normalErrors[p.b])*.5f).coerceIn(0f,1f)
            val k = kernel(6f*cap*p.weight*port*support*facing)
            val sum = max(max(p.separation,0f),a+b-2f*radius)
            val difference = max(abs(a-b)-2f*radius,0f)
            if (k > sum && k > difference) return true
        }
        return false
    }
    /** Exact bound for opposing straight faces. Their two distances sum to the gap;
     * the cubic blend is largest at its midpoint. A lower bound on its support thus
     * proves the complete strip filled without sampling its interior. */
    fun connectedBand(left: Float,top: Float,right: Float,bottom: Float): Boolean {
        for (index in pairs.indices) {
            val p = pairs[index]; val a = boxes[p.a]; val b = boxes[p.b]
            val ra = radii[p.a]; val rb = radii[p.b]
            val gap: Float; val lo: Float; val hi: Float
            if (p.vertical) {
                if (left < max(a.left+ra,b.left+rb) || right > min(a.right-ra,b.right-rb) ||
                    top < min(a.top,b.top) || bottom > max(a.bottom,b.bottom)) continue
                gap = max(a.top-b.bottom,b.top-a.bottom); lo = left; hi = right
            } else {
                if (top < max(a.top+ra,b.top+rb) || bottom > min(a.bottom-ra,b.bottom-rb) ||
                    left < min(a.left,b.left) || right > max(a.right,b.right)) continue
                gap = max(a.left-b.right,b.left-a.right); lo = top; hi = bottom
            }
            if (gap <= 0f) return true
            val port = min(port(p,lo),port(p,hi))
            val support = 1f-smooth((gap/band).coerceIn(0f,1f))
            val k = kernel(6f*cap*p.weight*port*support*support)
            if (k > 3f*gap) return true
        }
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
        var field = band
        var count = 0
        for (i in 0 until n) {
            if (!near(i,x,y,band)) { distances[i] = band; continue }
            distances[i] = distance(i,x,y,true)
            field = min(field,distances[i])
            if (distances[i] < band) active[count++] = i
        }
        for (i in 0 until count) for (j in i+1 until count) {
            val a = active[i]; val b = active[j]
            val pair = pairAt[a*n+b] ?: continue
            val da = distances[a]; val db = distances[b]
            if (da >= band || db >= band) continue
            val cross = if (pair.vertical) x else y
            val port = port(pair,cross)
            val facing = ((1f-nx[a]*nx[b]-ny[a]*ny[b])*.5f).coerceIn(0f,1f)
            val support = (1f-smooth((da/band).coerceIn(0f,1f)))*(1f-smooth((db/band).coerceIn(0f,1f)))
            val k = kernel(6f*cap*pair.weight*port*facing*support)
            if (k <= 0f) continue
            val h = max(1f-abs(da-db)/k,0f)
            field = min(field,min(da,db)-k*h*h*h/6f)
        }
        // The patch bounds crop its polygons, not the field itself. In particular,
        // gradients at a crop edge must remain those of the shared outline surface.
        return field
    }
    private fun smooth(t: Float): Float = t*t*t*(t*(t*6f-15f)+10f)
    private fun port(p: PairSupport,cross: Float): Float {
        val span = p.end-p.start+2f*cap
        val u = abs(2f*cross-p.start-p.end)/span
        val edge = smooth(((1f-u)*span/(2f*cap)).coerceIn(0f,1f))
        // Local end fillets retain a broad joined row. The parabolic variation
        // removes a flat saddle, so a separating neck parts at its centre first.
        return p.overlap+(1f-p.overlap)*edge*max(1f-.5f*u*u,0f)
    }
    /** Subpixel kernels fade out smoothly; their undamped reach is at most 1/4 dp.
     * This retains a continuous surface instead of numerically tracing invisible slivers. */
    private fun kernel(k: Float): Float {
        val minimum = cap*.1875f
        if (k <= minimum) return 0f
        if (k >= minimum*2f) return k
        return k*smooth(((k-minimum)/minimum).coerceIn(0f,1f))
    }
}

/** Optional native benchmark counters, kept outside the field's geometry. */
internal class SelectionOutlineProfile {
    var evaluationNanos = 0L
    var pathNanos = 0L
    var samples = 0L
    var cells = 0L
    var curves = 0L
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
        val cached = currentPatches[key] ?: previousPatches[key] ?: path(SelectionOutlineField(local,gap,
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
        fun sample(x: Float,y: Float): Float {
            profile?.let { it.samples++ }
            return field.value(x,y)
        }
        val roots = HashMap<Long,MutableList<Long>>()
        fun root(x1: Float,y1: Float,x2: Float,y2: Float,a: Float,b: Float): Long {
            if (a == 0f) return packed(x1,y1)
            if (b == 0f) return packed(x2,y2)
            val axis = if (x1 == x2) 1 else if (y1 == y2) 0 else -1
            val fixed = if (axis == 1) x1 else y1
            val key = (fixed.toRawBits().toLong() shl 1) or axis.toLong()
            val lower = if (axis == 1) min(y1,y2) else min(x1,x2)
            val upper = if (axis == 1) max(y1,y2) else max(x1,x2)
            if (axis >= 0) roots[key]?.let { known ->
                for (i in known.indices) {
                    val p = known[i]; val coordinate = if (axis == 1) py(p) else px(p)
                    if (coordinate > lower && coordinate < upper) return p
                }
            }
            fun finish(t: Float,exact: Boolean): Long {
                val p = packed(x1+(x2-x1)*t,y1+(y2-y1)*t)
                if (exact && axis >= 0) roots.getOrPut(key) { mutableListOf() }.add(p)
                return p
            }
            var lo = 0f; var hi = 1f; var va = a; var vb = b
            var t = a/(a-b)
            var previousSide = 0
            val length = hypot(x2-x1,y2-y1)
            repeat(12) {
                t = (lo+(hi-lo)*va/(va-vb)).coerceIn(lo+(hi-lo)*.025f,hi-(hi-lo)*.025f)
                val v = sample(x1+(x2-x1)*t,y1+(y2-y1)*t)
                if (abs(v) < resolution*.000001f) return finish(t,true)
                // Illinois regula falsi keeps a nearly flat endpoint from pinning
                // every subsequent secant to the same unproductive side.
                if ((v <= 0f) == (va <= 0f)) {
                    lo = t; va = v
                    if (previousSide == 1) vb *= .5f
                    previousSide = 1
                } else {
                    hi = t; vb = v
                    if (previousSide == -1) va *= .5f
                    previousSide = -1
                }
                if ((hi-lo)*length <= tolerance*.125f) return finish((lo+hi)*.5f,true)
            }
            return finish(t,false)
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
            val probe = resolution*.015625f
            for (i in 1..3) {
                val x = px(a)+dx*i*.25f; val y = py(a)+dy*i*.25f
                val v = sample(x,y)
                val derivative = abs(sample(x+nx*probe,y+ny*probe)-sample(x-nx*probe,y-ny*probe))/(2f*probe)
                if (derivative < .0001f || abs(v) > derivative*tolerance) return false
            }
            return true
        }
        val normals = HashMap<Long,Long>()
        fun normal(p: Long): Long = normals.getOrPut(p) {
            val e = resolution*.015625f
            val nx = sample(px(p)+e,py(p))-sample(px(p)-e,py(p))
            val ny = sample(px(p),py(p)+e)-sample(px(p),py(p)-e)
            val length = hypot(nx,ny).coerceAtLeast(.0000001f)
            packed(nx/length,ny/length)
        }
        val controls = FloatArray(4)
        fun cubic(a: Long,b: Long): Boolean {
            val dx = px(b)-px(a); val dy = py(b)-py(a); val length = hypot(dx,dy)
            if (length < resolution) return false
            val na = normal(a); val nb = normal(b)
            var ax = -py(na); var ay = px(na); var bx = -py(nb); var by = px(nb)
            if (ax*dx+ay*dy < 0f) { ax = -ax; ay = -ay }
            if (bx*dx+by*dy < 0f) { bx = -bx; by = -by }
            // Equal Hermite handle lengths are fitted to the actual moving field at
            // the midpoint, then checked along the complete curve before accepting it.
            val ux = (ax-bx)*.375f; val uy = (ay-by)*.375f
            val mx = (px(a)+px(b))*.5f; val my = (py(a)+py(b))*.5f
            var handle = length/3f
            if (hypot(ux,uy) > .0001f) repeat(3) {
                val x = mx+ux*handle; val y = my+uy*handle
                val e = resolution*.125f
                val v = sample(x,y)
                val derivative = (sample(x+ux*e,y+uy*e)-sample(x-ux*e,y-uy*e))/(2f*e)
                if (abs(derivative) > .00001f) handle = (handle-v/derivative).coerceIn(0f,length*2f)
            }
            val c1x = px(a)+ax*handle; val c1y = py(a)+ay*handle
            val c2x = px(b)-bx*handle; val c2y = py(b)-by*handle
            val count = if (length > resolution*8f) 8 else 4
            for (i in 1 until count) {
                val t = i.toFloat()/count; val s = 1f-t
                val x = s*s*s*px(a)+3f*s*s*t*c1x+3f*s*t*t*c2x+t*t*t*px(b)
                val y = s*s*s*py(a)+3f*s*s*t*c1y+3f*s*t*t*c2y+t*t*t*py(b)
                val tx = 3f*s*s*(c1x-px(a))+6f*s*t*(c2x-c1x)+3f*t*t*(px(b)-c2x)
                val ty = 3f*s*s*(c1y-py(a))+6f*s*t*(c2y-c1y)+3f*t*t*(py(b)-c2y)
                val tangent = hypot(tx,ty)
                if (tangent < .00001f) return false
                val nx = -ty/tangent; val ny = tx/tangent; val probe = resolution*.015625f
                val derivative = abs(sample(x+nx*probe,y+ny*probe)-sample(x-nx*probe,y-ny*probe))/(2f*probe)
                if (derivative < .0001f || abs(sample(x,y)) > derivative*tolerance) return false
            }
            controls[0] = c1x; controls[1] = c1y; controls[2] = c2x; controls[3] = c2y
            return true
        }
        fun cell(left: Float,top: Float,right: Float,bottom: Float,a: Float,b: Float,c: Float,d: Float) {
            profile?.let { it.cells++ }
            val x = (left+right)*.5f; val y = (top+bottom)*.5f
            val w = right-left; val h = bottom-top
            // Exact item contours already cover these spans. Only added contact pixels
            // belong in this path; retaining interior cells needlessly complicates its union.
            if (field.covered(left,top,right,bottom) || !field.changesOutline(left,top,right,bottom) ||
                field.uniform(x,y,hypot(w,h)*.5f)) return
            if (field.connectedBand(left,top,right,bottom)) {
                path.addRect(Rect(left,top,right,bottom),Path.Direction.Clockwise)
                return
            }
            if (w > h*2f) {
                val e = sample(x,top); val g = sample(x,bottom)
                cell(left,top,x,bottom,a,e,g,d); cell(x,top,right,bottom,e,b,c,g)
                return
            }
            if (h > w*2f) {
                val k = sample(left,y); val f = sample(right,y)
                cell(left,top,right,y,a,b,f,k); cell(left,y,right,bottom,k,f,c,d)
                return
            }
            val e = sample(x,top); val f = sample(right,y)
            val g = sample(x,bottom); val k = sample(left,y); val m = sample(x,y)
            val code = (if(a<=0f)1 else 0) or (if(b<=0f)2 else 0) or (if(c<=0f)4 else 0) or (if(d<=0f)8 else 0)
            val allInside = code == 15 && e<=0f && f<=0f && g<=0f && k<=0f && m<=0f
            if (allInside && max(w,h)<=resolution) {
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
                val isCubic = hypot(px(end)-px(start),py(end)-py(start)) > resolution*4f && cubic(start,end)
                if (isCubic || straight(start,end)) {
                    if (isCubic) profile?.let { it.curves++ }
                    var first = true
                    var initial = 0L; var previous = 0L
                    fun edge(p: Long) {
                        if (isCubic && previous == start && p == end)
                            path.cubicTo(controls[0],controls[1],controls[2],controls[3],px(p),py(p))
                        else if (isCubic && previous == end && p == start)
                            path.cubicTo(controls[2],controls[3],controls[0],controls[1],px(p),py(p))
                        else path.lineTo(px(p),py(p))
                        previous = p
                    }
                    fun add(p: Long) {
                        if (first) { path.moveTo(px(p),py(p)); first=false; initial=p; previous=p }
                        else edge(p)
                    }
                    if (a<=0f) add(packed(left,top))
                    if ((a<=0f)!=(b<=0f)) add(r0)
                    if (b<=0f) add(packed(right,top))
                    if ((b<=0f)!=(c<=0f)) add(r1)
                    if (c<=0f) add(packed(right,bottom))
                    if ((c<=0f)!=(d<=0f)) add(r2)
                    if (d<=0f) add(packed(left,bottom))
                    if ((d<=0f)!=(a<=0f)) add(r3)
                    edge(initial); path.close(); return
                }
            }
            cell(left,top,x,y,a,e,m,k)
            cell(x,top,right,y,e,b,f,m)
            cell(x,y,right,bottom,m,f,c,g)
            cell(left,y,x,bottom,k,m,g,d)
        }
        val bounds = field.bounds
        cell(bounds.left,bounds.top,bounds.right,bounds.bottom,
            sample(bounds.left,bounds.top),sample(bounds.right,bounds.top),
            sample(bounds.right,bounds.bottom),sample(bounds.left,bounds.bottom))
        profile?.let { it.evaluationNanos += System.nanoTime()-started }
        return path
    }
}
