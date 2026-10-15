package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaylistCoverActionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun popupUsesCommittedCoverAndRemovalRestoresSetAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = PlaylistController(database.playlists(), scope)
        val playlist = ManualPlaylist(name = "Cover test", coverUri = "content://test/custom-cover")
        try {
            runBlocking {
                withTimeout(5000) { controller.state.first { !it.loading } }
                val created = CompletableDeferred<Boolean>()
                controller.create(playlist.name) { created.complete(it) }
                assertTrue(withTimeout(5000) { created.await() })
                val covered = CompletableDeferred<Boolean>()
                controller.cover(controller.state.value.playlists.single().id, playlist.coverUri) { covered.complete(it) }
                assertTrue(withTimeout(5000) { covered.await() })
            }
            val id = controller.state.value.playlists.single().id
            var open by mutableStateOf(false)
            compose.setContent { LocalicaTheme {
                val state by controller.state.collectAsState()
                val anchor = rememberGlassAnchor()
                ActionTextButton({ open = true }, modifier = androidx.compose.ui.Modifier.glassAnchor(anchor)) { androidx.compose.material3.Text("Open collection") }
                // Deliberately stale item: current committed playlist state owns the action.
                CollectionActions(open, anchor, BrowserItem(MediaRef(MediaKind.Playlist, id), playlist.name,
                    null, emptyList()), state, controller, null, emptyList(), {}, { open = false })
            } }
            compose.onNodeWithText("Open collection").performClick()
            compose.onNodeWithText("Remove playlist image").performScrollTo().performClick()
            compose.waitUntil(5000) { controller.state.value.playlists.single().coverUri == null }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Open collection").performClick()
            compose.onNodeWithText("Set playlist image").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Remove playlist image").assertDoesNotExist()
            runBlocking { assertNull(database.playlists().read().single().coverUri) }
        } finally { scope.cancel(); database.close() }
    }

    @Test fun failedRemovalRetainsCommittedCover() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val original = ManualPlaylist(name = "Keep cover", coverUri = "content://test/original")
        database.playlists().save(emptyList(), listOf(original))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = PlaylistController(database.playlists(), scope)
        try {
            withTimeout(5000) { controller.state.first { !it.loading } }
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_cover BEFORE UPDATE ON playlists BEGIN SELECT RAISE(ABORT, 'Injected failure'); END")
            val complete = CompletableDeferred<Boolean>()
            controller.cover(original.id, null) { complete.complete(it) }
            assertFalse(withTimeout(5000) { complete.await() })
            assertNotNull(controller.state.value.problem)
            assertEquals(original, controller.state.value.playlists.single())
            assertEquals(original, database.playlists().read().single())
        } finally { scope.cancel(); database.close() }
    }
}
