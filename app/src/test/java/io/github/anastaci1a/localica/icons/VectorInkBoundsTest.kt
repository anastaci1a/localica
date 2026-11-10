package io.github.anastaci1a.localica.icons

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class VectorInkBoundsTest {
    private fun bounds(body: String): Pair<Float, Float> = requireNotNull(SvgIconParser.parse(
        """<svg viewBox="0 0 24 24">$body</svg>""".toByteArray()).toImageVector(shiftY = 0f).geometricInkBounds())
    private fun check(top: Float, bottom: Float, body: String) {
        val actual = bounds(body)
        assertEquals("top", top, actual.first, .00001f)
        assertEquals("bottom", bottom, actual.second, .00001f)
    }
    @Test fun capBoundsFollowTheActualDiagonalOutline() {
        val root = sqrt(.5f)
        for ((cap, reach) in listOf("butt" to root, "round" to 1f, "square" to sqrt(2f)))
            check(4f - reach, 20f + reach, """<path d="M4 4L20 20" fill="none" stroke="black" stroke-width="2" stroke-linecap="$cap"/>""")
    }
    @Test fun joinsIncludeOnlyTheirActualRoundBevelOrMiterGeometry() {
        for ((join, top) in listOf("round" to 3f, "bevel" to (4f - 1f / sqrt(5f)), "miter" to (4f - sqrt(5f))))
            check(top, 20f + 1f / sqrt(5f), """<path d="M4 20L12 4L20 20" fill="none" stroke="black" stroke-width="2" stroke-linecap="butt" stroke-linejoin="$join"/>""")
        check(4f - 1f / sqrt(5f), 20f + 1f / sqrt(5f), """<path d="M4 20L12 4L20 20" fill="none" stroke="black" stroke-width="2" stroke-linejoin="miter" stroke-miterlimit="1"/>""")
    }
    @Test fun bezierBoundsUseDerivativeRootsRatherThanTheControlHullOrGrid() {
        check(0f, 20f, """<path d="M4 12C4 -4 20 -4 20 12L20 20H4Z"/>""")
        check(2f, 12f, """<path d="M4 12Q12 -8 20 12Z"/>""")
        check(-1f, 13f, """<path d="M4 12C4 -4 20 -4 20 12" fill="none" stroke="black" stroke-width="2" stroke-linecap="round"/>""")
    }
    @Test fun arcsAndAffineStrokeTransformsKeepTheirAnalyticExtrema() {
        check(8f, 12f, """<path d="M4 12A8 4 0 0 1 20 12Z"/>""")
        check(7f, 13f, """<path d="M4 12A8 4 0 0 1 20 12" fill="none" stroke="black" stroke-width="2" stroke-linecap="round"/>""")
        check(5f, 17f, """<g transform="translate(0 -4) scale(2 3)"><path d="M4 4L8 6" fill="none" stroke="black" stroke-width="2" stroke-linecap="round"/></g>""")
    }
    @Test fun zeroLengthRoundCapsRemainVisibleAndTransparentPathsDoNotAffectBounds() {
        for (cap in listOf("round", "square"))
            check(11f, 13f, """<path d="M12 12L12 12" fill="none" stroke="black" stroke-width="2" stroke-linecap="$cap"/>""")
        assertNull(SvgIconParser.parse("""<svg viewBox="0 0 24 24"><path d="M12 12L12 12" fill="none" stroke="black" stroke-width="2"/></svg>""".toByteArray())
            .toImageVector(shiftY = 0f).geometricInkBounds())
        check(4f, 20f, """<path d="M4 4H20V20H4Z"/><path d="M0 -10H24V30H0Z" fill-opacity="0"/>""")
    }
}
