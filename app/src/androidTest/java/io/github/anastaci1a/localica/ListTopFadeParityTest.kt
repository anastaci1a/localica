package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.listTopFade
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The band-limited, brush-caching fade must match the pre-change full-viewport offscreen fade pixel for pixel,
 * including translucent overlapping content that crosses the band edge and strength changes that must rebuild the cached brush. */
class ListTopFadeParityTest {
    @get:Rule val compose = createComposeRule()
    private val strength = mutableFloatStateOf(1f)

    /** Verbatim pre-change implementation (full-viewport Offscreen, brush rebuilt each draw). */
    private fun Modifier.frozenFade(strength: () -> Float) = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val fade = strength().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
        if (fade == 0f) return@drawWithContent
        val height = 80.dp.toPx().coerceAtMost(size.height)
        drawRect(Brush.verticalGradient(*Array(17) { i ->
            val t = i / 16f
            val mask = t * t * (3f - 2f * t)
            val alpha = when (fade) { 1f -> mask; 0f -> 1f; else -> 1f + (mask - 1f) * fade }
            t to Color.Black.copy(alpha = alpha)
        }, endY = height), size = Size(size.width, height), blendMode = BlendMode.DstIn)
    }

    @Composable private fun Fixture(tag: String, height: Int, fade: Modifier) {
        Box(Modifier.size(60.dp, height.dp).background(Color(0xFF204060)).testTag(tag)) {
            Canvas(Modifier.matchParentSize().then(fade)) {
                drawRect(Color(0xFFE0E0E0))
                var y = 0f
                var i = 0
                while (y < size.height) {   // overlapping translucent stripes, crossing the 80 dp band edge
                    drawRect(Color(if (i % 2 == 0) 0x80FF0000 else 0x6000FF00), Offset(i * 3f % 20f, y), Size(size.width * .7f, 17.3f))
                    y += 11.7f; i++
                }
            }
        }
    }

    @Test fun matchesThePreChangeFadePixelForPixelAcrossStrengthsAndHeights() {
        compose.setContent {
            Row { listOf(200, 60).forEach { h ->
                Fixture("new-$h", h, Modifier.listTopFade { strength.floatValue })
                Fixture("old-$h", h, Modifier.frozenFade { strength.floatValue })
            } }
        }
        for (value in listOf(1f, .5f, 0f, .25f, 1f)) {
            compose.runOnIdle { strength.floatValue = value }
            compose.waitForIdle()
            for (h in listOf(200, 60)) {
                val new = compose.onNodeWithTag("new-$h").captureToImage().toPixelMap()
                val old = compose.onNodeWithTag("old-$h").captureToImage().toPixelMap()
                assertEquals(old.width, new.width); assertEquals(old.height, new.height)
                var differing = 0; var first = ""
                for (y in 0 until old.height) for (x in 0 until old.width) if (old[x, y] != new[x, y]) {
                    if (differing++ == 0) first = "$x,$y old=${old[x, y]} new=${new[x, y]}"
                }
                assertEquals("strength=$value height=$h: $differing differing pixels, first $first", 0, differing)
            }
        }
    }
}
