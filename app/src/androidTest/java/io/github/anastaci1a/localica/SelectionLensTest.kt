package io.github.anastaci1a.localica

import android.graphics.Region
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidPath
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp

class SelectionLensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstSelectionRedrawsTheLensAndPartialSelectionRemovesIt() {
        var chosen by mutableStateOf(false)
        var partial by mutableStateOf(false)
        compose.setContent { LocalicaTheme {
            ConnectedSelectionHost(Modifier.size(200.dp).background(Color.Black).testTag("scene")) {
                Box(Modifier.padding(20.dp)) {
                    Box(Modifier.size(160.dp, 80.dp).selectionLens(chosen, partial).background(Color(0xFF202020)).testTag("item"))
                }
            }
        } }
        val original = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        compose.runOnIdle { chosen = true }
        compose.onNodeWithTag("item").assertIsSelected()
        val selected = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        var brighter = 0
        for (y in 0 until original.height) for (x in 0 until original.width) {
            if (selected[x, y].red > original[x, y].red + .1f) brighter++
        }
        assertTrue("The first selection must draw a visible lens without another interaction: $brighter", brighter > 30)
        compose.runOnIdle { partial = true }
        val partialPixels = compose.onNodeWithTag("scene").captureToImage().toPixelMap()
        var residual = 0
        for (y in 0 until original.height) for (x in 0 until original.width) {
            if (partialPixels[x, y].red > original[x, y].red + .1f) residual++
        }
        assertEquals("Partial groups must not retain a lens", 0, residual)
    }

    private fun region(vararg rectangles: Rect): Region {
        val path = connectedSelectionPath(rectangles.map { SelectionRegion(it, 1f) }, 16f)
        return Region().apply { setPath(path.asAndroidPath(), Region(-1000, -1000, 1000, 1000)) }
    }
    @Test fun sideNeighborsJoinWithoutCoveringAnUnselectedHole() {
        val shape = region(Rect(0f, 0f, 80f, 80f), Rect(88f, 0f, 168f, 80f),
            Rect(0f, 88f, 80f, 168f), Rect(176f, 88f, 256f, 168f))
        assertTrue(shape.contains(84, 40))
        assertTrue(shape.contains(40, 84))
        assertFalse("Unselected tile must remain uncovered", shape.contains(120, 120))
    }
    @Test fun diagonalNeighborsHaveOnlyANarrowCornerBridge() {
        val shape = region(Rect(0f, 0f, 80f, 80f), Rect(88f, 88f, 168f, 168f))
        assertTrue(shape.contains(84, 84))
        assertFalse(shape.contains(120, 40))
        assertFalse(shape.contains(40, 120))
        val split = region(Rect(0f, 0f, 80f, 80f))
        assertFalse(split.contains(84, 84))
    }
}
