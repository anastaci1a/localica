package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.layout.PopupViewportScope
import io.github.anastaci1a.localica.ui.spec.GlassDialogHeight
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PopupViewportSlotTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun defaultContentReaches350AfterGrowingFrom300() {
        var pixels by mutableIntStateOf(300)
        compose.setContent { LocalicaTheme {
            GlassMorphPopup(true, rememberGlassAnchor(), {}, GlassPlacement.Center) {
                val density = LocalDensity.current
                Box(Modifier.fillMaxWidth().height(with(density) { pixels.toDp() }))
            }
        } }
        compose.waitForIdle()
        assertEquals(300f, height(), 1f)
        compose.runOnIdle { pixels = 350 }
        compose.waitForIdle()
        assertEquals(350f, height(), 1f)
    }

    @Test fun contentSpring300Toward1000Remeasured600NeverCollapses() {
        var pixels by mutableIntStateOf(300)
        compose.setContent { LocalicaTheme {
            GlassMorphPopup(true, rememberGlassAnchor(), {}, GlassPlacement.Center) {
                val density = LocalDensity.current
                Box(Modifier.fillMaxWidth().height(with(density) { pixels.toDp() }))
            }
        } }
        compose.waitForIdle()
        assertEquals(300f, height(), 1f)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { pixels = 1000 }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertTrue(height() >= 299f)
        compose.runOnUiThread { pixels = 600 }
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertTrue("The inherited content envelope cannot normalize below its300px origin", height() >= 299f)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(600f, height(), 1f)
    }

    @Test fun remainingImeIsMeasuredFromActualIncomingConsumptionWithOneFraction() {
        val ime = WindowInsets(0, 0, 0, 600)
        val consumed = arrayOf(WindowInsets(0, 0, 0, 84))
        val constraints = Constraints.fixed(720, 1154)
        val scope = PopupViewportScope(constraints, GlassDialogHeight.ViewportFraction(.94f), ime, consumed)
        val measured = scope.measure(constraints, Density(1f), margin = 21f, contentHeightLimit = 1112)
        assertEquals(516, measured.remainingIme)
        assertEquals(638, measured.viewportHeight)
        assertEquals(596, measured.contentMaxHeight)
        assertEquals(560, measured.fixedHeight)
        assertFalse(measured.empty)
    }

    @Test fun simultaneousContentAndImeSetupRetainsContentDestinationAndNoFixedExtent() {
        val constraints = Constraints.fixed(720, 1154)
        val scope = PopupViewportScope(constraints, GlassDialogHeight.Content,
            WindowInsets(0, 0, 0, 600), arrayOf(WindowInsets(0, 0, 0, 84)))
        val measured = scope.measure(constraints, Density(1f), margin = 21f, contentHeightLimit = 600)
        assertEquals(600, measured.contentMaxHeight)
        assertEquals(0, measured.remainingIme)
        assertNull(measured.fixedHeight)
        assertFalse(measured.empty)
    }

    @Test fun zeroAndShortViewportsStayEmptyUntilARealFixedExtentFits() {
        for (height in listOf(0, 10, 42)) {
            val constraints = Constraints.fixed(720, height)
            val scope = PopupViewportScope(constraints, GlassDialogHeight.ViewportFraction(.94f),
                WindowInsets(0, 0, 0, 600), arrayOf(WindowInsets(0, 0, 0, 84)))
            assertTrue(scope.measure(constraints, Density(1f), margin = 21f, contentHeightLimit = 1).empty)
        }
    }

    private fun height(): Float = compose.onNodeWithTag("glass-morph-surface").fetchSemanticsNode().boundsInRoot.height
}
