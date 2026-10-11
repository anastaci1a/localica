package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
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

class LibraryFilterPresentationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun filterAndSavedSortBecomeVisibleTogetherAboveTheFadingList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        val tasks = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        var route by mutableStateOf(BrowseRoute(MainTab.Library))
        val preferences = BrowsePreferences(context)
        preferences.write(route.copy(filter = MediaKind.Album), BrowseStyle(ascending = false), false)
        val tracks = (0..29).map { Track("song:$it", "Song $it", album = "Album " + it.toString().padStart(2, '0'),
            fileName = "$it.mp3", relativePath = "$it.mp3") }
        compose.setContent { LocalicaTheme {
            BrowserScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaylistState(loading = false),
                route, { route = it }, {}, {}, { _, _, _ -> }, null, null, true, false,
                libraryFilters = LibraryFilters(allCollections = setOf(MediaKind.Album)), queryDispatcher = dispatcher)
        } }
        fun finishQuery() { requireNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Expected a pending library query" }.run(); compose.waitForIdle() }
        finishQuery()
        compose.onNodeWithText("All").assertIsSelected()
        compose.onNodeWithText("Album 00").assertIsDisplayed()
        val filters = compose.onNodeWithTag("library-filters").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot
        assertEquals("Filters float over the same fading viewport", list.top, filters.top, 1f)
        assertTrue("First item initially clears the filters", compose.onNodeWithText("Album 00").fetchSemanticsNode().boundsInRoot.top > filters.bottom)
        compose.onNodeWithText("Albums").performClick()
        // Hold the new query: the previous arrangement must still carry its previous label.
        compose.onNodeWithText("All").assertIsSelected()
        compose.onNodeWithText("Albums").assertIsNotSelected()
        compose.onNodeWithText("Album 00").assertIsDisplayed()
        finishQuery()
        compose.onNodeWithText("Albums").assertIsSelected()
        compose.onNodeWithText("Album 29").assertIsDisplayed()
        compose.onNodeWithText("Album 00").assertDoesNotExist()
    }
}
