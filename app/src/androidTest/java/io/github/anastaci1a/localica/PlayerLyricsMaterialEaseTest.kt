package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * #564: the material parameters (glass tone, frost coating, the panel theme and the backdrops outside the player)
 * ease with the same clock as the foreground. The host mirrors LocalicaScreen: it reads the player's shared
 * presented polarity ([PlayerBackgroundPolarityState.lightMix]) for its backdrop and panel theme.
 */
class PlayerLyricsMaterialEaseTest {
    @get:Rule val compose = createComposeRule()
    private val owner = PlayerBackgroundPolarityState()
    private var lyrics by mutableStateOf(false)
    private var settings by mutableStateOf(InterfaceSettings(artworkLayout = ArtworkLayout.FullBackground,
        glass = GlassAppearance(contrast = .3f)))
    @Volatile private var inkComposition: PlayerInkComposition? = null
    private var reference: LyricsPhaseReference? = null
    @Volatile private var tone = Float.NaN
    @Volatile private var coating = Float.NaN
    @Volatile private var panelInk = Float.NaN
    @Volatile private var backdropMix = Float.NaN

    private class Frame(val shared: Float, val tone: Float, val coating: Float, val panel: Float, val backdrop: Float)
    private val frames = ArrayList<Frame>()

    private fun mount() {
        val bitmap = android.graphics.Bitmap.createBitmap(150, 300, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.GRAY) }
        val visual = PlayerVisual(bitmap.asImageBitmap(), kotlinx.coroutines.runBlocking { playerPalette(bitmap) }, contentKey = "gray")
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalDensity provides Density(1f),
                LocalPlayerInkCompositionObserver provides { inkComposition = it }, LocalGlassSurfaceDrawObserver provides {
                if (it.panel > 0f && it.width < 100f) { tone = it.toneLightMix; coating = it.coatingLightMix } }) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val mix = owner.lightMix
                    val backdrop = rememberFrostedBackdrop(Unit, enabled = false, dark = mix >= .5f, lightMix = mix, retainRaster = false)
                    SideEffect { backdropMix = backdrop.lightMix }
                    GlassPanelTheme(mix) { val ink = LocalContentColor.current; SideEffect { panelInk = ink.red } }
                    // A surface outside the player that samples the shared backdrop, as the browse chrome and Queue do.
                    LiquidGlassSurface(Modifier.size(48.dp), source = backdrop, panelProgress = 1f, cornerRadius = 12.dp) { Text("x") }
                    BoxWithConstraints(Modifier.width(300.dp).height(minOf(560.dp, maxHeight))) {
                        PlayerSurface(PlaybackState(connected = true, title = "Region", artist = "Artwork", durationMs = 120000),
                            true, {}, settings, remember { Animatable(1f) }, {}, {}, {}, {}, {}, {},
                            visual = visual, backgroundPolarity = owner, lyricsOpen = lyrics) { Text("Controls") }
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun record(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Play").captureNativeFrame()
        val phase = requireNotNull(inkComposition).lyricsProgress
        reference?.assertFrame("shared polarity", phase, owner.lightMix)
        assertEquals("outside backdrop follows this frame", owner.lightMix, backdropMix, .015f)
        assertEquals("glass tone follows this frame", owner.lightMix, tone, .015f)
        assertEquals("coating follows this frame", owner.lightMix, coating, .015f)
        assertEquals("panel ink follows this frame", lerp(Color(0xFF202124), Color.White, owner.lightMix).red, panelInk, .015f)
        frames += Frame(owner.lightMix, tone, coating, panelInk, backdropMix)
    }

    private fun check(name: String, values: List<Float>, towardLight: Boolean) {
        assertTrue("$name never changed: $values", abs(values.last() - values.first()) > .9f)
        values.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("$name not monotonic at $i: $a -> $b", if (towardLight) b >= a - 1e-3f else b <= a + 1e-3f)
        }
    }

    private fun run(layout: ArtworkLayout, open: Boolean, towardLight: Boolean) {
        settings = settings.copy(artworkLayout = layout)
        lyrics = !open; owner.pin(!towardLight); mount()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { lyrics = open }; repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        frames.clear()
        frames += Frame(owner.lightMix, tone, coating, panelInk, backdropMix)
        compose.runOnIdle {
            val phase = requireNotNull(inkComposition).lyricsProgress
            reference = LyricsPhaseReference(owner.lightMix, phase, if (open) 1f else 0f).also {
                it.join(if (towardLight) 1f else 0f, phase, if (open) 1f else 0f)
            }
            owner.pin(towardLight)
        }
        record(45)
        check("shared polarity", frames.map { it.shared }, towardLight)
        check("outside backdrop", frames.map { it.backdrop }, towardLight)
        check("glass tone", frames.map { it.tone }, towardLight)
        check("panel coating", frames.map { it.coating }, towardLight)
        // The panel theme ink runs from dark ink (red .1255) to white; normalise to the 0..1 fraction.
        check("panel theme ink", frames.map { ((it.panel - .1255f) / .8745f).coerceIn(0f, 1f) }, towardLight)
        // The player foreground and every material read the same presented fraction.
        // record() compares every material with the same frame, including the faster first #608 step.
    }

    @Test fun openingToTheLyricsThemeEasesTheMaterialsFullBackground() = run(ArtworkLayout.FullBackground, open = true, towardLight = false)
    @Test fun openingToTheLyricsThemeEasesTheMaterialsFloating() = run(ArtworkLayout.Floating, open = true, towardLight = false)
    @Test fun closingToTheLightThemeEasesTheMaterialsFullBackground() = run(ArtworkLayout.FullBackground, open = false, towardLight = true)
    @Test fun closingToTheLightThemeEasesTheMaterialsFloating() = run(ArtworkLayout.Floating, open = false, towardLight = true)

    @Test fun reducedMotionSnapsTheMaterials() {
        settings = settings.copy(reducedMotion = true)
        owner.pin(true); mount()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { lyrics = true }; repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        frames.clear()
        compose.runOnIdle { owner.pin(false) }
        record(10)
        assertEquals(0f, frames.last().backdrop, 0f); assertEquals(0f, frames.last().shared, 0f)
        frames.forEach { f -> listOf(f.shared, f.backdrop).forEach { assertTrue("snap, not ease: $it", it == 0f || it == 1f) } }
    }
}
