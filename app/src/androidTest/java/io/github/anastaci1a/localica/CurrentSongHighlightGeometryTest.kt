package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CurrentSongHighlightGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun playbackFrostMatchesSelectionSilhouetteThroughResizeAndReversal() = highlight(false)
    @Test fun rightToLeftPlaybackFrostMatchesSelectionSilhouetteThroughResizeAndReversal() = highlight(true)

    @Test fun retainedFrostKeepsCircularSelectionCornersAsItsSourceImageShrinks() {
        var expected by mutableStateOf(false)
        var factor by mutableFloatStateOf(1f)
        compose.setContent {
            val renderer = remember { CurrentSongHighlightRenderer() }
            Box(Modifier.size(220.dp, 180.dp).background(Color.White).testTag("retained-contour")) {
                Box(Modifier.padding(20.dp).size(180.dp, 140.dp).drawBehind {
                    val bounds = mediaItemBounds(size * factor)
                    if (expected) drawPath(connectedSelectionPath(listOf(SelectionRegion(bounds)), MediaSelectionGap.toPx()),
                        Color.Magenta.copy(alpha = .13f))
                    else with(renderer) { draw(bounds, Color.Magenta, sourceSize = size) }
                })
            }
        }
        for (scale in listOf(1f, .8f, .55f, .8f, 1f)) {
            compose.runOnIdle { factor = scale; expected = false }
            val actual = compose.onNodeWithTag("retained-contour").captureToImage().toPixelMap()
            compose.runOnIdle { expected = true }
            val reference = compose.onNodeWithTag("retained-contour").captureToImage().toPixelMap()
            assertSilhouette(reference, actual, "retained scale=$scale")
        }
    }

    private fun highlight(rtl: Boolean) {
        var expected by mutableStateOf(false)
        var visual by mutableStateOf(Size(180f, 120f))
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                val density = LocalDensity.current
                Box(Modifier.size(360.dp, 240.dp).background(Color.White).testTag("highlight-contour")) {
                    val draw = if (expected) Modifier.drawBehind {
                        val bounds = mediaItemBounds(size, Size(visual.width.dp.toPx(), visual.height.dp.toPx()), layoutDirection)
                        drawPath(connectedSelectionPath(listOf(SelectionRegion(bounds)), MediaSelectionGap.toPx()),
                            Color.Magenta.copy(alpha = .13f))
                    } else Modifier.currentSongHighlight(true, Color.Magenta) {
                        with(density) { Size(visual.width.dp.toPx(), visual.height.dp.toPx()) }
                    }
                    Box(Modifier.padding(90.dp, 40.dp).size(180.dp, 120.dp).then(draw))
                }
            }
        }
        // Includes full-size list/gallery endpoints and intermediate/reversed
        // visual sizes larger and smaller than the destination's measurement.
        for (size in listOf(Size(180f, 120f), Size(250f, 56f), Size(112f, 80f), Size(250f, 56f), Size(180f, 120f))) {
            compose.runOnIdle { visual = size; expected = false }
            val actual = compose.onNodeWithTag("highlight-contour").captureToImage().toPixelMap()
            compose.runOnIdle { expected = true }
            val reference = compose.onNodeWithTag("highlight-contour").captureToImage().toPixelMap()
            assertSilhouette(reference, actual, "rtl=$rtl visual=$size")
        }
    }

    private fun assertSilhouette(expected: PixelMap, actual: PixelMap, label: String) {
        fun ink(image: PixelMap, x: Int, y: Int): Boolean = image[x, y].red - image[x, y].green > .05f
        var solid = 0
        var mismatch = 0
        for (y in 1 until expected.height - 1) for (x in 1 until expected.width - 1) {
            val filled = ink(expected, x, y)
            // Ignore only one raster pixel around the antialiased silhouette.
            if ((-1..1).any { dy -> (-1..1).any { dx -> ink(expected, x + dx, y + dy) != filled } }) continue
            if (filled) solid++
            if (filled != ink(actual, x, y)) mismatch++
        }
        assertTrue("The fixture must contain a visible silhouette: $label", solid > 100)
        assertEquals("Playback bounds and circular corners must match selection: $label", 0, mismatch)
    }

    @Test fun selectionTracksTheSameVisualBoundsInBothDirections() {
        var expected by mutableStateOf(false)
        var visual by mutableStateOf(Size(180f, 100f))
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                val density = LocalDensity.current
                ConnectedSelectionHost(Modifier.size(300.dp, 220.dp).background(Color.Black).testTag("selection-contour")) {
                    if (expected) {
                        Box(Modifier.padding(30.dp).size(visual.width.dp, visual.height.dp).selectionLens(true, false))
                    } else {
                        Box(Modifier.padding(30.dp).size(180.dp, 100.dp).selectionLens(true, false) {
                            with(density) { Size(visual.width.dp.toPx(), visual.height.dp.toPx()) }
                        })
                    }
                }
            }
        } }
        for (rtl in listOf(false, true)) {
            for (size in listOf(Size(180f, 100f), Size(120f, 56f), Size(90f, 140f), Size(180f, 100f))) {
                compose.runOnIdle { direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr; visual = size; expected = false }
                val actual = compose.onNodeWithTag("selection-contour").captureToImage().toPixelMap()
                compose.runOnIdle { expected = true }
                val reference = compose.onNodeWithTag("selection-contour").captureToImage().toPixelMap()
                var difference = 0f
                for (y in 0 until actual.height) for (x in 0 until actual.width) {
                    difference += kotlin.math.abs(actual[x, y].red - reference[x, y].red)
                }
                assertTrue("Selection must follow visual bounds rather than destination measurement: rtl=$rtl visual=$size difference=$difference",
                    difference / (actual.width * actual.height) < .001f)
            }
        }
    }
}
