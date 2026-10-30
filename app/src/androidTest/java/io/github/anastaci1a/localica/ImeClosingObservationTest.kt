package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.rememberImeClosing
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImeClosingObservationTest {
    @get:Rule val compose = createComposeRule()

    private class Insets(bottom: Int = 0) : WindowInsets {
        var bottom by mutableIntStateOf(bottom)
        override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getTop(density: Density) = 0
        override fun getBottom(density: Density) = bottom
    }

    @Test fun intermediateDistancesDoNotRepublishUnchangedClosingState() {
        val ime = Insets()
        val source = Insets()
        val target = Insets()
        val publications = mutableListOf<Boolean>()
        var observed = false
        compose.setContent {
            val closing = rememberImeClosing(ime, source, target, Density(1f)).value
            SideEffect { observed = closing; publications += closing }
        }
        compose.waitForIdle()
        fun step(i: Int, s: Int, t: Int, changes: Boolean) {
            val before = publications.size
            val old = observed
            compose.runOnIdle { ime.bottom = i; source.bottom = s; target.bottom = t }
            compose.waitForIdle()
            compose.runOnIdle {
                val expected = t == 0 && (i > 0 || s > 0)
                assertEquals(expected, observed)
                assertEquals(changes, old != expected)
                if (changes) {
                    assertTrue(publications.size > before)
                    assertTrue(publications.drop(before).all { it == expected })
                } else assertEquals(before, publications.size)
            }
        }
        // Opening and open states stay false despite changing raw distances.
        step(1, 0, 300, false)
        step(120, 0, 300, false)
        step(300, 0, 300, false)
        // Closing stays true until both current and source have cleared.
        step(300, 300, 0, true)
        step(120, 300, 0, false)
        step(0, 300, 0, false)
        step(0, 1, 0, false)
        step(0, 0, 0, true)
        step(-1, -1, 0, false)
        step(1, 0, 0, true)
        step(1, 0, 1, true)
    }

    @Test fun replacingInsetOwnersOrDensityRebindsTheObservation() {
        val oldIme = Insets(100)
        val source = Insets()
        val target = Insets()
        val newIme = Insets()
        var ime by mutableStateOf<WindowInsets>(oldIme)
        var density by mutableStateOf(Density(1f))
        var observed = false
        var publications = 0
        val densityInsets = object : WindowInsets {
            override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getTop(density: Density) = 0
            override fun getBottom(density: Density) = if (density.density > 1f) 100 else 0
        }
        compose.setContent {
            val closing = rememberImeClosing(ime, source, target, density).value
            SideEffect { observed = closing; publications++ }
        }
        compose.runOnIdle { assertTrue(observed); ime = newIme }
        compose.runOnIdle { assertFalse(observed) }
        val count = compose.runOnIdle { publications }
        compose.runOnIdle { oldIme.bottom = 0 }
        compose.runOnIdle { assertEquals(count, publications); assertFalse(observed); newIme.bottom = 1 }
        compose.runOnIdle { assertTrue(observed); ime = densityInsets }
        compose.runOnIdle { assertFalse(observed); density = Density(2f) }
        compose.runOnIdle { assertTrue(observed); density = Density(1f) }
        compose.runOnIdle { assertFalse(observed) }
    }
}
