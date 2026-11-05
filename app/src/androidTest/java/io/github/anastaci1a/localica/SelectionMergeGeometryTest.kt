package io.github.anastaci1a.localica

import android.graphics.Region
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidPath
import io.github.anastaci1a.localica.ui.SelectionRegion
import io.github.anastaci1a.localica.ui.connectedSelectionPath
import io.github.anastaci1a.localica.ui.SelectionOutlineField
import io.github.anastaci1a.localica.ui.SelectionOutlineReferenceContour
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Geometry proofs run without the compositor, so antialiasing cannot hide a sharp join. */
class SelectionMergeGeometryTest {
    @Test fun contactPatchesMatchTheFieldWithinQuarterDpIncludingTriplesAndHoles() {
        val cases = listOf(
            listOf(Rect(0f,0f,80f,80f),Rect(40f,40f,120f,120f)),
            listOf(Rect(0f,0f,80f,80f),Rect(81f,0f,161f,80f)),
            listOf(Rect(0f,0f,80f,80f),Rect(81f,0f,161f,80f),Rect(0f,81f,80f,161f)),
            listOf(Rect(0f,0f,80f,80f),Rect(40f,40f,120f,120f),Rect(80f,0f,160f,80f)))
        fun covered(path: android.graphics.Path,expand: Boolean): Region {
            val scaled = android.graphics.Path(path).apply { transform(android.graphics.Matrix().apply { setScale(8f,8f) }) }
            val outline = if (!expand) scaled else android.graphics.Path().also {
                android.graphics.Paint().apply { style = android.graphics.Paint.Style.FILL_AND_STROKE; strokeWidth = 4.1f
                    strokeJoin = android.graphics.Paint.Join.ROUND }.getFillPath(scaled,it)
            }
            return Region().apply { setPath(outline,Region(-100,-100,1500,1500)) }
        }
        for ((index,boxes) in cases.withIndex()) {
            val regions = boxes.map { SelectionRegion(it,12f) }
            val fast = connectedSelectionPath(regions,16f).asAndroidPath()
            val reference = SelectionOutlineReferenceContour().path(SelectionOutlineField(regions,16f),.125f).asAndroidPath()
            val excess = covered(fast,false).apply { op(covered(reference,true),Region.Op.DIFFERENCE) }
            val missing = covered(reference,false).apply { op(covered(fast,true),Region.Op.DIFFERENCE) }
            assertTrue("case=$index excess=${excess.bounds}",excess.isEmpty)
            assertTrue("case=$index missing=${missing.bounds}",missing.isEmpty)
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
