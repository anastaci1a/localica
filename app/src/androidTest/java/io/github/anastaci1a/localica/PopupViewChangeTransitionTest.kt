package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * #627: a view saved from the View popup takes the same anchored, continuous transaction as a
 * pinch. Every presented frame from the apply onwards is captured on the paused clock (the
 * popup retracts over the list meanwhile) and each tracked cover must move monotonically, in
 * bounded steps, without blinking, while the topmost fully visible item keeps its place.
 */
class PopupViewChangeTransitionTest {
    @get:Rule val compose = localicaComposeRule()

    private val list = BrowseStyle(listSize = ListSize.Normal, quickSearch = false)
    private fun gallery(columns: Int) = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = columns, quickSearch = false)

    @Test fun listToGallery3() = inspect(list, gallery(3))
    @Test fun gallery3ToList() = inspect(gallery(3), list)
    @Test fun gallery3To5() = inspect(gallery(3), gallery(5))
    @Test fun gallery5To2() = inspect(gallery(5), gallery(2))
    @Test fun gallery2To4() = inspect(gallery(2), gallery(4))
    @Test fun gallery4To1() = inspect(gallery(4), gallery(1))
    @Test fun gallery1To3() = inspect(gallery(1), gallery(3))
    @Test fun listToGallery3WithSelection() = inspect(list, gallery(3), selected = true)
    @Test fun gallery3To5WithSelection() = inspect(gallery(3), gallery(5), selected = true)
    @Test fun subviewGallery3To5() = inspect(gallery(3), gallery(5), subview = true)
    @Test fun subviewListToGallery4WithSelection() = inspect(list, gallery(4), subview = true, selected = true)

    private class Cover(val area: Int, val box: Rect?)
    private class Frame(val label: String, val time: Long, val covers: List<Cover>)

    // Saturated palette in {0, 128, 255}; greys and the frame marker's green family excluded.
    private val palette = buildList {
        val levels = listOf(0, 128, 255)
        for (r in levels) for (g in levels) for (b in levels) {
            if (r == g && g == b) continue
            if (g == 255 && b == 0) continue
            add(Triple(r, g, b))
        }
    }

    private fun inspect(source: BrowseStyle, target: BrowseStyle, selected: Boolean = false, subview: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val tracks = List(140) { index -> Track("popup-view:$index", "Item ${index.toString().padStart(3, '0')}",
            artist = "Popup View", album = "Popup View", fileName = "$index.mp3", relativePath = "$index.mp3") }
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("popup-view-root", "Music")), tracks = tracks)
        val route = if (subview) {
            val album = LibraryViews.groups(snapshot, SongListView.Albums, LibraryQuery()).single()
            BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, album.id), "Popup View")
        } else BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        // The topmost fully visible item is leftmost in every source row (60 is divisible by 1-5).
        val anchor = 60
        val tracked = tracks.subList(anchor, anchor + palette.size)
        val selection = SessionSelection().apply {
            if (selected) select(route.collection, listOf(tracks[anchor], tracks[anchor + 2]).map { SongRow(it.id, it) })
        }
        val bitmaps = mutableListOf<Bitmap>()
        fun solid(color: Int) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.also { bitmaps += it }
        val grey = solid(android.graphics.Color.rgb(96, 96, 96))
        // A solid cover is exact at any size; a large edge keeps it warm instead of reloading a missing file.
        val artwork = BrowseArtworkScope().apply {
            tracks.forEachIndexed { index, track ->
                val colour = palette.getOrNull(index - anchor)
                hold(track.id, listOf(track.uri), ArtworkCache.CachedArtwork(
                    colour?.let { (r, g, b) -> solid(android.graphics.Color.rgb(r, g, b)) } ?: grey, 4096))
            }
        }
        var stage by mutableIntStateOf(0)
        var sorting by mutableStateOf(false)
        val frames = mutableListOf<Frame>()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: requireNotNull(context.getExternalFilesDir(null))
        val name = "${if (subview) "subview" else "root"}-${source.label()}-${target.label()}${if (selected) "-selected" else ""}"
        val output = File(root, "popup-view-change/$name").also { it.mkdirs() }
        prefs.write(route, source, true)
        try {
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalBrowseArtwork provides artwork, LocalReducedMotion provides false,
                    LocalSessionSelection provides selection) {
                    Box(Modifier.size(360.dp, 640.dp).testTag("popup-view-scene")) {
                        BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route, {}, {}, {},
                            { _, _, _ -> fail("Changing the view must not start playback") }, null, null, true, false,
                            sorting = sorting, onSortDismiss = { sorting = false }, presentationOnly = true,
                            queryDispatcher = Dispatchers.Unconfined)
                        PresentationMarker(composeStage = true) { stage }
                    }
                }
            } }
            val grid = compose.onNodeWithTag("browser-list")
            val scene = compose.onNodeWithTag("popup-view-scene")
            compose.waitForIdle()
            grid.performScrollToIndex(anchor + 1)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browse-art-${tracks[anchor].id}", true).fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()

            sorting = true
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browse-options-dialog").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            if (source.presentation != target.presentation)
                compose.onNodeWithText(if (target.presentation == Presentation.List) "List" else "Gallery", substring = false).performClick()
            if (target.presentation != Presentation.List) {
                compose.onNodeWithContentDescription("Items per row").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(target.itemsPerRow!!.toFloat()) }
                compose.onNodeWithText("Items per row: ${target.itemsPerRow}").assertExists()
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false

            fun sample(label: String): Frame {
                compose.runOnUiThread { stage = (stage + 1) % 24 }
                compose.mainClock.advanceTimeByFrame()
                val png = if (label in setOf("popup-open", "source", "apply-0", "apply-6", "apply-30", "settled-3")) File(output, "$label.png") else null
                val frame = Frame(label, compose.mainClock.currentTime, measure(scene, stage, density, png))
                frames += frame
                return frame
            }
            sample("popup-open")
            val before = sample("source")
            compose.onNodeWithText("Save", substring = false).performSemanticsAction(SemanticsActions.OnClick)
            repeat(70) { sample("apply-$it") }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            repeat(4) { sample("settled-$it") }
            val after = frames.last()
            compose.runOnIdle { assertFalse("Save closes the popup", sorting) }
            assertEquals("The saved view persists", target.presentation, prefs.read(route).presentation)
            if (target.presentation != Presentation.List) assertEquals(target.itemsPerRow, prefs.read(route).itemsPerRow)

            val tolerance = 2f
            val moving = frames.dropWhile { it !== before }
            var compared = 0
            tracked.indices.forEach { i ->
                val s = before.covers[i]; val f = after.covers[i]
                val start = s.box; val end = f.box
                if (start == null || end == null) return@forEach
                // Only covers fully inside the scene at both endpoints are continuous witnesses.
                if (!inside(start, scene) || !inside(end, scene)) return@forEach
                compared++
                val item = "item ${anchor + i}"
                // A selected cover's card inset eases on the shared selection geometry, overshooting about 2 dp.
                val span = if (selected && (i == 0 || i == 2)) 3f * density else tolerance
                fun edges(r: Rect) = floatArrayOf(r.left, r.top, r.right, r.bottom)
                val from = edges(start); val to = edges(end)
                var previous = from
                var elapsed = 0
                moving.drop(1).forEach { frame ->
                    val cover = frame.covers[i]
                    val box = cover.box
                    assertTrue("$item stays presented through the transition (no blink) at ${frame.label}: area ${cover.area} " +
                        "versus ${s.area}/${f.area}", box != null && cover.area >= min(s.area, f.area) / 8)
                    elapsed++
                    // Morphing cells cross; a clipped cover (not a solid square) only proves presence.
                    if (occluded(cover)) return@forEach
                    val now = edges(box!!)
                    for (e in 0 until 4) {
                        val range = abs(to[e] - from[e])
                        val step = now[e] - previous[e]
                        assertTrue("$item edge $e jumps $step px over $elapsed frame(s) at ${frame.label} (range $range): $start -> $end",
                            abs(step) <= (range * .34f + 3f * density) * elapsed)
                        assertTrue("$item edge $e reverses by $step px at ${frame.label}: $start -> $end",
                            step * sign(to[e] - from[e]) >= -tolerance || range < tolerance)
                        assertTrue("$item edge $e leaves its source/destination span at ${frame.label}: ${now[e]} not in " +
                            "${from[e]}..${to[e]}", now[e] >= min(from[e], to[e]) - span && now[e] <= max(from[e], to[e]) + span)
                    }
                    previous = now; elapsed = 0
                }
                if (i == 0) assertEquals("The topmost visible item keeps its place: $start -> $end", start.top, end.top, 12f * density)
            }
            // One gallery-1 cover fills most of the scene, so only the anchor fits at that end.
            val witnesses = if (listOf(source, target).any { it.presentation != Presentation.List && it.itemsPerRow == 1 }) 1 else 2
            assertTrue("At least $witnesses continuous witness(es), found $compared", compared >= witnesses)
        } finally {
            File(output, "frames.csv").writeText("label,time," + tracked.indices.joinToString(",") {
                "a${anchor + it},l${anchor + it},t${anchor + it},r${anchor + it},b${anchor + it}" } + "\n" +
                frames.joinToString("\n") { frame -> "${frame.label},${frame.time}," + frame.covers.joinToString(",") {
                    "${it.area},${it.box?.left},${it.box?.top},${it.box?.right},${it.box?.bottom}" } })
            compose.mainClock.autoAdvance = true
            prefs.write(route, original, overridden)
            bitmaps.forEach { it.recycle() }
        }
    }

    private fun occluded(cover: Cover): Boolean {
        val box = cover.box ?: return true
        return abs(box.width - box.height) > 4f || cover.area < box.width * box.height * .85f
    }

    private fun inside(box: Rect, scene: SemanticsNodeInteraction): Boolean {
        val bounds = scene.fetchSemanticsNode().size
        return box.left > 1f && box.top > 1f && box.right < bounds.width - 1f && box.bottom < bounds.height - 1f
    }

    private fun BrowseStyle.label() = if (presentation == Presentation.List) "list" else "gallery$itemsPerRow"

    /** Captures the scene's own window (never the popup's) once the marker proves the frame. */
    private fun measure(scene: SemanticsNodeInteraction, stage: Int, density: Float, png: File? = null): List<Cover> {
        lateinit var pixels: IntArray
        var width = 0; var height = 0
        compose.waitUntil(5000) {
            val bitmap = scene.captureToImage().asAndroidBitmap()
            try {
                width = bitmap.width; height = bitmap.height
                val marker = bitmap.getPixel(2, 2)
                val ok = android.graphics.Color.green(marker) > 230 && android.graphics.Color.blue(marker) < 25 &&
                    abs(android.graphics.Color.red(marker) - stage * 8) <= 2
                if (ok) pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
                if (ok && png != null) png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ok
            } finally { bitmap.recycle() }
        }
        val marker = (10 * density).toInt()
        val rows = Array(palette.size) { IntArray(height) }
        val columns = Array(palette.size) { IntArray(width) }
        val index = HashMap<Int, Int>().apply { palette.forEachIndexed { i, (r, g, b) -> put(r * 65536 + g * 256 + b, i) } }
        fun level(value: Int) = when { value <= 12 -> 0; abs(value - 128) <= 12 -> 128; value >= 243 -> 255; else -> -1 }
        for (y in 0 until height) for (x in 0 until width) {
            if (x < marker && y < marker) continue
            val pixel = pixels[y * width + x]
            val r = level(android.graphics.Color.red(pixel)); if (r < 0) continue
            val g = level(android.graphics.Color.green(pixel)); if (g < 0) continue
            val b = level(android.graphics.Color.blue(pixel)); if (b < 0) continue
            val i = index[r * 65536 + g * 256 + b] ?: continue
            rows[i][y]++; columns[i][x]++
        }
        return palette.indices.map { i ->
            val area = rows[i].sum()
            val peakRow = rows[i].max(); val peakColumn = columns[i].max()
            // Ignore sparse look-alike pixels (antialiasing, glass) outside the solid cover.
            val minimum = (12 * density / 2.625f).toInt().coerceAtLeast(4)
            if (peakRow < minimum || peakColumn < minimum) Cover(0, null) else {
                val top = rows[i].indexOfFirst { it >= peakRow / 2 }; val bottom = rows[i].indexOfLast { it >= peakRow / 2 } + 1
                val left = columns[i].indexOfFirst { it >= peakColumn / 2 }; val right = columns[i].indexOfLast { it >= peakColumn / 2 } + 1
                Cover(area, Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
            }
        }
    }
}
