package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/**
 * #705 evidence only (no assertions about the expected look): native window frames of the real
 * BrowserScreen for a Library tab and playlist/album/artist sub-lists during a cold load, at the
 * same paused main-clock times. Row artwork reads block on FIFOs, so each row stays in its
 * artwork-gated text-skeleton stage; collection heroes are real light/dark covers decoded first.
 * Writes PNG frames and per-row bar/background pixel metrics to the additional test output.
 */
class SublistLoadingPlaceholderCaptureTest {
    // CI disables system animations; keep Compose motion at 1x so the shared shimmer actually advances.
    @get:Rule val compose = localicaAndroidComposeRule<androidx.activity.ComponentActivity>(object : androidx.compose.ui.MotionDurationScale {
        override val scaleFactor = 1f
    })
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val covers get() = File(context.filesDir, "playlist-covers")
    private val files = mutableListOf<File>()
    private val holds = mutableListOf<FileDescriptor>()
    private val output by lazy { nativeEvidenceDirectory("sublist-placeholder-705", context.getExternalFilesDir(null)) }

    private class HeldQueries : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
    }

    @Before fun clean() {
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        context.getSharedPreferences("library-query", 0).edit().clear().commit()
        ArtworkCache.invalidate()
    }

    @After fun release() {
        compose.mainClock.autoAdvance = true
        // Remove names first (no new reader can block), then end every held read with EOF.
        files.forEach { it.delete() }
        holds.forEach { runCatching { Os.close(it) } }
        ArtworkCache.invalidate()
        ArtworkColors.publish(emptyMap())
        clean()
    }

    private fun png(name: String, argb: Int): String {
        covers.mkdirs()
        val file = File(covers, "sub705-$name.png")
        file.outputStream().use { Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }
            .compress(Bitmap.CompressFormat.PNG, 100, it) }
        files += file
        return android.net.Uri.fromFile(file).toString()
    }

    /** A cover path whose read blocks until [release]: the test holds the only writer end. */
    private fun heldCover(name: String): String {
        covers.mkdirs()
        val file = File(covers, "sub705-$name.fifo")
        file.delete()
        Os.mkfifo(file.path, "600".toInt(8))
        holds += Os.open(file.path, OsConstants.O_RDWR, 0)
        files += file
        return android.net.Uri.fromFile(file).toString()
    }

    // Letter prefixes keep the hero track first under title and track-number order alike.
    private val titles = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L")
        .map { "$it held song with a long enough title" }

    /** First track is a real cover (the collection hero); the others are held. */
    private fun tracks(hero: String): List<Track> = titles.mapIndexed { index, title ->
        Track(if (index == 0) hero else heldCover("row-$index"), title, artist = "Collection artist",
            albumArtist = "Collection artist", album = "Collection album", trackNumber = index + 1,
            fileName = "sub705-$index.mp3", relativePath = "sub705-$index.mp3")
    }

    @Test fun libraryTab() = capture("library", null, light = true)
    @Test fun playlistDark() = capture("playlist", MediaKind.Playlist, light = false)
    @Test fun playlistLight() = capture("playlist", MediaKind.Playlist, light = true)
    @Test fun albumDark() = capture("album", MediaKind.Album, light = false)
    @Test fun albumLight() = capture("album", MediaKind.Album, light = true)
    @Test fun artistDark() = capture("artist", MediaKind.Artist, light = false)

    private fun capture(name: String, kind: MediaKind?, light: Boolean) {
        val variant = "$name-${if (light) "light" else "dark"}"
        val heroColor = if (light) android.graphics.Color.rgb(232, 222, 204) else android.graphics.Color.rgb(58, 50, 44)
        val hero = png("hero-$variant", heroColor)
        val tracks = tracks(hero)
        val snapshot = LibrarySnapshot(tracks = tracks)
        val playlistCover = png("playlist-cover-$variant", heroColor)
        val playlist = ManualPlaylist(id = "sub705-playlist", name = "Held playlist", coverUri = playlistCover,
            entries = tracks.mapIndexed { index, track -> SongRow("sub705-entry-$index", track) })
        val route = when (kind) {
            null -> BrowseRoute(MainTab.Songs)
            MediaKind.Playlist -> BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id), playlist.name)
            MediaKind.Album -> LibraryViews.groups(snapshot, SongListView.Albums, LibraryQuery()).single()
                .let { BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, it.id), it.title) }
            MediaKind.Artist -> LibraryViews.groups(snapshot, SongListView.Artists, LibraryQuery()).single()
                .let { BrowseRoute(MainTab.Artists, MediaRef(MediaKind.Artist, it.id), it.title) }
            else -> error("Unsupported route")
        }
        // Same explicit row style everywhere (the recordings use compact rows with trailing artist).
        BrowsePreferences(context).write(route, BrowseStyle(listSize = ListSize.Compact, extraInfo = ExtraInfo.Artist), false)
        // Stored colours mark the held covers as existing art, which gates text (#526) and tints art (#525).
        ArtworkColors.publish(tracks.drop(1).associate { it.uri to android.graphics.Color.rgb(120, 112, 104) })
        ArtworkCache.configure(context)
        // The collection header decodes its hero before the page mounts (a light or dark collection).
        runBlocking { PlayerVisualCache.read(context, hero); PlayerVisualCache.read(context, playlistCover) }
        val items = BrowseQueries.evaluate(snapshot, listOf(playlist), route).items
        val queries = HeldQueries()
        compose.setContent { LocalicaTheme {
            BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(listOf(playlist), loading = false), route,
                {}, {}, {}, { _, _, _ -> }, null, null, true, false, queryDispatcher = queries)
        } }
        compose.mainClock.autoAdvance = false
        val start = compose.mainClock.currentTime
        val log = StringBuilder("variant=$variant route=$route api=${android.os.Build.VERSION.SDK_INT} " +
            "density=${context.resources.displayMetrics.density}\n")
        fun frame(stage: String) {
            val elapsed = compose.mainClock.currentTime - start
            val image = compose.onRoot().captureNativeFrame().asAndroidBitmap()
            File(output, "$variant-$stage-t$elapsed.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            log.append("frame stage=$stage t=$elapsed shimmerPhase=${"%.3f".format(((elapsed % 1100) / 1100f) * 3f - 1f)} " +
                "catalogViewport=${compose.onAllNodesWithTag("browse-loading").fetchSemanticsNodes().isNotEmpty()} " +
                "skeletonItems=${compose.onAllNodesWithTag("browse-skeleton-item", true).fetchSemanticsNodes().size}\n")
            if (stage.startsWith("rows")) rowMetrics(image, items, stage, elapsed, log)
        }
        // Stage A: catalog query pending.
        repeat(12) { compose.mainClock.advanceTimeByFrame() }
        frame("catalog")
        // Publish the catalog; rows enter while their artwork stays held.
        var guard = 0
        while (compose.onAllNodesWithTag("browse-text-${items[0].key}", true).fetchSemanticsNodes().isEmpty() && guard++ < 300) {
            while (queries.tasks.isNotEmpty()) queries.tasks.poll()?.run()
            compose.mainClock.advanceTimeByFrame()
        }
        val published = compose.mainClock.currentTime - start
        // A collection's header fills the first viewport: jump (no fling) so held rows are on screen.
        if (kind != null) {
            val grid = compose.onAllNodes(hasScrollToIndexAction()).onFirst()
            runCatching { grid.performScrollToKey(items[1].key) }.onFailure {
                log.append("scrollToKey failed: ${it.message}; using index 1\n")
                runCatching { grid.performScrollToIndex(1) }
            }
            repeat(3) { while (queries.tasks.isNotEmpty()) queries.tasks.poll()?.run(); compose.mainClock.advanceTimeByFrame() }
        }
        log.append("catalog-published t=$published\n")
        for (offset in listOf(0L, 16L, 80L, 160L, 300L, 550L, 1100L)) {
            val target = start + published + offset
            while (compose.mainClock.currentTime < target) compose.mainClock.advanceTimeByFrame()
            frame("rows-reveal")
        }
        // Matched shimmer phases: the same absolute clock times in every variant (period 1100 ms).
        for (absolute in listOf(3300L, 3575L, 3850L, 4125L)) {
            while (compose.mainClock.currentTime < start + absolute) {
                while (queries.tasks.isNotEmpty()) queries.tasks.poll()?.run()
                compose.mainClock.advanceTimeByFrame()
            }
            frame("rows-phase")
        }
        File(output, "$variant-metrics.txt").writeText(log.toString())
        assertTrue("Held rows exist for $variant", compose.onAllNodesWithTag("browse-text-${items[1].key}", true)
            .fetchSemanticsNodes().isNotEmpty())
    }

    private fun rowMetrics(image: Bitmap, items: List<BrowserItem>, stage: String, elapsed: Long, log: StringBuilder) {
        fun luma(c: Int) = .2126f * android.graphics.Color.red(c) + .7152f * android.graphics.Color.green(c) + .0722f * android.graphics.Color.blue(c)
        fun mean(x: Int, y: Int): IntArray {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val px = (x + dx).coerceIn(0, image.width - 1); val py = (y + dy).coerceIn(0, image.height - 1)
                val c = image.getPixel(px, py); r += android.graphics.Color.red(c); g += android.graphics.Color.green(c); b += android.graphics.Color.blue(c); n++
            }
            return intArrayOf(r / n, g / n, b / n)
        }
        for ((index, item) in items.withIndex().drop(1).take(6)) {
            val text = compose.onAllNodesWithTag("browse-text-${item.key}", true).fetchSemanticsNodes().singleOrNull() ?: continue
            val art = compose.onAllNodesWithTag("browse-art-${item.key}", true).fetchSemanticsNodes().singleOrNull() ?: continue
            val info = compose.onAllNodesWithTag("browse-info-${item.key}", true).fetchSemanticsNodes().singleOrNull()
            val bounds: Rect = text.boundsInRoot
            if (bounds.bottom <= 0f || bounds.top >= image.height) continue
            val layouts = mutableListOf<TextLayoutResult>()
            runCatching { compose.onNodeWithText(item.title, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) } }
            val line = layouts.firstOrNull()
            val lineRight = line?.getLineRight(0) ?: (bounds.width / 2f)
            val y = (bounds.top + (line?.let { (it.getLineTop(0) + it.getLineBottom(0)) / 2f } ?: bounds.height / 2f)).roundToInt()
            if (y < 3 || y > image.height - 4) continue
            val bg = mean(((art.boundsInRoot.right + bounds.left) / 2f).roundToInt(), y)
            val bar = mean((bounds.left + 6f).roundToInt(), y)
            val profile = (0 until 24).map { step ->
                val x = (bounds.left + 4f + (lineRight - 8f) * step / 23f).roundToInt().coerceIn(0, image.width - 1)
                luma(image.getPixel(x, y))
            }
            val infoText = info?.boundsInRoot?.let { r ->
                val c = mean((r.right - 6f).roundToInt(), r.center.y.roundToInt())
                " infoBarEnd=${c.joinToString(",")}"
            }.orEmpty()
            log.append("row stage=$stage t=$elapsed index=$index y=$y bg=${bg.joinToString(",")} bar=${bar.joinToString(",")}" +
                " lumaBg=${"%.1f".format(luma(android.graphics.Color.rgb(bg[0], bg[1], bg[2])))}" +
                " lumaBar=${"%.1f".format(luma(android.graphics.Color.rgb(bar[0], bar[1], bar[2])))}" +
                " barProfileMin=${"%.1f".format(profile.min())} barProfileMax=${"%.1f".format(profile.max())}" +
                " lineRight=${"%.1f".format(lineRight)} textBounds=$bounds$infoText\n")
        }
    }
}
