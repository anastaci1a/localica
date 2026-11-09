package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Full player pixels and geometry against frozen main at identical sampled progress values.
 * Captures use one implementation at a time, so neither arm can occlude the other. */
class PlayerDeferredFrameEqualityTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun floatingFrameMatchesMainThroughThresholdsAndReversals() = compare(ArtworkLayout.Floating, false, LayoutDirection.Ltr)
    @Test fun fullBackgroundFrameMatchesMainThroughThresholdsAndReversals() = compare(ArtworkLayout.FullBackground, false, LayoutDirection.Ltr)
    @Test fun minimizedSelectedLyricsMatchMain() = compare(ArtworkLayout.FullBackground, true, LayoutDirection.Ltr)
    @Test fun rightToLeftFrameMatchesMain() = compare(ArtworkLayout.Floating, false, LayoutDirection.Rtl)

    private fun compare(layout: ArtworkLayout, lyrics: Boolean, direction: LayoutDirection) {
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width)
                setPixel(x, y, android.graphics.Color.rgb(40 + x * 3, 30 + y * 2, 210 - x * 2))
        }
        val visual = PlayerVisual(bitmap.asImageBitmap(), runBlocking { playerPalette(bitmap) })
        val appearance = GlassAppearance(renderer = GlassRendererMode.None, contrast = 0f)
        val settings = InterfaceSettings(artworkLayout = layout, glass = appearance)
        val state = PlaybackState(connected = true, title = "Same frame", artist = "Artist", album = "Album", durationMs = 120000)
        val phase = Animatable(0f)
        var reference by mutableStateOf(true)
        var epoch by mutableIntStateOf(0)
        lateinit var scope: CoroutineScope
        val work = mutableListOf<String>()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalLayoutDirection provides direction, LocalGlassAppearance provides appearance,
                LocalPlayerWorkObserver provides { kind, _ -> work += kind }) {
                scope = rememberCoroutineScope()
                BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFFE1E2E3)).testTag("frame-root")) {
                    key(reference, epoch) {
                        if (reference) ReferencePlayerSurface(state, false, {}, settings, phase, {}, {}, {}, {}, {}, {},
                            lyricsOpen = lyrics, visual = visual) { Text("Album actions") }
                        else PlayerSurface(state, false, {}, settings, phase, {}, {}, {}, {}, {}, {},
                            lyricsOpen = lyrics, visual = visual) { ink -> DrawInkText("Album actions", ink) }
                    }
                }
            }
        } }
        // Each arm receives the same relative clock advances and the same immutable visual.
        val samples = listOf(0f, .08f, .38f, .3801f, .7f, .7001f, .8f, .8301f, .99f, 1f, .6f, .25f, .75f, 0f)
        data class Frame(val pixels: IntArray, val width: Int, val height: Int, val bounds: Map<String, androidx.compose.ui.geometry.Rect>)
        fun captureArm(old: Boolean): List<Frame> {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { scope.launch(start = CoroutineStart.UNDISPATCHED) { phase.stop(); phase.snapTo(0f) }; reference = old; epoch++ }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            return samples.map { p ->
                compose.runOnIdle { scope.launch(start = CoroutineStart.UNDISPATCHED) { phase.stop(); phase.snapTo(p) } }
                repeat(8) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                val tags = listOf("player-surface", "player-artwork", "player-title", "player-artist", "player-transport-row")
                val bounds = tags.associateWith { compose.onNodeWithTag(it, true).fetchSemanticsNode().boundsInRoot }
                val pixels = compose.onNodeWithTag("frame-root").captureToImage().toPixelMap()
                Frame(IntArray(pixels.width * pixels.height) { i -> pixels[i % pixels.width, i / pixels.width].toArgb() }, pixels.width, pixels.height, bounds)
            }
        }
        try {
            val original = captureArm(true)
            val candidate = captureArm(false)
            original.zip(candidate).forEachIndexed { i, (a, b) ->
                assertEquals("Width at ${samples[i]}", a.width, b.width)
                assertEquals("Height at ${samples[i]}", a.height, b.height)
                assertEquals("Geometry at ${samples[i]}", a.bounds, b.bounds)
                val mismatches = a.pixels.indices.count { a.pixels[it] != b.pixels[it] }
                if (mismatches > 0) savePlayerEqualityEvidence("PlayerDeferredFrameEqualityTest-$layout-lyrics$lyrics-$direction-${samples[i]}", a.pixels, b.pixels, a.width, a.height)
                assertEquals("Exact pixels at ${samples[i]} ($layout, lyrics=$lyrics, $direction)", 0, mismatches)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
