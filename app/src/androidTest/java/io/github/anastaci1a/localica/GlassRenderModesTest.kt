package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.GlassRendererMode
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the shared material paths with real controls; content stays sharp. */
class GlassRenderModesTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun noneAveragesMovingContentAndNeverInvokesTheOpticalFilter() {
        val runtime = GlassRendererRuntime(GlassRendererMode.None)
        val optical = AtomicInteger()
        var changed by mutableStateOf(false)
        lateinit var source: FrostedBackdrop
        compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(blur = 1f, whiteTint = 0f,
                contrast = 0f, shadowStrength = 0f, renderer = GlassRendererMode.None),
                LocalGlassOpticalWork provides { optical.incrementAndGet() }) {
                source = rememberFrostedBackdrop(Unit, dark = false)
                Box(Modifier.size(240.dp, 420.dp)) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        val stripe = size.width / 8
                        repeat(8) { x -> drawRect(if (x % 2 == 0) Color.Red else if (changed) Color.Green else Color.Blue,
                            Offset(x * stripe, 0f), Size(stripe, size.height)) }
                    }
                    CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LiquidGlassSurface(Modifier.fillMaxWidth().height(60.dp).testTag("plain"), cornerRadius = 12.dp,
                                pressFeedback = false, shadow = false)
                            GlassActionButton(AppIcon.ActionRevert, "Mode action", Modifier.testTag("action")) { }
                            ListSearchPill("Queue search", {}, Modifier.testTag("queue-search"))
                            SelectionLens(Modifier.fillMaxWidth().height(48.dp).testTag("selector"), remember { Animatable(0f) })
                            FrostedSurface(Modifier.fillMaxWidth().height(60.dp).testTag("drawer"), shadow = false) { }
                            Box(Modifier.fillMaxWidth().height(64.dp)) {
                                SeekCancelTarget(remember { SeekCancelTargetState().apply { update(true) } }, false, 0.dp, true)
                            }
                        }
                    }
                }
            }
        } } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        fun center() = compose.onNodeWithTag("plain").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
        val first = center()
        assertTrue("Red/blue stripes become their uniform average: $first", first.red > .2f && first.blue > .2f && first.green < .12f)
        val pixels = compose.onNodeWithTag("plain").captureToImage().toPixelMap()
        for (x in pixels.width / 4 until pixels.width * 3 / 4 step 3) {
            val pixel = pixels[x, pixels.height / 2]
            assertEquals(first.red, pixel.red, .035f)
            assertEquals(first.blue, pixel.blue, .035f)
        }
        for (tag in listOf("action", "queue-search", "selector", "drawer", "cancel-seek")) compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.runOnIdle { changed = true }
        compose.waitUntil(15_000) { center().green > .2f && center().blue < .12f }
        assertEquals("None does not execute refraction or blur, even with maximum saved blur", 0, optical.get())
    }

    @Test fun noneSamplesTheTransformedFootprintWhileTheSourceStaysMounted() {
        val runtime = GlassRendererRuntime(GlassRendererMode.None)
        var shift by mutableFloatStateOf(0f)
        lateinit var source: FrostedBackdrop
        val colors = listOf(Color.Red, Color.Green, Color.Blue)
        compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(whiteTint = 0f, contrast = 0f)) {
                source = rememberFrostedBackdrop(Unit, dark = false)
                Box(Modifier.size(240.dp, 100.dp)) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        colors.forEachIndexed { index, color -> drawRect(color,
                            Offset(index * size.width / 3, 0f), Size(size.width / 3, size.height)) }
                    }
                    LiquidGlassSurface(Modifier.size(80.dp, 80.dp).graphicsLayer {
                        translationX = shift.dp.toPx(); scaleX = .75f; scaleY = .8f
                    }.testTag("moving-average"), source, cornerRadius = 12.dp,
                        pressFeedback = false, shadow = false)
                }
            }
        } } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        val owner = source
        for (index in listOf(0, 1, 2, 1, 0)) {
            compose.runOnIdle { shift = index * 80f }
            val pixels = compose.onNodeWithTag("moving-average").captureToImage().toPixelMap()
            val actual = pixels[pixels.width / 2, pixels.height / 2]
            val expected = colors[index]
            assertEquals("Red at translated footprint $index", expected.red, actual.red, .04f)
            assertEquals("Green at translated footprint $index", expected.green, actual.green, .04f)
            assertEquals("Blue at translated footprint $index", expected.blue, actual.blue, .04f)
            assertSame(owner, source)
        }
    }

    @Test fun switchingTranslucentMaterialNeverStacksTwoCompleteRenderers() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        var stage by mutableIntStateOf(0)
        lateinit var view: android.view.View
        lateinit var source: FrostedBackdrop
        compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(blur = 0f, whiteTint = 0f,
                contrast = 0f, shadowStrength = 0f)) {
                view = androidx.compose.ui.platform.LocalView.current
                source = rememberFrostedBackdrop(Unit, dark = false)
                Box(Modifier.size(120.dp).testTag("transparent-switch")) {
                    Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black) }
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.Blue.copy(alpha = .5f)) }
                    LiquidGlassSurface(Modifier.fillMaxSize(), source, cornerRadius = 12.dp,
                        pressFeedback = false, shadow = false)
                    PresentationMarker { stage }
                }
            }
        } } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        compose.mainClock.autoAdvance = false
        fun sample(): Color {
            val token = compose.runOnIdle { stage = stage % 30 + 1; stage }
            compose.mainClock.advanceTimeByFrame()
            val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("transparent-switch"), token) { view }
            return pixels[pixels.width / 2, pixels.height / 2]
        }
        try {
            val baseline = sample()
            for (mode in listOf(GlassRendererMode.None, GlassRendererMode.Normal)) {
                compose.runOnIdle { runtime.request(mode) }
                repeat(45) { frame ->
                    val actual = sample()
                    assertEquals("Uniform translucent blue must retain opacity: $mode frame=$frame", baseline.blue, actual.blue, .035f)
                    assertEquals(baseline.red, actual.red, .035f)
                    assertEquals(baseline.green, actual.green, .035f)
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun switchingAndReversingNeverDropsTheBackingOrReplacesTheSurface() {
        val runtime = GlassRendererRuntime(GlassRendererMode.Normal)
        var stage by mutableIntStateOf(0)
        lateinit var view: android.view.View
        lateinit var source: FrostedBackdrop
        val owners = mutableSetOf<Int>()
        compose.setContent { CompositionLocalProvider(LocalGlassRendererRuntime provides runtime) { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(blur = 0f, whiteTint = 0f,
                contrast = 0f, shadowStrength = 0f), LocalGlassSurfaceDrawObserver provides { owners += it.surfaceIdentity }) {
                view = androidx.compose.ui.platform.LocalView.current
                source = rememberFrostedBackdrop(Unit, dark = false)
                Box(Modifier.size(180.dp).testTag("switch-frame")) {
                    Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                        drawRect(Color(0xFFFF8080))
                        drawRect(Color(0xFF8080FF), Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
                    }
                    LiquidGlassSurface(Modifier.fillMaxSize(), source, cornerRadius = 12.dp,
                        pressFeedback = false, shadow = false)
                    PresentationMarker { stage }
                }
            }
        } } }
        compose.waitUntil(15_000) { source.recorded && (Build.VERSION.SDK_INT >= 33 || source.snapshot != null) }
        compose.mainClock.autoAdvance = false
        try {
            for (mode in listOf(GlassRendererMode.None, GlassRendererMode.Normal, GlassRendererMode.None)) {
                compose.runOnIdle { runtime.request(mode) }
                repeat(12) {
                    val token = compose.runOnIdle { stage = stage % 30 + 1; stage }
                    compose.mainClock.advanceTimeByFrame()
                    val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("switch-frame"), token) { view }
                    val c = pixels[pixels.width / 3, pixels.height / 2]
                    assertTrue("Backing vanished during mode switch: $mode frame=$it color=$c", c.red > .35f && c.blue > .35f)
                    assertTrue(compose.runOnIdle { source.recorded })
                }
            }
            compose.runOnIdle { assertEquals("Mode changes retain the existing surface", 1, owners.size) }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
