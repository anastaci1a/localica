package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sign

/** Experimental native comparison. Medium damping is deliberately held to the
 * same axis criterion; known model limitations must remain visible in this draft. */
class GlassReboundTest {
    @get:Rule val compose = createComposeRule()
    @Test fun verticalModerateDefault() = compare(false, .10f, 8f)
    @Test fun verticalStrongDefault() = compare(false, .21f, 8f)
    @Test fun horizontalModerateDefault() = compare(true, .10f, 8f)
    @Test fun horizontalStrongDefault() = compare(true, .21f, 8f)
    @Test fun verticalModerateMediumDamping() = compare(false, .10f, 20f)
    @Test fun verticalStrongMediumDamping() = compare(false, .21f, 20f)
    @Test fun horizontalModerateMediumDamping() = compare(true, .10f, 20f)
    @Test fun horizontalStrongMediumDamping() = compare(true, .21f, 20f)

    @Test fun horizontalContactDefault() = contact(true, 8f)
    @Test fun verticalContactMediumDamping() = contact(false, 20f)
    @Test fun horizontalContactMediumDamping() = contact(true, 20f)

    private fun contact(horizontal: Boolean, damping: Float) {
        val raster = GlassChoiceRaster(compose)
        raster.setContent(listOf("First", "Second", "Third"), horizontal, damping)
        val rest = raster.bounds()
        var touching = true
        try {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("First").performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(300)
            val held = raster.bounds()
            compose.onNodeWithText("First").performTouchInput { up() }
            touching = false
            compose.mainClock.advanceTimeBy(2000)
            val final = raster.bounds()
            val description = "contact horizontal=$horizontal damping=$damping rest=$rest held=$held final=$final"
            android.util.Log.i("GlassRebound", description)
            assertTrue("Contact retains visible footprint expansion: $description", held.width > rest.width && held.height > rest.height)
            assertEquals(description, rest.width, final.width, 1f)
            assertEquals(description, rest.height, final.height, 1f)

        } finally {
            if (touching) runCatching { compose.onNodeWithText("First").performTouchInput { cancel() } }
            compose.mainClock.autoAdvance = true
        }
    }

    private fun compare(horizontal: Boolean, pressure: Float, damping: Float) {
        val raster = GlassChoiceRaster(compose)
        raster.setContent(listOf("First", "Second", "Third"), horizontal, damping)
        val rest = raster.bounds()
        fun along(r: Rect) = if (horizontal) r.width else r.height
        fun across(r: Rect) = if (horizontal) r.height else r.width
        var touching = true
        try {
            compose.mainClock.autoAdvance = false
            val cell = compose.onNodeWithTag("choice-lens").fetchSemanticsNode().boundsInRoot
            val stride = if (horizontal) cell.width else cell.height
            // Invert the production pressure curve. Drag beyond the first cell;
            // selected semantics remain the real gesture owner, not a shape stub.
            val excess = -ln(1f - pressure / .22f) / 2.4f
            val drag = stride * excess + raster.touchSlop
            compose.onNodeWithText("First").performTouchInput {
                down(center); moveBy(if (horizontal) Offset(-drag, 0f) else Offset(0f, -drag))
            }
            compose.mainClock.advanceTimeBy(300)
            val held = raster.bounds()
            compose.onNodeWithText("First").performTouchInput { up() }
            touching = false
            val frames = mutableListOf(held)
            repeat(24) { compose.mainClock.advanceTimeBy(32); frames += raster.bounds() }
            compose.mainClock.advanceTimeBy(1600)
            val final = raster.bounds()
            fun ring(axis: (Rect) -> Float): Float {
                val values = frames.map { axis(it) - axis(rest) }
                val first = values.firstOrNull { abs(it) >= 1f } ?: return 0f
                val crossing = values.indexOfFirst { abs(it) >= 1f && sign(it) != sign(first) }
                return if (crossing < 0) 0f else values.drop(crossing).maxOf { abs(it) }
            }
            val axial = ring(::along); val secondary = ring(::across)
            val description = "horizontal=$horizontal pressure=$pressure damping=$damping rest=$rest held=$held final=$final axialRingPx=$axial crossRingPx=$secondary frames=$frames"
            android.util.Log.i("GlassRebound", description)
            assertEquals("Travel extent settles: $description", along(rest), along(final), 1f)
            assertEquals("Cross extent settles: $description", across(rest), across(final), 1f)
            assertTrue("Release must visibly rebound along travel: $description", axial >= 1f)
            assertTrue("Cross-axis ringing cannot dominate travel: $description", secondary <= axial)

        } finally {
            if (touching) runCatching { compose.onNodeWithText("First").performTouchInput { cancel() } }
            compose.mainClock.autoAdvance = true
        }
    }
}
