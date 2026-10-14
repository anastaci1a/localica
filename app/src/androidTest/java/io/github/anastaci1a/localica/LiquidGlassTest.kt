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

    @Test fun resizingAnInactiveBackdropDoesNotRecomposeItsScreen() {
        lateinit var source: FrostedBackdrop
        var compositions = 0
        compose.setContent { LocalicaTheme {
            source = rememberFrostedBackdrop(Unit, enabled = false)
            SideEffect { compositions++ }
        } }
        compose.waitForIdle()
        val settled = compositions
        repeat(10) { frame ->
            compose.runOnIdle { source.size = androidx.compose.ui.unit.IntSize(300, 60 + frame * 40) }
            compose.waitForIdle()
        }
        assertEquals("A mini-player resize must not rebuild the surrounding screen just to schedule sampling", settled, compositions)
    }

    @Test fun restoredSelectorStartsAtRestAndOnlyDeformsWhenItMoves() {
        var position by mutableFloatStateOf(2f)
        lateinit var shape: State<GlassShape>
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme { shape = rememberGlassBody({ position }) } }
        compose.mainClock.advanceTimeBy(64)
        assertEquals("Restoring a selected option must not invent an arrival impulse", GlassShape(), shape.value)
        compose.runOnIdle { position = 1.6f }
        compose.mainClock.advanceTimeBy(32)
        assertNotEquals("Actual movement still drives deformation", GlassShape(), shape.value)
        compose.mainClock.autoAdvance = true
    }

    @Test fun adaptiveContrastPreservesBlackToGrayOrderingAtEveryStrength() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 33)
        var strength by mutableFloatStateOf(0f)
        var whiteTint by mutableFloatStateOf(0f)
        var dark by mutableStateOf(false)
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalGlassAppearance provides
            io.github.anastaci1a.localica.navigation.GlassAppearance(blur = 0f, whiteTint = whiteTint, contrast = strength, shadowStrength = 0f)) {
            Box(Modifier.fillMaxSize()) {
                val source = rememberFrostedBackdrop(Unit, dark = dark)
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    repeat(16) { i -> drawRect(Color(i / 15f, i / 15f, i / 15f), Offset(size.width * i / 16f, 0f), Size(size.width / 16f, size.height)) }
                }
                LiquidGlassSurface(Modifier.align(Alignment.Center).fillMaxWidth().height(80.dp).testTag("ramp"), source,
                    refraction = 0f, inkOverride = if (dark) Color.White else Color.Black)
            }
        } } }
        for (polarity in listOf(false, true)) for (tint in listOf(0f, .2f, .6f, 1f)) for (contrast in listOf(0f, .4f, 1f)) {
            compose.runOnIdle { dark = polarity; whiteTint = tint; strength = contrast }
            val pixels = compose.onNodeWithTag("ramp").captureToImage().toPixelMap()
            val ramp = (0..15).map { pixels[(pixels.width * (it + .5f) / 16).toInt(), pixels.height / 2].red }
            assertTrue("No tonal reversal (dark=$polarity tint=$tint contrast=$contrast): $ramp", ramp.zipWithNext().all { (a, b) -> a <= b + .005f })
        }
    }

    @Test fun tintLiftsShadowsMoreThanHighlightsWithoutBleachingYellow() {
        org.junit.Assume.assumeTrue("Per-pixel tone mapping requires runtime shaders", Build.VERSION.SDK_INT >= 33)
        var tint by mutableFloatStateOf(0f)
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalGlassAppearance provides
            io.github.anastaci1a.localica.navigation.GlassAppearance(blur = 0f, whiteTint = tint, contrast = 0f, shadowStrength = 0f)) {
            Box(Modifier.fillMaxSize()) {
                val source = rememberFrostedBackdrop(Unit)
                Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                    val colors = listOf(Color(.03f, .03f, .03f), Color(.4f, .4f, .4f), Color(.9f, .9f, .9f), Color.Yellow)
                    colors.forEachIndexed { i, color -> drawRect(color, Offset(size.width * i / 4f, 0f), Size(size.width / 4f, size.height)) }
                }
                LiquidGlassSurface(Modifier.align(Alignment.Center).fillMaxWidth().height(96.dp).testTag("tonal-lens"), source, refraction = 0f)
            }
        } } }
        fun colors() = compose.onNodeWithTag("tonal-lens").captureToImage().toPixelMap().let { pixels ->
            (0..3).map { pixels[(pixels.width * (it + .5f) / 4).toInt(), pixels.height / 2] }
        }
        val original = colors()
        compose.runOnIdle { tint = .18f }
        val toned = colors()
        val lift = (0..2).map { toned[it].red - original[it].red }
        assertTrue("Shadows receive a visible, stronger lift: $lift", lift[0] > .15f && lift[0] > lift[1] && lift[1] > lift[2])
        assertTrue("Highlights remain close to their original brightness", lift[2] < .02f)
        assertTrue("Bright yellow retains saturation: ${toned[3]}", toned[3].blue < .04f)
        compose.runOnIdle { tint = 1f }
        assertTrue("Full tint remains available", colors().all { it.red > .98f && it.green > .98f && it.blue > .98f })
    }

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
        val interior = pixels[pixels.width / 4, pixels.height / 2]
        assertTrue("Transparent source pixels must preserve the immediate red backdrop: $edge",
            edge.red > .97f && edge.green < .04f && edge.blue < .04f)
        assertTrue("Curved edge reflection must not become a coating across the transparent face: $interior",
            interior.red > .97f && interior.green < .01f && interior.blue < .01f)
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
