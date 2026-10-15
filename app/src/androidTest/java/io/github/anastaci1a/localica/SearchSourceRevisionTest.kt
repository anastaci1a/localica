package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class SearchSourceRevisionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sameQueryCannotPublishAnOldSourceAfterMetadataChanges() {
        val tasks = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        fun snapshot(title: String) = LibrarySnapshot(tracks = listOf(Track("one", title, fileName = "one.mp3", relativePath = "one.mp3")))
        var library by mutableStateOf(snapshot("Original"))
        val route = BrowseRoute(MainTab.Songs, search = true, text = "i")
        var shown: BrowsePresentationState? = null
        compose.setContent {
            val state = rememberBrowsePresentation(library, emptyList(), emptyList(), LibraryFilters(), route,
                BrowseStyle(), true, dispatcher, BrowseSortSnapshot.Default)
            SideEffect { shown = state }
        }
        fun nextQuery() = requireNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Expected queued search" }
        nextQuery().run()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Original", shown!!.current!!.result.rows.single().track.title)
            library = snapshot("Intermediate")
        }
        compose.waitForIdle()
        val superseded = nextQuery()
        compose.runOnIdle {
            assertTrue("Retained rows are pending, not a current result", shown!!.pending)
            library = snapshot("Final")
        }
        compose.waitForIdle()
        nextQuery().run()
        compose.waitForIdle()
        superseded.run()
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(shown!!.pending)
            assertEquals("Final", shown!!.current!!.result.rows.single().track.title)
        }
    }

    @Test fun sameQueryCannotPublishAnOldCollectionSortAfterPreferencesChange() {
        val tasks = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val library = LibrarySnapshot(tracks = listOf(
            Track("alpha", "Alpha", album = "Album", fileName = "alpha.mp3", relativePath = "alpha.mp3"),
            Track("zulu", "Zulu", album = "Album", fileName = "zulu.mp3", relativePath = "zulu.mp3")))
        fun ordering(ascending: Boolean) = BrowseSortSnapshot.fromValues(mapOf(
            "contents.Album.sort" to "Title", "contents.Album.ascending" to ascending))
        var sorts by mutableStateOf(ordering(true))
        var shown: BrowsePresentationState? = null
        compose.setContent {
            val state = rememberBrowsePresentation(library, emptyList(), emptyList(), LibraryFilters(),
                BrowseRoute(MainTab.Albums), BrowseStyle(), true, dispatcher, sorts)
            SideEffect { shown = state }
        }
        fun nextQuery() = requireNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Expected queued query" }
        val superseded = nextQuery()
        compose.runOnIdle { sorts = ordering(false) }
        compose.waitForIdle()
        nextQuery().run()
        compose.waitForIdle()
        superseded.run()
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(shown!!.pending)
            assertEquals(listOf("zulu"), shown!!.current!!.result.items.single().coverUris)
            assertEquals(sorts, shown!!.current!!.sorts)
        }
    }
}
