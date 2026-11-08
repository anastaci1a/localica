package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Every frame of a mutable list's drag pickup and landing keeps the moved rows' covers on
 * screen (#580): the resting row, the lifted row and the drag card hand over without a frame
 * in which none of them paints. Playlist and queue, single and multiple selection.
 */
class DragHandoffArtworkTest {
    @get:Rule val compose = createComposeRule()
    private val selection = SessionSelection()
    private val colours = intArrayOf(0xFFE62828.toInt(), 0xFF28C83C.toInt(), 0xFF3250E6.toInt(), 0xFFE6C81E.toInt(),
        0xFFE628C8.toInt(), 0xFF28D2D2.toInt())

    private fun tracks(): List<Track> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "playlist-covers").apply { mkdirs() }
        return colours.indices.map { i ->
            val file = File(dir, "drag-580-$i.png")
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(colours[i]) }
                .let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            Track("file://${file.absolutePath}", "Song $i", album = "Album $i", fileName = "$i.mp3", relativePath = "$i.mp3", id = "drag-580-$i")
        }
    }

    // For sRGB bytes, abs(channel / 255f - target / 255f) < .16f is exactly abs(channel - target) <= 40.
    // These six colour cubes are disjoint. Native fills build one exact label lookup, zero outside every cube.
    private val coverLabels by lazy {
        ByteArray(1 shl 24).also { labels ->
            colours.forEachIndexed { i, colour ->
                val r = colour shr 16 and 0xFF; val g = colour shr 8 and 0xFF; val b = colour and 0xFF
                for (red in (r - 40).coerceAtLeast(0)..(r + 40).coerceAtMost(255))
                    for (green in (g - 40).coerceAtLeast(0)..(g + 40).coerceAtMost(255)) {
                        val row = (red shl 16) or (green shl 8)
                        java.util.Arrays.fill(labels, row + (b - 40).coerceAtLeast(0),
                            row + (b + 40).coerceAtMost(255) + 1, (i + 1).toByte())
                    }
            }
        }
    }

    private data class Frame(val covers: IntArray, val titles: IntArray, val lightInk: BooleanArray, val image: ImageBitmap)

    // #678 instrumentation (log only): where counts() spends its time per method.
    private var semanticsNs = 0L; private var captureNs = 0L; private var coverLoopNs = 0L; private var titleLoopNs = 0L; private var frames = 0
    private fun logProfile(scenario: String, started: Long) {
        android.util.Log.i("Trim678", "DragHandoffArtworkTest $scenario totalMs=${(System.nanoTime() - started) / 1_000_000} frames=$frames " +
            "semanticsMs=${semanticsNs / 1_000_000} captureMs=${captureNs / 1_000_000} coverLoopMs=${coverLoopNs / 1_000_000} titleLoopMs=${titleLoopNs / 1_000_000}")
    }

    /** Cover colours and title ink from the same presented frame, including both handoff owners. */
    private fun counts(indices: List<Int>, lightInk: BooleanArray? = null): Frame {
        frames++
        var t = System.nanoTime()
        val root = compose.onRoot()
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val titleBounds = indices.map { index ->
            compose.onAllNodesWithText("Song $index", useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.boundsInRoot.translate(-origin).inflate(4 * compose.density.density) }.distinct()
        }
        semanticsNs += System.nanoTime() - t; t = System.nanoTime()
        val image = root.captureToImage()
        val map = image.toPixelMap()
        captureNs += System.nanoTime() - t; t = System.nanoTime()
        val labels = coverLabels
        val pixels = map.buffer
        val hits = IntArray(colours.size + 1)
        for (y in 0 until map.height step 2) for (x in 0 until map.width step 2) {
            hits[labels[pixels[map.bufferOffset + y * map.stride + x] and 0xFFFFFF].toInt()]++
        }
        val result = IntArray(indices.size) { hits[indices[it] + 1] }
        coverLoopNs += System.nanoTime() - t; t = System.nanoTime()
        // Playlist detail inherits white ink over its coloured artwork backdrop; queue
        // rows can use dark ink. Infer the baseline polarity from the title's surrounding
        // background, then hold it fixed so an empty frame cannot redefine the probe.
        fun luminance(x: Int, y: Int): Float {
            val c = map[x, y]
            return .2126f * c.red + .7152f * c.green + .0722f * c.blue
        }
        val polarity = lightInk ?: BooleanArray(indices.size) { i ->
            titleBounds[i].firstOrNull()?.let { bounds ->
                val y = bounds.top.toInt().coerceIn(0, map.height - 1)
                val left = bounds.left.toInt().coerceIn(0, map.width - 1)
                val right = bounds.right.toInt().coerceIn(left + 1, map.width)
                (left until right).map { x -> luminance(x, y) }.average() < .5
            } ?: false
        }
        val titles = IntArray(indices.size) { i ->
            titleBounds[i].maxOfOrNull { bounds ->
                var ink = 0
                for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(map.height))
                    for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(map.width)) {
                        val light = luminance(x, y)
                        if (if (polarity[i]) light > .65f else light < .4f) ink++
                    }
                ink
            } ?: 0
        }
        titleLoopNs += System.nanoTime() - t
        return Frame(result, titles, polarity, image)
    }

    private fun drag(scenario: String, rowTag: (Int) -> String, moved: List<Int>, target: Int) {
        val started = System.nanoTime()
        semanticsNs = 0; captureNs = 0; coverLoopNs = 0; titleLoopNs = 0; frames = 0
        val failures = mutableListOf<String>()
        lateinit var base: Frame
        compose.waitUntil(10000) { base = counts(moved); base.covers.all { it > 20 } && base.titles.all { it > 20 } }
        val list = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot
        val from = compose.onNodeWithTag(rowTag(moved.first())).fetchSemanticsNode().boundsInRoot.center - list.topLeft
        val to = compose.onNodeWithTag(rowTag(target)).fetchSemanticsNode().boundsInRoot.center - list.topLeft
        // Trailing cards fly under the top card, so per frame the top card's cover must stay; all settle.
        fun check(label: String, all: Boolean = false) {
            val now = counts(moved, base.lightInk)
            android.util.Log.i("Trim678", "$scenario $label covers=${now.covers.toList()} titles=${now.titles.toList()}")
            val before = failures.size
            (if (all) now.covers.indices else 0..0).forEach {
                if (now.covers[it] < base.covers[it] * .5f)
                    failures += "$label: cover ${moved[it]} has ${now.covers[it]} of ${base.covers[it]}"
                if (now.titles[it] < base.titles[it] * .35f)
                    failures += "$label: title ${moved[it]} has ${now.titles[it]} of ${base.titles[it]} ink pixels"
            }
            if (failures.size > before && before < 8) {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val dir = nativeEvidenceDirectory("drag-handoff", context.cacheDir)
                File(dir, "$scenario-${label.replace(' ', '-')}.png").outputStream().use {
                    now.image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        compose.mainClock.autoAdvance = false
        var held = false
        try {
            compose.onNodeWithTag("browser-list").performTouchInput { down(from); held = true }
            // Every frame through recognition, pickup and the first flight.
            repeat(60) { frame -> compose.mainClock.advanceTimeByFrame(); check("pickup frame ${frame + 1}") }
            compose.onNodeWithTag("browser-list").performTouchInput { moveTo(from + Offset(0f, 30f)) }
            repeat(10) { frame -> compose.mainClock.advanceTimeByFrame(); check("move frame ${frame + 1}") }
            compose.onNodeWithTag("browser-list").performTouchInput { moveTo(to) }
            repeat(6) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag("browser-list").performTouchInput { up(); held = false }
            repeat(60) { frame -> compose.mainClock.advanceTimeByFrame(); check("landing frame ${frame + 1}") }
        } finally {
            if (held) compose.onNodeWithTag("browser-list").performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("drag-stack").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        check("settled", all = true)
        logProfile(scenario, started)
        assertTrue(failures.take(30).joinToString("\n"), failures.isEmpty())
    }

    private fun playlist(multiple: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = PlaylistController(db.playlists(), scope)
        try {
            val tracks = tracks()
            runBlocking {
                controller.state.first { !it.loading }
                val saved = CompletableDeferred<Boolean>()
                controller.create("Mix", tracks) { saved.complete(it) }
                assertTrue(withTimeout(5000) { saved.await() })
            }
            val playlist = controller.state.value.playlists.single()
            val library = LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false)
            val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id), key = "drag-580")
            compose.setContent { LocalicaTheme {
                val state by controller.state.collectAsState()
                val host = remember { SongDragHost() }
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalSessionSelection provides selection, LocalSongDragHost provides host) {
                        BrowserScreen(library, state, route, {}, {}, {}, { _, _, _ -> }, null, controller, true, false)
                    }
                    ForegroundSongDragOverlay(host)
                }
            } }
            val ids = playlist.entries.map { it.id }
            // The playlist hero is real: arrange the song rows once, as DragReorderTest does.
            var arranged = false
            compose.waitUntil(5000) {
                var ready = false
                compose.runOnUiThread { ready = selection.visible.any { it.id == ids.first() } }
                if (!arranged && ready) { compose.onNodeWithTag("browser-list").performScrollToKey(ids.first()); arranged = true }
                compose.onAllNodesWithTag("media-${ids[4]}").fetchSemanticsNodes().isNotEmpty()
            }
            if (multiple) {
                compose.onNodeWithTag("media-${ids[1]}").performTouchInput { longClick() }
                compose.onNodeWithTag("media-${ids[2]}").performClick()
                compose.waitForIdle()
            }
            drag("playlist-$multiple", { "media-${ids[it]}" }, if (multiple) listOf(1, 2) else listOf(1), 4)
        } finally { compose.runOnIdle { selection.clear() }; scope.cancel(); db.close() }
    }

    private fun queue(multiple: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        val tracks = tracks()
        val entries = tracks.mapIndexed { i, track -> QueueEntry("drag-580-entry-$i", track) }
        val queue = ListeningQueue(id = "drag-580", name = "Drag queue", entries = entries, currentEntryId = entries[0].id)
        val queueState = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
        val library = LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false)
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id), title = "Drag queue", key = "drag-580-queue")
        val host = SongDragHost()
        compose.setContent { LocalicaTheme {
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalSessionSelection provides selection, LocalSongDragHost provides host) {
                    BrowserScreen(library, PlaylistState(loading = false), route, {}, {}, {}, { _, _, _ -> }, null, null,
                        true, false, queueState = queueState, playingEntryId = entries[0].id)
                }
                ForegroundSongDragOverlay(host)
            }
        } }
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithTag("media-${entries[3].id}").fetchSemanticsNodes().isNotEmpty() }
            if (multiple) {
                compose.onNodeWithTag("media-${entries[1].id}").performTouchInput { longClick() }
                compose.onNodeWithTag("media-${entries[2].id}").performClick()
                compose.waitForIdle()
            }
            drag("queue-$multiple", { "media-${entries[it].id}" }, if (multiple) listOf(1, 2) else listOf(1), 4)
        } finally { compose.runOnIdle { selection.clear() } }
    }

    @Test fun playlistSingleDragKeepsItsCoverInEveryPickupAndLandingFrame() = playlist(false)
    @Test fun playlistMultiDragKeepsItsCoversInEveryPickupAndLandingFrame() = playlist(true)
    @Test fun queueSingleDragKeepsItsCoverInEveryPickupAndLandingFrame() = queue(false)
    @Test fun queueMultiDragKeepsItsCoversInEveryPickupAndLandingFrame() = queue(true)
}
