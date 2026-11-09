package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** #691: removing a disabled cover recording preserves presented RGBA8, including transitions. */
class PlayerCoverEqualityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun disabledCoverMatchesHistoricalRecordingThroughEnableAndDisable() {
        var enabled by mutableStateOf(false)
        var showHistorical by mutableStateOf(true)
        var phase by mutableFloatStateOf(0f)
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.size(128.dp).background(Color(0xFF234567)).testTag("comparison")) {
                for (historical in listOf(true, false)) {
                    val source = rememberFrostedBackdrop(Unit, enabled = false)
                    val wash = rememberFrostedBackdrop(Unit, enabled = false, retainRaster = false)
                    Box(Modifier.size(128.dp).graphicsLayer { alpha = if (historical == showHistorical) 1f else 0f }) {
                        Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(wash)) { drawRect(Color(0xFF123456)) }
                        val recording = if (historical) Modifier.historicalCover(source, wash, enabled)
                            else Modifier.recordPlayerCoverBackdrop(source, wash, enabled)
                        Canvas(Modifier.fillMaxSize().graphicsLayer {
                            translationX = .375f; translationY = .625f
                            scaleX = 1.015625f; scaleY = .984375f
                            alpha = .73f; clip = true; shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp)
                        }.then(recording).graphicsLayer { alpha = .61f; clip = true }) {
                            drawRect(Color(0xFFBADA55), Offset(phase + .375f, 3.625f))
                            drawCircle(Color.Red.copy(alpha = .47f), 33.25f, Offset(61.5f, 72.125f))
                            drawLine(Color.White, Offset(0f, 1.25f + phase), Offset(128f, 126.75f), .875f)
                        }
                    }
                }
            }
        } } }
        for ((index, admitted) in listOf(false, true, false, true, false).withIndex()) {
            compose.runOnIdle { enabled = admitted; phase = index * .375f; showHistorical = true }
            compose.waitForIdle()
            val before = compose.onNodeWithTag("comparison").captureToImage().toPixelMap()
            compose.runOnIdle { showHistorical = false }
            compose.waitForIdle()
            val after = compose.onNodeWithTag("comparison").captureToImage().toPixelMap()
            for (y in 0 until before.height) for (x in 0 until before.width)
                assertEquals("transition=$index enabled=$admitted x=$x y=$y", before[x, y].toArgb(), after[x, y].toArgb())
        }
    }
}

/** Frozen historical path: the candidate is the real production modifier. */
@Composable
private fun Modifier.historicalCover(source: FrostedBackdrop, wash: FrostedBackdrop, enabled: Boolean): Modifier {
    val cover = rememberGraphicsLayer()
    val cutout = remember { Path() }
    return drawWithContent {
        drawContent()
        if (enabled) drawLayer(cover)
    }.recordFrostedBackdrop(source, backing = wash, enabled = enabled, displayCutout = {
        cutout.reset(); cutout.addRect(Rect(0f, 0f, size.width, size.height)); cutout
    }).drawWithContent {
        cover.record { this@drawWithContent.drawContent() }
        drawLayer(cover)
    }
}
