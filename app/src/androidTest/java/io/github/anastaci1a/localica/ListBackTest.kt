package io.github.anastaci1a.localica

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ListBackTest {
    @get:Rule val compose = createComposeRule()
    private fun position() = compose.onNodeWithTag("browser-list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun back() {
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("browser-list"))
        pressSystemBack()
    }

    @Test fun backScrollsThenClearsFilterThenDelegatesOneStepPerPress() {
        val tracks = (0..99).map { Track("back:$it", "Song ${it.toString().padStart(3, '0')}", fileName = "$it.mp3", relativePath = "$it.mp3") }
        var route by mutableStateOf(BrowseRoute(MainTab.Library, filter = MediaKind.Song))
        var ordinaryBack by mutableIntStateOf(0)
        compose.setContent { LocalicaTheme {
            BackHandler { ordinaryBack++ }
            BrowserScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaylistState(), route,
                { route = it }, {}, {}, { _, _, _ -> }, null, null, true, false,
                listBackEnabled = true, reducedMotion = true)
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media-back:0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browser-list").performScrollToIndex(65)
        assertTrue(position() > 0f)
        back()
        compose.waitUntil(5000) { position() == 0f }
        compose.runOnIdle { assertEquals(MediaKind.Song, route.filter); assertEquals(0, ordinaryBack) }
        back()
        compose.waitUntil(5000) { route.filter == null }
        compose.runOnIdle { assertEquals(0, ordinaryBack) }
        back()
        compose.runOnIdle { assertEquals(1, ordinaryBack) }
    }

    @Test fun onlyTheSettledPageOwnsNativeBackAndTabReselection() {
        val first = Any()
        val second = Any()
        var selected by mutableIntStateOf(0)
        var request by mutableIntStateOf(0)
        val executed = mutableListOf<Int>()
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize().testTag("browser-list")) {
                ListReturnActions(request, first, selected == 0, true, ListReturnAction.ScrollTop,
                    ready = {}, execute = { executed += 0 })
                ListReturnActions(request, second, selected == 1, true, ListReturnAction.ScrollTop,
                    ready = {}, execute = { executed += 1 })
            }
        } }
        back()
        compose.runOnIdle { assertEquals(listOf(0), executed); selected = 1 }
        back()
        compose.runOnIdle { assertEquals(listOf(0, 1), executed); request++ }
        compose.waitUntil(5000) { executed.size == 3 }
        compose.runOnIdle { assertEquals(listOf(0, 1, 1), executed) }
    }

    @Test fun replacingQueryOwnerCancelsBackWaitingForItsResult() {
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        var owner by mutableStateOf(Any())
        val executed = mutableListOf<Any>()
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize().testTag("browser-list")) {
                val currentOwner = owner
                ListReturnActions(0, currentOwner, true, true, ListReturnAction.ClearFilter,
                    ready = { started.complete(Unit); ready.await() }, execute = { executed += currentOwner })
            }
        } }
        back()
        compose.waitUntil(5000) { started.isCompleted }
        compose.runOnIdle { owner = Any() }
        compose.waitForIdle()
        compose.runOnIdle { ready.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(executed.isEmpty()) }
        back()
        compose.waitUntil(5000) { executed.size == 1 }
        compose.runOnIdle { assertSame(owner, executed.single()) }
    }

    @Test fun authoritativeEmptyFilterCanClearWithoutWaitingForRows() {
        var route by mutableStateOf(BrowseRoute(MainTab.Library, filter = MediaKind.Album))
        var ordinaryBack by mutableIntStateOf(0)
        compose.setContent { LocalicaTheme {
            BackHandler { ordinaryBack++ }
            BrowserScreen(LibraryUiState(LibrarySnapshot(), loading = false), PlaylistState(), route,
                { route = it }, {}, {}, { _, _, _ -> }, null, null, true, false,
                listBackEnabled = true, reducedMotion = true)
        } }
        compose.onNodeWithTag("browser-list").assertExists()
        back()
        compose.waitUntil(5000) { route.filter == null }
        compose.runOnIdle { assertEquals(0, ordinaryBack) }
        back()
        compose.runOnIdle { assertEquals(1, ordinaryBack) }
    }

    @Test fun selectionDismissalPrecedesDetailScrollAndOrdinaryHistory() {
        val tracks = (0..99).map { Track("detail-back:$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Selected list"), tracks)
        val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id), playlist.name)
        val selection = SessionSelection()
        var ordinaryBack by mutableIntStateOf(0)
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalSessionSelection provides selection) {
            BackHandler { ordinaryBack++ }
            BrowserScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaylistState(listOf(playlist), false), route,
                {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                listBackEnabled = true, reducedMotion = true)
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media-${playlist.entries.first().id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("browser-list").performScrollToIndex(65)
        val before = position()
        compose.runOnIdle { selection.select(route.collection, listOf(SongRow(playlist.entries.first().id, tracks.first()))) }
        back()
        compose.waitUntil(5000) { !selection.active }
        assertEquals(before, position())
        compose.runOnIdle { assertEquals(0, ordinaryBack) }
        back()
        compose.waitUntil(5000) { position() == 0f }
        compose.runOnIdle { assertEquals(0, ordinaryBack) }
        back()
        compose.runOnIdle { assertEquals(1, ordinaryBack) }
    }
}
