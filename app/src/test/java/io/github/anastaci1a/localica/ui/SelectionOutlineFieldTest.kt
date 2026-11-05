package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SelectionOutlineFieldTest {
    private fun body(x: Float,y: Float=0f) = SelectionRegion(Rect(x,y,x+80f,y+80f),12f)
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
