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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import java.io.File
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
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
    private val visual = PlayerVisual(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.RED)
    }.asImageBitmap(), PlayerPalette(Color.Black, Color.Black, Color.Black))

    private val progress = Animatable(0f)
    private var lyricsMode by mutableStateOf(true)
    private var layoutMode by mutableStateOf(ArtworkLayout.FullBackground)
    private lateinit var scope: CoroutineScope
    private var density = 1f
    private var statusInset = 0f

    private fun setPlayer(track: io.github.anastaci1a.localica.library.Track? = null) {
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            density = LocalDensity.current.density
            statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
            PlayerSurface(if (track == null) state else state.copy(trackId = track.id, trackUri = track.uri), false, {},
                InterfaceSettings(artworkLayout = layoutMode), progress, {}, {}, {}, {}, {}, {},
                lyricsOpen = lyricsMode, track = track, visual = visual)
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
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle { lyricsMode = true }
                val tops = mutableListOf(ordinary)
                repeat(60) { compose.mainClock.advanceTimeBy(16); tops += artTop() }
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                val end = artTop()
                assertEquals("Opening ends at the lyrics-layout band in $layout", expectedTop(), end, 1.5f)
                assertTrue("The artwork actually moved in $layout", end - ordinary > 4f)
                for (i in 1 until tops.size) assertTrue("No jump while opening in $layout at frame $i: ${tops[i - 1]} -> ${tops[i]}",
                    kotlin.math.abs(tops[i] - tops[i - 1]) <= (end - ordinary) / 4f + 2f)
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

    /** Green separates light lyric ink from the red cover; compare both possible compositing orders. */
    private fun assertLyricsUnderArtwork(tag: String) {
        snap(1f)
        compose.waitUntil(10000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag(tag, true).fetchSemanticsNodes().isNotEmpty()
        }
        forEachLayout { layout ->
            snap(1f)
            compose.waitForIdle()
            fun green(p: Float): Triple<Float, Float, String> {
                snap(p)
                val surface = bounds("player-surface")
                val capture = compose.onNodeWithTag("player-surface").captureToImage()
                val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                if (output != null) {
                    val dir = File(output, "lyrics-artwork-entry").apply { mkdirs() }
                    File(dir, "$tag-$layout-$p.png").outputStream().use {
                        capture.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                val image = capture.toPixelMap()
                val content = bounds(tag)
                val cover = bounds("player-artwork")
                val viewport = bounds("lyrics-panel")
                // Stable lyric content can extend beyond its shrinking clip; exclude metadata outside it.
                val left = maxOf(content.left, cover.left, viewport.left)
                val right = minOf(content.right, cover.right, viewport.right)
                val top = maxOf(content.top, cover.top, viewport.top)
                val bottom = minOf(content.bottom, cover.bottom, viewport.bottom)
                var high = 0f; var low = 1f
                for (y in (top - surface.top).toInt() until (bottom - surface.top).toInt())
                    for (x in (left - surface.left).toInt() until (right - surface.left).toInt()) {
                        if (x !in 0 until image.width || y !in 0 until image.height) continue
                        val g = image[x, y].green
                        high = maxOf(high, g); low = minOf(low, g)
                    }
                return Triple(high, low, "content=$content cover=$cover")
            }
            val (ink, backgroundAtRest, fullDetail) = green(1f)
            assertTrue("$tag has measurable contrast at rest in $layout: $ink $fullDetail",
                ink - backgroundAtRest > .1f)
            for (p in listOf(.88f, .91f, .94f)) {
                val (observed, floor, detail) = green(p)
                val coverAlpha = 1f - p
                val x = ((p - .78f) / .22f).coerceIn(0f, 1f)
                val lyricAlpha = x * x * (3f - 2f * x)
                val background = floor / (1f - coverAlpha)
                val under = (1f - coverAlpha) * (lyricAlpha * ink + (1f - lyricAlpha) * background)
                val over = lyricAlpha * ink + (1f - lyricAlpha) * (1f - coverAlpha) * background
                val message = "$tag $layout p=$p observed=$observed lyricsUnderArtwork=$under lyricsOverArtwork=$over " +
                    "ink=$ink floor=$floor $detail"
                println("LyricsArtworkZOrder $message")
                assertTrue("Lyrics draw under the artwork: $message",
                    kotlin.math.abs(observed - under) < kotlin.math.abs(observed - over))
            }
        }
    }
}
