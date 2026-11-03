package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
}
