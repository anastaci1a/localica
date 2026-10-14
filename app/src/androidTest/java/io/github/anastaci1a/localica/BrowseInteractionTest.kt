package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BrowseInteractionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun longPressStartsSelectionAndCanceledPlaylistDialogKeepsIt() {
        val track = Track("missing", "Repeated", fileName = "tone.mp3", relativePath = "tone.mp3")
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Mix"), listOf(track, track))
        var played: Int? = null
        val route = BrowseRoute(MainTab.Library, MediaRef(MediaKind.Playlist, playlist.id))
        compose.setContent { LocalicaTheme {
            BrowserScreen(LibraryUiState(loading = false), PlaylistState(listOf(playlist), false),
                route, {}, {}, {},
                { _, index, _ -> played = index }, null, null, true, false)
        } }
        val first = "media-${playlist.entries.first().id}"
        val last = "media-${playlist.entries.last().id}"
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithTag(last).performClick()
        compose.runOnIdle { assertEquals(1, played); played = null }
        compose.onNodeWithTag(last).performTouchInput { longClick() }
        compose.onNodeWithText("Select song").assertDoesNotExist()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithTag(first).performClick()
        compose.runOnIdle { assertNull(played) }
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithText("Add to playlist").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close selection").performClick()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }
    @Test fun collectionOverridesInheritIndependentlyFromCatalogAndSearch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        try {
            val preferences = BrowsePreferences(context)
            val album = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, "one"))
            val another = album.copy(collection = MediaRef(MediaKind.Playlist, "two"))
            val byYear = BrowseStyle(TrackSort.Year, false, Presentation.Standard)
            preferences.write(album, byYear, false)
            assertEquals(byYear, preferences.read(another))
            assertEquals(TrackSort.Title, preferences.read(BrowseRoute(MainTab.Albums)).sort)
            val override = BrowseStyle(TrackSort.Duration)
            preferences.write(another, override, true)
            assertEquals(override, preferences.read(another))
            assertEquals(byYear, preferences.read(album))
            preferences.reset(another)
            assertEquals(byYear, preferences.read(another))
            assertEquals(TrackSort.Title, preferences.read(album.copy(search = true)).sort)
            val fixedAlbum = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, "one"))
            preferences.write(fixedAlbum, byYear.copy(songGrouping = SongGrouping.Artist), true)
            val ordered = preferences.read(fixedAlbum)
            assertEquals(TrackSort.Album, ordered.sort)
            assertTrue(ordered.ascending)
            assertEquals(Presentation.List, ordered.presentation)
            assertEquals(SongGrouping.None, ordered.songGrouping)
        } finally { context.getSharedPreferences("browse-style", 0).edit().clear().commit() }
    }
}
