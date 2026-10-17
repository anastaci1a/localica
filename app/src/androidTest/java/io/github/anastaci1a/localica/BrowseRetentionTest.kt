package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.browse.*
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

    @Test fun returningHomeKeepsPinnedResultsWithoutRepeatingTheQuery() {
        val tasks = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val track = Track("song:1", "Pinned song", fileName = "song.mp3", relativePath = "song.mp3")
        val library = LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false)
        val playlists = PlaylistState(loading = false)
        val pins = listOf(MediaRef(MediaKind.Song, track.id))
        val cache = BrowsePresentationCache()
        var mounted by mutableStateOf(true)
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalBrowsePresentationCache provides cache) {
            if (mounted) PinnedHome(library, playlists, pins, {}, {}, { _, _ -> }, {}, { _, _ -> }, queryDispatcher = dispatcher)
        } } }
        compose.waitUntil(5000) { tasks.isNotEmpty() }
        requireNotNull(tasks.poll(5, TimeUnit.SECONDS)).run()
        compose.onNodeWithText("Pinned song").assertIsDisplayed()
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted = true }
        compose.onNodeWithText("Pinned song").assertIsDisplayed()
        assertTrue("Home should reuse the completed query immediately", tasks.isEmpty())
    }

    @Test fun pinnedCollectionUpdatesAfterInheritedSortOverrideAndReset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.getSharedPreferences("browse-style", 0)
        val legacy = context.getSharedPreferences("library-query", 0)
        store.edit().clear().commit(); legacy.edit().clear().commit()
        val preferences = BrowsePreferences(context)
        val first = Track("first", "Alpha", album = "Pinned album", trackNumber = 2, fileName = "a.mp3", relativePath = "a.mp3")
        val second = first.copy(uri = "second", id = "second", title = "Zulu", trackNumber = 1)
        val library = LibraryUiState(LibrarySnapshot(tracks = listOf(first, second)), loading = false)
        val playlists = PlaylistState(loading = false)
        val album = BrowseQueries.evaluate(library.snapshot, emptyList(), BrowseRoute(MainTab.Albums)).items.single()
        val detail = BrowseRoute(MainTab.Albums, album.ref)
        val pins = listOf(album.ref)
        val cache = BrowsePresentationCache()
        val tasks = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalBrowsePresentationCache provides cache) {
            PinnedHome(library, playlists, pins, {}, {}, { _, _ -> }, {}, { _, _ -> }, queryDispatcher = dispatcher)
        } } }
        fun finishQuery(expected: String) {
            compose.waitUntil(5000) { tasks.isNotEmpty() }
            requireNotNull(tasks.poll(5, TimeUnit.SECONDS)).run()
            compose.waitForIdle()
            assertEquals(listOf(expected), cache.home(library.snapshot, playlists.playlists, pins,
                preferences.sortSnapshot())?.single()?.coverUris)
        }
        try {
            finishQuery("second")
            compose.runOnIdle { preferences.write(detail, BrowseStyle(sort = TrackSort.Title), false) }
            compose.onNodeWithText("Pinned album").assertIsNotEnabled()
            finishQuery("first")
            compose.onNodeWithText("Pinned album").assertIsEnabled()
            compose.runOnIdle { preferences.write(detail, BrowseStyle(sort = TrackSort.Title, ascending = false), true) }
            finishQuery("second")
            compose.runOnIdle { preferences.reset(detail) }
            finishQuery("first")
            compose.onNodeWithText("Pinned album").assertIsDisplayed()
        } finally { store.edit().clear().commit(); legacy.edit().clear().commit() }
    }

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
        val preferences = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        for (style in listOf(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 3),
            BrowseStyle(listSize = ListSize.Compact), BrowseStyle(listSize = ListSize.Large, extraInfo = ExtraInfo.Year))) {
            compose.runOnIdle { preferences.write(route, style, false) }
            compose.waitForIdle()
            compose.onNodeWithText("First album").assertIsDisplayed()
            assertTrue("Layout-only changes reuse the ordered rows immediately", tasks.isEmpty())
        }
        compose.runOnIdle { library = LibrarySnapshot(tracks = listOf(track.copy(album = "Updated album"))) }
        finishQuery()
        compose.onNodeWithText("Updated album").assertIsDisplayed()
        compose.onNodeWithText("First album").assertDoesNotExist()
    }
}
