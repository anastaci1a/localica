package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

    private fun near(c: androidx.compose.ui.graphics.Color, argb: Int) =
        kotlin.math.abs(c.red - (argb shr 16 and 0xFF) / 255f) < .16f &&
            kotlin.math.abs(c.green - (argb shr 8 and 0xFF) / 255f) < .16f &&
            kotlin.math.abs(c.blue - (argb and 0xFF) / 255f) < .16f

    /** Pixels of each colour anywhere in the window, from one capture. */
    private fun counts(indices: List<Int>): IntArray {
        val map = compose.onRoot().captureToImage().toPixelMap()
        val result = IntArray(indices.size)
        for (y in 0 until map.height step 2) for (x in 0 until map.width step 2) {
            val c = map[x, y]
            indices.forEachIndexed { i, colour -> if (near(c, colours[colour])) result[i]++ }
        }
        return result
    }

    private fun drag(rowTag: (Int) -> String, moved: List<Int>, target: Int) {
        val failures = mutableListOf<String>()
        lateinit var base: IntArray
        compose.waitUntil(10000) { base = counts(moved); base.all { it > 20 } }
        val list = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot
        val from = compose.onNodeWithTag(rowTag(moved.first())).fetchSemanticsNode().boundsInRoot.center - list.topLeft
        val to = compose.onNodeWithTag(rowTag(target)).fetchSemanticsNode().boundsInRoot.center - list.topLeft
        // Trailing cards fly under the top card, so per frame the top card's cover must stay; all settle.
        fun check(label: String, all: Boolean = false) {
            val now = counts(moved)
            (if (all) now.indices else 0..0).forEach { if (now[it] < base[it] * .5f) failures += "$label: cover ${moved[it]} has ${now[it]} of ${base[it]}" }
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
            compose.waitUntil(5000) { compose.onAllNodesWithTag("media-${ids[3]}").fetchSemanticsNodes().isNotEmpty() }
            if (multiple) {
                compose.onNodeWithTag("media-${ids[1]}").performTouchInput { longClick() }
                compose.onNodeWithTag("media-${ids[2]}").performClick()
                compose.waitForIdle()
            }
            drag({ "media-${ids[it]}" }, if (multiple) listOf(1, 2) else listOf(1), 4)
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
            drag({ "media-${entries[it].id}" }, if (multiple) listOf(1, 2) else listOf(1), 4)
        } finally { compose.runOnIdle { selection.clear() } }
    }

    @Test fun playlistSingleDragKeepsItsCoverInEveryPickupAndLandingFrame() = playlist(false)
    @Test fun playlistMultiDragKeepsItsCoversInEveryPickupAndLandingFrame() = playlist(true)
    @Test fun queueSingleDragKeepsItsCoverInEveryPickupAndLandingFrame() = queue(false)
    @Test fun queueMultiDragKeepsItsCoversInEveryPickupAndLandingFrame() = queue(true)
}
