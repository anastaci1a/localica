package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
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

    @Test fun tabFeedbackAndHeadingArriveBeforeThePagerSettles() {
        compose.setContent { LocalicaTheme { LocalicaScreen(library, PlaybackState(connected = true), {}, {}, {}, {}, {}, {}) } }
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
                {}, {}, {}, {}, {}, {}, queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false), onNext = { next++ })
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
        compose.setContent { LocalicaTheme { LocalicaScreen(library, PlaybackState(connected = true), {}, {}, {}, {}, {}, {}) } }
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
        assertEquals(MainTab.entries, InterfacePreferences(context).read().tabs)
        assertEquals(.73f, InterfacePreferences(context).read().grayTone)
        InterfacePreferences(context).write(InterfaceSettings(grayTone = Float.NaN))
        assertEquals(.4f, InterfacePreferences(context).read().grayTone)
    }
    @Test fun queueInformationNavigatesOutToAlbumAndPreservesPlayback() = queueLink("Go to album", "Seasons")
    @Test fun queueInformationNavigatesOutToArtist() = queueLink("Go to artist", "Artist")
    @Test fun queueInformationNavigatesOutToAlbumArtist() = queueLink("Go to Album Artist", "Album artist")
    @Test fun queueInformationNavigatesOutToFolder() = queueLink("Go to folder", "Music")
    @Test fun queueTabInformationNavigatesOutToAlbum() = queueLink("Go to album", "Seasons", true)

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
        compose.onNodeWithText(action).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Collapse player").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText(destination).onFirst().assertIsDisplayed()
        // Artist headers include an album carousel above the songs. Navigation must
        // preserve that initial header rather than forcing the first song onscreen.
        compose.onNodeWithTag("browser-list").performScrollToIndex(1)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media-" + track.id).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Collapse player").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, playbackActions) }
        compose.onNodeWithTag("media-" + track.id).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
    }

}
