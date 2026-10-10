package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Offset
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
        val queue = QueueEdits.create(listOf(track), 0, "Evening queue")
        compose.setContent { LocalicaTheme { LocalicaScreen(
            LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
            PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title),
            {}, {}, {}, {}, {}, {}, queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)) } }
        val art = compose.onNodeWithTag("player-artwork", useUnmergedTree = true)
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
        compose.onNodeWithContentDescription("Close queue").performClick()
        compose.onNodeWithContentDescription("Collapse player").performClick()
        assertEquals(mini, art.fetchSemanticsNode().boundsInRoot)
    }
}
