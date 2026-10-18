package io.github.anastaci1a.localica

import android.os.Build
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.MainTab
import io.github.anastaci1a.localica.navigation.TabLensStyle
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real navbar placement exercises the circular-lens case; pixels verify padded sample origins. */
class SelectorReboundRecordingTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private var source: FrostedBackdrop? = null
    private var stage by mutableIntStateOf(0)

    @Test fun overlayKeepsItsLandmarkDuringCoupledRebound() = rebound(TabLensStyle.Overlay, false)
    @Test fun foregroundOverlayKeepsItsLandmarkDuringCoupledRebound() = rebound(TabLensStyle.Overlay, true)
    @Test fun adjustableGlassKeepsItsLandmarkDuringCoupledRebound() = rebound(TabLensStyle.Adjustable, false)

    private fun rebound(style: TabLensStyle, foreground: Boolean) {
        compose.setContent { LocalicaTheme {
            view = LocalView.current
            val backing = rememberFrostedBackdrop(Unit, glassBlur = 0f, whiteTint = 0f)
            source = backing
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(tabStyle = style,
                overlayForeground = foreground, overlayDarkening = 0f, tabBlur = 0f, tabWhiteTint = 0f,
                blur = 0f, whiteTint = 0f, contrast = 0f, shadowStrength = 0f, shapeDamping = 8f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(240.dp, 112.dp).testTag("rebound-scene")) {
                        Canvas(Modifier.matchParentSize().recordFrostedBackdrop(backing)) {
                            drawRect(Color.Blue)
                            // An origin error from the new cross padding moves this narrow
                            // source landmark away from the lens's zero-Y-deflection center.
                            drawRect(Color.Red, Offset(0f, 54.dp.toPx()), Size(size.width, 4.dp.toPx()))
                        }
                        Canvas(Modifier.matchParentSize()) { drawRect(Color.Black) }
                        Box(Modifier.offset(24.dp, 24.dp).size(192.dp, 64.dp)) {
                            val tabs = listOf(MainTab.Home, MainTab.Library, MainTab.Songs)
                            SlidingTabs(tabs, MainTab.Home, { 0f }, backing) { }
                        }
                        PresentationMarker { stage }
                    }
                }
            }
        } }
        if (Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source?.lensSnapshot != null }
        compose.waitForIdle()
        fun bounds() = compose.onNodeWithTag("tab-selector").fetchSemanticsNode().boundsInRoot
        val rest = bounds()
        val host = compose.onNodeWithTag("tab-track-host")
        val hostRest = host.getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        var held = false
        try {
            host.performTouchInput { down(Offset(width / 6f, centerY)); held = true; moveBy(Offset(-150f, 0f), 100) }
            compose.mainClock.advanceTimeBy(400)
            landmark(bounds())
            host.performTouchInput { up(); held = false }
            val frames = mutableListOf<Rect>()
            repeat(24) {
                compose.mainClock.advanceTimeBy(32)
                frames += bounds()
                landmark(frames.last())
                assertEquals("Recording clearance never changes resting input bounds", hostRest, host.getUnclippedBoundsInRoot())
            }
            assertTrue("Circular navbar lens must have visible width rebound", frames.maxOf { it.width } - frames.minOf { it.width } > 1f)
            assertTrue("Circular navbar lens must have visible height rebound", frames.maxOf { it.height } - frames.minOf { it.height } > 1f)
            assertTrue("Circular navbar has resolved opposite-phase dimensions", frames.drop(4).any {
                abs(it.width - rest.width) > 1f && abs(it.height - rest.height) > 1f &&
                    (it.width - rest.width) * (it.height - rest.height) < 0f
            })
            compose.mainClock.advanceTimeBy(2400)
            val final = bounds()
            assertEquals(rest.width, final.width, 1f)
            assertEquals(rest.height, final.height, 1f)
            landmark(final)
        } finally {
            try {
                if (held) host.performTouchInput { cancel() }
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }

    private fun landmark(lens: Rect) {
        compose.runOnIdle { stage = (stage + 1) % 32 }
        val scene = compose.onNodeWithTag("rebound-scene")
        val origin = scene.fetchSemanticsNode().boundsInRoot.topLeft
        val pixels = capturePresentedPopupPixels(compose, scene, stage) { view }
        val y = (lens.center.y - origin.y).roundToInt().coerceIn(1, pixels.height - 2)
        // Sample on either side of the centered glyph, away from its white/accent ink.
        for (fraction in listOf(.18f, .82f)) {
            val x = (lens.left + lens.width * fraction - origin.x).roundToInt().coerceIn(1, pixels.width - 2)
            val colors = (y - 1..y + 1).map { pixels[x, it] }
            assertTrue("Padded navbar sampling lost its center landmark at $lens fraction=$fraction: $colors",
                colors.any { it.red > .6f && it.blue < .4f })
        }
    }
}
