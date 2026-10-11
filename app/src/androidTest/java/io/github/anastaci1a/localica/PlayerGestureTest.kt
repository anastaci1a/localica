package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerGestureTest {
    @get:Rule val compose = createComposeRule()
    @Test fun slowDragMovesTheSameArtworkThenExpandedSwipeOpensQueue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        val track = Track("missing", "Evening", artist = "Artist", fileName = "song.mp3", relativePath = "song.mp3")
        val queue = QueueEdits.create(listOf(track) + (1..40).map { track.copy(id = "track:$it", uri = "missing:$it", title = "Queue song $it") }, 0, "Evening queue")
        compose.setContent { LocalicaTheme { LocalicaScreen(
            LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
            PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title),
            {}, {}, {}, {}, {}, {}, queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)) } }
        val art = compose.onNodeWithTag("player-artwork", useUnmergedTree = true)
        art.assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        compose.onNodeWithTag("player-title", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        val mini = art.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { down(mini.center); repeat(24) { moveBy(Offset(0f, -10f), delayMillis = 40) } }
        compose.mainClock.advanceTimeBy(32)
        val middle = art.fetchSemanticsNode().boundsInRoot
        assertTrue("Artwork must follow a slow drag: $mini -> $middle", middle.top < mini.top && middle.width > mini.width)
        compose.onRoot().performTouchInput { advanceEventTime(600); up() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
        val full = art.fetchSemanticsNode().boundsInRoot
        assertTrue(full.top < middle.top && full.width > middle.width)
        compose.onNodeWithTag("player-surface").performTouchInput {
            swipe(Offset(8f, centerY), Offset(8f, centerY - 240f), durationMillis = 1200)
        }
        compose.onNodeWithContentDescription("Queue actions").assertIsDisplayed()
        compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Queue song 30"))
        compose.onNodeWithTag("browser-list").performTouchInput {
            swipe(Offset(centerX, height * .35f), Offset(centerX, height * .55f), durationMillis = 700)
        }
        compose.onNodeWithContentDescription("Close queue").assertIsDisplayed()
        compose.onNodeWithTag("browser-list").performScrollToIndex(0)
        // A slow pull from the content (not the handle) hands off at the list's top.
        compose.onNodeWithTag("browser-list").performTouchInput {
            swipe(Offset(centerX, height * .35f), Offset(centerX, height * .75f), durationMillis = 1000)
        }
        compose.onNodeWithContentDescription("Close queue").assertDoesNotExist()
        // Hold a collapsing gesture; browsing must already be light before releasing.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("player-surface").performTouchInput {
            down(Offset(8f, centerY)); repeat(20) { moveBy(Offset(0f, 8f), delayMillis = 30) }
        }
        compose.mainClock.advanceTimeBy(32)
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val heldBounds = compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
        val exposedHeight = heldBounds.top - rootBounds.top
        assertTrue("The held drag must expose browsing: $heldBounds", exposedHeight > 16f)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        // Touch slop varies with density; sample above the actual moving surface.
        val exposed = pixels[4, (exposedHeight / 2).toInt()]
        assertTrue("Exposed browsing background must be light during drag: $exposed", exposed.red > .8f && exposed.green > .8f && exposed.blue > .8f)
        compose.onNodeWithTag("player-surface").performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("Collapse player").performClick()
        assertEquals(mini, art.fetchSemanticsNode().boundsInRoot)
    }
}
