package io.github.anastaci1a.localica

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
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
    @Test fun mutableSongDotsHoldSelectsBeforeReleaseWithoutOpeningActionsOrPlaying() {
        val track = Track("missing", "Held", fileName = "tone.mp3", relativePath = "tone.mp3")
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Mix"), listOf(track))
        val route = BrowseRoute(MainTab.Library, MediaRef(MediaKind.Playlist, playlist.id))
        val session = SessionSelection()
        var plays = 0
        val preferences = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = preferences.read(route)
        val originalOverride = preferences.overridden(route)
        preferences.write(route, original.copy(presentation = Presentation.List, listSize = ListSize.Normal), originalOverride)
        try {
            compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalSessionSelection provides session) {
                BrowserScreen(LibraryUiState(loading = false), PlaylistState(listOf(playlist), false),
                    route, {}, {}, {}, { _, _, _ -> plays++ }, null, null, true, false)
            } } }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Actions for Held").fetchSemanticsNodes().isNotEmpty() }
            val dots = compose.onNodeWithContentDescription("Actions for Held")
            var held = false
            try {
                dots.performTouchInput { down(center); held = true; advanceEventTime(650); moveTo(center) }
                compose.runOnIdle {
                    assertTrue("Dots select while held, not on release", session.active)
                    assertFalse("The new selection panel waits for the initiating dots pointer", session.panelVisible)
                    assertEquals(listOf(playlist.entries.single().id), session.entries.map { it.row.id })
                    assertEquals(0, plays)
                }
                compose.onNodeWithTag("glass-morph-surface").assertDoesNotExist()
                dots.performTouchInput { up(); held = false }
                compose.runOnIdle { assertTrue(session.panelVisible); assertEquals(0, plays) }
                compose.onNodeWithTag("glass-morph-surface").assertDoesNotExist()
                // Accessibility has no held pointer, so selection presents immediately.
                compose.runOnIdle { session.clear() }
                dots.performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
                compose.runOnIdle { assertTrue(session.active); assertTrue(session.panelVisible) }
            } finally { if (held) dots.performTouchInput { cancel() } }
        } finally { preferences.write(route, original, originalOverride) }
    }

    @Test fun nonmutableSongDotsHoldOpensTheSameMenuAsATapWithoutPlaying() {
        val track = Track("missing", "Held", fileName = "tone.mp3", relativePath = "tone.mp3")
        val route = BrowseRoute(MainTab.Songs)
        val preferences = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = preferences.read(route)
        val originalOverride = preferences.overridden(route)
        preferences.write(route, original.copy(presentation = Presentation.List, listSize = ListSize.Normal), originalOverride)
        val session = SessionSelection()
        var plays = 0
        try {
            compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalSessionSelection provides session) {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")),
                    tracks = listOf(track)), loading = false), PlaylistState(loading = false),
                    route, {}, {}, {}, { _, _, _ -> plays++ }, null, null, true, false)
            } } }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Actions for Held").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Actions for Held").performTouchInput { longClick() }
            compose.onNodeWithText("Select song").assertIsDisplayed()
            compose.onNodeWithText("Information").assertIsDisplayed()
            compose.runOnIdle { assertFalse(session.active); assertEquals(0, plays) }
        } finally { preferences.write(route, original, originalOverride) }
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
            val editableAlbum = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, "one"))
            preferences.write(editableAlbum, byYear.copy(songGrouping = SongGrouping.Artist), true)
            val ordered = preferences.read(editableAlbum)
            assertEquals(byYear.copy(songGrouping = SongGrouping.Artist), ordered)
            val tab = BrowseRoute(MainTab.Songs)
            val filter = BrowseRoute(MainTab.Library, filter = MediaKind.Song)
            val normal = BrowseStyle(listSize = ListSize.Normal)
            val compact = normal.copy(listSize = ListSize.Compact, extraInfo = ExtraInfo.Year)
            preferences.write(tab, normal, false)
            preferences.write(filter, compact, true)
            assertEquals(compact, preferences.read(tab))
            assertFalse(preferences.overridden(filter))
            assertEquals(compact, preferences.read(filter))
            assertEquals(compact, BrowsePreferences(context).read(filter))
            val search = filter.copy(search = true, text = "first")
            preferences.write(search, compact, true)
            assertEquals(compact, preferences.read(search.copy(text = "second")))
            // Clearing the override and changing defaults is one atomic publication.
            val observed = mutableListOf<BrowseStyle>()
            val stop = preferences.observe(filter) { observed += it }
            preferences.write(filter, normal.copy(listSize = ListSize.Large), false)
            assertFalse(preferences.overridden(filter))
            assertEquals(ListSize.Large, preferences.read(tab).listSize)
            assertTrue(observed.all { it.listSize == ListSize.Large })
            stop()
        } finally { context.getSharedPreferences("browse-style", 0).edit().clear().commit() }
    }
}
