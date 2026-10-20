package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlayerExpansionMotionTest {
    @get:Rule val compose = createComposeRule()
    private val state = PlaybackState(connected = true, title = "Evening", artist = "Artist", durationMs = 120000)
    private val visual = PlayerVisual(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.RED)
    }.asImageBitmap(), PlayerPalette(Color(0xFF303040), Color(0xFF303040), Color(0xFF303040)))

    @Test fun heldExpansionAlreadyShowsRealLyricsInBothArtworkLayouts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("held-expansion-", ".opus", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("lyrics.opus").use { input -> file.outputStream().use(input::copyTo) }
            exerciseHeldLyrics(Track(Uri.fromFile(file).toString(), "Lyrics", fileName = file.name, relativePath = file.name), "lyric-lines")
        } finally { file.delete() }
    }

    @Test fun heldExpansionAlreadyShowsMissingLyricsInBothArtworkLayouts() {
        NoLyricsFixture("Missing").use { exerciseHeldLyrics(it.track, "empty-lyrics") }
    }

    private fun exerciseHeldLyrics(track: Track, contentTag: String) {
        val progress = Animatable(0f)
        var expanded by mutableStateOf(false)
        var layout by mutableStateOf(ArtworkLayout.FullBackground)
        lateinit var scope: CoroutineScope
        var fingerDown = false
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            PlayerSurface(state.copy(trackId = track.id, trackUri = track.uri), expanded, { expanded = it },
                InterfaceSettings(artworkLayout = layout), progress, {}, {}, {}, {}, {}, {},
                lyricsOpen = true, track = track, visual = visual) { Text("Album-only action") }
        } } }
        try {
            for (artworkLayout in ArtworkLayout.entries) {
                compose.mainClock.autoAdvance = true
                compose.runOnIdle { layout = artworkLayout; expanded = false; scope.launch { progress.snapTo(0f) } }
                compose.waitForIdle()
                val mini = compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
                compose.mainClock.autoAdvance = false
                val start = Offset(mini.left + mini.width / 2, mini.center.y)
                // Keep the same contact down through full expansion and its reversal.
                fingerDown = true
                compose.onRoot().performTouchInput {
                    down(start)
                    repeat(40) { index -> moveTo(Offset(start.x, start.y * (1f - (index + 1) / 40f)), delayMillis = 32) }
                }
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    assertEquals("Held endpoint is fully expanded in $artworkLayout", 1f, progress.value, .0001f)
                    assertFalse("No release event has changed expanded", expanded)
                }
                compose.waitUntil(10000) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.onAllNodesWithTag(contentTag, true).fetchSemanticsNodes().isNotEmpty()
                }
                // Let an asynchronously loaded document finish its own crossfade while still held.
                compose.mainClock.advanceTimeBy(400)
                val content = compose.onNodeWithTag(contentTag, true).captureToImage().toPixelMap()
                var brightest = 0f
                for (y in 0 until content.height step 2) for (x in 0 until content.width step 2)
                    brightest = maxOf(brightest, content[x, y].red)
                assertTrue("Lyrics/empty ink is physically visible before release: $brightest", brightest > .28f)
                val player = compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
                val title = compose.onNodeWithTag("player-title").fetchSemanticsNode().boundsInRoot
                val seek = compose.onNodeWithTag("player-seek-row").fetchSemanticsNode().boundsInRoot
                val transport = compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
                val panel = compose.onNodeWithTag("lyrics-panel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                compose.onNodeWithTag(contentTag, true).assertIsDisplayed()
                compose.onNodeWithText("Album-only action", useUnmergedTree = true).assertDoesNotExist()
                // Inspect actual player pixels, not a potentially transparent cover layer capture.
                val pixels = compose.onNodeWithTag("player-surface").captureToImage().toPixelMap()
                val cover = compose.onNodeWithTag("player-artwork", true).fetchSemanticsNode().boundsInRoot
                for (fy in listOf(.3f, .5f, .7f)) for (fx in listOf(.3f, .5f, .7f)) {
                    val x = (cover.left - player.left + cover.width * fx).toInt().coerceIn(0, pixels.width - 1)
                    val y = (cover.top - player.top + cover.height * fy).toInt().coerceIn(0, pixels.height - 1)
                    val color = pixels[x, y]
                    assertTrue("Red cover is gone from the player before release: $color", color.red < color.blue + .1f)
                }

                compose.onRoot().performTouchInput { moveBy(Offset(0f, mini.top * .14f), delayMillis = 160) }
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithText("Album-only action", useUnmergedTree = true).assertDoesNotExist()
                compose.onRoot().performTouchInput { moveTo(Offset(start.x, 0f), delayMillis = 160) }
                compose.mainClock.advanceTimeByFrame()
                assertEquals("Held reversal restores the exact seek target", seek,
                    compose.onNodeWithTag("player-seek-row").fetchSemanticsNode().boundsInRoot)
                compose.onRoot().performTouchInput { advanceEventTime(200); up() }
                fingerDown = false
                compose.mainClock.autoAdvance = true
                compose.waitForIdle()
                assertTrue(expanded)
                assertEquals("Release cannot change lyrics geometry", panel, compose.onNodeWithTag("lyrics-panel").fetchSemanticsNode().boundsInRoot)
                assertEquals(title, compose.onNodeWithTag("player-title").fetchSemanticsNode().boundsInRoot)
                assertEquals(seek, compose.onNodeWithTag("player-seek-row").fetchSemanticsNode().boundsInRoot)
                assertEquals(transport, compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot)
                assertEquals(player, compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot)

                // Sample both directions, including the former low-opacity action ghost window.
                for (p in listOf(.99f, .96f, .92f, .86f, .8f, .78f, .5f, .78f, .8f, .86f, .92f, .96f, .99f, 1f)) {
                    compose.runOnIdle { scope.launch { progress.snapTo(p) } }
                    compose.onNodeWithTag("player-actions-row", true).assertDoesNotExist()
                    compose.onNodeWithText("Album-only action", useUnmergedTree = true).assertDoesNotExist()
                    if (p <= .78f) compose.onNodeWithTag("lyrics-panel").assertIsNotDisplayed()
                    else compose.onNodeWithTag(contentTag, true).assertExists()
                }
            }
        } finally {
            if (fingerDown) runCatching { compose.onRoot().performTouchInput { cancel() } }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun artworkSeekAndActionsHaveSeparateScreenSpaceMotionBetweenTitleAndTransport() {
        val progress = Animatable(0f)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            PlayerSurface(state, false, {}, InterfaceSettings(), progress, {}, {}, {}, {}, {}, {}, visual = visual) {
                Text("Album-only action")
            }
        } } }
        val tags = listOf("player-title", "player-seek-row", "player-actions-row")
        fun tops() = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top } +
            compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { scope.launch { progress.snapTo(1f) } }
        val full = tops()
        val samples = mutableMapOf<Float, List<Float>>()
        for (p in listOf(.98f, .95f, .9f, .85f, .9f, .95f, .98f, 1f)) {
            compose.runOnIdle { scope.launch { progress.snapTo(p) } }
            val current = tops()
            if (p < 1f) {
                val movement = current.zip(full) { now, target -> now - target }
                assertTrue("Title < seek < actions < transport at $p: $movement",
                    movement.zipWithNext().all { (slower, faster) -> slower < faster })
            } else assertEquals(full, current)
            samples[p]?.let { assertEquals("Reversal uses the same displayed trajectory at $p", it, current) }
            samples[p] = current
        }
    }
}
