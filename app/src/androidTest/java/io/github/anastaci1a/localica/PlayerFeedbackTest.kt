package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.CoverProvider
import io.github.anastaci1a.localica.playback.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pausedTriangleHasNoInternalSeamAtMiniAndFullPlayerSizes() {
        var playing by mutableStateOf(true)
        compose.setContent { LocalicaTheme {
            Row {
                listOf(24, 40).forEach { side ->
                    TransportButton(TransportGlyph.PlayPause, playing, "Transport $side", {},
                        Modifier.size(side.dp).background(Color.White).testTag("triangle-$side"),
                        side.dp, Color.Black, true, false)
                }
            }
        } }
        compose.runOnIdle { playing = false }
        compose.waitForIdle()
        for (side in listOf(24, 40)) {
            val pixels = compose.onNodeWithTag("triangle-$side").captureToImage().toPixelMap()
            // Interior crosses the shared edge of the old two-polygon triangle.
            for (y in (pixels.height * .4f).toInt()..(pixels.height * .6f).toInt())
                for (x in (pixels.width * .36f).toInt()..(pixels.width * .57f).toInt())
                    assertTrue("Solid paused triangle at $side dp, pixel $x,$y", pixels[x, y].red < .02f)
        }
    }

    @Test fun artworkRemainsVisibleUntilReplacementIsReadyAndObsoleteLoadsCannotWin() {
        val red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val blue = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val slow = CompletableDeferred<Bitmap?>()
        val next = CompletableDeferred<Bitmap?>()
        var uri by mutableStateOf("red")
        var ready: PlayerVisual? = null
        val provider = CoverProvider { _, uris, _ -> when (uris.firstOrNull()) {
            "red" -> red
            "slow" -> slow.await()
            "blue" -> next.await()
            else -> null
        } }
        compose.setContent { LocalicaTheme {
            val visual = rememberPlayerVisual(uri, provider)
            SideEffect { ready = visual }
            PlayerArtwork(visual, Modifier.size(160.dp).testTag("cover"), Corners.artwork, ContentScale.Fit, 450)
        } }
        compose.waitUntil(5000) { ready?.artwork != null }
        fun center(): Color = compose.onNodeWithTag("cover").captureToImage().let { it.toPixelMap()[it.width / 2, it.height / 2] }
        assertTrue(center().red > .95f)
        compose.runOnIdle { uri = "slow" }
        assertTrue("Slow cover reads retain the previous image", center().red > .95f)
        compose.runOnIdle { uri = "blue" }
        assertTrue("Rapid skips do not insert a blank frame", center().red > .95f)
        compose.mainClock.autoAdvance = false
        next.complete(blue)
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            (ready?.palette?.middle?.blue ?: 0f) > .5f
        }
        compose.mainClock.advanceTimeBy(225)
        val middle = center()
        assertTrue("Cover blending must not expose a dark gap: $middle", middle.red + middle.blue > .94f)
        compose.mainClock.autoAdvance = true
        assertTrue(center().blue > .95f)
        slow.complete(red)
        compose.waitForIdle()
        assertTrue("Canceled reads cannot overwrite the latest song", center().blue > .95f)
        compose.runOnIdle { uri = "no-cover" }
        compose.waitUntil(5000) { ready?.artwork == null }
        assertTrue("A genuinely missing cover eventually replaces the old artwork", center().blue < .95f)
    }

    @Test fun sharedArtworkLoadingStopsForMissingCoversAndNeverReusesAnotherRowsImage() {
        val loaded = CompletableDeferred<Bitmap?>()
        val missing = CompletableDeferred<Bitmap?>()
        val blue = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        var uri by mutableStateOf("first")
        val provider = CoverProvider { _, uris, _ -> if (uris.first() == "first") loaded.await() else missing.await() }
        compose.setContent { LocalicaTheme {
            MediaArtwork(listOf(uri), Modifier.size(120.dp).testTag("thumbnail"), provider)
        } }
        compose.onNodeWithTag("artwork-loading").assertIsDisplayed()
        loaded.complete(blue)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("artwork-loading").fetchSemanticsNodes().isEmpty() }
        fun center(): Color = compose.onNodeWithTag("thumbnail").captureToImage().let { it.toPixelMap()[it.width / 2, it.height / 2] }
        fun showsBlueCover(): Boolean = center().let { it.blue > .95f && it.red < .05f && it.green < .05f }
        assertTrue(showsBlueCover())
        compose.runOnIdle { uri = "missing" }
        compose.onNodeWithTag("artwork-loading").assertIsDisplayed()
        assertFalse("Recycled rows must not retain an unrelated cover", showsBlueCover())
        missing.complete(null)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("artwork-loading").fetchSemanticsNodes().isEmpty() }
        assertFalse(showsBlueCover())
    }

    @Test fun failuresUseDismissiblePlainLanguageDialogsWithoutRepeatingOnPositionUpdates() {
        var state by mutableStateOf(PlaybackState(connected = true, title = "Morning light", trackUri = "song:1", isBuffering = true))
        var retries = 0
        var skips = 0
        compose.setContent { LocalicaTheme { PlaybackFailureDialog(state, true, { retries++ }, { skips++ }) } }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(problem = PlaybackProblem.Unreadable, isBuffering = false) }
        compose.onNodeWithText("Couldn't play this song").assertIsDisplayed()
        compose.onNodeWithText("Morning light").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
        compose.runOnIdle { state = state.copy(positionMs = 200) }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(problem = null) }
        compose.runOnIdle { state = state.copy(problem = PlaybackProblem.Unsupported) }
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Skip song").performClick()
        assertEquals(1, skips)
        compose.runOnIdle { state = state.copy(connected = false, problem = PlaybackProblem.Connection) }
        compose.onNodeWithText("Playback disconnected").assertIsDisplayed()
        compose.onNodeWithText("Skip song").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }
}
