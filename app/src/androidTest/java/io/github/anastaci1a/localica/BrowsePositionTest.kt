package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BrowsePositionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun libraryReselectionScrollsBeforeSeparatelyClearingItsFilter() {
        val tracks = (0..99).map { Track("reselect:$it", "Song ${it.toString().padStart(3, '0')}", fileName = "$it.mp3", relativePath = "$it.mp3") }
        var route by mutableStateOf(BrowseRoute(MainTab.Library, filter = MediaKind.Song))
        var request by mutableIntStateOf(0)
        compose.setContent { LocalicaTheme {
            BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                PlaylistState(loading = false), route, { route = it }, {}, {}, { _, _, _ -> }, null, null, true, false,
                scrollToTopRequest = request, reducedMotion = true, queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        } }
        val list = compose.onNodeWithTag("browser-list")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media-reselect:0").fetchSemanticsNodes().isNotEmpty() }
        list.performScrollToIndex(65)
        compose.runOnIdle { request++ }
        compose.waitUntil(5000) { list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() == 0f }
        compose.runOnIdle { assertEquals(MediaKind.Song, route.filter); request++ }
        compose.waitUntil(5000) { route.filter == null }
    }

    @Test fun bothBackControlsRestoreEveryCollectionCatalogPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Library), reducedMotion = true))
        val tracks = (0..99).map { index ->
            val n = index.toString().padStart(3, '0')
            Track("track:$n", "Song $n", artist = "Artist $n", albumArtist = "Album artist $n", album = "Album $n",
                fileName = "$n.mp3", relativePath = "$n.mp3", locations = listOf(TrackLocation("root:$n", "track:$n", "$n.mp3")))
        }
        val folders = tracks.map { FolderSelection(it.locations.single().folderUri, "Folder " + it.fileName.substringBefore('.')) }
        val playlists = tracks.map { PlaylistEdits.append(ManualPlaylist(name = "Playlist " + it.fileName.substringBefore('.')), listOf(it)) }
        try {
            compose.setContent { LocalicaTheme {
                LocalicaScreen(LibraryUiState(LibrarySnapshot(folders, tracks), loading = false), PlaybackState(connected = true),
                    {}, {}, {}, {}, {}, {}, playlist = PlaylistState(playlists, false))
            } }
            val types = listOf("Albums" to "Album", "Artists" to "Artist", "Album Artists" to "Album artist", "Folders" to "Folder", "Playlists" to "Playlist")
            for ((filter, prefix) in types) {
                compose.onNodeWithText(filter, useUnmergedTree = true).performScrollTo().performClick()
                for (hardware in listOf(false, true)) {
                    android.util.Log.i("BrowsePositionTest", "Restoring $filter; hardware=$hardware")
                    val title = "$prefix 060"
                    compose.scrollToUncoveredItem(hasText(title))
                    val before = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
                    compose.onNodeWithText(title).performClick()
                    compose.onNodeWithTag("browser-list").performScrollToIndex(1)
                    // Browse queries run on Dispatchers.Default, outside Compose's idle clock.
                    // Await their first laid-out result before testing navigation and position.
                    compose.waitUntil(5000) {
                        runCatching { compose.onNodeWithText("Song 060").assertIsDisplayed() }.isSuccess
                    }
                    compose.onNodeWithText("Song 060").assertIsDisplayed()
                    if (hardware) {
                        val list = compose.onNodeWithTag("browser-list")
                        val scrolled = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f
                        compose.awaitExpectedWindowFocus(list)
                        pressSystemBack()
                        if (scrolled) {
                            compose.waitUntil(5000) {
                                list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() == 0f
                            }
                            // The first press only scrolls this detail; history is a separate action.
                            compose.onNodeWithContentDescription("Back").assertExists()
                            compose.awaitExpectedWindowFocus(list)
                            pressSystemBack()
                        }
                    } else compose.onNodeWithContentDescription("Back").performClick()
                    try {
                        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty() &&
                            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
                    } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                        throw AssertionError("$filter / hardware=$hardware: ${compose.onRoot().printToString()}", failure)
                    }
                    compose.onNodeWithText(title).assertIsDisplayed()
                    assertEquals("$filter / hardware=$hardware", before, compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot)
                }
            }
        } finally { context.getSharedPreferences("interface", 0).edit().clear().commit() }
    }
}
