package io.github.anastaci1a.localica.ui.spec

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

/** #640 Spec constants: stock values from DESIGN.md §1.2/§1.5/§2 with the operator's H3 follow committed. */
class FocusSpecTest {
    @Test fun stockValuesMatchTheDesign() {
        val s = StockFocusSpec
        assertEquals(100, s.deadZoneMs); assertEquals(220, s.settleMs); assertEquals(1.04f, s.liftScale, 0f)
        assertEquals(PhaseWindow(0f, 1f), s.lift); assertEquals(PhaseWindow(.15f, 1f), s.blurFront); assertEquals(PhaseWindow(0f, .6f), s.blurStrength)
        assertEquals(12f, s.followScaleDp, 0f); assertEquals(8f, s.followFallbackScaleDp, 0f)
        assertEquals(.35f, s.followSpringResponseSeconds, 0f); assertEquals(.8f, s.followSpringDampingRatio, 0f)
        assertEquals(((2 * PI / .35) * (2 * PI / .35)).toFloat(), s.followSpringStiffness, 1e-2f)
        assertEquals(listOf(4f, 10f, 20f), s.pyramidSigmasDp); assertEquals(.25f, s.captureScale, 0f); assertEquals(64f, s.maskFeatherDp, 0f)
    }

    @Test fun windowsAndValuesAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { PhaseWindow(1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { PhaseWindow(Float.NaN, 1f) }
        assertThrows(IllegalArgumentException::class.java) { FocusSpec(deadZoneMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { FocusSpec(liftScale = .9f) }
        assertThrows(IllegalArgumentException::class.java) { FocusSpec(followScaleDp = 0f) }
        assertThrows(IllegalArgumentException::class.java) { FocusSpec(followSpringDampingRatio = 0f) }
        assertThrows(IllegalArgumentException::class.java) { FocusSpec(pyramidSigmasDp = listOf(10f, 4f)) }
        assertThrows(IllegalArgumentException::class.java) { FocusSpec(dimDark = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { FocusSpec(captureScale = 0f) }
        assertEquals(FocusSpec(), FocusSpec().copy())
    }
}
