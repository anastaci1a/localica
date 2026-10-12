package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LiquidGlassTest {
    @get:Rule val compose = createComposeRule()

    @Test fun paddedBackdropDoesNotExpandAContentSizedControl() {
        compose.setContent { ClearGlassTheme { Box(Modifier.fillMaxSize()) {
            val source = rememberFrostedBackdrop(Unit)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.Blue) }
            Column {
                LiquidGlassSurface(Modifier.fillMaxWidth().testTag("content-sized-lens"), source) {
                    Box(Modifier.fillMaxWidth().height(40.dp))
                }
                androidx.compose.material3.Text("Below the control")
            }
        } } }
        compose.onNodeWithTag("content-sized-lens").assertHeightIsEqualTo(40.dp)
        compose.onNodeWithText("Below the control").assertIsDisplayed()
    }

    @Test fun lightPillTransmitsAndRefreshesSceneWithoutBlurringForeground() = verifyLens(false)
    @Test fun darkBubbleTransmitsAndRefreshesSceneWithoutBlurringForeground() = verifyLens(true)

    @Test fun transparentListPixelsLeaveTheDrawerVisibleThroughTheLens() {
        compose.setContent { ClearGlassTheme { Box(Modifier.fillMaxSize().background(Color.Red)) {
            val source = rememberFrostedBackdrop(Unit)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                // A transparent list over a drawer must not replace that drawer with raw artwork.
                drawRect(Color.Blue, Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
            }
            LiquidGlassSurface(Modifier.align(Alignment.Center).size(300.dp, 96.dp).testTag("transparent-lens"), source)
        } } }
        val pixels = compose.onNodeWithTag("transparent-lens").captureToImage().toPixelMap()
        val edge = pixels[(pixels.width * .02f).toInt(), pixels.height / 2]
        assertTrue("Transparent source pixels must preserve the immediate red backdrop: $edge",
            edge.red > .97f && edge.green < .01f && edge.blue < .01f)
    }

    private fun verifyLens(dark: Boolean) {
        lateinit var source: FrostedBackdrop
        var swapped by mutableStateOf(false)
        compose.setContent { ClearGlassTheme { Box(Modifier.fillMaxSize()) {
            // Keep the scene key constant: explicit invalidation also covers settled list scrolls.
            source = rememberFrostedBackdrop(Unit, dark = dark)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                drawRect(if (swapped) Color.Blue else Color.Red, size = Size(size.width / 2, size.height))
                drawRect(if (swapped) Color.Red else Color.Blue, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
            }
            LiquidGlassSurface(Modifier.align(Alignment.Center).size(if (dark) 96.dp else 300.dp, 96.dp).testTag("lens"), source) {
                Box(Modifier.align(Alignment.Center).size(12.dp).background(Color.Black).testTag("foreground"))
            }
        } } }
        if (Build.VERSION.SDK_INT < 31) compose.waitUntil(10000) { source.fallback != null }
        val lens = compose.onNodeWithTag("lens")
        val pixels = lens.captureToImage().toPixelMap()
        val y = pixels.height / 2
        val left = pixels[pixels.width / 4, y]
        val right = pixels[pixels.width * 3 / 4, y]
        assertTrue("Keep the red backdrop visible: $left", left.red > left.blue + .15f)
        assertTrue("Keep the blue backdrop visible: $right", right.blue > right.red + .15f)
        assertTrue("Clear lens centers must not acquire a white frost: $left / $right", left.green < .03f && right.green < .03f)
        assertTrue("The outer shadow must not darken the transparent center: $left / $right", left.red > .97f && right.blue > .97f)
        val center = pixels[pixels.width / 2, y]
        assertTrue("Foreground must remain black and unfiltered: $center", center.red + center.green + center.blue < .05f)
        val previous = source.fallback
        compose.runOnIdle { swapped = true; source.refresh() }
        if (Build.VERSION.SDK_INT < 31) compose.waitUntil(10000) { source.fallback !== previous }
        compose.waitUntil(5000) {
            val updated = lens.captureToImage().toPixelMap()
            val color = updated[updated.width / 4, updated.height / 2]
            color.blue > color.red + .15f
        }
    }
}

@Composable
private fun ClearGlassTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassAppearance provides io.github.anastaci1a.localica.navigation.GlassAppearance(blur = 0f, whiteTint = 0f, contrast = 0f)) {
        LocalicaTheme(content = content)
    }
}
