package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.lyrics.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Check final root pixels: a correctly clipped list node alone cannot prove that translated ink
 * stays inside the viewport, or that the missing-lyrics decoration uses that same mask. */
class LyricsViewportMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun translatedLyricsAndMissingInkStayInsideThePresentedFadeInBothDirections() {
        var missing by mutableStateOf(false)
        var height by mutableStateOf(192.dp)
        var offset by mutableStateOf(0.dp)
        var reduced by mutableStateOf(false)
        val document = LrcParser.parse(List(48) { "████████████" }.joinToString("\n"))
        compose.setContent { LocalicaTheme {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = Color.Green, onSurfaceVariant = Color.Green)) {
                CompositionLocalProvider(LocalContentColor provides Color.Green) {
                    Box(Modifier.size(320.dp, 440.dp).background(Color.Black).testTag("clip-scene")) {
                        LyricsPanel(null, 0, reduced, {}, Modifier.offset(32.dp, 64.dp).width(256.dp).height(height),
                            contentHeight = 320.dp, contentOffset = offset,
                            result = if (missing) LyricsResult() else LyricsResult(listOf(LyricSource("fixture", "Fixture", document))))
                    }
                }
            }
        } }
        for (reduce in listOf(false, true)) for (empty in listOf(false, true)) {
            compose.runOnIdle { reduced = reduce; missing = empty; offset = 0.dp; height = 192.dp }
            compose.waitForIdle()
            val samples = if (empty) listOf(-160, -144, -128, 16, 32, 48, 32, 16, -128, -144, -160)
                else listOf(-144, -136, -128, -120, -112, -104, -96, -104, -112, -120, -128, -136, -144)
            var topInk = false
            var bottomInk = false
            val snapshots = mutableMapOf<Int, List<Float>>()
            for (dy in samples) {
                compose.runOnIdle { offset = dy.dp; height = if (dy % 32 == 0) 192.dp else 208.dp }
                val root = compose.onNodeWithTag("clip-scene")
                val rootBounds = root.fetchSemanticsNode().boundsInRoot
                val viewport = compose.onNodeWithTag("lyrics-viewport", true).fetchSemanticsNode().boundsInRoot
                val pixels = root.captureToImage().toPixelMap()
                var inside = 0f
                var top = 0f
                var bottom = 0f
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val green = pixels[x, y].green
                    val rootX = rootBounds.left + x + .5f
                    val rootY = rootBounds.top + y + .5f
                    val within = rootX >= viewport.left && rootX < viewport.right && rootY >= viewport.top && rootY < viewport.bottom
                    if (!within) {
                        if (green > .005f) fail("Escaped ink: empty=$empty reduced=$reduce dy=$dy at $x,$y: $green")
                    } else {
                        inside += green
                        if (rootY - viewport.top < 2f) top = maxOf(top, green)
                        if (viewport.bottom - rootY < 2f) bottom = maxOf(bottom, green)
                    }
                }
                assertTrue("Fixture must draw ink inside its clip: empty=$empty dy=$dy", inside > 1f)
                assertTrue("Top edge fades translated ink, not only its old bounds", top < .2f)
                assertTrue("Bottom edge fades translated ink, not only its old bounds", bottom < .2f)
                topInk = topInk || top > .002f
                bottomInk = bottomInk || bottom > .002f
                val sample = listOf(inside, top, bottom)
                snapshots[dy]?.let { assertEquals("Reversing restores the exact presented ink", it, sample) }
                snapshots[dy] = sample
            }
            assertTrue("Top fade assertion must contain real ink", topInk)
            assertTrue("Bottom fade assertion must contain real ink", bottomInk)
        }
    }
}
