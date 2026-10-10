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
        fileName = "evening.mp3", relativePath = "evening.mp3")
    private val library = LibraryUiState(LibrarySnapshot(listOf(FolderSelection("tree", "Music")), listOf(track)), loading = false)
    private fun back() { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack(); compose.waitForIdle() }
    @Before fun reset() { context.getSharedPreferences("interface", 0).edit().clear().commit() }
    @After fun cleanup() { reset() }

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
        compose.onNode(hasSetTextAction()).assertTextContains("Evening")
        compose.onNodeWithContentDescription("Collapse player").assertDoesNotExist()
    }

    @Test fun detailBackWorksWithoutTabHistoryAndSettingsPersist() {
        InterfacePreferences(context).write(InterfaceSettings(tabHistory = false, confirmExit = true, reducedMotion = true))
        compose.setContent { LocalicaTheme { LocalicaScreen(library, PlaybackState(connected = true), {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithText("Seasons").performClick()
        compose.onNodeWithText("Evening").assertIsDisplayed()
        back()
        compose.onNodeWithText("Seasons").assertIsDisplayed()
        back()
        compose.onNodeWithText("Close Localica?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(InterfacePreferences(context).read().tabHistory)
    }

    @Test fun codecKeepsRouteIdentityAndAllSearchState() {
        val route = BrowseRoute(MainTab.Library, MediaRef(MediaKind.Album, "a"), "Album", true, false, "word", MediaKind.Song)
        val state = NavigationState().switch(MainTab.Library).open(route).switch(MainTab.Home)
        assertEquals(state, NavigationCodec.decode(NavigationCodec.encode(state)))
        InterfacePreferences(context).write(InterfaceSettings(tabs = MainTab.entries, reducedMotion = true))
        assertEquals(MainTab.entries, InterfacePreferences(context).read().tabs)
    }
}
