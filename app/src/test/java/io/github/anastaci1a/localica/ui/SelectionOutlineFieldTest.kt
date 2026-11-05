package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SelectionOutlineFieldTest {
    private fun body(x: Float,y: Float=0f) = SelectionRegion(Rect(x,y,x+80f,y+80f),12f)
    @Test fun straightBandBoundsNeverCoverAnOutsideFieldPoint() {
        val random = java.util.Random(556)
        var admitted = 0
        for (vertical in listOf(false,true)) for (gap in listOf(-4f,.5f,1f,2f,4f,7f))
            for (strength in listOf(.15f,.3f,.6f,1f)) for (offset in listOf(0f,20f)) {
                val second = if (vertical) body(offset,80f+gap) else body(80f+gap,offset)
                val field = SelectionOutlineField(listOf(body(0f),second),16f) { _,_ -> strength }
                repeat(32) {
                    val lo = 12f+offset+random.nextFloat()*15f
                    val hi = lo+random.nextFloat()*15f
                    val start = 76f+random.nextFloat()*4f
                    val end = 84f+maxOf(gap,0f)+random.nextFloat()*4f
                    val box = if (vertical) Rect(lo,start,hi,end) else Rect(start,lo,end,hi)
                    if (field.connectedBand(box.left,box.top,box.right,box.bottom)) {
                        admitted++
                        for (x in 0..8) for (y in 0..8) assertTrue("$box gap=$gap strength=$strength",
                            field.value(box.left+box.width*x/8f,box.top+box.height*y/8f) <= .0001f)
                    }
                }
            }
        assertTrue("Exercise both joined and overlapping strips",admitted>100)
    }
    @Test fun contactCullingNeverDropsAddedOutlinePixels() {
        val random = java.util.Random(556)
        var outside = 0
        for (offset in listOf(0f,12f,40f,78f)) for (gap in listOf(-12f,.5f,2f,6f))
            for (strength in listOf(0f,.3f,1f)) {
                val a = body(0f); val b = body(80f+gap,offset)
                val field = SelectionOutlineField(listOf(a,b),16f) { _,_ -> strength }
                val first = SelectionOutlineField(listOf(a),16f)
                val second = SelectionOutlineField(listOf(b),16f)
                repeat(64) {
                    val x = 60f+random.nextFloat()*40f; val y = -10f+random.nextFloat()*110f
                    val size = .1f+random.nextFloat()*8f
                    if (!field.changesOutline(x,y,x+size,y+size)) for (i in 0..4) for (j in 0..4) {
                        val px = x+size*i/4f; val py = y+size*j/4f
                        if (minOf(first.value(px,py),second.value(px,py)) > .0001f) {
                            outside++
                            assertTrue("Culled x=$x y=$y size=$size offset=$offset gap=$gap strength=$strength",
                                field.value(px,py) > 0f)
                        }
                    }
                }
            }
        assertTrue(outside>1000)
    }
    @Test fun separatedAndDiagonalCellsDoNotConnect() {
        for (gap in listOf(8f,16f,32f)) {
            val field = SelectionOutlineField(listOf(body(0f),body(80f+gap)),16f)
            assertTrue(field.value(80f+gap/2,40f)>0f)
        }
        for (offset in listOf(.01f,1f,4f,8f)) {
            val field = SelectionOutlineField(listOf(body(0f),body(80f+offset,80f+offset)),16f)
            assertTrue(field.value(80f+offset/2,80f+offset/2)>0f)
        }
        // Overlapping bounding boxes still have a diagonal-only gap between their round corners.
        for (overlap in listOf(.1f,2f,4f,6f)) {
            val offset = 80f-overlap
            val field = SelectionOutlineField(listOf(body(0f),body(offset,offset)),16f)
            assertTrue(field.value(40f+offset*.5f,40f+offset*.5f)>0f)
        }
    }
    @Test fun touchingCoincidentCardsDoNotInflateTheirExterior() {
        val a = body(0f)
        for (count in 1..12) {
            val field = SelectionOutlineField(List(count) { a },16f)
            assertEquals(1f,field.value(81f,40f),.0001f)
        }
    }
    @Test fun filletSupportIsBoundedAndUnselectedHolesStayOpen() {
        val field = SelectionOutlineField(listOf(body(0f),body(81f),body(0f,81f)),16f)
        assertTrue(field.value(80.5f,40f)<0f)
        assertTrue(field.value(120f,120f)>0f)
        assertTrue(field.value(-4.01f,40f)>0f)
        assertTrue(field.value(80.5f,-4.01f)>0f)
    }
    @Test fun permutationsAndReflectionsDescribeTheSameSurface() {
        val regions = listOf(body(0f),body(81f),body(40f,40f))
        val a = SelectionOutlineField(regions,16f)
        val b = SelectionOutlineField(regions.reversed(),16f)
        val mirror = SelectionOutlineField(regions.map { SelectionRegion(Rect(-it.bounds.right,it.bounds.top,-it.bounds.left,it.bounds.bottom),it.corner) },16f)
        for (x in -5..170 step 3) for (y in -5..125 step 3) {
            val v = a.value(x.toFloat(),y.toFloat())
            assertEquals(v,b.value(x.toFloat(),y.toFloat()),.0001f)
            assertEquals(v,mirror.value(-x.toFloat(),y.toFloat()),.0001f)
        }
    }
    @Test fun displayedSeparationProjectionAndProgressAreContinuousAtAdmissionBoundaries() {
        // Check the implicit value, including points outside the currently filled silhouette.
        // Vanishing support must be continuous before a contour disappears.
        for (boundary in listOf(0f,8f)) for (strength in listOf(0f,.3f,1f)) {
            val a = SelectionOutlineField(listOf(body(0f),body(80f+boundary-.0001f)),16f) { _,_ -> strength }
            val b = SelectionOutlineField(listOf(body(0f),body(80f+boundary+.0001f)),16f) { _,_ -> strength }
            for (x in 75..94) for (y in -5..85) assertTrue("boundary=$boundary strength=$strength x=$x y=$y a=${a.value(x.toFloat(),y.toFloat())} b=${b.value(x.toFloat(),y.toFloat())}",abs(a.value(x.toFloat(),y.toFloat())-b.value(x.toFloat(),y.toFloat()))<.003f)
        }
        val off = SelectionOutlineField(listOf(body(0f),body(81f)),16f) { _,_ -> 0f }
        val epsilon = SelectionOutlineField(listOf(body(0f),body(81f)),16f) { _,_ -> .0001f }
        for (x in 75..87) assertTrue(abs(off.value(x.toFloat(),40f)-epsilon.value(x.toFloat(),40f))<.003f)
    }
}
