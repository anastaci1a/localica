package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import kotlin.math.*

// UI coordinates and lobe radii are bounded; avoid the general scaled hypot routine in hot geometry loops.
private fun outlineLength(x: Double,y: Double): Double = sqrt(x*x+y*y)

internal data class OutlinePoint(val x: Double,val y: Double) {
    operator fun plus(b: OutlinePoint) = OutlinePoint(x+b.x,y+b.y)
    operator fun minus(b: OutlinePoint) = OutlinePoint(x-b.x,y-b.y)
    operator fun times(s: Double) = OutlinePoint(x*s,y*s)
    fun dot(b: OutlinePoint) = x*b.x+y*b.y
    fun cross(b: OutlinePoint) = x*b.y-y*b.x
    fun length() = outlineLength(x,y)
    fun unit(): OutlinePoint { val n=length(); return if(n>1e-20) this*(1/n) else OutlinePoint(0.0,0.0) }
}
private const val TAU = PI*2
private fun positiveAngle(a: Double): Double = ((a%TAU)+TAU)%TAU
internal fun outlineEase(t: Double): Double {
    val p=t.coerceIn(0.0,1.0)
    return p*p*p*(p*(p*6-15)+10)
}
internal fun outlineReach(progress: Double,separation: Double,halfSpan: Double,unit: Double): Double =
    min(4*unit,halfSpan*.5)*outlineEase(progress)*outlineEase(1-max(separation,0.0)/(8*unit))

internal data class OutlineBox(val left: Double,val top: Double,val right: Double,val bottom: Double) {
    fun contains(p: OutlinePoint,e: Double=0.0) = p.x>=left-e && p.x<=right+e && p.y>=top-e && p.y<=bottom+e
    fun overlaps(b: OutlineBox,e: Double=0.0) = left<=b.right+e && right>=b.left-e && top<=b.bottom+e && bottom>=b.top-e
    fun inflate(r: Double)=OutlineBox(left-r,top-r,right+r,bottom+r)
}

/** Clockwise primitives, including the negative sweeps of concave base fillets. */
internal class OutlineEdge(val owner: Int,val parent: Int,val a: OutlinePoint,val b: OutlinePoint,
    val center: OutlinePoint?=null,val radius: Double=0.0,val angle: Double=0.0,val sweep: Double=0.0) {
    private val dx=b.x-a.x
    private val dy=b.y-a.y
    private val lineLengthSquared=dx*dx+dy*dy
    val length = if(center==null) outlineLength(dx,dy) else abs(sweep)*radius
    private val lineTangent=if(center==null && length>0)OutlinePoint((b.x-a.x)/length,(b.y-a.y)/length) else OutlinePoint(0.0,0.0)
    val normal=OutlinePoint(lineTangent.y,-lineTangent.x)
    val curve=OutlineCurve(center,radius,if(center==null)normal else null,if(center==null)normal.dot(a) else 0.0)
    val convex: Double get()=if(sweep>=0) 1.0 else -1.0
    val bounds: OutlineBox = run {
        var l=min(a.x,b.x); var r=max(a.x,b.x); var t=min(a.y,b.y); var d=max(a.y,b.y)
        if(center!=null) for(i in 0..3) {
            val p=parameterAtAngle(i*PI/2)
            if(p in 0.0..1.0) { val q=point(p); l=min(l,q.x);r=max(r,q.x);t=min(t,q.y);d=max(d,q.y) }
        }
        OutlineBox(l,t,r,d)
    }
    fun parameterAtAngle(theta: Double): Double {
        val s=abs(sweep)
        val d=positiveAngle((theta-angle)*if(sweep>=0) 1 else -1)
        if(d<=s+1e-10) return (d/s).coerceIn(0.0,1.0)
        val startDistance=TAU-d; val endDistance=d-s
        return if(startDistance<endDistance) -startDistance/s else 1+endDistance/s
    }
    fun parameter(p: OutlinePoint): Double = if(center==null) ((p.x-a.x)*dx+(p.y-a.y)*dy)/lineLengthSquared
        else parameterAtAngle(atan2(p.y-center.y,p.x-center.x))
    fun point(t: Double): OutlinePoint {
        if(t<=0) return a
        if(t>=1) return b
        if(center==null) return OutlinePoint(a.x+dx*t,a.y+dy*t)
        // Stable even for a shallow lobe whose circle centre is far off screen.
        val delta=sweep*t; val k=2*radius*sin(delta/2)
        return OutlinePoint(a.x-k*sin(angle+delta/2),a.y+k*cos(angle+delta/2))
    }
    fun tangent(t: Double): OutlinePoint = if(center==null) lineTangent else {
        val theta=angle+sweep*t; val sign=if(sweep>=0) 1 else -1
        OutlinePoint(-sin(theta)*sign,cos(theta)*sign)
    }
    fun outward(t: Double): OutlinePoint { val v=tangent(t);return OutlinePoint(v.y,-v.x) }
    fun distance(p: OutlinePoint): Double {
        val t=parameter(p)
        if(center!=null && t in 0.0..1.0) return abs(outlineLength(p.x-center.x,p.y-center.y)-radius)
        val nearest=point(t.coerceIn(0.0,1.0))
        return outlineLength(p.x-nearest.x,p.y-nearest.y)
    }
    fun emit(path: Path,from: Double=0.0,to: Double=1.0) {
        if(center==null) { val p=point(to);path.lineTo(p.x.toFloat(),p.y.toFloat());return }
        val total=sweep*(to-from)
        var count=max(1,ceil(abs(total)/(PI/2)).toInt())
        // Standard circular cubics are bounded well below a quarter dp here.
        while(radius*.0003*(abs(total)/count/(PI/2)).pow(6)> .01) count*=2
        for(i in 0 until count) {
            val u=from+(to-from)*i/count; val v=from+(to-from)*(i+1)/count
            val start=point(u);val end=point(v); val k=4.0/3*radius*tan(sweep*(v-u)/4)
            val ta=angle+sweep*u;val tb=angle+sweep*v
            path.cubicTo((start.x-sin(ta)*k).toFloat(),(start.y+cos(ta)*k).toFloat(),
                (end.x+sin(tb)*k).toFloat(),(end.y-cos(tb)*k).toFloat(),end.x.toFloat(),end.y.toFloat())
        }
    }
}

internal sealed class OutlineComponent(val id: Int,val parent: Int) {
    abstract val bounds: OutlineBox
    abstract val edges: List<OutlineEdge>
    abstract fun contains(p: OutlinePoint): Boolean
    open fun distance(p: OutlinePoint): Double {
        val d=edges.minOf { it.distance(p) }
        return if(contains(p)) -d else d
    }
    open fun emit(path: Path) {
        if(edges.isEmpty())return
        path.moveTo(edges.first().a.x.toFloat(),edges.first().a.y.toFloat())
        edges.forEach { it.emit(path) };path.close()
    }
}

internal class OutlineBody(id: Int,parent: Int,val box: Rect,val corner: Double): OutlineComponent(id,parent) {
    private val contour=RoundRect(box,CornerRadius(corner.toFloat()))
    override val bounds=OutlineBox(box.left.toDouble(),box.top.toDouble(),box.right.toDouble(),box.bottom.toDouble())
    val cx=(bounds.left+bounds.right)/2;val cy=(bounds.top+bounds.bottom)/2
    val hx=(bounds.right-bounds.left)/2-corner;val hy=(bounds.bottom-bounds.top)/2-corner
    override fun contains(p: OutlinePoint): Boolean {
        if(!bounds.contains(p))return false
        val x=max(abs(p.x-cx)-hx,0.0);val y=max(abs(p.y-cy)-hy,0.0)
        return x*x+y*y<=corner*corner+1e-12
    }
    override fun distance(p: OutlinePoint): Double {
        val x=abs(p.x-cx)-hx;val y=abs(p.y-cy)-hy
        return outlineLength(max(x,0.0),max(y,0.0))+min(max(x,y),0.0)-corner
    }
    override fun emit(path: Path) { path.addRoundRect(contour,Path.Direction.Clockwise) }
    override val edges: List<OutlineEdge> = buildList {
        val l=bounds.left;val t=bounds.top;val r=bounds.right;val b=bounds.bottom;val c=corner
        fun line(x1: Double,y1: Double,x2: Double,y2: Double) {
            if(outlineLength(x2-x1,y2-y1)>1e-12)add(OutlineEdge(id,parent,OutlinePoint(x1,y1),OutlinePoint(x2,y2)))
        }
        fun arc(x: Double,y: Double,start: Double) {
            if(c<=1e-12)return
            val center=OutlinePoint(x,y)
            fun p(a: Double)=OutlinePoint(x+c*cos(a),y+c*sin(a))
            add(OutlineEdge(id,parent,p(start),p(start+PI/2),center,c,start,PI/2))
        }
        line(l+c,t,r-c,t);arc(r-c,t+c,-PI/2)
        line(r,t+c,r,b-c);arc(r-c,b-c,0.0)
        line(r-c,b,l+c,b);arc(l+c,b-c,PI/2)
        line(l,b-c,l,t+c);arc(l+c,t+c,PI)
    }
}

/** One tangent circular lobe. Its base fillets meet the item's straight side without a corner. */
internal class OutlineLobe(id: Int,parent: Int,val origin: OutlinePoint,val outward: OutlinePoint,
    val halfSpan: Double,val reach: Double,val fillet: Double): OutlineComponent(id,parent) {
    private val across=OutlinePoint(-outward.y,outward.x)
    private val rotation=atan2(outward.y,outward.x)
    private val circle=halfSpan*halfSpan/(2*reach)-fillet+reach/2
    private val phi=2*atan(reach/halfSpan)
    private val contactX=2*fillet*sin(phi/2).pow(2)
    private val contactY=halfSpan-fillet*sin(phi)
    private fun world(x: Double,y: Double)=OutlinePoint(origin.x+outward.x*x+across.x*y,origin.y+outward.y*x+across.y*y)
    override val edges: List<OutlineEdge> = buildList {
        val top=world(0.0,-halfSpan);val upper=world(contactX,-contactY)
        val lower=world(contactX,contactY);val bottom=world(0.0,halfSpan)
        add(OutlineEdge(id,parent,top,upper,world(fillet,-halfSpan),fillet,rotation+PI,-phi))
        add(OutlineEdge(id,parent,upper,lower,world(reach-circle,0.0),circle,rotation-phi,2*phi))
        add(OutlineEdge(id,parent,lower,bottom,world(fillet,halfSpan),fillet,rotation-PI+phi,-phi))
        add(OutlineEdge(id,parent,bottom,top))
    }
    override val bounds: OutlineBox = edges.map { it.bounds }.reduce { a,b ->
        OutlineBox(min(a.left,b.left),min(a.top,b.top),max(a.right,b.right),max(a.bottom,b.bottom)) }
    override fun contains(p: OutlinePoint): Boolean {
        if(!bounds.contains(p))return false
        val dx=p.x-origin.x;val dy=p.y-origin.y
        val x=dx*outward.x+dy*outward.y;val y=abs(dx*across.x+dy*across.y)
        if(x<0 || y>halfSpan)return false
        val edge=if(y<=contactY) reach-y*y/(circle+sqrt(max(circle*circle-y*y,0.0)))
            else { val delta=halfSpan-y;delta*delta/(fillet+sqrt(max(fillet*fillet-delta*delta,0.0))) }
        return x<=edge+1e-12
    }
}

private data class OutlineVertex(val id: Int,val point: OutlinePoint) {
    val incoming=mutableListOf<OutlineSegment>()
    val outgoing=mutableListOf<OutlineSegment>()
}
private data class OutlineCut(val parameter: Double,val vertex: OutlineVertex)
private class OutlineSegment(val edge: OutlineEdge,val start: Double,val end: Double,
    val a: OutlineVertex,val b: OutlineVertex) {
    val length=edge.length*(end-start)
}
private class OutlineVertices(private val epsilon: Double) {
    val vertices=mutableListOf<OutlineVertex>()
    private val byPosition=HashMap<Long,OutlineVertex>()
    private fun key(x: Int,y: Int)=(x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    fun get(p: OutlinePoint): OutlineVertex {
        val x=round(p.x/epsilon).toInt();val y=round(p.y/epsilon).toInt()
        val square=epsilon*epsilon
        fun close(v: OutlineVertex): Boolean {
            val dx=v.point.x-p.x;val dy=v.point.y-p.y
            return dx*dx+dy*dy<=square
        }
        byPosition[key(x,y)]?.let { if(close(it))return it }
        for(dx in -1..1)for(dy in -1..1) {
            if(dx==0 && dy==0)continue
            val known=byPosition[key(x+dx,y+dy)] ?: continue
            if(close(known))return known
        }
        return OutlineVertex(vertices.size,p).also { vertices+=it;byPosition[key(x,y)]=it }
    }
}
internal data class OutlineCurve(val center: OutlinePoint?,val radius: Double,val normal: OutlinePoint?,val constant: Double)
private fun OutlineEdge.offset(distance: Double): OutlineCurve? {
    if(distance==0.0)return curve
    if(center==null) { val n=normal;return OutlineCurve(null,0.0,n,n.dot(a)+distance) }
    val r=radius+convex*distance
    return if(r>1e-12) OutlineCurve(center,r,null,0.0) else null
}
private inline fun curveIntersections(a: OutlineCurve,b: OutlineCurve,emit: (OutlinePoint)->Unit) {
    if(a.center==null && b.center==null) {
        val n=a.normal!!;val m=b.normal!!;val d=n.cross(m)
        if(abs(d)>1e-12)emit(OutlinePoint((a.constant*m.y-n.y*b.constant)/d,(n.x*b.constant-a.constant*m.x)/d))
    } else if(a.center==null || b.center==null) {
        val line=if(a.center==null)a else b;val circle=if(a.center!=null)a else b
        val n=line.normal!!;val c=circle.center!!;val distance=n.dot(c)-line.constant
        if(abs(distance)>circle.radius+1e-9)return
        val footX=c.x-n.x*distance;val footY=c.y-n.y*distance
        val along=sqrt(max((circle.radius-abs(distance))*(circle.radius+abs(distance)),0.0))
        emit(OutlinePoint(footX-n.y*along,footY+n.x*along))
        if(along>1e-10)emit(OutlinePoint(footX+n.y*along,footY-n.x*along))
    } else {
        val dx=b.center.x-a.center.x;val dy=b.center.y-a.center.y;val d=outlineLength(dx,dy)
        if(d<1e-12 || d>a.radius+b.radius+1e-9 || d<abs(a.radius-b.radius)-1e-9)return
        val along=((a.radius-b.radius)*(a.radius+b.radius)+d*d)/(2*d)
        val height=sqrt(max((a.radius-along)*(a.radius+along),0.0))
        val inverse=1/d;val ux=dx*inverse;val uy=dy*inverse
        val centerX=a.center.x+ux*along;val centerY=a.center.y+uy*along
        emit(OutlinePoint(centerX-uy*height,centerY+ux*height))
        if(height>1e-10)emit(OutlinePoint(centerX+uy*height,centerY-ux*height))
    }
}
private inline fun edgeIntersections(a: OutlineEdge,b: OutlineEdge,epsilon: Double,emit: (Double,Double,OutlinePoint)->Unit) {
    if(!a.bounds.overlaps(b.bounds,epsilon))return
    if(a.center==null && b.center==null && abs(a.normal.cross(b.normal))<1e-12) {
        // Coincident faces split at both ends; their opposing internal edges disappear.
        if(abs((b.a-a.a).dot(a.normal))>epsilon)return
        for(p in listOf(a.a,a.b,b.a,b.b)) {
            val ta=a.parameter(p);val tb=b.parameter(p)
            if(ta>=-1e-8 && ta<=1+1e-8 && tb>=-1e-8 && tb<=1+1e-8)emit(ta.coerceIn(0.0,1.0),tb.coerceIn(0.0,1.0),p)
        }
        return
    }
    curveIntersections(a.offset(0.0)!!,b.offset(0.0)!!) { p ->
        val ta=a.parameter(p);val tb=b.parameter(p)
        if(ta>=-1e-8 && ta<=1+1e-8 && tb>=-1e-8 && tb<=1+1e-8)
            emit(ta.coerceIn(0.0,1.0),tb.coerceIn(0.0,1.0),p)
    }
}
private data class OutlineSide(val segment: OutlineSegment,val from: Double,val to: Double) {
    val low get()=min(from,to)
    val high get()=max(from,to)
}
private const val OUTLINE_WALK_LIMIT=8
private class OutlineChain(start: OutlineSegment,private val backwards: Boolean) {
    private val sides=mutableListOf<OutlineSide>()
    private var next: OutlineSegment?=start
    operator fun get(index: Int): OutlineSide=requireNotNull(getOrNull(index))
    fun getOrNull(index: Int): OutlineSide? {
        if(index !in 0 until OUTLINE_WALK_LIMIT)return null
        while(sides.size<=index) {
            val segment=next ?: return null
            if(sides.any { it.segment===segment }) { next=null;return null }
            sides+=OutlineSide(segment,if(backwards)segment.end else segment.start,if(backwards)segment.start else segment.end)
            val vertex=if(backwards)segment.a else segment.b
            val candidates=if(backwards)vertex.incoming else vertex.outgoing
            next=when(candidates.size) {
                0->null
                1->candidates[0]
                else->{
                    val tangent=segment.edge.tangent(if(backwards)segment.start else segment.end)
                    candidates.maxBy { it.edge.tangent(if(backwards)it.end else it.start).dot(tangent) }
                }
            }
        }
        return sides[index]
    }
}
internal data class OutlineFilletProof(val center: OutlinePoint,val radius: Double,val a: OutlinePoint,val b: OutlinePoint,
    val tangentA: OutlinePoint,val tangentB: OutlinePoint)
private class OutlineFillet(val first: OutlineChain,val second: OutlineChain,val indexA: Int,val indexB: Int,
    val ta: Double,val tb: Double,val center: OutlinePoint,val radius: Double) {
    private val a=first[indexA].segment.edge.point(ta)
    private val b=second[indexB].segment.edge.point(tb)
    val proof=OutlineFilletProof(center,radius,a,b,first[indexA].segment.edge.tangent(ta),second[indexB].segment.edge.tangent(tb))
    fun emit(path: Path) {
        val corner=first[0].segment.edge.point(first[0].from)
        path.moveTo(corner.x.toFloat(),corner.y.toFloat())
        fun inside(edge: OutlineEdge,from: Double,to: Double) {
            // These return edges are covered by the original component. A chord
            // stays inside a convex edge; only a concave edge needs its full arc.
            if(edge.center==null || edge.convex>0) {
                val p=edge.point(to);path.lineTo(p.x.toFloat(),p.y.toFloat())
            } else edge.emit(path,from,to)
        }
        for(i in 0..indexA) {
            val s=first[i];inside(s.segment.edge,s.from,if(i==indexA)ta else s.to)
        }
        val angle=atan2(a.y-center.y,a.x-center.x)
        val end=atan2(b.y-center.y,b.x-center.x)
        val sweep=-positiveAngle(angle-end)
        OutlineEdge(-1,-1,a,b,center,radius,angle,sweep).emit(path)
        for(i in indexB downTo 0) {
            val s=second[i];inside(s.segment.edge,if(i==indexB)tb else s.to,s.from)
        }
        path.close()
    }
}

/** All model work is analytic: rounded edges, tangent circular lobes and rolling fillets.
 * The positive-winding result is the single frost mask and shadow caster. */
internal class AnalyticSelectionOutline(private val profile: SelectionOutlineProfile?=null) {
    private data class Corner(val vertex: OutlineVertex,val incoming: OutlineSegment,val outgoing: OutlineSegment) {
        val parentA get()=min(incoming.edge.parent,outgoing.edge.parent)
        val parentB get()=max(incoming.edge.parent,outgoing.edge.parent)
    }
    private var components=emptyList<OutlineComponent>()
    private var cachedBodies=emptyList<OutlineBody>()
    private var cachedCycles=emptyList<Pair<List<OutlineEdge>,List<OutlineSegment>>?>()
    private var unit=1.0
    private var epsilon=1e-5
    var fillets: List<OutlineFilletProof> = emptyList();private set
    var lobeCount=0;private set
    private var segmentCount=0
    private fun sideIntent(a: OutlineBody,b: OutlineBody): Pair<Double,Double>? {
        val dx=max(a.bounds.left-b.bounds.right,b.bounds.left-a.bounds.right)
        val dy=max(a.bounds.top-b.bounds.bottom,b.bounds.top-a.bounds.bottom)
        val horizontal=dx>=dy
        val start=if(horizontal)max(a.bounds.top+a.corner,b.bounds.top+b.corner) else max(a.bounds.left+a.corner,b.bounds.left+b.corner)
        val end=if(horizontal)min(a.bounds.bottom-a.corner,b.bounds.bottom-b.corner) else min(a.bounds.right-a.corner,b.bounds.right-b.corner)
        return if(end>start) (if(horizontal)dx else dy) to (end-start) else null
    }
    private fun addLobes(bodies: List<OutlineBody>,strength: (Int,Int)->Float): List<OutlineComponent> {
        val all=bodies.toMutableList<OutlineComponent>()
        for(i in bodies.indices)for(j in i+1 until bodies.size) {
            val a=bodies[i];val b=bodies[j];val side=sideIntent(a,b) ?: continue
            val gap=side.first
            if(gap<0 || gap>=8*unit)continue
            val reach=outlineReach(strength(i,j).toDouble(),gap,side.second/2,unit)
            if(reach<=unit*1e-4)continue
            val dx=max(a.bounds.left-b.bounds.right,b.bounds.left-a.bounds.right)
            val dy=max(a.bounds.top-b.bounds.bottom,b.bounds.top-a.bounds.bottom)
            val horizontal=dx>=dy
            val start=if(horizontal)max(a.bounds.top+a.corner,b.bounds.top+b.corner) else max(a.bounds.left+a.corner,b.bounds.left+b.corner)
            val middle=start+side.second/2;val half=side.second/2
            val fillet=min(4*unit,half*.25)
            fun add(body: OutlineBody,other: OutlineBody) {
                val positive=if(horizontal)body.cx<other.cx else body.cy<other.cy
                val origin=if(horizontal)OutlinePoint(if(positive)body.bounds.right else body.bounds.left,middle)
                    else OutlinePoint(middle,if(positive)body.bounds.bottom else body.bounds.top)
                val direction=if(horizontal)OutlinePoint(if(positive)1.0 else -1.0,0.0)
                    else OutlinePoint(0.0,if(positive)1.0 else -1.0)
                all+=OutlineLobe(all.size,body.parent,origin,direction,half,reach,fillet)
            }
            add(a,b);add(b,a)
        }
        return all
    }
    private fun segments(vertices: OutlineVertices,scope: List<OutlineComponent>): List<OutlineSegment> {
        val edges=scope.flatMap { it.edges }
        val cuts=edges.map { e -> mutableListOf(OutlineCut(0.0,vertices.get(e.a)),OutlineCut(1.0,vertices.get(e.b))) }
        val byOwner=edges.indices.groupBy { edges[it].owner }
        val owners=byOwner.keys.toList()
        for(u in owners.indices)for(v in u+1 until owners.size) {
            val ownerA=owners[u];val ownerB=owners[v]
            val compA=components[ownerA];val compB=components[ownerB]
            if(!compA.bounds.overlaps(compB.bounds,epsilon))continue
            val body=compA as? OutlineBody ?: compB as? OutlineBody
            val lobe=compA as? OutlineLobe ?: compB as? OutlineLobe
            if(body!=null && lobe!=null && body.parent==lobe.parent) {
                // Their only shared boundary is the known straight attachment.
                for(i in byOwner.getValue(body.id)) {
                    val edge=edges[i]
                    if(edge.center!=null || edge.normal.dot(lobe.outward)<1-1e-8)continue
                    for(p in listOf(lobe.edges.first().a,lobe.edges[2].b)) {
                        val t=edge.parameter(p)
                        if(t in -1e-8..1.00000001)cuts[i]+=OutlineCut(t.coerceIn(0.0,1.0),vertices.get(p))
                    }
                }
                continue
            }
            for(i in byOwner.getValue(ownerA))for(j in byOwner.getValue(ownerB)) {
                val a=edges[i];val b=edges[j]
                edgeIntersections(a,b,epsilon) { ta,tb,p ->
                    val vertex=vertices.get(p);cuts[i]+=OutlineCut(ta,vertex);cuts[j]+=OutlineCut(tb,vertex)
                }
            }
        }
        val active=mutableListOf<OutlineSegment>()
        val byEnds=HashMap<Long,MutableList<OutlineSegment>>()
        for(i in edges.indices) {
            val edge=edges[i];val points=cuts[i].sortedBy { it.parameter }
            for(k in 0 until points.lastIndex) {
                val a=points[k];val b=points[k+1]
                if(a.vertex.id==b.vertex.id || (b.parameter-a.parameter)*edge.length<=epsilon)continue
                val t=(a.parameter+b.parameter)/2
                val outside=edge.point(t)+edge.outward(t)*(epsilon*.1)
                if(scope.any { it.id!=edge.owner && it.bounds.contains(outside) && it.contains(outside) })continue
                val segment=OutlineSegment(edge,a.parameter,b.parameter,a.vertex,b.vertex)
                val key=(a.vertex.id.toLong() shl 32) or b.vertex.id.toLong()
                val same=byEnds.getOrPut(key) { mutableListOf() }
                if(same.any { (it.edge.point((it.start+it.end)/2)-edge.point(t)).length()<epsilon })continue
                same+=segment;active+=segment
                a.vertex.outgoing+=segment;b.vertex.incoming+=segment
            }
        }
        return active
    }
    private fun corners(scope: List<OutlineComponent>): List<Corner> {
        val vertices=OutlineVertices(epsilon)
        segmentCount+=segments(vertices,scope).size
        return vertices.vertices.mapNotNull { v ->
            if(v.incoming.size!=1 || v.outgoing.size!=1)return@mapNotNull null
            val a=v.incoming.single();val b=v.outgoing.single()
            // A lobe is analytically tangent to its own body. At vanishing reach,
            // circle parameters lose a few angular bits; never round that C1 join again.
            if(a.edge.parent==b.edge.parent &&
                (components[a.edge.owner] is OutlineBody || components[b.edge.owner] is OutlineBody))return@mapNotNull null
            val u=a.edge.tangent(a.end);val w=b.edge.tangent(b.start)
            if(u.cross(w)<-1e-7 || u.dot(w)<-1+1e-7)Corner(v,a,b) else null
        }
    }
    private fun localCorners(bodies: List<OutlineBody>): List<Corner> {
        val groups=bodies.indices.map { parent -> components.filter { it.parent==parent } }
        val result=mutableListOf<Corner>()
        for(a in groups.indices)for(b in a until groups.size) {
            val left=groups[a];val right=groups[b]
            if(a==b) {
                val lobes=left.filterIsInstance<OutlineLobe>()
                if(lobes.indices.none { i -> (i+1 until lobes.size).any { j -> lobes[i].bounds.overlaps(lobes[j].bounds) } })continue
            } else if(left.none { x -> right.any { y -> x.bounds.overlaps(y.bounds,epsilon) } })continue
            val scope=if(a==b)left else left.filter { x -> x is OutlineBody || right.any { y -> x.bounds.overlaps(y.bounds,8*unit) } }+
                right.filter { y -> y is OutlineBody || left.any { x -> y.bounds.overlaps(x.bounds,8*unit) } }
            for(corner in corners(scope)) {
                if(corner.parentA!=a || corner.parentB!=b)continue
                val p=corner.vertex.point
                if(components.any { it.parent!=a && it.parent!=b && it.bounds.contains(p) && it.distance(p)<-epsilon })continue
                result+=corner
            }
        }
        return result
    }
    /** Rounded bodies already have a cyclic boundary. Reuse that topology during
     * flight rather than rebuilding an arrangement of all their exposed fragments.
     * Clearance checks still reject contacts hidden by any third selection. */
    private fun parametricCorners(bodies: List<OutlineBody>): List<Corner>? {
        val outlines=mutableListOf<List<OutlineEdge>>()
        val boxes=mutableListOf<OutlineBox>()
        val lobesByParent=components.filterIsInstance<OutlineLobe>().groupBy { it.parent }
        for(body in bodies) {
            val lobes=lobesByParent[body.parent].orEmpty()
            if(lobes.isEmpty()) { outlines+=body.edges;boxes+=body.bounds;continue }
            val outline=mutableListOf<OutlineEdge>()
            for(edge in body.edges) {
                if(edge.center!=null) { outline+=edge;continue }
                val attached=lobes.filter { edge.normal.dot(it.outward)>1-1e-8 }
                    .sortedBy { edge.parameter(it.edges.first().a) }
                if(attached.isEmpty()) { outline+=edge;continue }
                var end=0.0
                for(lobe in attached) {
                    val start=edge.parameter(lobe.edges.first().a)
                    val finish=edge.parameter(lobe.edges[2].b)
                    // Multiple lobes on one face can overlap; retain the general
                    // arrangement for that topology rather than assuming an order.
                    if(start<end-1e-8)return null
                    if((start-end)*edge.length>epsilon)outline+=OutlineEdge(body.id,body.parent,edge.point(end),lobe.edges.first().a)
                    outline+=lobe.edges.take(3)
                    end=finish
                }
                if((1-end)*edge.length>epsilon)outline+=OutlineEdge(body.id,body.parent,edge.point(end),edge.b)
            }
            outlines+=outline
            boxes+=lobes.fold(body.bounds) { box,lobe -> OutlineBox(min(box.left,lobe.bounds.left),min(box.top,lobe.bounds.top),
                max(box.right,lobe.bounds.right),max(box.bottom,lobe.bounds.bottom)) }
        }
        // Most flight contours never meet another boundary. Build their linked
        // walks only after a visible intersection actually requests a fillet.
        val cycles=arrayOfNulls<List<OutlineSegment>>(outlines.size)
        fun cycle(index: Int): List<OutlineSegment> {
            cycles[index]?.let { return it }
            val edges=outlines[index]
            val retained=cachedCycles.getOrNull(index)?.takeIf { it.first===edges }?.second
            val segments=retained ?: run {
                val vertices=edges.mapIndexed { i,e -> OutlineVertex(i,e.a) }
                edges.mapIndexed { i,e ->
                    val a=vertices[i];val b=vertices[(i+1)%vertices.size]
                    OutlineSegment(e,0.0,1.0,a,b).also { a.outgoing+=it;b.incoming+=it }
                }
            }
            cycles[index]=segments
            return segments
        }
        val result=mutableListOf<Corner>()
        for(i in bodies.indices)for(j in i+1 until bodies.size) {
            if(!boxes[i].overlaps(boxes[j],epsilon))continue
            val left=outlines[i];val right=outlines[j]
            for(ai in left.indices) {
              val edgeA=left[ai]
              if(!edgeA.bounds.overlaps(boxes[j],epsilon))continue
              for(bi in right.indices) {
                val edgeB=right[bi]
                if(!edgeB.bounds.overlaps(boxes[i],epsilon))continue
                edgeIntersections(edgeA,edgeB,epsilon) { ta,tb,p ->
                    val cross=edgeA.tangent(ta).cross(edgeB.tangent(tb))
                    if(abs(cross)<1e-7)return@edgeIntersections
                    if(components.any { it.id!=edgeA.owner && it.id!=edgeB.owner && it.bounds.contains(p) && it.distance(p)<-epsilon })return@edgeIntersections
                    val a=cycle(i)[ai];val b=cycle(j)[bi]
                    val vertex=OutlineVertex(-1,p)
                    val incoming=if(cross<0)a else b;val outgoing=if(cross<0)b else a
                    val at=if(cross<0)ta else tb;val bt=if(cross<0)tb else ta
                    val first=OutlineSegment(incoming.edge,0.0,at,incoming.a,vertex)
                    val second=OutlineSegment(outgoing.edge,bt,1.0,vertex,outgoing.b)
                    if(result.any { it.parentA==i && it.parentB==j && (it.vertex.point-p).length()<epsilon })return@edgeIntersections
                    result+=Corner(vertex,first,second)
                }
            }
        }
        }
        cachedCycles=outlines.indices.map { i -> cycles[i]?.let { outlines[i] to it } }
        segmentCount=cycles.sumOf { it?.size ?: 0 }
        return result
    }
    private fun request(corner: Corner,bodies: List<OutlineBody>,strength: (Int,Int)->Float): Double {
        val a=corner.parentA;val b=corner.parentB
        if(a==b)return 4*unit
        val bodyA=bodies[a];val bodyB=bodies[b]
        val x=abs(bodyA.cx-bodyB.cx)-bodyA.hx-bodyB.hx
        val y=abs(bodyA.cy-bodyB.cy)-bodyA.hy-bodyB.hy
        val separation=outlineLength(max(x,0.0),max(y,0.0))+min(max(x,y),0.0)-bodyA.corner-bodyB.corner
        var depth=max(-separation,0.0)
        val side=sideIntent(bodyA,bodyB)
        if(side!=null) {
            val reach=outlineReach(strength(a,b).toDouble(),side.first,side.second/2,unit)
            depth=max(depth,2*reach-side.first)
        }
        val sourceA=components[corner.incoming.edge.owner];val sourceB=components[corner.outgoing.edge.owner]
        if(sourceA is OutlineLobe || sourceB is OutlineLobe) {
            val edgeA=corner.incoming.edge;val edgeB=corner.outgoing.edge
            if(edgeA.center!=null && edgeB.center!=null && edgeA.convex>0 && edgeB.convex>0) {
                val circleDepth=edgeA.radius+edgeB.radius-(edgeA.center-edgeB.center).length()
                val capReach=min((sourceA as? OutlineLobe)?.reach ?: Double.POSITIVE_INFINITY,
                    (sourceB as? OutlineLobe)?.reach ?: Double.POSITIVE_INFINITY)
                depth=max(depth,min(circleDepth,capReach*2))
            }
        }
        var radius=4*unit*outlineEase(depth/(4*unit))
        val normalA=corner.incoming.edge.outward(corner.incoming.end)
        val normalB=corner.outgoing.edge.outward(corner.outgoing.start)
        val bisector=sqrt(max((1+normalA.dot(normalB))/2,0.0))
        for(component in components) {
            if(component.parent==a || component.parent==b)continue
            // A third selection continuously closes the available exterior wedge.
            // Reserve its clearance before solving contacts, avoiding repeated
            // infeasible large-radius trials near a three-way intersection.
            val p=corner.vertex.point;val box=component.bounds
            val boxDistance=outlineLength(max(max(box.left-p.x,p.x-box.right),0.0),max(max(box.top-p.y,p.y-box.bottom),0.0))
            if(boxDistance*bisector/(1+bisector)*.9>=radius)continue
            val clearance=max(component.distance(corner.vertex.point),0.0)
            radius=min(radius,clearance*bisector/(1+bisector)*.9)
        }
        return radius
    }
    private fun candidate(corner: Corner,first: OutlineChain,second: OutlineChain,radius: Double): OutlineFillet? {
        var best: OutlineFillet?=null;var bestTravel=Double.POSITIVE_INFINITY
        for(total in 0 until (2*OUTLINE_WALK_LIMIT-1)) {
          for(i in 0..min(total,OUTLINE_WALK_LIMIT-1)) {
            val j=total-i
            val sideA=first.getOrNull(i) ?: continue
            val sideB=second.getOrNull(j) ?: continue
            val a=sideA.segment.edge;val b=sideB.segment.edge
            val ca=a.offset(radius) ?: continue;val cb=b.offset(radius) ?: continue
            curveIntersections(ca,cb) { center ->
                fun contact(edge: OutlineEdge,curve: OutlineCurve): OutlinePoint = if(edge.center==null)
                    OutlinePoint(center.x-edge.normal.x*radius,center.y-edge.normal.y*radius) else {
                    val scale=edge.radius/curve.radius
                    OutlinePoint(edge.center.x+(center.x-edge.center.x)*scale,edge.center.y+(center.y-edge.center.y)*scale)
                }
                val pa=contact(a,ca);val pb=contact(b,cb)
                val ta=a.parameter(pa);val tb=b.parameter(pb)
                if(ta<sideA.low-1e-8 || ta>sideA.high+1e-8 || tb<sideB.low-1e-8 || tb>sideB.high+1e-8)return@curveIntersections
                val angle=atan2(pa.y-center.y,pa.x-center.x)
                val end=atan2(pb.y-center.y,pb.x-center.x)
                val sweep=-positiveAngle(angle-end)
                if(sweep < -PI-1e-7 || sweep > -1e-8)return@curveIntersections
                val travel=(pa-corner.vertex.point).length()+(pb-corner.vertex.point).length()
                if(travel>=bestTravel)return@curveIntersections
                if(components.any { it.bounds.contains(center,radius) && it.distance(center)<radius-epsilon*.25 })return@curveIntersections
                best=OutlineFillet(first,second,i,j,ta.coerceIn(sideA.low,sideA.high),tb.coerceIn(sideB.low,sideB.high),center,radius)
                bestTravel=travel
            }
          }
          if(best!=null)return best
        }
        return best
    }
    internal fun inspect(regions: List<SelectionRegion>,gap: Float,strength: (Int,Int)->Float): List<OutlineFilletProof> {
        prepare(regions,gap,strength)
        return fillets
    }
    private fun prepare(regions: List<SelectionRegion>,gap: Float,strength: (Int,Int)->Float): List<OutlineFillet> {
        val started=if(profile!=null)System.nanoTime() else 0L
        unit=gap.toDouble()/16;epsilon=unit*1e-5
        val bodies=regions.mapIndexed { i,r ->
            val radius=r.radius(gap).toDouble()
            cachedBodies.getOrNull(i)?.takeIf { it.box==r.bounds && it.corner==radius }
                ?: OutlineBody(i,i,r.bounds,radius)
        }
        cachedBodies=bodies
        components=addLobes(bodies,strength);lobeCount=components.size-bodies.size
        segmentCount=0
        val corners=parametricCorners(bodies) ?: if(components.size>8)localCorners(bodies) else corners(components)
        val arranged=if(profile!=null)System.nanoTime() else 0L
        val patches=mutableListOf<OutlineFillet>()
        for(corner in corners) {
            val radius=request(corner,bodies,strength)
            if(radius<=unit*1e-5)continue
            val first=OutlineChain(corner.incoming,true);val second=OutlineChain(corner.outgoing,false)
            var patch=candidate(corner,first,second,radius)
            if(patch==null) {
                var low=0.0;var high=radius
                repeat(10) {
                    val r=(low+high)/2;val next=candidate(corner,first,second,r)
                    if(next!=null) { low=r;patch=next } else high=r
                }
            }
            patch?.let(patches::add)
        }
        fillets=patches.map { it.proof }
        val rounded=if(profile!=null)System.nanoTime() else 0L
        profile?.let {
            it.evaluationNanos+=rounded-started
            it.arrangementNanos+=arranged-started;it.roundingNanos+=rounded-arranged
            it.samples+=components.sumOf { component -> component.edges.size }
            it.cells+=segmentCount;it.curves+=patches.size
        }
        return patches
    }
    fun path(regions: List<SelectionRegion>,gap: Float,strength: (Int,Int)->Float): Path {
        val patches=prepare(regions,gap,strength)
        val rounded=if(profile!=null)System.nanoTime() else 0L
        val path=Path().apply {
            for(component in components)component.emit(this)
            for(patch in patches)patch.emit(this)
        }
        profile?.let { it.pathNanos+=System.nanoTime()-rounded }
        return path
    }
}

internal class SelectionOutlineProfile {
    var evaluationNanos = 0L
    var pathNanos = 0L
    var samples = 0L
    var cells = 0L
    var curves = 0L
    var arrangementNanos=0L
    var roundingNanos=0L
}

