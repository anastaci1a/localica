package io.github.anastaci1a.localica

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

class PlayerLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val track = Track("file:///missing.mp3", "Morning light", artist = "Localica", fileName = "morning.mp3", relativePath = "morning.mp3")
    private val playback = PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title, artist = track.artist, durationMs = 125000)
    @Test fun floatingBarsAlignAndRemainSeparate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        val queue = QueueEdits.create(listOf(track), 0, "Morning")
        compose.setContent { LocalicaTheme { LocalicaScreen(LibraryUiState(loading = false), playback,
            {}, {}, {}, {}, {}, {}, queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)) } }
        val mini = compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("tab-pill").fetchSemanticsNode().boundsInRoot
        val utility = compose.onNodeWithTag("utility-pill").fetchSemanticsNode().boundsInRoot
        assertEquals(mini.left, tabs.left, 1f); assertEquals(mini.right, utility.right, 1f)
        assertEquals(tabs.top, utility.top, 1f); assertEquals(tabs.bottom, utility.bottom, 1f)
        assertTrue(mini.bottom < tabs.top); assertTrue(tabs.right < utility.left)
        assertEquals(tabs.top - mini.bottom, utility.left - tabs.right, 2f)
        compose.onNodeWithContentDescription("Previous").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next").assertIsDisplayed()
    }
    @Test fun artworkModesConvergeOnOneLyricsLayoutWithLowerControls() {
        var settings by mutableStateOf(InterfaceSettings())
        var lyrics by mutableStateOf(false)
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(playback, true, {}, settings, remember { Animatable(1f) }, {}, {}, {}, {}, {}, {},
                lyricsOpen = lyrics, onLyrics = { lyrics = it }, track = track, onSettings = { settings = it }) { Text("Extra player action") }
        } } }
        var lyricBounds: androidx.compose.ui.geometry.Rect? = null
        var transportBounds: androidx.compose.ui.geometry.Rect? = null
        for (layout in ArtworkLayout.entries) {
            compose.runOnIdle { settings = settings.copy(artworkLayout = layout); lyrics = false }
            val player = compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
            val cover = compose.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot
            if (layout == ArtworkLayout.FullBackground) assertEquals(player.width, cover.width, 2f)
            else assertTrue(cover.width < player.width)
            val before = compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("Show lyrics").performClick()
            val lines = compose.onNodeWithTag("lyrics-panel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val controls = compose.onNodeWithContentDescription("Play").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Lyrics should use the upper screen", lines.height > player.height * 0.35f)
            assertTrue("Controls ease down", controls.top > before.top)
            assertTrue("Lyrics stay above controls", lines.bottom < controls.top)
            compose.onNodeWithText("Lyrics", substring = false).assertDoesNotExist()
            compose.onNodeWithText("Extra player action").assertDoesNotExist()
            if (lyricBounds != null) { assertEquals(lyricBounds, lines); assertEquals(transportBounds, controls) }
            lyricBounds = lines; transportBounds = controls
        }
    }
    @Test fun artworkLayoutChoicePersists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        try {
            val selected = InterfaceSettings(artworkLayout = ArtworkLayout.Floating)
            preferences.write(selected)
            assertEquals(selected, InterfacePreferences(context).read())
        } finally { context.getSharedPreferences("interface", 0).edit().clear().commit() }
    }
}
