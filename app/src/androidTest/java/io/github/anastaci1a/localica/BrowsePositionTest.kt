package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
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
            val types = listOf("Albums" to "Album", "Artists" to "Artist", "Album artists" to "Album artist", "Folders" to "Folder", "Playlists" to "Playlist")
            for ((filter, prefix) in types) {
                compose.onNodeWithText(filter, useUnmergedTree = true).performScrollTo().performClick()
                for (hardware in listOf(false, true)) {
                    val title = "$prefix 060"
                    compose.scrollToUncoveredItem(hasText(title))
                    val before = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
                    compose.onNodeWithText(title).performClick()
                    compose.onNodeWithText("Song 060").assertIsDisplayed()
                    if (hardware) UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
                    else compose.onNodeWithContentDescription("Back").performClick()
                    compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText(title).assertIsDisplayed()
                    assertEquals("$filter / hardware=$hardware", before, compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot)
                }
            }
        } finally { context.getSharedPreferences("interface", 0).edit().clear().commit() }
    }
}
