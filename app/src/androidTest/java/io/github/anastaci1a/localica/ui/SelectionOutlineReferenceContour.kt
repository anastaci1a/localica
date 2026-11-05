package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import kotlin.math.*

/** Fine uniform-grid reference; deliberately independent of the production contour sampler. */
internal class SelectionOutlineReferenceContour(private val profile: SelectionOutlineProfile? = null) {
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
    private var values = FloatArray(0)
    private var stamps = IntArray(0)
    private var links = IntArray(0)
    private var edgeStamps = IntArray(0)
    private var active = IntArray(0)
    private var activeCount = 0
    private var stamp = 0
    private var stride = 0
    private var rows = 0
    private var x0 = 0f
    private var y0 = 0f
    private var step = 1f
    private lateinit var field: SelectionOutlineField
    private fun sample(x: Int,y: Int): Float {
        val i = y*stride+x
        if (stamps[i] != stamp) { stamps[i] = stamp; values[i] = field.value(x0+x*step,y0+y*step) }
        return values[i]
    }
    fun path(source: SelectionOutlineField, resolution: Float): Path {
        field = source; step = resolution
        val b = field.bounds.inflate(step*2f)
        x0 = floor(b.left/step)*step; y0 = floor(b.top/step)*step
        stride = ceil((b.right-x0)/step).toInt()+1; rows = ceil((b.bottom-y0)/step).toInt()+1
        val count = stride*rows
        if (values.size < count) { values = FloatArray(count); stamps = IntArray(count); links = IntArray(count*4); edgeStamps = IntArray(count*2); active = IntArray(count*2) }
        stamp++
        activeCount = 0
        fun edge(x: Int,y: Int,w: Int,h: Int,which: Int): Int = when(which) {
            0 -> (y*stride+x)*2
            1 -> (y*stride+x+w)*2+1
            2 -> ((y+h)*stride+x)*2
            else -> (y*stride+x)*2+1
        }
        fun connect(a: Int,b: Int) {
            fun prepare(e: Int) {
                if (edgeStamps[e] != stamp) {
                    edgeStamps[e] = stamp; links[e*2] = -1; links[e*2+1] = -1; active[activeCount++] = e
                }
            }
            prepare(a); prepare(b)
            val p = a*2; val q = b*2
            if (links[p] == -1) links[p] = b else links[p+1] = b
            if (links[q] == -1) links[q] = a else links[q+1] = a
        }
        fun leaf(x: Int,y: Int,w: Int,h: Int) {
            val a = sample(x,y); val b = sample(x+w,y); val c = sample(x+w,y+h); val d = sample(x,y+h)
            val code = (if(a <= 0f)1 else 0) or (if(b <= 0f)2 else 0) or
                (if(c <= 0f)4 else 0) or (if(d <= 0f)8 else 0)
            if (code == 0 || code == 15) return
            fun join(i: Int,j: Int) = connect(edge(x,y,w,h,i),edge(x,y,w,h,j))
            when(code) {
                1,14 -> join(0,3)
                2,13 -> join(0,1)
                3,12 -> join(1,3)
                4,11 -> join(1,2)
                6,9 -> join(0,2)
                7,8 -> join(2,3)
                else -> {
                    val center = field.value(x0+(x+w*.5f)*step,y0+(y+h*.5f)*step) <= 0f
                    if (center == (code == 5)) { join(0,1); join(2,3) }
                    else { join(0,3); join(1,2) }
                }
            }
        }
        fun visit(x: Int,y: Int,w: Int,h: Int) {
            val hw = w*step*.5f; val hh = h*step*.5f
            if (field.uniform(x0+x*step+hw,y0+y*step+hh,sqrt(hw*hw+hh*hh))) return
            if (w == 1 && h == 1) { leaf(x,y,w,h); return }
            val axis = field.straightAxis(x0+x*step,y0+y*step,x0+(x+w)*step,y0+(y+h)*step)
            if (axis == 0) {
                if (w == 1) leaf(x,y,w,h)
                else { val half = w/2; visit(x,y,half,h); visit(x+half,y,w-half,h) }
                return
            }
            if (axis == 1) {
                if (h == 1) leaf(x,y,w,h)
                else { val half = h/2; visit(x,y,w,half); visit(x,y+half,w,h-half) }
                return
            }
            val a = max(w/2,1); val c = max(h/2,1)
            visit(x,y,a,c)
            if (w>a) visit(x+a,y,w-a,c)
            if (h>c) visit(x,y+c,a,h-c)
            if (w>a && h>c) visit(x+a,y+c,w-a,h-c)
        }
        val evaluateStart = if (profile != null) System.nanoTime() else 0L
        visit(0,0,stride-1,rows-1)
        val pathStart = if (profile != null) System.nanoTime() else 0L
        profile?.let { it.evaluationNanos += pathStart-evaluateStart }
        val path = Path().apply { fillType = PathFillType.EvenOdd }
        fun point(edge: Int): Long {
            val i = edge/2; val x = i%stride; val y = i/stride
            val vertical = edge%2 == 1
            val a = sample(x,y); val b = sample(x+if(vertical)0 else 1,y+if(vertical)1 else 0)
            val t = if (a == b) .5f else (a/(a-b)).coerceIn(0f,1f)
            val px = x0+(x+if(vertical)0f else t)*step
            val py = y0+(y+if(vertical)t else 0f)*step
            return (px.toRawBits().toLong() shl 32) or (py.toRawBits().toLong() and 0xffffffffL)
        }
        fun px(p: Long) = Float.fromBits((p shr 32).toInt())
        fun py(p: Long) = Float.fromBits(p.toInt())
        for (index in 0 until activeCount) {
            val start = active[index]
            if (links[start*2] < 0) continue
            val first = point(start); val second = point(links[start*2])
            val dx = px(second)-px(first); val dy = py(second)-py(first)
            val length = hypot(dx,dy).coerceAtLeast(.00001f)
            val mx = (px(first)+px(second))*.5f; val my = (py(first)+py(second))*.5f
            val epsilon = step*.125f
            val rightFilled = field.value(mx-dy/length*epsilon,my+dx/length*epsilon) <
                field.value(mx+dy/length*epsilon,my-dx/length*epsilon)
            var current = start
            var previous = links[start*2+if (rightFilled) 1 else 0]
            path.moveTo(px(first),py(first))
            var anchor = first
            var pending = first
            do {
                val p = current*2
                val next = if (links[p] == previous) links[p+1] else links[p]
                links[p] = -2; links[p+1] = -2
                previous = current; current = next
                if (current >= 0 && current != start) {
                    val p = point(current)
                    val ax = px(pending)-px(anchor); val ay = py(pending)-py(anchor)
                    val bx = px(p)-px(anchor); val by = py(p)-py(anchor)
                    if (abs(ax*by-ay*bx) > .00001f || ax*bx+ay*by < 0f) {
                        path.lineTo(px(pending),py(pending))
                        anchor = pending
                    }
                    pending = p
                }
            } while (current >= 0 && current != start && links[current*2] >= 0)
            path.lineTo(px(pending),py(pending))
            path.close()
        }
        profile?.let { it.pathNanos += System.nanoTime()-pathStart }
        return path
    }
}
