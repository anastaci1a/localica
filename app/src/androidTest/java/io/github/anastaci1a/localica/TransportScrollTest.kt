package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TransportScrollTest {
    @get:Rule val compose = localicaComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor = 1f
    })

    @Test fun skipCrossfadesTheDepartingTriangleAtMiniAndFullSizes() {
        compose.setContent {
            CompositionLocalProvider(LocalMotionSpeed provides (4f / 3f)) {
                Row {
                    listOf(24, 40).forEach { side ->
                        TransportButton(TransportGlyph.Next, false, "Next $side", {},
                            Modifier.size(side.dp).background(Color.White).testTag("skip-$side"),
                            side.dp, Color.Black, true, false)
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        for (side in listOf(24, 40)) {
            val node = compose.onNodeWithTag("skip-$side")
            val start = node.captureToImage().toPixelMap()
            node.performClick()
            compose.mainClock.advanceTimeBy(192)
            val middle = node.captureToImage().toPixelMap()
            val ink = middle[(middle.width * .86f).toInt(), middle.height / 2].red
            assertTrue("Departing wedge must fade, not remain opaque ($side dp): $ink", ink > .15f && ink < .98f)
            compose.mainClock.advanceTimeBy(800)
            val end = node.captureToImage().toPixelMap()
            for (y in 0 until start.height) for (x in 0 until start.width)
                assertEquals("Rest frame $side at $x,$y", start[x,y], end[x,y])
        }
    }
}
