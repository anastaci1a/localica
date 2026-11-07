package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
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

    // Exercise the complete player hit-test stack: standalone LyricsLines and semantic
    // click/scroll actions bypass the invisible artwork node that caused #632.
    private fun withNativeLyrics(block: (MutableState<PlaybackState>, MutableList<Long>, () -> Int) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File.createTempFile("lyrics-input-", ".mp3", instrumentation.targetContext.cacheDir)
        copyTestAsset(instrumentation.context.assets, "tone.mp3", file)
        io.github.anastaci1a.localica.library.ContainerTags.configure()
        org.jaudiotagger.audio.AudioFileIO.read(file).apply {
            tag = org.jaudiotagger.tag.id3.ID3v24Tag().apply {
                setField(org.jaudiotagger.tag.FieldKey.LYRICS,
                    (0..59).joinToString("\n") { "[00:${it.toString().padStart(2, '0')}.00]Input line $it" })
            }
            commit()
        }
        val track = Track(android.net.Uri.fromFile(file).toString(), "Input probe", fileName = file.name, relativePath = file.name)
        val state = mutableStateOf(PlaybackState(connected = true, trackId = track.id, trackUri = track.uri,
            title = track.title, positionMs = 15000, durationMs = 60000))
        val sought = mutableListOf<Long>()
        var escaped = 0
        try {
            compose.setContent { LocalicaTheme { androidx.compose.foundation.layout.BoxWithConstraints {
                PlayerSurface(state.value, true, { escaped++ },
                    InterfaceSettings(reducedMotion = true, artworkLayout = ArtworkLayout.FullBackground),
                    remember { androidx.compose.animation.core.Animatable(1f) }, {}, { sought += it }, {}, {},
                    { escaped++ }, {}, lyricsOpen = true, track = track,
                    // A real player has queue navigation available even while artwork gestures are disabled.
                    onArtworkVisit = { _, result -> result(true) })
            } } }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Input line 15").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            block(state, sought) { escaped }
        } finally { file.delete() }
    }

    private fun nativeLyricsOffset() = compose.onNodeWithTag("lyric-lines").fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun fullPlayerLyricsAcceptNativeScrollWithoutOpeningQueue() = withNativeLyrics { _, _, escaped ->
        val before = nativeLyricsOffset()
        compose.onNodeWithTag("lyric-lines").performTouchInput { swipeUp() }
        assertTrue("A native swipe must move the real lyric list", nativeLyricsOffset() > before + 1f)
        assertEquals("Lyrics consume the gesture before parent navigation", 0, escaped())
    }

    @Test fun fullPlayerLyricsAcceptNativeTapToSeek() = withNativeLyrics { _, sought, escaped ->
        compose.onNodeWithText("Input line 16").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(16000L), sought); assertEquals(0, escaped()) }
    }

    @Test fun fullPlayerLyricsDetachAndResumeFollowThroughNativeInput() = withNativeLyrics { state, _, escaped ->
        compose.onNodeWithTag("lyric-lines").performTouchInput { swipeUp() }
        compose.onNodeWithContentDescription("Return to current line").assertIsDisplayed()
        val detached = nativeLyricsOffset()
        compose.runOnIdle { state.value = state.value.copy(positionMs = 45000) }
        assertEquals("Playback must not steal a manually detached viewport", detached, nativeLyricsOffset(), .01f)
        compose.onNodeWithContentDescription("Return to current line").performTouchInput { click() }
        compose.onNodeWithText("Input line 45").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription("Return to current line").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(positionMs = 48000) }
        compose.onNodeWithText("Input line 48").assertIsDisplayed().assertIsSelected()
        assertEquals(0, escaped())
    }
    @Test fun missingLyricsCanBeOpenedAndOtherArtworkActionsStillPersist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        val emptyFixture = NoLyricsFixture("Song")
        val track = emptyFixture.track
        val queue = QueueEdits.create(listOf(track), 0, "Queue")
        try {
            compose.setContent { LocalicaTheme { LocalicaScreen(LibraryUiState(loading = false),
                PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title),
                {}, {}, {}, {}, {}, {}, queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)) } }
            compose.onNodeWithText("Song").performClick()
            compose.onNodeWithContentDescription("Show lyrics").assertIsDisplayed()
            compose.onNodeWithTag("player-artwork").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("empty-lyrics").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("lyrics-panel").assertIsDisplayed()
            compose.onNodeWithContentDescription("Hide lyrics").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("lyrics-panel").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
            compose.onNodeWithTag("player-artwork").performTouchInput { longClick() }
            compose.onNodeWithText("Configure tap action").performClick()
            compose.onNodeWithText("View artwork").performClick()
            assertEquals(ArtworkAction.View, InterfacePreferences(context).read().artworkTap)
            compose.onNodeWithTag("player-artwork").performClick()
            compose.onNodeWithContentDescription("Close artwork").assertIsDisplayed()
            pressSystemBack()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Close artwork").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
        } finally { emptyFixture.close(); context.getSharedPreferences("interface", 0).edit().clear().commit() }
    }
    @Test fun lyricsRemainOpenAcrossSongsWithAndWithoutSources() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        val file = java.io.File(context.cacheDir, "lyrics-interaction.opus")
        instrumentation.context.assets.open("lyrics.opus").use { input -> file.outputStream().use(input::copyTo) }
        val withLyrics = Track(android.net.Uri.fromFile(file).toString(), "With lyrics", fileName = file.name, relativePath = file.name)
        val second = withLyrics.copy(id = "second-lyric-song", title = "Second with lyrics")
        val emptyFixture = NoLyricsFixture("Without lyrics")
        val without = emptyFixture.track
        var track by mutableStateOf(withLyrics)
        try {
            compose.setContent { LocalicaTheme {
                val queue = remember(track) { QueueEdits.create(listOf(track), 0, "Lyrics queue") }
                LocalicaScreen(LibraryUiState(loading = false),
                    PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title),
                    {}, {}, {}, {}, {}, {}, queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false))
            } }
            compose.onNodeWithText("With lyrics").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Show lyrics").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription("Show lyrics").onFirst().performClick()
            compose.onNodeWithTag("lyrics-panel").assertIsDisplayed()
            compose.runOnIdle { track = second }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Lyrics sources").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("lyrics-panel").assertIsDisplayed()
            pressSystemBack()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("lyrics-panel").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("Show lyrics").onFirst().performClick()
            compose.runOnIdle { track = without }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("empty-lyrics").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("lyrics-panel").assertIsDisplayed()
            compose.onNodeWithContentDescription("Hide lyrics").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("lyrics-panel").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Show lyrics").assertIsDisplayed()
        } finally { emptyFixture.close(); file.delete(); context.getSharedPreferences("interface", 0).edit().clear().commit() }
    }
    @Test fun timedLinesFollowPlaybackAndCanSeekAfterManualScroll() {
        val document = LrcParser.parse((0..25).joinToString("\n") { "[00:${it.toString().padStart(2, '0')}]Line $it" })
        var sought: Long? = null
        compose.setContent { LocalicaTheme { LyricsLines(document, 15000, true, { sought = it }, Modifier.height(300.dp)) } }
        compose.onNodeWithText("Line 15").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("lyric-lines").performTouchInput { swipeDown() }
        compose.onNodeWithContentDescription("Return to current line").assertIsDisplayed().performClick()
        compose.onNodeWithText("Line 15").performClick()
        assertEquals(15000L, sought)
    }
}
