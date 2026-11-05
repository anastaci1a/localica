package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
                            GlassActionButton(R.drawable.ic_revert, "Mode action", Modifier.testTag("action")) { }
                            ListSearchPill("Queue search", {}, Modifier.testTag("queue-search"))
                            SelectionLens(Modifier.fillMaxWidth().height(48.dp).testTag("selector"), remember { Animatable(0f) })
                            FrostedSurface(Modifier.fillMaxWidth().height(60.dp).testTag("drawer"), shadow = false) { }
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
        for (tag in listOf("action", "queue-search", "selector", "drawer")) compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.runOnIdle { changed = true }
        compose.waitUntil(15_000) { center().green > .2f && center().blue < .12f }
        assertEquals("None does not execute refraction or blur, even with maximum saved blur", 0, optical.get())
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
                    compose.mainClock.advanceTimeByFrame()
                    val token = compose.runOnIdle { ++stage }
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
