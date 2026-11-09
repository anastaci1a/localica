package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Album carousels keep every cover's pixels on screen in every frame of select, deselect,
 * adjacent and rapid toggles (#574). Each cover is a distinct solid colour; its rest-frame
 * pixel count is the baseline, and the lifted or settling cover must never lose most of it.
 */
class CarouselSelectionArtworkTest {
    @get:Rule val compose = createComposeRule()

    private val colours = intArrayOf(0xFFE62828.toInt(), 0xFF28C83C.toInt(), 0xFF3250E6.toInt(), 0xFFE6C81E.toInt())

    private fun cover(index: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "playlist-covers").apply { mkdirs() }
        val file = File(dir, "carousel-574-$index.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(colours[index]) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "file://${file.absolutePath}"
    }

    private fun near(pixel: androidx.compose.ui.graphics.Color, argb: Int): Boolean {
        val r = (argb shr 16 and 0xFF) / 255f; val g = (argb shr 8 and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
        return kotlin.math.abs(pixel.red - r) < .16f && kotlin.math.abs(pixel.green - g) < .16f && kotlin.math.abs(pixel.blue - b) < .16f
    }

    // #678 instrumentation (log only): where counts() spends its time per method.
    private var captureNs = 0L; private var loopNs = 0L; private var frames = 0
    private fun logProfile(method: String, started: Long) {
        android.util.Log.i("Trim678", "CarouselSelectionArtworkTest $method totalMs=${(System.nanoTime() - started) / 1_000_000} frames=$frames " +
            "captureMs=${captureNs / 1_000_000} loopMs=${loopNs / 1_000_000}")
    }

    /** Pixels of each cover's colour inside its (lift-inflated) cell bounds, from one window capture. */
    private fun counts(cells: List<Pair<Int, Rect>>): IntArray {
        frames++
        var t = System.nanoTime()
        val map = compose.onRoot().captureToImage().toPixelMap()
        captureNs += System.nanoTime() - t; t = System.nanoTime()
        return IntArray(cells.size) { i ->
            val (colour, bounds) = cells[i]
            val grown = bounds.inflate(bounds.width * .12f)
            var n = 0
            for (y in grown.top.toInt().coerceAtLeast(0) until grown.bottom.toInt().coerceAtMost(map.height) step 2)
                for (x in grown.left.toInt().coerceAtLeast(0) until grown.right.toInt().coerceAtMost(map.width) step 2)
                    if (near(map[x, y], colours[colour])) n++
            n
        }.also { loopNs += System.nanoTime() - t }
    }

    private class Recorder { val failures = mutableListOf<String>() }

    /** Steps [frames] single frames after [change], checking every cover in every frame. */
    private fun Recorder.step(label: String, cells: List<Pair<Int, Rect>>, baseline: IntArray, frames: Int, change: () -> Unit) {
        compose.runOnUiThread(change)
        repeat(frames) { frame ->
            compose.mainClock.advanceTimeByFrame()
            val now = counts(cells)
            android.util.Log.i("Trim678", "$label frame ${frame + 1} counts=${now.toList()}")
            now.indices.forEach { i ->
                if (now[i] < baseline[i] * .6f) failures += "$label frame ${frame + 1}: cover ${cells[i].first} has ${now[i]} of ${baseline[i]} pixels"
            }
        }
    }

    private fun Recorder.settle(label: String, cells: List<Pair<Int, Rect>>, baseline: IntArray) {
        compose.mainClock.advanceTimeBy(600)
        val now = counts(cells)
        now.indices.forEach { i ->
            if (now[i] < baseline[i] * .6f) failures += "$label settled: cover ${cells[i].first} has ${now[i]} of ${baseline[i]} pixels"
        }
    }

    /** Fully on-screen cells only; lazy rows do not compose offscreen tiles. */
    private fun visibleCells(matchers: List<Pair<Int, SemanticsMatcher>>): List<Pair<Int, Rect>> {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        return matchers.mapNotNull { (colour, matcher) ->
            compose.onAllNodes(matcher).fetchSemanticsNodes().firstOrNull()?.let { colour to it.boundsInRoot }
        }.filter { (_, bounds) -> bounds.left >= root.left && bounds.right <= root.right && bounds.width > 0f }
    }

    private fun baseline(cells: List<Pair<Int, Rect>>): IntArray {
        lateinit var counts: IntArray
        compose.waitUntil(10000) { counts = counts(cells); counts.all { it > 50 } }
        return counts
    }

    /** Horizontal extent of each resting cover's colour on its middle row, then the gaps between neighbours (#573). */
    private fun assertGalleryGap(cells: List<Pair<Int, Rect>>) {
        val map = compose.onRoot().captureToImage().toPixelMap()
        val runs = cells.map { (colour, bounds) ->
            var top = Int.MAX_VALUE; var bottom = -1
            for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(map.height))
                if (near(map[bounds.center.x.toInt(), y], colours[colour])) { top = minOf(top, y); bottom = y }
            assertTrue("cover $colour is drawn", bottom >= 0)
            val y = (top + bottom) / 2
            var left = Int.MAX_VALUE; var right = -1
            for (x in (bounds.left.toInt() - 4).coerceAtLeast(0) until (bounds.right.toInt() + 4).coerceAtMost(map.width))
                if (near(map[x, y], colours[colour])) { left = minOf(left, x); right = x }
            left to right
        }.sortedBy { it.first }
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val gallery = 2 * (io.github.anastaci1a.localica.browse.GalleryGapDp / 2 * density + .5f).toInt()
        runs.zipWithNext().forEach { (a, b) ->
            val gap = b.first - a.second - 1
            assertEquals("cover-to-cover gap $gap px is the gallery gap $gallery px (density $density): $runs", gallery.toFloat(), gap.toFloat(), 1.5f)
        }
    }

    @Test fun artistAlbumCarouselCoversShareTheGalleryGap() {
        val names = listOf("First", "Second", "Third", "Fourth")
        val albums = LibraryViews.groups(LibrarySnapshot(tracks = names.mapIndexed { i, name ->
            Track(cover(i), name, album = name, artist = "Artist", albumArtist = "Artist", fileName = "$i", relativePath = "$i", id = "carousel:$i")
        }), SongListView.Albums, LibraryQuery())
        // One explicit album keeps the others (and their covers) at rest.
        val resting = albums.last()
        compose.setContent { LocalicaTheme { ArtistAlbumChoices(albums, listOf(resting.id)) {} } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.waitForIdle()
        val cells = visibleCells(names.mapIndexed { i, name -> i to hasText(name) }).filter { names[it.first] != resting.title }
        assertTrue("Two resting neighbours are visible: $cells", cells.size >= 2)
        baseline(cells)
        compose.mainClock.advanceTimeBy(600)
        assertGalleryGap(cells)
    }

    @Test fun homeCarouselCoversShareTheGalleryGap() {
        val tracks = (0..3).map { Track(cover(it), "Pinned $it", album = "Album $it", fileName = "$it.mp3", relativePath = "$it.mp3", id = "home-573-$it") }
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks)
        compose.setContent { LocalicaTheme {
            PinnedHome(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), tracks.map { MediaRef(MediaKind.Song, it.id) },
                {}, {}, { _, _ -> }, {}, { _, _ -> })
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("pin-${tracks[1].id}").fetchSemanticsNodes().isNotEmpty() }
        val cells = visibleCells((0..3).map { it to hasTestTag("pin-${tracks[it].id}") })
        assertTrue("Two neighbours are visible: $cells", cells.size >= 2)
        baseline(cells)
        assertGalleryGap(cells)
    }

    @Test fun artistAlbumCarouselKeepsEveryCoverThroughSingleAdjacentAndRapidToggles() {
        val names = listOf("First", "Second", "Third", "Fourth")
        val albums = LibraryViews.groups(LibrarySnapshot(tracks = names.mapIndexed { i, name ->
            Track(cover(i), name, album = name, artist = "Artist", albumArtist = "Artist", fileName = "$i", relativePath = "$i", id = "carousel:$i")
        }), SongListView.Albums, LibraryQuery())
        val ids = albums.associate { it.title to it.id }
        var explicit by mutableStateOf(emptyList<String>())
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize().testTag("carousel")) { ArtistAlbumChoices(albums, explicit) { explicit = it } }
        } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.waitForIdle()
        val cells = visibleCells(names.mapIndexed { i, name -> i to hasText(name) })
        assertTrue("At least two adjacent covers are fully visible: $cells", cells.size >= 2)
        val base = baseline(cells)
        val first = ids.getValue(names[cells[0].first]); val second = ids.getValue(names[cells[1].first])
        compose.mainClock.autoAdvance = false
        val recorder = Recorder()
        val started = System.nanoTime(); captureNs = 0; loopNs = 0; frames = 0
        try {
            repeat(3) { round ->
                with(recorder) {
                    step("r$round only first", cells, base, 16) { explicit = listOf(first) }
                    step("r$round back to all", cells, base, 16) { explicit = emptyList() }
                    step("r$round only first again", cells, base, 16) { explicit = listOf(first) }
                    step("r$round move to adjacent", cells, base, 16) { explicit = listOf(second) }
                    step("r$round join adjacent", cells, base, 16) { explicit = listOf(first, second) }
                    step("r$round leave first", cells, base, 16) { explicit = listOf(second) }
                    settle("r$round single", cells, base)
                    // Rapid: interrupt every motion after one or two frames.
                    step("r$round rapid a", cells, base, 1) { explicit = listOf(first) }
                    step("r$round rapid b", cells, base, 2) { explicit = listOf(second) }
                    step("r$round rapid c", cells, base, 1) { explicit = emptyList() }
                    step("r$round rapid d", cells, base, 2) { explicit = listOf(first, second) }
                    step("r$round rapid e", cells, base, 1) { explicit = listOf(first) }
                    step("r$round rapid f", cells, base, 16) { explicit = emptyList() }
                    settle("r$round rapid", cells, base)
                }
            }
        } finally { compose.mainClock.autoAdvance = true; logProfile("artistAlbum", started) }
        assertTrue(recorder.failures.take(40).joinToString("\n"), recorder.failures.isEmpty())
    }

    @Test fun homeCarouselKeepsEveryCoverThroughSingleAdjacentAndRapidToggles() {
        val tracks = (0..3).map { Track(cover(it), "Pinned $it", album = "Album $it", fileName = "$it.mp3", relativePath = "$it.mp3", id = "home-574-$it") }
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks)
        val pins = tracks.map { MediaRef(MediaKind.Song, it.id) }
        val session = SessionSelection()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalSessionSelection provides session) {
                PinnedHome(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), pins,
                    {}, {}, { _, _ -> }, {}, { _, _ -> })
            }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("pin-${tracks[1].id}").fetchSemanticsNodes().isNotEmpty() }
        val cells = visibleCells((0..3).map { it to hasTestTag("pin-${tracks[it].id}") })
        assertTrue("At least two adjacent covers are fully visible: $cells", cells.size >= 2)
        val base = baseline(cells)
        val a = SongRow(tracks[cells[0].first].id, tracks[cells[0].first]); val b = SongRow(tracks[cells[1].first].id, tracks[cells[1].first])
        compose.mainClock.autoAdvance = false
        val recorder = Recorder()
        val started = System.nanoTime(); captureNs = 0; loopNs = 0; frames = 0
        try {
            repeat(3) { round ->
                with(recorder) {
                    step("r$round select a", cells, base, 16) { session.toggle(null, a) }
                    step("r$round deselect a", cells, base, 16) { session.toggle(null, a) }
                    step("r$round select a again", cells, base, 16) { session.toggle(null, a) }
                    step("r$round join b", cells, base, 16) { session.toggle(null, b) }
                    step("r$round leave a", cells, base, 16) { session.toggle(null, a) }
                    step("r$round leave b", cells, base, 16) { session.toggle(null, b) }
                    settle("r$round single", cells, base)
                    step("r$round rapid a", cells, base, 1) { session.toggle(null, a) }
                    step("r$round rapid b", cells, base, 2) { session.toggle(null, b) }
                    step("r$round rapid c", cells, base, 1) { session.toggle(null, a) }
                    step("r$round rapid d", cells, base, 2) { session.toggle(null, a) }
                    step("r$round rapid e", cells, base, 1) { session.toggle(null, b) }
                    step("r$round rapid f", cells, base, 16) { session.toggle(null, a) }
                    settle("r$round rapid", cells, base)
                }
            }
        } finally { compose.mainClock.autoAdvance = true; compose.runOnUiThread { session.clear() }; logProfile("home", started) }
        assertTrue(recorder.failures.take(40).joinToString("\n"), recorder.failures.isEmpty())
    }
}
