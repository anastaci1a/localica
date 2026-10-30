package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowseQueries
import io.github.anastaci1a.localica.home.PinPreferences
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeSelectionTest {
    @get:Rule val compose = createComposeRule()
    private fun track(id: String) = Track(id, "Pinned $id", album = "Album $id", fileName = "$id.mp3", relativePath = "$id.mp3")

    @Test fun homeScopeTogglesSongsAndAllRangeKeepForeignOccurrences() {
        val tracks = listOf(track("C"), track("D"), track("E"))
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks)
        val source = MediaRef(MediaKind.Playlist, "P")
        val foreign = SongRow("entry-A", track("A"))
        val session = SessionSelection().apply { select(source, listOf(foreign)); visible = listOf(foreign, SongRow("entry-B", track("B"))); this.source = source }
        var openedSongs = 0
        var openedCollections = 0
        val album = BrowseQueries.evaluate(snapshot, emptyList(), BrowseRoute(MainTab.Albums)).items.first().ref
        val pins = tracks.map { MediaRef(MediaKind.Song, it.id) } + album
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalSessionSelection provides session) {
                Column {
                    Box(Modifier.weight(1f)) {
                        PinnedHome(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), pins,
                            {}, {}, { item, _ -> if (item.row == null) openedCollections++ else openedSongs++ }, {}, { _, _ -> }, reducedMotion = true)
                    }
                    Box(Modifier.height(220.dp).verticalScroll(rememberScrollState())) {
                        SessionSelectionPanel(session, null, null, PlaylistState(loading = false))
                    }
                }
            }
        } }
        compose.waitUntil(5000) { compose.runOnUiThread { session.visible.map { it.id } == listOf("C", "D", "E") && session.source == null } }
        fun pin(id: String) = compose.onNodeWithTag("pin-$id")
        pin("C").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithTag("home-pins").performScrollToNode(hasTestTag("pin-E"))
        pin("E").performClick().assertIsSelected()
        compose.onNodeWithText("Range").performClick()
        compose.runOnIdle {
            assertEquals(listOf("entry-A", "C", "E", "D"), session.entries.map { it.row.id })
            assertEquals(source, session.entries.first().source)
            assertTrue(session.entries.drop(1).all { it.source == null })
            assertEquals(0, openedSongs)
        }
        compose.onNodeWithTag("home-pins").performScrollToNode(hasTestTag("pin-D"))
        pin("D").performClick().assertIsNotSelected()
        compose.onNodeWithText("All", substring = false).performClick()
        pin("D").assertIsSelected()
        compose.runOnIdle { assertEquals(4, session.entries.size); assertFalse(session.entries.any { it.row.id == "entry-B" }) }
        compose.onNodeWithTag("home-pins").performScrollToNode(hasTestTag("pin-Album:${album.id}"))
        compose.onNodeWithTag("pin-Album:${album.id}").performClick()
        compose.runOnIdle { assertEquals(1, openedCollections); session.clear() }
        compose.onNodeWithTag("home-pins").performScrollToNode(hasTestTag("pin-C"))
        pin("C").performClick()
        compose.runOnIdle { assertEquals(1, openedSongs) }
        pin("C").performTouchInput { longClick() }
        compose.onNodeWithText("Select song").performClick()
        compose.runOnIdle { assertEquals(listOf("C"), session.entries.map { it.row.id }); assertNull(session.entries.single().source) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("All", substring = false).performClick()
        compose.runOnIdle { assertEquals(listOf("C", "D", "E"), session.entries.map { it.row.id }) }
    }

    @Test fun inactiveHomeCannotReplaceScopeAndEmptyActiveHomeClearsIt() {
        val foreignSource = MediaRef(MediaKind.Playlist, "foreign")
        val foreignRows = listOf(SongRow("foreign-entry", track("A")))
        val session = SessionSelection().apply { visible = foreignRows; source = foreignSource }
        var active by mutableStateOf(false)
        var pins by mutableStateOf(listOf(MediaRef(MediaKind.Song, "C")))
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = listOf(track("C"), track("D")))
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalSessionSelection provides session) {
                PinnedHome(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), pins,
                    {}, {}, { _, _ -> }, {}, { _, _ -> }, selectionVisible = active)
            }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("pin-C").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(foreignRows, session.visible); assertEquals(foreignSource, session.source)
            pins = listOf(MediaRef(MediaKind.Song, "D")) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("pin-D").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(foreignRows, session.visible); assertEquals(foreignSource, session.source); active = true }
        compose.waitUntil(5000) { compose.runOnUiThread { session.visible.map { it.id } == listOf("D") && session.source == null } }
        compose.runOnIdle { pins = emptyList() }
        compose.waitUntil(5000) { compose.runOnUiThread { session.visible.isEmpty() && session.source == null } }
    }

    @Test fun settledHomeOwnsScopeAfterPlaylistTabAndOffscreenRecomposition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val interfaceStore = context.getSharedPreferences("interface", 0)
        val pinStore = context.getSharedPreferences("pins", 0)
        val browseStore = context.getSharedPreferences("browse-style", 0)
        interfaceStore.edit().clear().commit(); pinStore.edit().clear().commit(); browseStore.edit().clear().commit()
        val songs = listOf(track("A"), track("B"), track("C"), track("D"))
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Scope mix"), songs.take(2))
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Home, MainTab.Playlists), reducedMotion = true))
        PinPreferences(context).write(songs.drop(2).map { MediaRef(MediaKind.Song, it.id) })
        var playlistState by mutableStateOf(PlaylistState(listOf(playlist), loading = false))
        try {
            compose.setContent { LocalicaTheme {
                LocalicaScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = songs), loading = false),
                    PlaybackState(connected = true), {}, {}, {}, {}, {}, {}, playlist = playlistState)
            } }
            compose.onNodeWithContentDescription("Playlists").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Actions for Scope mix").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Actions for Scope mix").performClick()
            compose.onNodeWithText("Select all songs").performClick()
            compose.onNodeWithText("2 selected").assertIsDisplayed()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() }
            compose.awaitExpectedWindowFocus(compose.onNodeWithContentDescription("Home"))
            compose.onNodeWithContentDescription("Home").performClick()
            compose.onNodeWithContentDescription("Collapse selection").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("pin-C").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("pin-C").performClick().assertIsSelected()
            compose.onNodeWithText("Selection: 3").assertIsDisplayed().performClick()
            // Recompose an offscreen playlist page while Home remains settled.
            compose.runOnIdle { playlistState = playlistState.copy(playlists = listOf(playlist.copy(name = "Changed offscreen"))) }
            compose.onNodeWithText("All", substring = false).performClick()
            compose.onNodeWithText("4 selected").assertIsDisplayed()
            compose.onNodeWithContentDescription("Playlists").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Changed offscreen").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("4 selected").assertIsDisplayed()
        } finally {
            interfaceStore.edit().clear().commit(); pinStore.edit().clear().commit(); browseStore.edit().clear().commit()
        }
    }

    @Test fun constrainedHomeReturnsOnlyItsVerticalListAndPreservesThePinnedCarouselScope() {
        val songs = (0..15).map { track(it.toString()) }
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = songs)
        val pins = songs.map { MediaRef(MediaKind.Song, it.id) }
        val session = SessionSelection()
        var opened = 0
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalSessionSelection provides session, LocalBrowseObscuredInset provides 64.dp) {
                Box(Modifier.size(320.dp, 260.dp).testTag("home-return-host")) {
                    PinnedHome(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), pins,
                        {}, {}, { _, _ -> opened++ }, {}, { _, _ -> }, reducedMotion = true)
                }
            }
        } }
        compose.waitUntil(5000) { compose.runOnUiThread { session.visible.size == songs.size } }
        val home = compose.onNodeWithTag("home-list")
        val carousel = compose.onNodeWithTag("home-pins")
        fun vertical() = home.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        fun horizontal() = carousel.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        carousel.performScrollToIndex(9)
        val carouselPosition = horizontal()
        assertTrue(carouselPosition > 0f)
        assertEquals(0f, vertical(), 0f)
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        home.performScrollToIndex(1)
        assertTrue(vertical() > 0f)
        val visibleSongs = compose.runOnIdle { session.visible.toList() }
        compose.onNodeWithContentDescription("Return to top").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { vertical() == 0f }
        assertEquals(carouselPosition, horizontal(), 0f)
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(visibleSongs, session.visible)
            assertNull(session.source)
            assertFalse(session.active)
            assertEquals(0, opened)
        }
    }

    @Test fun scrolledHomeReturnHidesForInactivePlayerAndSelectionOwnership() {
        val songs = (0..9).map { track(it.toString()) }
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = songs)
        val pins = songs.map { MediaRef(MediaKind.Song, it.id) }
        val session = SessionSelection()
        var active by mutableStateOf(true)
        var playerOpen by mutableStateOf(false)
        compose.setContent {
            val homeActive = active
            val homePlayerOpen = playerOpen
            LocalicaTheme {
            CompositionLocalProvider(LocalSessionSelection provides session) {
                Box(Modifier.size(320.dp, 240.dp)) {
                    PinnedHome(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), pins,
                        {}, {}, { _, _ -> }, {}, { _, _ -> }, reducedMotion = true,
                        selectionVisible = homeActive, playerOpen = homePlayerOpen)
                }
            }
        } }
        compose.waitUntil(5000) { compose.runOnUiThread { session.visible.size == songs.size } }
        val home = compose.onNodeWithTag("home-list")
        home.performScrollToIndex(1)
        fun position() = home.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = position()
        assertTrue(before > 0f)
        compose.onNodeWithContentDescription("Return to top").assertIsDisplayed()
        compose.runOnIdle { active = false }
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        assertEquals(before, position(), 0f)
        compose.runOnIdle { active = true; playerOpen = true }
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        assertEquals(before, position(), 0f)
        compose.runOnIdle { playerOpen = false; session.select(null, listOf(SongRow(songs.first().id, songs.first()))) }
        compose.onNodeWithContentDescription("Return to top").assertDoesNotExist()
        assertEquals(before, position(), 0f)
        compose.runOnIdle { session.clear() }
        compose.onNodeWithContentDescription("Return to top").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { position() == 0f }
    }

}
