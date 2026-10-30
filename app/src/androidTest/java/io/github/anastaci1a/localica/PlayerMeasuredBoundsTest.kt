package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.PlayerBackgroundPolarityState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerMeasuredBoundsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneMeasurementRefreshesMovedSiblingsAndRemovesDetachedCoordinates() {
        val owner = PlayerBackgroundPolarityState()
        var shift by mutableStateOf(0)
        var showSecond by mutableStateOf(true)
        lateinit var background: LayoutCoordinates
        lateinit var first: LayoutCoordinates
        lateinit var second: LayoutCoordinates
        compose.setContent {
            Box(Modifier.size(180.dp).onGloballyPositioned { background = it }) {
                Box(Modifier.offset(x = shift.dp, y = shift.dp).size(120.dp)) {
                    Box(Modifier.size(20.dp).onGloballyPositioned { first = it })
                    if (showSecond) Box(Modifier.offset(x = 40.dp).size(25.dp)
                        .onGloballyPositioned { second = it })
                }
            }
        }
        compose.runOnIdle {
            owner.measure("background", background)
            owner.measure("first", first)
            owner.measure("second", second)
            assertEquals(background.localBoundingBoxOf(second, clipBounds = false), owner.measured["second"])
            shift = 17
        }
        compose.runOnIdle {
            val before = owner.measured
            // Only first reports to the owner; both siblings moved with their parent.
            owner.measure("first", first)
            assertNotEquals(before["second"], owner.measured["second"])
            assertEquals(background.localBoundingBoxOf(first, clipBounds = false), owner.measured["first"])
            assertEquals(background.localBoundingBoxOf(second, clipBounds = false), owner.measured["second"])
            val current = owner.measured
            owner.measure("background", background)
            assertSame(current, owner.measured)
            showSecond = false
        }
        compose.runOnIdle {
            assertFalse(second.isAttached)
            owner.measure("background", background)
            assertFalse(owner.measured.containsKey("second"))
            assertEquals(background.localBoundingBoxOf(first, clipBounds = false), owner.measured["first"])
            owner.detach()
            assertTrue(owner.measured.isEmpty())
        }
    }
}
