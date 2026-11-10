package io.github.anastaci1a.localica.icons

import org.junit.Assert.*
import org.junit.Test

class FillInkBoundsTest {
    private val square = "M4 4H20V20H4Z"
    private fun vector(paths: String, rule: String = "nonzero") = SvgIconParser.parse(
        """<svg viewBox="0 0 24 24"><path d="$paths" fill-rule="$rule"/></svg>""".toByteArray()).toImageVector(shiftY = 0f)
    private fun unchanged(extra: String, rule: String = "nonzero") {
        val before = vector(square, rule); val after = vector("$square $extra", rule)
        assertEquals(before.geometricInkBounds(), after.geometricInkBounds())
        assertEquals(0f, before.opticalShiftY(), 0f)
        assertEquals("An unpainted fill contour cannot move the visible square", before.opticalShiftY(), after.opticalShiftY(), 0f)
    }
    @Test fun emptyOpenHorizontalAndDiagonalFillContoursCannotChangeBoundsOrMove() {
        unchanged("M0 2H24")
        unchanged("M0 0L24 2L12 1Z")
    }
    @Test fun aSeparateUnpaintedFillPathCannotChangeBoundsOrMove() {
        val combined = SvgIconParser.parse(
            """<svg viewBox="0 0 24 24"><path d="$square"/><path d="M0 2H24"/></svg>""".toByteArray()).toImageVector(shiftY = 0f)
        assertEquals(vector(square).geometricInkBounds(), combined.geometricInkBounds())
        assertEquals(0f, combined.opticalShiftY(), 0f)
    }
    @Test fun retracedBezierFillContoursCannotChangeBoundsOrMove() {
        unchanged("M0 2Q12 -2 24 2Q12 -2 0 2Z")
    }
    @Test fun oppositeWindingContoursCancelEvenWhenLinesAreSplitDifferently() {
        unchanged("M0 0H24V2H0Z M0 0V2H12H24V0H12Z")
    }
    @Test fun evenOddDuplicateContoursCancelButNonzeroDuplicatesRemainPainted() {
        val ghost = "M0 0H24V2H0Z"
        unchanged("$ghost $ghost", "evenodd")
        assertEquals(0f, requireNotNull(vector("$square $ghost $ghost").geometricInkBounds()).first, 0f)
    }
    @Test fun exactBezierCancellationSurvivesRationalSubdivision() {
        unchanged("M0 -30C0 -30 0 -21 27 -3C18 -9 12 -14 8 -18C4 -22 2 -25 1 -27C0 -29 0 -30 0 -30Z")
    }
    @Test fun oppositelyWoundEllipseArcsCancelTheirFillSupport() {
        unchanged("M12 0A2 1 0 0 1 12 2A2 1 0 0 1 12 0Z M12 0A2 1 0 0 0 12 2A2 1 0 0 0 12 0Z")
    }
    @Test fun zeroSignedAreaSelfCrossingFillRetainsItsPaintedBounds() {
        val bowtie = vector("M4 4L20 20H4L20 4Z", "evenodd")
        assertEquals(4f to 20f, bowtie.geometricInkBounds())
        assertEquals(0f, bowtie.opticalShiftY(), 0f)
    }
}
