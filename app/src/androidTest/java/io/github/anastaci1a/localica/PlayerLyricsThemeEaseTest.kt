package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.draw.drawBehind
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * #564: the light/dark polarity change between the normal player and the lyrics screen eases over the lyrics
 * open/close clock, for the foreground (text and icons) and for the controls' own ink, in both artwork layouts,
 * in both directions, reversibly mid-way, and snaps under Reduced Motion. The real PlayerSurface runs on a manual
 * clock; the sampled decision is pinned so the test chooses exactly when the theme changes. Each frame's values are
 * recorded and the sequences are checked for monotonic interpolation and the absence of a single-frame jump.
 */
class PlayerLyricsThemeEaseTest {
    @get:Rule val compose = createComposeRule()
    private val owner = PlayerBackgroundPolarityState()
    private var lyrics by mutableStateOf(false)
    private var expanded by mutableStateOf(true)
    private var settings by mutableStateOf(InterfaceSettings(artworkLayout = ArtworkLayout.FullBackground,
        glass = GlassAppearance(contrast = .3f)))
    @Volatile private var inkComposition: PlayerInkComposition? = null
    private var pixelInk by mutableStateOf(Color.Black)
    private var sampleHeld by mutableStateOf(false)
    /** Latest drawn ink of every glass surface, by surface identity. */
    private val glassInk = ConcurrentHashMap<Int, Float>()

    private class Frame(val foreground: Float, val glass: Map<Int, Float>)
    private val recorded = ArrayList<Frame>()

    private fun mount(realDecision: Boolean = false) {
        val bitmap = android.graphics.Bitmap.createBitmap(150, 300, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.GRAY) }
        val palette = if (realDecision) {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            PlayerPalette(Color.Black, Color.Black, Color.Black, .5f, wash = bitmap)
        } else kotlinx.coroutines.runBlocking { playerPalette(bitmap) }
        val visual = PlayerVisual(bitmap.asImageBitmap(), palette, contentKey = "gray")
        compose.setContent { LocalicaTheme {
            val preparationBarrier: (suspend () -> Unit)? = if (realDecision) remember {
                suspend { snapshotFlow { sampleHeld }.first { !it }; Unit }
            } else null
            CompositionLocalProvider(LocalDensity provides Density(1f),
                LocalPlayerBackgroundPreparationBarrier provides preparationBarrier,
                LocalPlayerInkCompositionObserver provides { inkComposition = it; if (realDecision) pixelInk = it.contentColor },
                LocalGlassSurfaceDrawObserver provides { glassInk[it.surfaceIdentity] = Color(it.inkArgb).red }) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.width(300.dp).height(minOf(560.dp, maxHeight))) {
                        PlayerSurface(PlaybackState(connected = true, title = "Region", artist = "Artwork", durationMs = 120000),
                            expanded, { expanded = it }, settings, remember { Animatable(if (expanded) 1f else 0f) }, {}, {}, {}, {}, {}, {},
                            visual = visual, backgroundPolarity = owner, lyricsOpen = lyrics) {
                            Text("Controls", Modifier.testTag("control-ink"))
                        }
                    }
                    if (realDecision) Box(Modifier.align(Alignment.TopEnd).size(12.dp).testTag("ink-pixel")
                        .drawBehind { drawRect(pixelInk) })
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    private fun record(count: Int) = repeat(count) {
        frame()
        recorded += Frame(inkComposition?.contentColor?.red ?: Float.NaN, glassInk.toMap())
    }
    private fun openLyrics(open: Boolean) { compose.runOnIdle { lyrics = open } }
    private fun pin(light: Boolean) { compose.runOnIdle { owner.pin(light) } }

    private fun foreground() = recorded.map { it.foreground }
    /** Each glass surface's drawn ink over the frames it was present in. */
    private fun glassSequences(): List<List<Float>> = recorded.flatMap { it.glass.keys }.distinct()
        .map { id -> recorded.mapNotNull { it.glass[id] } }

    private fun assertEased(name: String, values: List<Float>, towardLight: Boolean?, maxStep: Float, minIntermediates: Int = 4) {
        val low = values.min(); val high = values.max()
        assertTrue("$name never changed polarity: $values", high - low > .3f)
        values.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("$name single-frame jump at $i: $a -> $b", abs(b - a) <= maxStep)
            if (towardLight == true) assertTrue("$name not monotonic at $i: $a -> $b", b >= a - 1e-3f)
            if (towardLight == false) assertTrue("$name not monotonic at $i: $a -> $b", b <= a + 1e-3f)
        }
        val intermediates = values.count { it > low + .05f && it < high - .05f }
        assertTrue("$name should pass through $minIntermediates+ intermediate frames, saw $intermediates: $values", intermediates >= minIntermediates)
    }

    private fun assertEveryChannelEased(towardLight: Boolean?, maxStep: Float = .4f) {
        assertEased("foreground", foreground(), towardLight, maxStep)
        val glass = glassSequences()
        assertTrue("glass controls were drawn", glass.isNotEmpty())
        glass.forEachIndexed { i, sequence -> if (sequence.max() - sequence.min() > .3f) assertEased("glass ink #$i", sequence, towardLight, maxStep) }
        assertTrue("some glass control follows the polarity", glass.any { it.max() - it.min() > .3f })
    }


    /** No pin: the real sampler must supply BOTH endpoints before the layout moves. */
    @Test fun sampledThemeTracksEveryLyricsFrameInBothDirectionsAndOnReversal() {
        settings = settings.copy(glass = settings.glass.copy(contrast = 0f))
        mount(realDecision = true)
        compose.waitUntil(10000) { owner.decision.confidence == PlayerBackgroundConfidence.Current &&
            inkComposition?.contentColor?.red == 0f }
        compose.mainClock.autoAdvance = false
        val rows = mutableListOf<String>("leg,frame,progress,ink,material,pixel,sampledLight,sampleConfidence")
        val failures = mutableListOf<String>()
        fun capture(leg: String, count: Int) = repeat(count) { index ->
            frame()
            val pixels = compose.onNodeWithTag("ink-pixel").captureNativeFrame().toPixelMap()
            val pixel = pixels[pixels.width / 2, pixels.height / 2].red
            val ink = requireNotNull(inkComposition)
            val progress = ink.lyricsProgress
            val expected = lerp(Color.Black, Color.White, progress).red
            rows += listOf(leg, index, progress, ink.contentColor.red, owner.lightMix, pixel,
                owner.decision.lightInk, owner.decision.confidence).joinToString(",")
            if (abs(ink.contentColor.red - expected) > .035f || abs(owner.lightMix - progress) > .035f ||
                abs(pixel - expected) > .04f) failures += rows.last()
        }
        // Model a sampler starved by changing geometry. No decision is pinned:
        // the genuine new result is released only after the layout has settled.
        fun begin(open: Boolean) { compose.runOnIdle { sampleHeld = true; lyrics = open } }
        fun release() {
            compose.runOnIdle { sampleHeld = false }
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            do {
                // Publishing the prepared result runs in a composition SideEffect.
                // Manual time must flush that composition as well as the worker.
                capture("sample-settlement", 1)
            } while (owner.decision.confidence != PlayerBackgroundConfidence.Current && android.os.SystemClock.elapsedRealtime() < deadline)
            if (owner.decision.confidence != PlayerBackgroundConfidence.Current) failures += "released sampler did not settle"
        }
        begin(true); capture("open", 40)
        if (inkComposition?.lyricsProgress != 1f) failures += "opening did not reach the lyrics endpoint"
        release()
        capture("open-settled", 4)
        begin(false); capture("close", 40)
        if (inkComposition?.lyricsProgress != 0f) failures += "closing did not reach the player endpoint"
        release()
        capture("close-settled", 4)
        begin(true); capture("partial-open", 12)
        if (requireNotNull(inkComposition).lyricsProgress !in .05f..0.95f) failures += "reversal did not interrupt intermediate progress"
        openLyrics(false); capture("reverse", 40)
        if (inkComposition?.lyricsProgress != 0f) failures += "reversal did not return to the player endpoint"
        release(); capture("reverse-settled", 4)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        File(root, "lyrics-ink-frames.csv").writeText(rows.joinToString("\n"))
        assertTrue("Ink/material must follow the layout on every captured frame:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun openingToDark(layout: ArtworkLayout) {
        settings = settings.copy(artworkLayout = layout)
        pin(true); mount()
        compose.mainClock.autoAdvance = false
        openLyrics(true); record(6)
        recorded.clear()
        pin(false)            // the lyrics theme differs: this arrives part-way through the animation
        record(40)
        assertEveryChannelEased(towardLight = false)
        assertEquals(0f, foreground().last(), .02f)
    }

    @Test fun openingLyricsEasesTheThemeToDarkWithFullBackgroundArtwork() = openingToDark(ArtworkLayout.FullBackground)
    @Test fun openingLyricsEasesTheThemeToDarkWithFloatingArtwork() = openingToDark(ArtworkLayout.Floating)

    private fun closingToLight(layout: ArtworkLayout) {
        settings = settings.copy(artworkLayout = layout)
        lyrics = true; pin(false); mount()
        compose.mainClock.autoAdvance = false
        openLyrics(false); record(6)
        recorded.clear()
        pin(true)
        record(40)
        assertEveryChannelEased(towardLight = true)
        assertEquals(1f, foreground().last(), .02f)
    }

    @Test fun closingLyricsEasesTheThemeToLightWithFullBackgroundArtwork() = closingToLight(ArtworkLayout.FullBackground)
    @Test fun closingLyricsEasesTheThemeToLightWithFloatingArtwork() = closingToLight(ArtworkLayout.Floating)

    @Test fun reversingTheLyricsAnimationMidEaseContinuesWithoutAJump() {
        pin(true); mount()
        compose.mainClock.autoAdvance = false
        openLyrics(true); record(6)
        recorded.clear()
        pin(false); record(6)
        openLyrics(false)      // reversed part-way through the ease
        record(40)
        foreground().zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("foreground jump at $i: $a -> $b", abs(b - a) <= .4f)
            assertTrue("foreground drifted back toward the old ink at $i", b <= a + 1e-3f)
        }
        glassSequences().forEachIndexed { i, s -> s.zipWithNext().forEach { (a, b) ->
            assertTrue("glass ink #$i jump $a -> $b", abs(b - a) <= .4f); assertTrue("glass ink #$i drifted back", b <= a + 1e-3f) } }
        assertEquals(0f, foreground().last(), .02f)
    }

    @Test fun aThemeThatChangesBackMidEaseTurnsAroundWithoutAJump() {
        pin(true); mount()
        compose.mainClock.autoAdvance = false
        openLyrics(true); record(6)
        recorded.clear()
        pin(false); record(5)
        pin(true); record(40)
        foreground().zipWithNext().forEachIndexed { i, (a, b) -> assertTrue("foreground jump at $i: $a -> $b", abs(b - a) <= .4f) }
        glassSequences().forEachIndexed { i, s -> s.zipWithNext().forEach { (a, b) -> assertTrue("glass ink #$i jump $a -> $b", abs(b - a) <= .4f) } }
        assertEquals(1f, foreground().last(), .02f)
    }

    @Test fun reducedMotionSnapsTheThemeWithTheLyricsLayout() {
        settings = settings.copy(reducedMotion = true)
        pin(true); mount()
        compose.mainClock.autoAdvance = false
        openLyrics(true); record(3)
        recorded.clear()
        pin(false); record(10)
        assertEquals("foreground reaches the lyrics theme at once", 0f, foreground().last(), .02f)
        foreground().forEach { assertTrue("no eased value under Reduced Motion: $it", it < .02f || it > .98f) }
        glassSequences().forEach { s -> val lo = s.min(); val hi = s.max()
            s.forEach { assertTrue("glass ink snaps: $it in $lo..$hi", abs(it - lo) < .02f || abs(it - hi) < .02f) } }
    }

    @Test fun collapsingAndExpandingWithLyricsOpenNeverChangesThePolarity() {
        lyrics = true; pin(true); mount()
        compose.mainClock.autoAdvance = false
        record(4); recorded.clear(); glassInk.clear()
        compose.runOnIdle { expanded = false }; record(40)
        compose.runOnIdle { expanded = true }; record(40)
        glassSequences().forEachIndexed { i, s -> assertTrue("glass ink #$i constant while the player moves: ${s.min()}..${s.max()}", s.max() - s.min() < .02f) }
    }

    @Test fun aThemeDecidedWhileCollapsedEasesInWithTheExpansion() {
        lyrics = true; pin(true); expanded = false; mount()
        compose.mainClock.autoAdvance = false
        record(4)
        pin(false)            // decided while the mini player is showing; nothing visible changes
        record(4); recorded.clear()
        compose.runOnIdle { expanded = true }; record(40)
        foreground().zipWithNext().forEachIndexed { i, (a, b) -> assertTrue("foreground jump at $i: $a -> $b", abs(b - a) <= .4f) }
    }
}
