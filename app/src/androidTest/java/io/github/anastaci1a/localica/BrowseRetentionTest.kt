package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class BrowseRetentionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returningPageHasResultsBeforeAnyNewQueryAndLibraryChangesInvalidateThem() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("browse-style", 0).edit().clear().commit()
        val tasks = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val track = Track("song:1", "Song", album = "First album", fileName = "song.mp3", relativePath = "song.mp3")
        var library by mutableStateOf(LibrarySnapshot(tracks = listOf(track)))
        var mounted by mutableStateOf(true)
        val route = BrowseRoute(MainTab.Albums)
        val cache = BrowsePresentationCache()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalBrowsePresentationCache provides cache) {
                if (mounted) BrowserScreen(LibraryUiState(library, loading = false), PlaylistState(loading = false),
                    route, {}, {}, {}, { _, _, _ -> }, null, null, true, false, queryDispatcher = dispatcher)
            }
        } }
        fun finishQuery() {
            compose.waitUntil(5000) { tasks.isNotEmpty() }
            requireNotNull(tasks.poll(5, TimeUnit.SECONDS)).run()
            compose.waitForIdle()
        }
        finishQuery()
        compose.onNodeWithText("First album").assertIsDisplayed()
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted = true }
        compose.onNodeWithText("First album").assertIsDisplayed()
        assertTrue("Returning content must not wait for a duplicate query", tasks.isEmpty())
        compose.runOnIdle { library = LibrarySnapshot(tracks = listOf(track.copy(album = "Updated album"))) }
        finishQuery()
        compose.onNodeWithText("Updated album").assertIsDisplayed()
        compose.onNodeWithText("First album").assertDoesNotExist()
    }
}
