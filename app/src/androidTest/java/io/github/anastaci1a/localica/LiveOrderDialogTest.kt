package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LiveOrderDialogTest {
    @get:Rule val compose = createComposeRule()
    private fun track(id: String, title: String) = Track("missing:$id", title,
        fileName = "$id.mp3", relativePath = "$id.mp3", id = id)

    @Test fun additionsAndLiveKeysReenableApplyAndFailedSaveRemainsRetryableAfterReopen() {
        val a = track("a", "Alpha")
        val z = track("z", "Zulu")
        var playlist by mutableStateOf(ManualPlaylist(id = "mix", name = "Mix",
            entries = listOf(SongRow("a1", a), SongRow("z1", z)),
            order = SavedOrder(listOf("z1", "a1"), TrackSort.Title, true)))
        var snapshot by mutableStateOf(LibrarySnapshot(tracks = listOf(a, z)))
        var open by mutableStateOf(true)
        var fail = false
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
            if (open) {
                BrowseSortDialog(BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, "mix")),
                    BrowseStyle(), false, { open = false }, { _, _ -> }, {},
                    mutableOrder = playlist.order, mutableOrderIds = playlist.entries.map { it.id },
                    mutableOrderRows = playlist.entries, orderSnapshot = snapshot,
                    onApplySort = { method, ascending, done ->
                        if (!fail) playlist = PlaylistEdits.sort(playlist, method, ascending, snapshot)
                        done(!fail)
                    })
            }
        } } }
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.runOnIdle {
            playlist = PlaylistEdits.append(playlist, listOf(track("new", "Aardvark")))
        }
        compose.waitUntil(10000) {
            compose.onAllNodes(hasText("Apply") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("new", "a", "z"), playlist.entries.map { it.track.id }) }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle {
            snapshot = LibrarySnapshot(tracks = listOf(a.copy(title = "Zebra"), z.copy(title = "Apple")))
            open = true
            fail = true
        }
        compose.waitUntil(10000) {
            compose.onAllNodes(hasText("Apply") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        compose.onNodeWithText("Couldn’t save this order. Try again.").assertExists()
        compose.waitUntil(10000) {
            compose.onAllNodes(hasText("Apply") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Apply").assertIsEnabled()
        compose.runOnIdle { fail = false }
        compose.onNodeWithText("Apply").performClick()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("new", "z", "a"), playlist.entries.map { it.track.id })
            assertEquals("Alpha", playlist.entries.last().track.title)
            assertEquals(listOf("z1", "a1") + playlist.entries.first().id, playlist.order.manual)
        }
    }
}
