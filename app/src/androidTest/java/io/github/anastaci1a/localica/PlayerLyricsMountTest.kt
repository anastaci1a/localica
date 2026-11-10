package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.lyrics.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The lyrics box stays mounted to the moving player, and its current line keeps its place in it (#509). */
class PlayerLyricsMountTest {
    @get:Rule val compose = createComposeRule()
    private val track = Track("file:///missing.mp3", "Morning light", artist = "Localica", fileName = "morning.mp3", relativePath = "morning.mp3")
    private val playback = PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title, artist = track.artist, durationMs = 125000)

    @Test fun lyricsBoxEdgesFollowThePlayerWhileMinimizing() {
        val progress = Animatable(1f)
        val expanded = true
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            PlayerSurface(playback, expanded, {}, InterfaceSettings(), progress, {}, {}, {}, {}, {}, {},
                lyricsOpen = true, track = track)
        } } }
        compose.waitForIdle()
        fun sample(): Triple<Float, Float, Float> {
            val player = rect("player-surface")
            val lyrics = rect("lyrics-panel")
            val title = rect("player-title")
            return Triple(player.top, lyrics.top - player.top, lyrics.bottom - player.top).also {
                assertEquals("The lower edge sits just above the presented title p=${progress.value} player=$player lyrics=$lyrics title=$title", title.top - player.top - 4f * density(),
                    lyrics.bottom - player.top, 2f)
            }
        }
        compose.onNodeWithTag("lyrics-panel", useUnmergedTree = true).assertIsDisplayed()
        val expandedTop = sample()
        val topOffset = expandedTop.second
        val playerTops = mutableListOf(expandedTop.first)
        val heights = mutableListOf(expandedTop.third - expandedTop.second)
        // Held positions while the lyrics are still visible (they fade out below .78).
        for (p in listOf(.95f, .9f, .85f, .8f, .85f, 1f)) {
            compose.runOnIdle { scope.launch { progress.snapTo(p) } }
            compose.waitForIdle()
            val (playerTop, top, bottom) = sample()
            assertEquals("The upper edge keeps its offset inside the player at p=$p", topOffset, top, 1.5f)
            playerTops += playerTop; heights += bottom - top
        }
        assertTrue("The player must actually move on screen: $playerTops", playerTops.max() > playerTops.first() + 20f)
        assertTrue("The box shrinks with the title: $heights", heights.min() < heights.first() - 10f)
        assertEquals("Reversal restores the exact box", heights.first(), heights.last(), 1.5f)
    }

    private fun density() = compose.density.density
    private fun rect(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun currentLyricAnchorKeepsItsRelativePositionInTheMovingPlayer() {
        var p by mutableFloatStateOf(1f)
        val metadata = 348f
        val document = LrcParser.parse((0..45).joinToString("\n") { "[00:%02d.00]line %02d".format(it, it) })
        compose.setContent { LocalicaTheme { Box(Modifier.size(320.dp, 700.dp)) {
            val surface = 560f * (1f - p)
            val viewport = playerLyricsViewport(7f + (metadata - 7f) * p, 84f)
            Box(Modifier.offset(y = surface.dp).fillMaxWidth().height(560.dp)) {
                LyricsPanel(null, 30_000L, true, {}, Modifier.offset(16.dp, viewport.top.dp).width(288.dp)
                    .height(viewport.height.dp).clipToBounds(), contentHeight = 320.dp,
                    result = LyricsResult(listOf(LyricSource("fixture", "Fixture", document))))
            }
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("line 30").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        fun anchor() = compose.onNodeWithText("line 30").getUnclippedBoundsInRoot().let { (it.top + it.bottom).value / 2f }
        val reference = anchor()
        for (progress in listOf(.9f, .8f, .6f, .8f, 1f)) {
            compose.runOnIdle { p = progress }
            compose.waitForIdle()
            val surface = 560f * (1f - progress)
            val panel = compose.onNodeWithTag("lyrics-panel", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals("Top edge at p=$progress", surface + 84f, panel.top.value, 1f)
            assertEquals("Bottom edge at p=$progress", surface + 7f + (metadata - 7f) * progress - 4f, panel.bottom.value, 1f)
            assertEquals("The anchor keeps its place in the player at p=$progress", reference, anchor() - surface, 1f)
        }
    }

    /** Samples [anchor] against the clip's midpoint over a minimize from lyrics; returns (anchor, midpoint) per held position. */
    private fun anchorsWhileMinimizing(playback: PlaybackState, track: Track, anchorTag: String, settled: String): List<Pair<Float, Float>> {
        val progress = Animatable(1f)
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            PlayerSurface(playback, true, {}, InterfaceSettings(reducedMotion = true), progress, {}, {}, {}, {}, {}, {},
                lyricsOpen = true, track = track)
        } } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag(settled, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        fun sample(): Pair<Float, Float> {
            val panel = rect("lyrics-panel")
            val anchor = if (anchorTag == "lyric-active") compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and
                hasAnyAncestor(hasTestTag("lyric-lines")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y
                else rect(anchorTag).center.y
            return anchor to (panel.top + panel.bottom) / 2f
        }
        val samples = mutableListOf(sample())
        for (p in listOf(.97f, .94f, .91f, .88f, .85f, .82f, .8f)) {
            compose.runOnIdle { scope.launch { progress.snapTo(p) } }
            compose.waitForIdle()
            samples += sample()
        }
        compose.runOnIdle { scope.launch { progress.snapTo(1f) } }
        compose.waitForIdle()
        assertEquals("Returning to rest restores the anchor", samples.first().first, sample().first, 1f)
        return samples
    }

    private fun assertTracksMidpoint(samples: List<Pair<Float, Float>>) {
        val (anchor0, mid0) = samples.first()
        assertTrue("The visible midpoint must actually move: $samples", kotlin.math.abs(samples.last().second - mid0) > 20f)
        samples.zipWithNext().forEachIndexed { index, (a, b) ->
            val step = b.first - a.first
            assertEquals("Anchor step $index follows the midpoint step: $samples", b.second - a.second, step, 1f)
            assertEquals("Anchor offset from the midpoint is constant at sample ${index + 1}: $samples",
                anchor0 - mid0, b.first - b.second, 1f)
            assertTrue("Anchor moves monotonically with the midpoint: $samples", step * (b.second - a.second) >= -1f)
        }
    }

    @Test fun currentLyricFollowsTheVisibleMidpointWhileMinimizing() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.cacheDir, "lyric-midpoint.opus")
        instrumentation.context.assets.open("lyrics.opus").use { input -> file.outputStream().use(input::copyTo) }
        val lyric = Track(android.net.Uri.fromFile(file).toString(), "Lyrics", fileName = file.name, relativePath = file.name)
        try {
            val document = kotlinx.coroutines.runBlocking { LyricsRepository(instrumentation.targetContext).load(lyric, 0) }
                .sources.first().document
            assertTrue("The fixture must be synced so exactly one line is current", document.synced)
            val line = document.lines.getOrElse(2) { document.lines.last() }
            val state = PlaybackState(connected = true, trackId = lyric.id, trackUri = lyric.uri, title = lyric.title,
                positionMs = line.timeMs!!, durationMs = 125000)
            val samples = anchorsWhileMinimizing(state, lyric, "lyric-active", "lyric-lines")
            // Rest: the current line is centred in the lyric-lines container (#717).
            val panel = rect("lyrics-panel")
            val current = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and
                hasAnyAncestor(hasTestTag("lyric-lines")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val container = compose.onNodeWithTag("lyric-lines", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val rest = compose.onNodeWithTag("lyrics-viewport", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("The current line rests centred in the container", container.center.y, current.center.y, 2f)
            assertEquals("The viewport is the clip", panel, rest)
            assertTracksMidpoint(samples)
        } finally { file.delete() }
    }

    @Test fun emptyLyricsIconFollowsTheVisibleMidpointWhileMinimizing() {
        NoLyricsFixture("Missing").use { fixture ->
            val track = fixture.track
            val state = PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title, durationMs = 125000)
            val samples = anchorsWhileMinimizing(state, track, "empty-lyrics", "empty-lyrics")
            // Rest: the icon is centred in the viewport.
            assertEquals("The icon rests centred", samples.first().second, samples.first().first, 1f)
            assertTracksMidpoint(samples)
        }
    }
}
