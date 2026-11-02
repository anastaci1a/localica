package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** #503: a filter switch is a hard reset; playlist covers never travel from gallery to list bounds. */
class FilterSwitchResetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class HeldQueries : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
    }

    @Before fun clear() {
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
    }

    @After fun restore() { compose.mainClock.autoAdvance = true; clear() }

    private fun playlistCards() = compose.onAllNodes(SemanticsMatcher("playlist card") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("media-Playlist:") == true
    }).fetchSemanticsNodes()

    @Test fun playlistsFilterShowsLoadingAndNoCoverIsDrawnAtItsGalleryBounds() {
        val root = BrowseRoute(MainTab.Library)
        val prefs = BrowsePreferences(context)
        prefs.write(root, BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5), false)
        prefs.write(root.copy(filter = MediaKind.Playlist), BrowseStyle(presentation = Presentation.List), false)
        val tracks = (0 until 40).map { Track("song:$it", "Song $it", album = "Zed album ${it % 20}",
            fileName = "$it.mp3", relativePath = "$it.mp3") }
        val playlists = (0 until 8).map { index ->
            ManualPlaylist(id = "p$index", name = "Alpha playlist $index", entries = listOf(SongRow("entry$index", tracks[index])))
        }
        val queries = HeldQueries()
        var route by mutableStateOf(root)
        compose.setContent { LocalicaTheme {
            BrowserScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaylistState(playlists, loading = false),
                route, { route = it }, {}, {}, { _, _, _ -> }, null, null, true, false,
                libraryFilters = LibraryFilters(allCollections = setOf(MediaKind.Album, MediaKind.Playlist)), queryDispatcher = queries)
        } }
        compose.waitUntil(5000) { queries.tasks.isNotEmpty() }
        queries.tasks.poll(5, TimeUnit.SECONDS)!!.run()
        compose.waitUntil(10000) { playlistCards().isNotEmpty() }
        compose.waitForIdle()
        val gallery = playlistCards().map { it.boundsInRoot }
        assertTrue("Unfiltered playlists must be gallery cells", gallery.all { it.width < gallery.first().width * 1.01f && it.width > 0f })
        val galleryWidth = gallery.first().width

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("filter-Playlists").performClick()
        // The new query is held: the switch must show loading placeholders, not the old gallery cells.
        repeat(25) {
            compose.mainClock.advanceTimeBy(16)
            assertTrue("Old gallery covers must not survive the filter switch (frame $it)", playlistCards().isEmpty())
        }
        compose.onNodeWithTag("browse-loading").assertIsDisplayed()

        compose.waitUntil(5000) { queries.tasks.isNotEmpty() }
        queries.tasks.poll(5, TimeUnit.SECONDS)!!.run()
        val frames = mutableListOf<Map<String, androidx.compose.ui.geometry.Rect>>()
        repeat(150) {
            compose.mainClock.advanceTimeBy(16)
            frames += playlistCards().associate { n -> n.config[SemanticsProperties.TestTag] to n.boundsInRoot }
        }
        val final = frames.last()
        assertTrue("Playlist list rows must be visible", final.isNotEmpty())
        assertTrue("Final rows are list rows, not gallery cells", final.values.all { it.width > galleryWidth * 2f })
        frames.forEachIndexed { index, frame ->
            frame.forEach { (tag, bounds) ->
                val expected = final.getValue(tag)
                assertEquals("$tag left at frame $index", expected.left, bounds.left, 1f)
                assertEquals("$tag width at frame $index", expected.width, bounds.width, 1f)
            }
        }
    }
}
