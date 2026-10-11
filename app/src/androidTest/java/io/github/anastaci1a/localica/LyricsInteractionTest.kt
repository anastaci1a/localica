package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.lyrics.LrcParser
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LyricsInteractionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun artworkGesturesPersistAndBackClosesLyricsBeforePlayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        val track = Track("file:///missing.mp3", "Song", fileName = "missing.mp3", relativePath = "missing.mp3")
        val queue = QueueEdits.create(listOf(track), 0, "Queue")
        try {
            compose.setContent { LocalicaTheme { LocalicaScreen(LibraryUiState(loading = false),
                PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title),
                {}, {}, {}, {}, {}, {}, queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)) } }
            compose.onNodeWithText("Song").performClick()
            compose.onNodeWithTag("player-artwork").performClick()
            compose.onNodeWithTag("lyrics-panel").assertIsDisplayed()
            pressSystemBack()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("lyrics-panel").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("lyrics-panel").assertDoesNotExist()
            compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
            compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
            compose.onNodeWithText("Configure tap action").performClick()
            compose.onNodeWithText("View artwork").performClick()
            assertEquals(ArtworkAction.View, InterfacePreferences(context).read().artworkTap)
            compose.onNodeWithTag("player-artwork").performClick()
            compose.onNodeWithText("Close artwork").assertIsDisplayed()
            pressSystemBack()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Close artwork").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
        } finally { context.getSharedPreferences("interface", 0).edit().clear().commit() }
    }
    @Test fun timedLinesFollowPlaybackAndCanSeekAfterManualScroll() {
        val document = LrcParser.parse((0..25).joinToString("\n") { "[00:${it.toString().padStart(2, '0')}]Line $it" })
        var sought: Long? = null
        compose.setContent { LocalicaTheme { LyricsLines(document, 15000, true, { sought = it }, Modifier.height(300.dp)) } }
        compose.onNodeWithText("Line 15").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("lyric-lines").performTouchInput { swipeDown() }
        compose.onNodeWithText("Return to current line").assertIsDisplayed().performClick()
        compose.onNodeWithText("Line 15").performClick()
        assertEquals(15000L, sought)
    }
}
