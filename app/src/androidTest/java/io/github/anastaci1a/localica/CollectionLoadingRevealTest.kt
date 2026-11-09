package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
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
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.coroutines.CoroutineContext

/** Real BrowserScreen cold-load stages, including collection header distance. No loading substitute. */
class CollectionLoadingRevealTest {
    @get:Rule val compose = localicaAndroidComposeRule<androidx.activity.ComponentActivity>(object : androidx.compose.ui.MotionDurationScale {
        override val scaleFactor = 1f
    })
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tracks = (0..19).map { index -> Track("collection725-$index", "Song %02d".format(index),
        artist = "Artist", albumArtist = "Artist", album = "Album", trackNumber = index + 1,
        fileName = "$index.mp3", relativePath = "$index.mp3",
        locations = listOf(TrackLocation("root725", "collection725-$index", "$index.mp3"))) }
    private val snapshot = LibrarySnapshot(listOf(FolderSelection("root725", "Music")), tracks)
    private val playlists = listOf(ManualPlaylist(id = "playlist725", name = "Playlist",
        entries = tracks.mapIndexed { index, track -> SongRow("entry725-$index", track) }))
    private val output by lazy { nativeEvidenceDirectory("collection-loading-725", context.getExternalFilesDir(null)) }
    private class HeldQueries : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.poll()?.run() }
    }
    @Before fun clean() {
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
        ArtworkCache.invalidate()
    }
    @After fun restore() { compose.mainClock.autoAdvance = true; ArtworkColors.publish(emptyMap()); clean() }

    private fun route(kind: MediaKind?, search: Boolean = false): BrowseRoute {
        val base = when (kind) {
            null -> BrowseRoute(MainTab.Songs)
            MediaKind.Playlist -> BrowseRoute(MainTab.Playlists, MediaRef(kind, playlists.single().id), "Playlist")
            MediaKind.Folder -> BrowseRoute(MainTab.Folders, MediaRef(kind, "root725"), "Music")
            else -> {
                val (tab, view) = when (kind) {
                    MediaKind.Album -> MainTab.Albums to SongListView.Albums
                    MediaKind.Artist -> MainTab.Artists to SongListView.Artists
                    MediaKind.AlbumArtist -> MainTab.AlbumArtists to SongListView.AlbumArtists
                    else -> error("Unsupported collection")
                }
                LibraryViews.groups(snapshot, view, LibraryQuery()).single().let { BrowseRoute(tab, MediaRef(kind, it.id), it.title) }
            }
        }
        return base.copy(search = search, text = if (search) "Song" else "", queryCommitted = search)
    }

    @Test fun libraryColdLoad() = cold(null)
    @Test fun playlistColdLoad() = cold(MediaKind.Playlist)
    @Test fun albumColdLoad() = cold(MediaKind.Album)
    @Test fun artistColdLoad() = cold(MediaKind.Artist)
    @Test fun albumArtistColdLoad() = cold(MediaKind.AlbumArtist)
    @Test fun folderColdLoad() = cold(MediaKind.Folder)
    @Test fun playlistSearchColdLoad() = cold(MediaKind.Playlist, search = true)
    @Test fun albumSearchColdLoad() = cold(MediaKind.Album, search = true)
    @Test fun artistSearchColdLoad() = cold(MediaKind.Artist, search = true)
    @Test fun albumArtistSearchColdLoad() = cold(MediaKind.AlbumArtist, search = true)
    @Test fun folderSearchColdLoad() = cold(MediaKind.Folder, search = true)
    @Test fun reducedMotionPlaylist() = cold(MediaKind.Playlist, reduced = true)
    @Test fun queryRefreshAndCachedReturnDoNotReplayColdLoading() = cold(MediaKind.Playlist, lifecycle = true)

    private fun cold(kind: MediaKind?, search: Boolean = false, reduced: Boolean = false, lifecycle: Boolean = false) {
        val route = route(kind, search)
        val name = "${kind?.name ?: "Library"}${if (search) "-search" else ""}${if (reduced) "-reduced" else ""}${if (lifecycle) "-lifecycle" else ""}"
        val cache = BrowsePresentationCache()
        var mounted by mutableStateOf(true)
        var currentRoute by mutableStateOf(route)
        val queries = HeldQueries()
        var ready by mutableStateOf(false)
        BrowsePreferences(context).write(route, BrowseStyle(listSize = ListSize.Compact, extraInfo = ExtraInfo.None,
            quickSearch = false), false)
        ArtworkColors.publish(tracks.associate { it.uri to ArtworkColors.MISSING })
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalBrowsePresentationCache provides cache) {
            if (mounted) {
            // A fixed narrow viewport leaves several songs visible below the real collection header.
            Box(Modifier.width(200.dp).fillMaxHeight()) {
                BrowserScreen(LibraryUiState(snapshot, loading = !ready), PlaylistState(playlists, loading = !ready), currentRoute,
                    {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    reducedMotion = reduced, queryDispatcher = queries)
            }
            }
            }
        } }
        compose.mainClock.autoAdvance = false
        val log = StringBuilder("variant=$name api=${android.os.Build.VERSION.SDK_INT} density=${context.resources.displayMetrics.density}\n")
        val samples = mutableMapOf<String, MutableList<Pair<Long, Bitmap>>>()
        var publishedAt = 0L
        fun frame(stage: String) {
            val catalog = compose.onAllNodesWithTag("browse-loading").fetchSemanticsNodes().isNotEmpty()
            val skeleton = compose.onAllNodesWithTag("browse-skeleton-item", true).fetchSemanticsNodes().size
            log.append("stage=$stage t=${compose.mainClock.currentTime} catalogViewport=$catalog skeletonItems=$skeleton\n")
            val image = compose.onRoot().captureNativeFrame().asAndroidBitmap()
            if (stage == "publication" || stage.startsWith("reveal-")) {
                BrowseQueries.evaluate(snapshot, playlists, route).items.take(12).forEach { item ->
                    val bounds = compose.onAllNodesWithTag("browse-text-${item.key}", true).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                    if (bounds != null && bounds.width > 0 && bounds.height > 0 && bounds.top >= 0 && bounds.bottom < image.height) {
                        val left = bounds.left.roundToInt(); val top = bounds.top.roundToInt()
                        val width = bounds.width.roundToInt().coerceAtMost(image.width - left)
                        val height = bounds.height.roundToInt().coerceAtMost(image.height - top)
                        samples.getOrPut(item.key) { mutableListOf() }.add((compose.mainClock.currentTime - publishedAt) to
                            Bitmap.createBitmap(image, left, top, width, height))
                    }
                }
            }
            File(output, "$name-$stage.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(output, "$name-metrics.txt").writeText(log.toString())
        }
        repeat(12) { compose.mainClock.advanceTimeByFrame() }
        frame("readiness-hold")
        compose.onNodeWithTag("browse-loading").assertIsDisplayed()
        assertTrue("Cold $name must fill its skeleton viewport", compose.onAllNodesWithTag("browse-skeleton-item", true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("browser-list").assertDoesNotExist()
        assertTrue("Not ready sources must not query", queries.tasks.isEmpty())
        compose.runOnIdle { ready = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("browse-loading").assertIsDisplayed()
        frame("query-hold")
        compose.waitUntil(5000) { queries.tasks.isNotEmpty() }
        val items = BrowseQueries.evaluate(snapshot, playlists, route).items
        compose.waitUntil(5000) {
            queries.drain(); compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty()
        }
        val published = compose.mainClock.currentTime
        publishedAt = published
        frame("publication")
        compose.onNodeWithTag("browse-loading").assertDoesNotExist()
        val visible = items.filter { compose.onAllNodesWithTag("browse-text-${it.key}", true).fetchSemanticsNodes()
            .any { node -> node.boundsInRoot.height > 0 && node.boundsInRoot.top < compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom } }
        assertTrue("Rows below the actual header must be visible for $name", visible.size >= 2)
        val delays = mutableMapOf<String, Float>()
        if (!reduced) {
            visible.forEach { compose.onNodeWithTag("browse-loading-title-${it.key}", true).assertExists() }
            val positions = visible.map { item -> compose.onNodeWithTag("media-${item.key}").fetchSemanticsNode().boundsInRoot }
            assertTrue("Top-left reveal uses laid-out row order", positions.zipWithNext().all { (a, b) -> b.top > a.top })
            val viewport = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot
            visible.zip(positions).forEach { (item, bounds) ->
                delays[item.key] = hypot((bounds.left - viewport.left) / context.resources.displayMetrics.density,
                    (bounds.top - viewport.top) / context.resources.displayMetrics.density) / 1.8f
            }
            log.append("published=$published rows=${visible.map { it.key }} positions=$positions\n")
        } else visible.forEach { compose.onNodeWithTag("browse-loading-title-${it.key}", true).assertDoesNotExist() }
        for (offset in listOf(80L, 160L, 240L, 320L, 480L, 640L, 960L, 1280L)) {
            while (compose.mainClock.currentTime < published + offset) { queries.drain(); compose.mainClock.advanceTimeByFrame() }
            frame("reveal-$offset")
        }
        visible.forEach { compose.onNodeWithTag("browse-loading-title-${it.key}", true).assertDoesNotExist() }
        if (!reduced) {
            // Pixel-derived text opacity must follow the same physical-distance wave as Library.
            // Use the first and final native glyph pixels as endpoints, excluding the low-contrast background.
            for (item in visible.take(3)) {
                val row = requireNotNull(samples[item.key])
                val first = row.first().second; val last = row.last().second
                val width = minOf(first.width, last.width); val height = minOf(first.height, last.height)
                fun luma(pixel: Int) = .2126f * android.graphics.Color.red(pixel) + .7152f * android.graphics.Color.green(pixel) + .0722f * android.graphics.Color.blue(pixel)
                val mask = (0 until height).flatMap { y -> (0 until width).map { x -> x to y } }
                    .filter { (x, y) -> kotlin.math.abs(luma(last.getPixel(x, y)) - luma(first.getPixel(x, y))) > 60f }
                assertTrue("Native glyph pixels must be measurable for $name/${item.key}", mask.size > 8)
                val delay = requireNotNull(delays[item.key])
                row.drop(1).dropLast(1).forEach { (time, image) ->
                    if (image.width >= width && image.height >= height) {
                        var numerator = 0.0; var denominator = 0.0
                        mask.forEach { (x, y) ->
                            val from = luma(first.getPixel(x, y)); val delta = luma(last.getPixel(x, y)) - from
                            numerator += (luma(image.getPixel(x, y)) - from) * delta
                            denominator += delta * delta
                        }
                        val measured = (numerator / denominator).toFloat()
                        // Publication precedes the shared animation's first advancing frame by at most 4 vsyncs.
                        val low = BrowseLoadingMotion.fade(time - 64f, delay) - .12f
                        val high = BrowseLoadingMotion.fade(time + 16f, delay) + .12f
                        log.append("wave row=${item.key} t=$time delay=$delay measured=$measured expected=$low..$high\n")
                        File(output, "$name-metrics.txt").writeText(log.toString())
                        assertTrue("$name/${item.key} at $time ms: native opacity $measured outside $low..$high (header-distance delay $delay)", measured in low..high)
                    }
                }
            }
        }
        if (lifecycle) {
            compose.runOnIdle { currentRoute = currentRoute.copy(text = "Song 01") }
            compose.waitUntil(5000) { compose.mainClock.advanceTimeByFrame(); queries.tasks.isNotEmpty() }
            compose.onNodeWithTag("browser-list").assertExists()
            compose.onNodeWithTag("browse-loading").assertDoesNotExist()
            visible.forEach { compose.onNodeWithTag("browse-loading-title-${it.key}", true).assertDoesNotExist() }
            queries.drain()
            repeat(24) { compose.mainClock.advanceTimeByFrame() }
            val refreshed = BrowseQueries.evaluate(snapshot, playlists, currentRoute).items.single()
            compose.onNodeWithTag("media-${refreshed.key}").assertExists()
            compose.onNodeWithTag("browse-loading-title-${refreshed.key}", true).assertDoesNotExist()
            frame("query-refresh")
            compose.runOnIdle { mounted = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { mounted = true }
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag("browse-loading").assertDoesNotExist()
            compose.onNodeWithTag("media-${refreshed.key}").assertExists()
            compose.onNodeWithTag("browse-loading-title-${refreshed.key}", true).assertDoesNotExist()
            assertTrue("Cached return must not dispatch another query", queries.tasks.isEmpty())
            frame("cached-return")
        }
        File(output, "$name-metrics.txt").writeText(log.toString())
    }
}
