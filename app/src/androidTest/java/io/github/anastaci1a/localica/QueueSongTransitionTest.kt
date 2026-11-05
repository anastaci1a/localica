package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * #578: with the Queue drawer open, a song change with different artwork eases the backdrop, the ink and every
 * material on ONE clock (the artwork blend's reveal): a common start and end, monotonic, no single-frame jump,
 * retargeting without jumps on rapid skips, and snapping under Reduced Motion. The host mirrors LocalicaScreen:
 * the player, and the Queue under a panel theme that reads the player's shared presented polarity.
 */
class QueueSongTransitionTest {
    @get:Rule val compose = createComposeRule()
    private val owner = PlayerBackgroundPolarityState()
    private val settings = InterfaceSettings(artworkLayout = ArtworkLayout.FullBackground, glass = GlassAppearance(contrast = .3f))
    private var visual by mutableStateOf<PlayerVisual?>(null)
    private var reduced by mutableStateOf(false)
    @Volatile private var queueInk = Float.NaN
    @Volatile private var backdropMix = Float.NaN

    private class Frame(val mix: Float, val queue: Float, val backdrop: Float, val shot: Float)
    private val frames = ArrayList<Frame>()

    private fun cover(color: Int, key: String): PlayerVisual {
        val bitmap = Bitmap.createBitmap(150, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return PlayerVisual(bitmap.asImageBitmap(), runBlocking { playerPalette(bitmap) }, contentKey = key)
    }
    private val white = cover(android.graphics.Color.rgb(246, 244, 240), "white")
    private val black = cover(android.graphics.Color.rgb(12, 12, 16), "black")

    private fun mount(first: PlayerVisual) {
        visual = first
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides reduced) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val mix = owner.lightMix
                    val queue = remember { Animatable(1f) }
                    val backdrop = rememberFrostedBackdrop(Unit, enabled = false, dark = mix >= .5f, lightMix = mix, retainRaster = false)
                    SideEffect { backdropMix = backdrop.lightMix }
                    PlayerSurface(PlaybackState(connected = true, title = "Now and then", artist = "Artist", durationMs = 120000),
                        true, {}, settings, remember { Animatable(1f) }, {}, {}, {}, {}, {}, {},
                        visual = visual, backgroundPolarity = owner, queueProgress = queue) { Text("Controls") }
                    GlassPanelTheme(mix) {
                        QueueSurface(queue, settings, {}, playerProgress = { 1f }) {
                            val ink = LocalContentColor.current
                            SideEffect { queueInk = ink.red }
                            Text("Queue row")
                        }
                    }
                }
            }
        } }
        compose.waitUntil(10000) { owner.decision.confidence == PlayerBackgroundConfidence.Current && owner.frame != null }
        compose.waitForIdle()
    }

    private fun gutter(): Float {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        var sum = 0f; var n = 0
        for (y in (pixels.height * .55f).toInt() until (pixels.height * .8f).toInt() step 6)
            for (x in (pixels.width * .01f).toInt() until (pixels.width * .045f).toInt()) { sum += pixels[x, y].red; n++ }
        return sum / n
    }

    private fun record(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        // Let off-thread preparation of the new artwork's samples land between frames.
        Thread.sleep(12)
        frames += Frame(owner.lightMix, (queueInk - .1255f) / .8745f, backdropMix, gutter())
    }

    private fun assertOneClock(name: String, toLight: Boolean) {
        val mix = frames.map { it.mix }; val queue = frames.map { it.queue }; val shot = frames.map { it.shot }
        assertTrue("$name: polarity ends at its target: ${mix.takeLast(3)}", abs(mix.last() - if (toLight) 1f else 0f) < .02f)
        assertTrue("$name: eased through intermediates: $mix", mix.count { it > .05f && it < .95f } >= 3)
        mix.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("$name: polarity jump at $i: $a -> $b", abs(b - a) <= .3f)
            assertTrue("$name: polarity not monotonic at $i", if (toLight) b >= a - 1e-3f else b <= a + 1e-3f)
        }
        // The Queue's theme reads the same eased fraction (its capture trails the polarity read by up to a frame).
        frames.forEachIndexed { i, f -> assertTrue("$name: queue ink $i ${f.queue} vs polarity ${f.mix}", abs(f.queue - f.mix) <= .2f) }
        assertTrue("$name: materials follow ${frames.map { it.backdrop }}", frames.all { abs(it.backdrop - it.mix) <= .3f })
        // The backdrop crossfade shares that start and end.
        val from = shot.first(); val to = shot.last()
        assertTrue("$name: backdrop changed: $shot", abs(to - from) > .5f)
        val fraction = shot.map { ((it - from) / (to - from)).coerceIn(0f, 1f) }
        fraction.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("$name: backdrop jump at $i: $a -> $b", abs(b - a) <= .3f)
            assertTrue("$name: backdrop not monotonic at $i", b >= a - .02f)
        }
        val inkFraction = mix.map { if (toLight) it else 1f - it }
        fun first(values: List<Float>, level: Float) = values.indexOfFirst { it > level }
        assertTrue("$name: common start ${first(inkFraction, .05f)} vs ${first(fraction, .05f)}", abs(first(inkFraction, .05f) - first(fraction, .05f)) <= 6)
        assertTrue("$name: common end ${first(inkFraction, .95f)} vs ${first(fraction, .95f)}", abs(first(inkFraction, .95f) - first(fraction, .95f)) <= 6)
        frames.indices.forEach { assertTrue("$name: shared progress at $it: ${inkFraction[it]} vs ${fraction[it]}", abs(inkFraction[it] - fraction[it]) <= .35f) }
    }

    @Test fun songChangeWithTheDrawerOpenEasesInkMaterialsAndBackdropOnOneClockToDark() {
        mount(white); compose.mainClock.autoAdvance = false
        compose.runOnIdle { visual = black }
        record(70)
        assertOneClock("light to dark artwork", toLight = true)
    }

    @Test fun songChangeWithTheDrawerOpenEasesBackToLightInk() {
        mount(black); compose.mainClock.autoAdvance = false
        compose.runOnIdle { visual = white }
        record(70)
        assertOneClock("dark to light artwork", toLight = false)
    }

    @Test fun rapidSkipsRetargetWithoutJumps() {
        mount(white); compose.mainClock.autoAdvance = false
        compose.runOnIdle { visual = black }
        record(22)
        compose.runOnIdle { visual = white }   // skip again before the first change has finished
        record(12)
        compose.runOnIdle { visual = black }
        record(70)
        frames.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("polarity jump at $i: ${a.mix} -> ${b.mix}", abs(b.mix - a.mix) <= .3f)
            assertTrue("queue ink jump at $i: ${a.queue} -> ${b.queue}", abs(b.queue - a.queue) <= .3f)
            assertTrue("backdrop jump at $i: ${a.shot} -> ${b.shot}", abs(b.shot - a.shot) <= .3f)
        }
        assertTrue("ends on the last song's ink: ${frames.last().mix}", frames.last().mix > .98f)
    }

    @Test fun reducedMotionSnapsTheWholeChange() {
        reduced = true
        mount(white); compose.mainClock.autoAdvance = false
        compose.runOnIdle { visual = black }
        record(12)
        frames.forEach { f -> listOf(f.mix, f.backdrop).forEach { assertTrue("snap, not ease: $it", it == 0f || it == 1f) } }
        assertEquals(1f, frames.last().mix, 0f)
        val shots = frames.map { it.shot }
        assertTrue("backdrop snapped, never eased: $shots", shots.all { abs(it - shots.first()) < .05f || abs(it - shots.last()) < .05f })
    }
}
