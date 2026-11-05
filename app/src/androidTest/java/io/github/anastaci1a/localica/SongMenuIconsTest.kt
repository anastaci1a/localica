package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The song and playlist menus keep their labels and order with the refreshed icons. */
class SongMenuIconsTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String) {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = args.getString("additionalTestOutputDir")?.let { java.io.File(it) } ?: context.getExternalFilesDir(null)!!
        directory.mkdirs()
        val file = java.io.File(directory, "$name.png")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file)
        assertTrue(file.length() > 0)
    }

    private fun labelsInOrder(labels: List<String>) {
        val tops = labels.map { compose.onNodeWithText(it).assertIsDisplayed().getBoundsInRoot().top.value }
        assertTrue("menu order changed: $tops", tops == tops.sorted())
    }

    @Test fun songMenuShowsRefreshedIcons() {
        val tracks = (0..2).map { Track("menu:$it", "Song $it", artist = "Artist", fileName = "$it.mp3", relativePath = "$it.mp3") }
        compose.setContent { LocalicaTheme {
            BrowserScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaylistState(),
                BrowseRoute(MainTab.Songs, null, null), {}, {}, {}, { _, _, _ -> },
                null, null, true, false, reducedMotion = true, queryDispatcher = Dispatchers.Unconfined)
        } }

        val actions = compose.awaitRowActions("Song 0")
        compose.awaitExpectedWindowFocus(actions)
        actions.performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Information").fetchSemanticsNodes().isNotEmpty() }
        labelsInOrder(listOf("Information", "Select song", "Play next", "Add to current queue", "Add to a queue", "Add to playlist"))
        compose.onNodeWithText("Pin to Home").assertExists()
        shoot("song-menu")
    }

    @Test fun playlistMenuShowsRefreshedIcons() {
        val playlist = ManualPlaylist(id = "p", name = "Mix")
        compose.setContent { LocalicaTheme {
            BrowserScreen(LibraryUiState(LibrarySnapshot(), loading = false), PlaylistState(listOf(playlist), loading = false),
                BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, "p"), "Mix"), {}, {}, {}, { _, _, _ -> },
                null, null, true, false, reducedMotion = true, queryDispatcher = Dispatchers.Unconfined)
        } }
        compose.onNodeWithContentDescription("Playlist actions").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Delete playlist").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pin to Home").assertExists()
        shoot("playlist-menu")
    }
}
