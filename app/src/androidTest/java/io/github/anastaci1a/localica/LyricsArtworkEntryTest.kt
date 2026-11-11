package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import java.io.File
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The artwork enters (and leaves) the lyrics screen from the lyrics layout's own centred band (#537). */
class LyricsArtworkEntryTest {
    @get:Rule val compose = createComposeRule()
    private val state = PlaybackState(connected = true, title = "Evening", artist = "Artist", durationMs = 120000)
    private fun visual(color: Int) = PlayerVisual(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
    }.asImageBitmap(), PlayerPalette(Color.Black, Color.Black, Color.Black))
    private val red = visual(android.graphics.Color.RED)
    private val white = visual(android.graphics.Color.WHITE)
    private var visual by mutableStateOf(red)

    private val progress = Animatable(0f)
    private var lyricsMode by mutableStateOf(true)
    private var layoutMode by mutableStateOf(ArtworkLayout.FullBackground)
    private lateinit var scope: CoroutineScope
    private var density = 1f
    private var statusInset = 0f
    private var clock: UiClock? = null
    private val joins = JoinRecorder("player-lyrics")

    private fun setPlayer(track: io.github.anastaci1a.localica.library.Track? = null) {
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            density = LocalDensity.current.density
            statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
            PlayerSurface(if (track == null) state else state.copy(trackId = track.id, trackUri = track.uri), false, {},
                InterfaceSettings(artworkLayout = layoutMode), progress, {}, {}, {}, {}, {}, {},
                lyricsOpen = lyricsMode, track = track, visual = visual) {
                val owner = LocalClockOwner.current
                SideEffect { clock = owner; owner?.events = joins }
            }
        } } }
    }

    private fun snap(p: Float) {
        compose.runOnIdle {
            val update = scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p) }
            assertTrue("Fixture progress is applied before measuring", update.isCompleted)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    // Unclipped: a full-background cover legitimately extends past the clipped player and screen edges.
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, true).getUnclippedBoundsInRoot().let {
        androidx.compose.ui.geometry.Rect(it.left.value * density, it.top.value * density, it.right.value * density, it.bottom.value * density)
    }
    /** Artwork top relative to the player surface, in pixels. */
    private fun artTop() = bounds("player-artwork").top - bounds("player-surface").top
    private fun artHeight() = bounds("player-artwork").height

    /** The #508 rule against the title as presented now: centred between the padded controls and the padded title. */
    private fun expectedTop(): Float {
        val surface = bounds("player-surface").top
        val controls = (statusInset + PlayerTopControlsHeight.value) * density + PlayerArtworkEdgePadding * density
        val titleTop = bounds("player-title").top - surface - PlayerArtworkEdgePadding * density
        return controls + (titleTop - controls - artHeight()) / 2f
    }

    private fun forEachLayout(block: (ArtworkLayout) -> Unit) {
        for (layout in ArtworkLayout.entries) {
            compose.runOnIdle { layoutMode = layout }
            block(layout)
        }
    }

    @Test fun artworkStartsCentredAgainstTheLyricsLayoutBandInBothLayouts() {
        setPlayer()
        forEachLayout { layout ->
            compose.runOnIdle { lyricsMode = false }
            snap(1f)
            val ordinary = artTop()
            assertEquals("Ordinary expanded placement keeps the #508 band in $layout", expectedTop(), ordinary, 1.5f)
            compose.runOnIdle { lyricsMode = true }
            compose.waitForIdle()
            snap(1f)
            val start = artTop()
            assertEquals("Lyrics-layout start is centred in the lyrics band in $layout", expectedTop(), start, 1.5f)
            assertTrue("The lyrics title sits lower, so the start differs from the ordinary position in $layout: $start vs $ordinary",
                start - ordinary > 4f)
        }
    }

    @Test fun minimizingFromLyricsIsContinuousAndEndsAtTheOrdinaryMiniPosition() {
        setPlayer()
        forEachLayout { layout ->
            compose.runOnIdle { lyricsMode = false }
            snap(0f)
            val mini = artTop()
            compose.runOnIdle { lyricsMode = true }
            compose.waitForIdle()
            snap(1f)
            val start = artTop()
            val tops = mutableListOf(start)
            val steps = 40
            for (i in 1..steps) { snap(1f - i / steps.toFloat()); tops += artTop() }
            assertEquals("No jump at the mini end in $layout", mini, tops.last(), 1.5f)
            val travel = kotlin.math.abs(start - mini)
            for (i in 1..steps) {
                val p = 1f - i / steps.toFloat()
                // The shared arc: linear blend between the endpoints with the existing 48 dp lift.
                val expected = mini + (start - mini) * p - 48f * density * p * (1f - p)
                assertEquals("Arc at p=$p in $layout", expected, tops[i], 2.5f)
                assertTrue("Continuous step at p=$p in $layout: ${tops[i - 1]} -> ${tops[i]} travel=$travel",
                    kotlin.math.abs(tops[i] - tops[i - 1]) <= travel / 6f + 4f * density)
            }
        }
    }

    @Test fun maximizingIntoLyricsRetracesTheMinimizingPositions() {
        setPlayer()
        forEachLayout { layout ->
            compose.runOnIdle { lyricsMode = true }
            snap(1f)
            val samples = (0..20).map { it / 20f }
            val down = samples.reversed().associateWith { p -> snap(p); artTop() }
            val up = samples.associateWith { p -> snap(p); artTop() }
            for ((p, top) in down) assertEquals("Reverse retraces at p=$p in $layout", top, up.getValue(p), 1f)
            snap(1f)
            assertEquals("The maximized end is the lyrics-layout position in $layout", expectedTop(), artTop(), 1.5f)
        }
    }

    @Test fun openingLyricsAtRestMovesTheArtworkContinuouslyToTheLyricsBand() {
        setPlayer()
        forEachLayout { layout ->
            compose.runOnIdle { lyricsMode = false }
            snap(1f)
            val ordinary = artTop()
            val closedTitle = bounds("player-title").top
            compose.runOnIdle { lyricsMode = true }
            compose.waitForIdle()
            val openArt = artTop()
            val titleTravel = bounds("player-title").top - closedTitle
            compose.runOnIdle { lyricsMode = false }
            compose.waitForIdle()
            joins.joins.clear()
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle { lyricsMode = true }
                val started = compose.mainClock.currentTime * 1_000_000
                val frames = mutableListOf<PhaseFrame>()
                repeat(60) {
                    compose.mainClock.advanceTimeByFrame()
                    val phase = (bounds("player-title").top - closedTitle) / titleTravel
                    frames += PhaseFrame(compose.mainClock.currentTime * 1_000_000, phase.toDouble())
                    // #508 geometry is linear in the same title phase. A fast legal frame
                    // can travel farther than one quarter of the old tween's distance.
                    assertEquals("Artwork follows the presented title in $layout at phase=$phase",
                        ordinary + (openArt - ordinary) * phase, artTop(), 1.5f)
                }
                assertTrue("The lyrics Engine records its motion joins", joins.joins.isNotEmpty())
                verifyReleaseLaw(LyricsLaw, "Artwork/title opening in $layout", frames, joins.joins,
                    fallbackJoin(started, 0.0, 0.0, true),
                    LyricsLaw.objectTime(titleTravel.toDouble(), requireNotNull(clock).context!!.fullPlayerReferenceTravelPx.toDouble()))
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                val end = artTop()
                assertEquals("Opening ends at the lyrics-layout band in $layout", expectedTop(), end, 1.5f)
                assertTrue("The artwork actually moved in $layout", end - ordinary > 4f)
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test fun missingLyricsIconDrawsUnderTheArtworkWhileMinimizingFromLyrics() {
        NoLyricsFixture("Missing").use { fixture ->
            setPlayer(fixture.track)
            assertLyricsUnderArtwork("empty-lyrics")
        }
    }

    @Test fun lyricsTextDrawsUnderTheArtworkWhileMinimizingFromLyrics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("lyrics-artwork-", ".opus", instrumentation.targetContext.cacheDir)
        try {
            copyTestAsset(instrumentation.context.assets, "lyrics.opus", file)
            val track = Track(android.net.Uri.fromFile(file).toString(), "Lyrics", fileName = file.name, relativePath = file.name)
            setPlayer(track)
            assertLyricsUnderArtwork("lyric-lines")
        } finally { file.delete() }
    }

    /** A cover above glyphs adds the same green to ink and adjacent blank pixels when red becomes white.
     * A cover underneath adds less green to ink, because the glyph masks the artwork. This paired
     * measurement includes the actual feather, shading and opacity rather than assuming their values. */
    private fun assertLyricsUnderArtwork(tag: String) {
        // #678 instrumentation (log only).
        val methodStart = System.nanoTime()
        var captureNs = 0L; var pngNs = 0L; var boundsNs = 0L; var loopNs = 0L
        snap(1f)
        compose.waitUntil(10000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag(tag, true).fetchSemanticsNodes().isNotEmpty()
        }
        val readyMs = (System.nanoTime() - methodStart) / 1_000_000
        forEachLayout { layout ->
            for (p in listOf(.88f, .91f, .94f)) {
                snap(p)
                fun capture(frame: PlayerVisual, name: String): androidx.compose.ui.graphics.PixelMap {
                    val t = System.nanoTime()
                    compose.runOnIdle { visual = frame }
                    compose.waitForIdle()
                    val capture = compose.onNodeWithTag("player-surface").captureToImage()
                    captureNs += System.nanoTime() - t
                    val pngStart = System.nanoTime()
                    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    if (output != null) {
                        val dir = File(output, "lyrics-artwork-entry").apply { mkdirs() }
                        File(dir, "$tag-$layout-$p-$name.png").outputStream().use {
                            capture.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    pngNs += System.nanoTime() - pngStart
                    return capture.toPixelMap()
                }
                val redPixels = capture(red, "red")
                val whitePixels = capture(white, "white")
                val boundsStart = System.nanoTime()
                val surface = bounds("player-surface")
                val content = bounds(tag)
                val cover = bounds("player-artwork")
                val viewport = bounds("lyrics-panel")
                boundsNs += System.nanoTime() - boundsStart
                val loopStart = System.nanoTime()
                // The middle of the cover has a flat horizontal feather; compare ink and blank on the same row.
                val left = maxOf(content.left, cover.left + cover.width * .3f, viewport.left)
                val right = minOf(content.right, cover.right - cover.width * .3f, viewport.right)
                val top = maxOf(content.top, cover.top, viewport.top)
                val bottom = minOf(content.bottom, cover.bottom, viewport.bottom)
                var underError = 0f; var overError = 0f; var rows = 0
                for (y in (top - surface.top).toInt() until (bottom - surface.top).toInt()) {
                    if (y !in 0 until redPixels.height) continue
                    var inkX = -1; var blankX = -1; var high = 0f; var low = 1f
                    for (x in (left - surface.left).toInt() until (right - surface.left).toInt()) {
                        if (x !in 0 until redPixels.width) continue
                        val green = redPixels[x, y].green
                        if (green > high) { high = green; inkX = x }
                        if (green < low) { low = green; blankX = x }
                    }
                    if (inkX < 0 || blankX < 0 || high - low < .04f) continue
                    val blankGain = whitePixels[blankX, y].green - low
                    val inkGain = whitePixels[inkX, y].green - high
                    if (blankGain < .025f) continue
                    // Estimate the glyph's opacity from its contrast over the red cover's blank background.
                    val glyphOpacity = ((high - low) / (1f - low)).coerceIn(0f, 1f)
                    underError += kotlin.math.abs(inkGain - blankGain)
                    overError += kotlin.math.abs(inkGain - blankGain * (1f - glyphOpacity))
                    rows++
                }
                loopNs += System.nanoTime() - loopStart
                val message = "$tag $layout p=$p rows=$rows underError=$underError overError=$overError " +
                    "content=$content cover=$cover"
                println("LyricsArtworkZOrder $message")
                android.util.Log.i("Trim678", "LyricsArtworkEntryTest $message")
                assertTrue("Matched frames must contain visible ink and cover: $message", rows >= 5)
                assertTrue("Lyrics draw under the artwork: $message", underError < overError)
            }
        }
        android.util.Log.i("Trim678", "LyricsArtworkEntryTest $tag totalMs=${(System.nanoTime() - methodStart) / 1_000_000} readyMs=$readyMs " +
            "captureMs=${captureNs / 1_000_000} pngMs=${pngNs / 1_000_000} boundsMs=${boundsNs / 1_000_000} loopMs=${loopNs / 1_000_000}")
    }
}
