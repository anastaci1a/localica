package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
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

/**
 * #503: every filter switch is a hard reset of the browse view. Filters share item keys, so a
 * playlist cover must never stay at, or animate from, its bounds in the previous filter's layout:
 * the switch shows the loading placeholders and the new layout appears without bounds motion.
 */
class FilterSwitchResetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class HeldQueries : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }

    @Before fun clear() {
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
    }

    @After fun restore() { compose.mainClock.autoAdvance = true; clear() }

    private fun nodes(prefix: String, unmerged: Boolean) = compose.onAllNodes(SemanticsMatcher(prefix) {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }, useUnmergedTree = unmerged).fetchSemanticsNodes()
        // Prefetched offscreen rows can publish semantics before they have visible bounds.
        .filter { !it.boundsInRoot.isEmpty }

    private fun tag(node: SemanticsNode) = node.config[SemanticsProperties.TestTag]
    /** Playlist covers, keyed by item: the artwork box inside each merged card. */
    private fun covers() = nodes("browse-art-Playlist:", true).associate { tag(it) to it.boundsInRoot }
    private fun cards() = nodes("media-Playlist:", false).map { it.boundsInRoot }
    private fun loading() = compose.onAllNodesWithTag("browse-loading").fetchSemanticsNodes().isNotEmpty()

    private fun same(a: Rect, b: Rect) = kotlin.math.abs(a.left - b.left) < 1f && kotlin.math.abs(a.top - b.top) < 1f &&
        kotlin.math.abs(a.width - b.width) < 1f && kotlin.math.abs(a.height - b.height) < 1f

    /** Clicks the chip and requires the route to have switched exactly once, before any cover check. */
    private fun switchFilter(routes: List<BrowseRoute>, current: () -> BrowseRoute, expected: MediaKind?) {
        val before = routes.size
        compose.onNodeWithTag("filter-Playlists").assertIsDisplayed().performClick()
        var frames = 0
        while (current().filter != expected && frames++ < 10) compose.mainClock.advanceTimeBy(16)
        assertEquals("The filter click must switch the route (route calls ${routes.drop(before).map { it.filter }})",
            expected, current().filter)
        assertEquals("The switch must be one route change, never reverted", listOf(expected), routes.drop(before).map { it.filter })
    }

    @Test fun playlistsFilterShowsLoadingAndNoCoverStaysAtOrMovesFromItsPreviousBounds() {
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
        val routes = mutableListOf<BrowseRoute>()
        var route by mutableStateOf(root)
        compose.setContent { LocalicaTheme {
            // Only two chips, so the Playlists chip is on screen at any test display width.
            BrowserScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaylistState(playlists, loading = false),
                route, { routes += it; route = it }, {}, {}, { _, _, _ -> }, null, null, true, false,
                libraryFilters = LibraryFilters(listOf(LibraryFilter.Albums, LibraryFilter.Playlists),
                    allCollections = setOf(MediaKind.Album, MediaKind.Playlist)), queryDispatcher = queries)
        } }
        compose.waitUntil(5000) { queries.tasks.isNotEmpty() }
        queries.tasks.poll(5, TimeUnit.SECONDS)!!.run()
        compose.waitUntil(10000) { covers().isNotEmpty() }
        compose.waitForIdle()
        val gallery = covers()
        val galleryCard = cards().first().width
        assertTrue("Unfiltered playlists must be gallery cells", cards().all { it.width < galleryCard * 1.01f })

        // Gallery -> Playlists (List), with the new query held so the switch frames are observable.
        compose.mainClock.autoAdvance = false
        switchFilter(routes, { route }, MediaKind.Playlist)
        var sawLoading = false
        repeat(4) { frame ->
            compose.mainClock.advanceTimeBy(16)
            covers().forEach { (key, bounds) -> assertFalse("$key is still laid out at its gallery bounds after the switch (held frame $frame)",
                gallery[key]?.let { same(it, bounds) } == true) }
            sawLoading = sawLoading || loading()
        }
        assertTrue("A filter switch must show the loading placeholders", sawLoading)
        queries.drain()
        val toList = mutableListOf<Map<String, Rect>>()
        repeat(150) {
            compose.mainClock.advanceTimeBy(16)
            queries.drain()
            toList += covers()
        }
        val list = toList.last()
        assertTrue("Playlist list rows must be visible", list.isNotEmpty() && cards().all { it.width > galleryCard * 2f })
        toList.forEachIndexed { frame, covers -> covers.forEach { (key, bounds) ->
            assertFalse("$key was laid out at its gallery bounds at list frame $frame", gallery[key]?.let { same(it, bounds) } == true)
            val final = list[key] ?: return@forEach
            assertEquals("$key left at list frame $frame (no motion from the gallery)", final.left, bounds.left, 1f)
            assertEquals("$key width at list frame $frame (no scaling from the gallery)", final.width, bounds.width, 1f)
        } }

        // Playlists (List) -> no filter: the cached gallery is also a hard reset, never a list-to-gallery morph.
        switchFilter(routes, { route }, null)
        sawLoading = false
        val toGallery = mutableListOf<Map<String, Rect>>()
        repeat(150) {
            compose.mainClock.advanceTimeBy(16)
            queries.drain()
            sawLoading = sawLoading || loading()
            toGallery += covers()
        }
        assertTrue("Returning to the cached unfiltered view must also show the loading placeholders", sawLoading)
        val back = toGallery.last()
        assertTrue("Gallery covers must be visible again", back.isNotEmpty())
        toGallery.forEachIndexed { frame, covers -> covers.forEach { (key, bounds) ->
            assertFalse("$key was laid out at its list bounds at gallery frame $frame", list[key]?.let { same(it, bounds) } == true)
            val final = back[key] ?: return@forEach
            assertEquals("$key left at gallery frame $frame (no motion from the list)", final.left, bounds.left, 1f)
            assertEquals("$key width at gallery frame $frame (no scaling from the list)", final.width, bounds.width, 1f)
        } }
    }
}
