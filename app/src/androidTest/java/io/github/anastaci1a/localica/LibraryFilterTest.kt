package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryFilterTest {
    @get:Rule val compose = createComposeRule()
    @Test fun holdingFilterOpensSharedSettingsAndChoicesOrderAndHiddenFilterPersist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filterPrefs = LibraryFilterPreferences(context)
        val interfacePrefs = InterfacePreferences(context)
        val previousFilters = filterPrefs.read()
        val previousInterface = interfacePrefs.read()
        try {
            filterPrefs.write(LibraryFilters(visible = listOf(LibraryFilter.All, LibraryFilter.Albums)))
            interfacePrefs.write(InterfaceSettings(tabs = listOf(MainTab.Library), reducedMotion = true))
            val snapshot = LibrarySnapshot(tracks = listOf(Track("missing", "Song", artist = "Singer", albumArtist = "Band", album = "Album",
                fileName = "song.mp3", relativePath = "song.mp3")))
            compose.setContent { LocalicaTheme {
                LocalicaScreen(LibraryUiState(snapshot, loading = false), PlaybackState(connected = true), {}, {}, {}, {}, {}, {})
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Band").fetchSemanticsNodes().size == 2 }
            compose.onNodeWithText("All").performTouchInput { longClick() }
            compose.onNodeWithText("Edit library filters and order").performClick()
            compose.onNodeWithText("Library filters").assertIsDisplayed()
            compose.onNodeWithText("Search settings").assertDoesNotExist()
            compose.onNode(isToggleable() and hasText("Artists")).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Close settings").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Singer").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(MediaKind.Artist in LibraryFilterPreferences(context).read().allCollections)
            compose.onNodeWithText("All").performTouchInput { longClick() }
            compose.onNodeWithText("Edit library filters and order").performClick()
            compose.onAllNodesWithText("Move down")[0].performScrollTo().performClick()
            assertEquals(listOf(LibraryFilter.Albums, LibraryFilter.All), LibraryFilterPreferences(context).read().visible)
            compose.onAllNodesWithText("Remove")[1].performScrollTo().performClick()
            compose.onNodeWithContentDescription("Close settings").performClick()
            compose.onNodeWithText("All").assertDoesNotExist()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Band").fetchSemanticsNodes().size <= 1 }
            compose.onNodeWithText("Singer").assertDoesNotExist()
            assertEquals(listOf(LibraryFilter.Albums), LibraryFilterPreferences(context).read().visible)
        } finally { filterPrefs.write(previousFilters); interfacePrefs.write(previousInterface) }
    }
}
