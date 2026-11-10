package io.github.anastaci1a.localica.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import io.github.anastaci1a.localica.ui.centroid
import io.github.anastaci1a.localica.ui.fillContours
import io.github.anastaci1a.localica.ui.signedArea
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/** #724: every pack glyph moves vertically half-way from its bounding-box centre toward its ink centre. */
class OpticalCentringTest {
    private fun vector(body: String, viewBox: String = "0 0 24 24") = SvgIconParser.parse(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="$viewBox">$body</svg>""".toByteArray()).toImageVector()

    /** Exact polygon bounds and area centroid in dp of the 24 dp icon, independent of the measurement under test. */
    private class Geometry(val boxCentreY: Float, val centroidY: Float)
    private fun geometry(v: ImageVector): Geometry {
        val contours = v.fillContours().map { c -> c.map { it * 24f } }
        val top = contours.minOf { c -> c.minOf { it.y } }
        val bottom = contours.maxOf { c -> c.maxOf { it.y } }
        val weight = contours.sumOf { abs(signedArea(it)).toDouble() }
        val centroidY = contours.sumOf { abs(signedArea(it)).toDouble() * centroid(it).y } / weight
        return Geometry((top + bottom) / 2f, centroidY.toFloat())
    }
    /** The translation the pipeline gives the glyph, in viewport units of the 24-unit icon. */
    private fun appliedShift(v: ImageVector) = (v.root.single() as VectorGroup).translationY

    // Synthetic glyphs: a base-down triangle has its ink centre 2.667 dp below its box centre (4..20, centroid 14.667).
    private val bottomHeavy = """<path d="M12 4 L20 20 L4 20 Z"/>"""
    private val topHeavy = """<path d="M4 4 L20 4 L12 20 Z"/>"""

    @Test fun bottomHeavyGlyphMovesHalfWayUpTowardItsInkCentre() {
        val g = geometry(vector(bottomHeavy))
        assertEquals("box centre rises by half the 2.667 dp ink offset", 12f - 4f / 3f, g.boxCentreY, .05f)
        assertEquals("ink now sits only half as low", 12f + 4f / 3f, g.centroidY, .05f)
    }

    @Test fun topHeavyGlyphMovesHalfWayDown() {
        val g = geometry(vector(topHeavy))
        assertEquals(12f + 4f / 3f, g.boxCentreY, .05f)
        assertEquals(12f - 4f / 3f, g.centroidY, .05f)
    }

    @Test fun symmetricGlyphsDoNotMove() {
        val rectangle = vector("""<path d="M4 8 H20 V16 H4 Z"/>""")
        assertEquals(listOf(4f, 8f, 20f, 8f, 20f, 16f, 4f, 16f),
            rectangle.fillContours().single().flatMap { listOf(it.x * 24f, it.y * 24f) })
        for (body in listOf(
            """<path d="M12 3 L21 12 L12 21 L3 12 Z"/>""",
            """<path d="M12 2 A10 10 0 1 1 11.99 2 Z"/>""",
            """<path d="M6 6 L18 18 M18 6 L6 18" fill="none" stroke="black" stroke-width="2.2" stroke-linecap="round"/>""",
            """<path d="M12 5 V19 M5 12 H19" fill="none" stroke="black" stroke-width="2" stroke-linecap="butt"/>""",
            // Symmetric about its own centre but placed high: its designed placement is kept.
            """<path d="M8 3 H16 V9 H8 Z"/>""",
        )) assertEquals(body, 0f, appliedShift(vector(body)), 0f)
    }

    @Test fun strokedBottomHeavyGlyphMovesUp() {
        // An inverted T: round-capped 2-unit strokes, ink 3..21 (box centre 12), ink centre about 3.5 below it.
        val shift = appliedShift(vector("""<path d="M12 4 V20 M5 20 H19" fill="none" stroke="black" stroke-width="2" stroke-linecap="round"/>"""))
        assertEquals(-1.75f, shift, .1f)
    }

    @Test fun nonSquareViewBoxesShiftInIconUnits() {
        // The same base-down triangle drawn at half scale on a 48-unit canvas still moves 1.333 dp of the 24 dp icon.
        val g = geometry(vector("""<path d="M24 8 L40 40 L8 40 Z"/>""", "0 0 48 48"))
        assertEquals(12f - 4f / 3f, g.boxCentreY, .05f)
    }
}
