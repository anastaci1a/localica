package io.github.anastaci1a.localica

import android.graphics.Region
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidPath
import io.github.anastaci1a.localica.ui.SelectionRegion
import io.github.anastaci1a.localica.ui.connectedSelectionPath
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Geometry proofs run without the compositor, so antialiasing cannot hide a sharp join. */
class SelectionMergeGeometryTest {
    @Test fun retainedPairStrengthsMatchFreshContoursAcrossReorderResizeAndDuplicateMembers() {
        val cache=io.github.anastaci1a.localica.ui.HoverPathCache()
        val base=List(4) { i -> SelectionRegion(Rect(i%2*81f,i/2*81f,i%2*81f+80f,i/2*81f+80f),12f) }
        val clip=Region(-20,-20,300,300)
        fun pixels(path: androidx.compose.ui.graphics.Path)=Region().apply { setPath(path.asAndroidPath(),clip) }
        for((step,items) in listOf(base,base.reversed(),base.take(2),base+base[0],base,base).withIndex()) {
            fun strength(a: Int,b: Int)=((a*7+b*3+step)%11)/10f
            val actual=cache.path(items,items,16f,::strength)
            val expected=connectedSelectionPath(items,16f,strength=::strength)
            assertEquals("step=$step",pixels(expected),pixels(actual))
            val rebuilds=cache.rebuilds
            assertSame(actual,cache.path(items,items,16f,::strength))
            assertEquals(rebuilds,cache.rebuilds)
        }
    }

    @Test fun opacitySubsetsReusePathsAndMatchTheRestPartitionAtRemoval() {
        val regions=List(3) { i -> SelectionRegion(Rect(0f,i*73f,120f,i*73f+72f),12f) }
        val cache=io.github.anastaci1a.localica.ui.SelectionOpacityPathCache()
        fun draw(p: Float)=cache.paths(regions,regions,16f,listOf(1f,p,1f),splitRestComponents=true) { _,_->1f }
        val first=draw(.4f)
        assertEquals(listOf(1,2),first.map { it.groups.size })
        val builds=cache.rebuilds
        val next=draw(.3f)
        assertEquals(builds,cache.rebuilds)
        assertSame(first[0].groups[0].path,next[0].groups[0].path)
        val removed=draw(0f)
        assertEquals(1,removed.size)
        assertEquals(listOf(listOf(0),listOf(2)),removed.single().groups.map { it.members })
        assertEquals(builds,cache.rebuilds)
        draw(.2f)
        assertEquals("Only the evicted full-group path must rebuild",builds+1,cache.rebuilds)
    }
    @Test fun tripleIntersectionsAndCoincidentItemsAreIndependentOfInputOrder() {
        val regions=listOf(SelectionRegion(Rect(0f,0f,80f,80f),12f),
            SelectionRegion(Rect(40f,40f,120f,120f),12f),SelectionRegion(Rect(80f,0f,160f,80f),12f))
        fun pixels(items: List<SelectionRegion>)=Region().apply {
            setPath(connectedSelectionPath(items,16f).asAndroidPath(),Region(-10,-10,180,140))
        }
        val expected=pixels(regions)
        for(items in listOf(regions.reversed(),listOf(regions[1],regions[2],regions[0]),regions+regions[0])) {
            val actual=pixels(items)
            for(x in 0..160)for(y in 0..120) assertEquals("$x,$y",expected.contains(x,y),actual.contains(x,y))
        }
    }
    @Test fun gridKeepsUnselectedHoleAndNeverBridgesDiagonalNeighbours() {
        for(density in listOf(1f,1.75f,2.625f)) for(progress in listOf(0f,.2f,.5f,1f)) {
            fun item(x: Int,y: Int)=SelectionRegion(Rect(x*120f*density,y*120f*density,
                (x*120f+119f)*density,(y*120f+119f)*density),12f*density)
            fun pixels(regions: List<SelectionRegion>)=Region().apply {
                setPath(connectedSelectionPath(regions,16f*density,strength={_,_->progress}).asAndroidPath(),Region(-20,-20,1100,1100))
            }
            val ring=(0..2).flatMap { y -> (0..2).filter { x -> x!=1 || y!=1 }.map { x -> item(x,y) } }
            val hole=pixels(ring)
            assertFalse("The unselected middle cell remains a hole",hole.contains((180*density).toInt(),(180*density).toInt()))
            for(selected in ring) assertTrue(hole.contains(selected.bounds.center.x.toInt(),selected.bounds.center.y.toInt()))
            val diagonal=pixels(listOf(item(0,0),item(1,1)))
            assertFalse("Diagonal-only neighbours stay separate",diagonal.contains((119.5f*density).toInt(),(119.5f*density).toInt()))
            val overlappingBoxes=pixels(listOf(item(0,0),SelectionRegion(
                Rect(114f*density,114f*density,233f*density,233f*density),12f*density)))
            assertFalse("Overlapping bounding boxes do not authorize a diagonal bridge between separated rounded corners",
                overlappingBoxes.contains((116.5f*density).toInt(),(116.5f*density).toInt()))
        }
    }
    @Test fun vanishingLobesPreserveBothItemInteriors() {
        for(frame in 65..75) {
            val t=frame/120f
            val separation=-4f+20f*t
            val strength=io.github.anastaci1a.localica.ui.hoverBridgeStrength(true,true,t to t,(1f-t) to (1f-t),1f to 1f)
            val path=connectedSelectionPath(listOf(SelectionRegion(Rect(16f,16f,256f,88f),12f),
                SelectionRegion(Rect(16f,88f+separation,256f,160f+separation),12f)),16f,strength={_,_->strength}).asAndroidPath()
            val samples=path.approximate(.01f)
            assertTrue("frame=$frame samples=${samples.size}",samples.all { it.isFinite() && abs(it)<1000 })
            val coverage=Region().apply { setPath(path,Region(0,0,400,280)) }
            assertTrue("frame=$frame samples=${samples.size}",coverage.contains(100,50) && coverage.contains(100,140))
        }
    }
    @Test fun concaveFilletMatchesItsFourDpRollingCircle() {
        val path = connectedSelectionPath(listOf(
            SelectionRegion(Rect(0f,0f,80f,80f),12f),SelectionRegion(Rect(40f,40f,120f,120f),12f)),16f).asAndroidPath()
        val scaled = android.graphics.Path(path).apply { transform(android.graphics.Matrix().apply { setScale(16f,16f) }) }
        val pixels = Region().apply { setPath(scaled,Region(-100,-100,2200,2200)) }
        for (x in 1281 until 1344) for (y in 577 until 640) {
            val px=(x+.5f)/16f;val py=(y+.5f)/16f
            val distance=hypot(px-84f,py-36f)
            if (abs(distance-4f)>.125f) assertEquals("Fillet at $px,$py",distance>4f,pixels.contains(x,y))
        }
    }

    @Test fun realOverlapsHaveRoundedConcaveIntersections() {
        val path = connectedSelectionPath(listOf(
            SelectionRegion(Rect(0f, 0f, 80f, 80f), 12f),
            SelectionRegion(Rect(40f, 40f, 120f, 120f), 12f)), 16f).asAndroidPath()
        // Normalize only for boundary inspection: the renderer's non-zero path also
        // retains interior contours, which do not contribute to its visible edge.
        val boundary = android.graphics.Path().apply { assertTrue(op(path,path,android.graphics.Path.Op.UNION)) }
        val samples = boundary.approximate(.02f)
        val points = (samples.indices step 3).map { samples[it + 1] to samples[it + 2] }
            .fold(mutableListOf<Pair<Float, Float>>()) { out, p -> out.apply { if (lastOrNull() != p) add(p) } }
        var sharp = 0
        for (i in 1 until points.lastIndex) {
            val (ax, ay) = points[i - 1]; val (bx, by) = points[i]; val (cx, cy) = points[i + 1]
            val nearJoin = hypot(bx - 80f, by - 40f) < 8f || hypot(bx - 40f, by - 80f) < 8f
            val u = hypot(bx - ax, by - ay); val v = hypot(cx - bx, cy - by)
            if (nearJoin && u > .01f && v > .01f) {
                val angle = acos((((bx-ax)*(cx-bx)+(by-ay)*(cy-by))/(u*v)).coerceIn(-1f,1f))
                if (angle > PI / 3) sharp++
            }
        }
        assertEquals("A plain union retains a sharp concave corner", 0, sharp)
    }

    @Test fun displayedSeparationDisconnectsEvenWhenRestPositionsAreAdjacent() {
        val rest = listOf(SelectionRegion(Rect(0f,0f,80f,80f),12f), SelectionRegion(Rect(88f,0f,168f,80f),12f))
        val moving = listOf(rest[0], SelectionRegion(Rect(140f,0f,220f,80f),12f))
        val path = connectedSelectionPath(moving,16f,rest)
        val coverage = Region().apply { setPath(path.asAndroidPath(), Region(-10,-10,250,120)) }
        assertFalse("A rest-adjacent pair must not leave a long rectangular tether", coverage.contains(110,40))
    }
}
