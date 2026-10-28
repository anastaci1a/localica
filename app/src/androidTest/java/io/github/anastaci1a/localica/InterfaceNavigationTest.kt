package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test

class InterfaceNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val track = Track("missing", "Evening", artist = "Artist", albumArtist = "Album artist", album = "Seasons",
        fileName = "evening.mp3", relativePath = "evening.mp3", locations = listOf(TrackLocation("tree", "missing", "evening.mp3")))
    private val library = LibraryUiState(LibrarySnapshot(listOf(FolderSelection("tree", "Music")), listOf(track)), loading = false)
    private fun back() { pressSystemBack(); compose.waitForIdle() }
    @Before fun reset() { context.getSharedPreferences("interface", 0).edit().clear().commit() }
    @After fun cleanup() { reset() }

    @Test fun playlistDetailOwnsItsOnlyHeaderAndInsetThroughTheRealNavigationShell() {
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Playlists), reducedMotion = true))
        val detail = io.github.anastaci1a.localica.playlist.ManualPlaylist(
            id = "shell-playlist", name = "Shell playlist",
            entries = listOf(SongRow("shell-row", track)))
        compose.setContent { LocalicaTheme { LocalicaScreen(library, PlaybackState(connected = true), {}, {}, {}, {}, {}, {},
            playlist = PlaylistState(listOf(detail), loading = false)) } }
        fun openAndAssert() {
            compose.waitUntil(5000) {
                compose.onAllNodes(hasText(detail.name) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(detail.name) and hasClickAction()).performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithContentDescription("Play playlist").fetchSemanticsNodes().isNotEmpty()
            }
            // BrowserScreen-only fixtures cannot catch an obsolete shell heading.
            compose.onAllNodesWithTag("page-heading").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("Back").assertCountEquals(1)
            compose.onAllNodesWithContentDescription("Sort").assertCountEquals(1)
            compose.onNodeWithContentDescription("Play playlist").assertIsDisplayed()
            compose.onNodeWithContentDescription("Shuffle playlist").assertIsDisplayed()
            compose.onNodeWithContentDescription("Playlist actions").assertIsDisplayed()
            assertEquals("The hero viewport must not inherit an extra shell status-bar inset",
                compose.onNodeWithTag("browse-background").fetchSemanticsNode().boundsInRoot.top,
                compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot.top, 1.1f)
        }
        openAndAssert()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasTestTag("page-heading") and hasText("Playlists")).assertIsDisplayed()
        openAndAssert()
        back()
        compose.waitUntil(5000) {
            compose.onAllNodes(hasTestTag("page-heading") and hasText("Playlists")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(detail.name) and hasClickAction()).assertIsDisplayed()
    }

    @Test fun tabFeedbackAndHeadingArriveBeforeThePagerSettles() {
        compose.setContent { LocalicaTheme { LocalicaScreen(library, PlaybackState(connected = true), {}, {}, {}, {}, {}, {},
            playlist = PlaylistState(loading = false)) } }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Library").performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithContentDescription("Library").assertIsSelected()
            compose.onNodeWithContentDescription("Home").assertIsNotSelected()
            compose.mainClock.advanceTimeBy(128)
            compose.onNode(hasTestTag("page-heading") and hasText("Library")).assertIsDisplayed()
            compose.onNodeWithContentDescription("Home").performClick()
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithContentDescription("Home").assertIsSelected()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun transportDoesNotExpandAndPlayerCollapsePreservesSearchAcrossRestoration() {
        var next = 0
        val restore = StateRestorationTester(compose)
        val queue = QueueEdits.create(listOf(track), 0, "Evening")
        restore.setContent { LocalicaTheme {
            LocalicaScreen(library, PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title),
                {}, {}, {}, {}, {}, {}, queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false), onNext = { next++ },
                playlist = PlaylistState(loading = false))
        } }
        compose.onNodeWithContentDescription("Next").performClick()
        compose.runOnIdle { assertEquals(1, next) }
        compose.onNodeWithContentDescription("Collapse player").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search library").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Evening")
        back() // dismiss keyboard before opening player
        compose.onNodeWithTag("player-surface").performTouchInput { swipeUp() }
        compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open queue").performClick()
        compose.onNodeWithContentDescription("Queue actions").assertIsDisplayed()
        back()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Collapse player").assertIsDisplayed()
        back()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasSetTextAction()).assertTextContains("Evening")
        compose.onNodeWithContentDescription("Collapse player").assertDoesNotExist()
    }

    @Test fun detailBackWorksWithoutTabHistoryAndSettingsPersist() {
        InterfacePreferences(context).write(InterfaceSettings(tabHistory = false, confirmExit = true, reducedMotion = true))
        compose.setContent { LocalicaTheme { LocalicaScreen(library, PlaybackState(connected = true), {}, {}, {}, {}, {}, {},
            playlist = PlaylistState(loading = false)) } }
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithText("Seasons").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText("Evening").assertIsDisplayed() }.isSuccess }
        back()
        // Native Back returns before navigation and the next window are necessarily drawn.
        // The album heading also says Seasons, so require the root's clickable album card.
        compose.waitUntil(5000) { runCatching { compose.onNode(hasText("Seasons") and hasClickAction()).assertIsDisplayed() }.isSuccess }
        compose.onNodeWithText("Seasons").assertIsDisplayed()
        back()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText("Close Localica?").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithText("Close Localica?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(InterfacePreferences(context).read().tabHistory)
    }

    @Test fun codecKeepsRouteIdentityAndAllSearchState() {
        val route = BrowseRoute(MainTab.Library, MediaRef(MediaKind.Album, "a"), "Album", true, false, "word", MediaKind.Song)
        val state = NavigationState().switch(MainTab.Library).open(route).switch(MainTab.Home)
        assertEquals(state, NavigationCodec.decode(NavigationCodec.encode(state)))
        InterfacePreferences(context).write(InterfaceSettings(tabs = MainTab.entries, reducedMotion = true, grayTone = .73f))
        assertEquals(MainTab.entries.filter { it != MainTab.MostPlayed }, InterfacePreferences(context).read().tabs)
        assertEquals(.73f, InterfacePreferences(context).read().grayTone)
        InterfacePreferences(context).write(InterfaceSettings(grayTone = Float.NaN))
        assertEquals(.4f, InterfacePreferences(context).read().grayTone)
    }
    @Test fun queueInformationNavigatesOutToAlbumAndPreservesPlayback() = queueLink("Album", "Seasons")
    @Test fun queueInformationNavigatesOutToArtist() = queueLink("Artist", "Artist")
    @Test fun queueInformationNavigatesOutToAlbumArtist() = queueLink("Album Artist", "Album artist")
    @Test fun queueInformationNavigatesOutToFolder() = queueLink("Location", "Music")
    @Test fun queueTabInformationNavigatesOutToAlbum() = queueLink("Album", "Seasons", true)

    private fun queueLink(action: String, destination: String, queueTab: Boolean = false) {
        if (queueTab) InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Queues), reducedMotion = true))
        var playbackActions = 0
        val queue = QueueEdits.create(listOf(track), 0, "Evening")
        compose.setContent { LocalicaTheme { LocalicaScreen(library,
            PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title),
            {}, {}, {}, {}, { playbackActions++ }, { playbackActions++ },
            queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false),
            onPrevious = { playbackActions++ }, onNext = { playbackActions++ }) } }
        if (!queueTab) {
            compose.onNodeWithTag("player-surface").performClick()
            compose.onNodeWithContentDescription("Open queue").performClick()
        }
        compose.onNodeWithContentDescription("Actions for Evening").performClick()
        compose.onNodeWithText("Information").performClick()
        compose.onNodeWithTag("song-information-$action").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText(destination).onFirst().assertIsDisplayed()
        // Artist headers include an album carousel above the songs. Navigation must
        // preserve that initial header rather than forcing the first song onscreen.
        if (action != "Location") {
            val playLabel = if (action == "Album") "Play album" else "Play selected albums"
            compose.onNode(hasTestTag("browser-list") and hasAnyDescendant(hasContentDescription(playLabel)))
                .performScrollToIndex(1)
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media-" + track.id).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Collapse player").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, playbackActions) }
        compose.onNodeWithTag("media-" + track.id).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
    }

}

