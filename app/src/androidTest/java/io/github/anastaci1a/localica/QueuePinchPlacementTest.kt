package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** #957 slice B: pinch indices are captured for visible keys only. At a mid-queue anchor, every
 * drawn key, including destination-only and retained ones, settles in its destination cell, and the
 * anchor stays on screen. Queue opening and restoration are QueueFollowingTest's. */
class QueuePinchPlacementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listToGalleryAtMidQueue() = pinch(BrowseStyle(listSize = ListSize.Large), outward = true)
    @Test fun galleryToListAtMidQueue() = pinch(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 4), outward = false)

    private fun pinch(start: BrowseStyle, outward: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tracks = (0 until 160).map { Track("pinch957:$it", "Song ${it.toString().padStart(3, '0')}", fileName = "$it.mp3", relativePath = "$it.mp3") }
        // Duplicate tracks: occurrence keys, not track ids, own each cell.
        val queue = ListeningQueue("pinch957", "Pinch", entries = List(400) { QueueEntry("o$it", tracks[it % tracks.size]) }, currentEntryId = null)
        val route = BrowseRoute(MainTab.Queues, MediaRef(MediaKind.Queue, queue.id), title = "Pinch", key = "pinch-957")
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        prefs.write(route, start, true)
        val frames = mutableListOf<PinchDrawnFrame>()
        try {
            val library = LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false)
            val queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)
            compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
                BrowserScreen(library, PlaylistState(loading = false), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queueState = queues, queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            } } }
            val list = compose.onNodeWithTag("browser-list")
            compose.waitForIdle()
            list.performScrollToIndex(200)
            compose.waitForIdle()
            pinchDrawProbe = { frames += it }
            compose.mainClock.autoAdvance = false
            val begin = if (outward) 150f else 300f
            val end = if (outward) begin * 1.85f else begin * .52f
            list.performTouchInput { down(0, Offset(centerX, centerY - begin)); down(1, Offset(centerX, centerY + begin)) }
            compose.mainClock.advanceTimeByFrame()
            for (frame in 1..110) {
                val half = begin + (end - begin) * frame / 110
                list.performTouchInput {
                    updatePointerTo(0, Offset(centerX, centerY - half))
                    updatePointerTo(1, Offset(centerX, centerY + half))
                    move(delayMillis = 0)
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                list.captureToImage()
            }
            list.performTouchInput { up(0); up(1) }
            // Paint the settle too: its last frame is the destination the overlay hands to the grid.
            repeat(90) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); list.captureToImage() }
            pinchDrawProbe = null
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()

            val label = "${start.presentation}/${start.listSize}/${start.itemsPerRow} ${if (outward) "out" else "in"}"
            val captured = frames.first()
            val final = frames.last()
            android.util.Log.i("QueuePinch957", "$label frames=${frames.size} held=${frames.count { it.held }} ready=${frames.count { it.destinationReady }} " +
                "captured=${captured.items.size} retainedFrames=${frames.count { f -> f.items.values.any { it.retained } }} " +
                "maxRetained=${frames.maxOf { f -> f.items.values.count { it.retained } }} final=${final.items.size} p=${final.progress}")
            assertTrue("$label settled at ${final.progress}", final.progress > .999f && !final.held)
            assertTrue("$label drew retained keys while held", frames.any { frame -> frame.held && frame.items.values.any { it.retained } })
            fun settled(key: Any) = compose.onAllNodesWithTag("media-$key").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            val pairs = final.items.filter { (_, item) -> item.alpha > .5f && item.bounds.visibleIn(final.viewport) }
                .mapNotNull { (key, item) -> settled(key)?.let { Triple(key, item.bounds, it) } }
            assertTrue("$label compares enough cells: ${pairs.size}", pairs.size >= 4)
            // One translation separates the probe's viewport space from the root.
            val dx = pairs.map { it.third.left - it.second.left }.sorted()[pairs.size / 2]
            val dy = pairs.map { it.third.top - it.second.top }.sorted()[pairs.size / 2]
            for ((key, drawn, cell) in pairs) assertTrue("$label: $key drew at $drawn but settles at $cell (offset $dx, $dy)",
                abs(cell.left - drawn.left - dx) <= 2f && abs(cell.top - drawn.top - dy) <= 2f)
            assertTrue("$label includes destination-only keys", pairs.any { it.first !in captured.items })
            val indices = pairs.map { (it.first as String).removePrefix("o").toInt() }
            assertTrue("$label stays mid-queue: $indices", indices.all { it in 100..300 })
            // The zoom anchor is the key under the fingers at capture; it is restored on screen.
            val center = captured.viewport.center
            val anchor = captured.items.entries.filter { !it.value.retained }.minBy { abs(it.value.bounds.center.y - center.y) }.key
            val listBounds = list.fetchSemanticsNode().boundsInRoot
            val anchorCell = settled(anchor)
            assertTrue("$label restores anchor $anchor: $anchorCell in $listBounds", anchorCell != null && anchorCell.overlaps(listBounds))
        } finally {
            pinchDrawProbe = null
            compose.mainClock.autoAdvance = true
            prefs.write(route, old, true)
        }
    }

    private fun Rect.visibleIn(viewport: Rect) =
        width > 0f && height > 0f && left < viewport.right && right > viewport.left && top < viewport.bottom && bottom > viewport.top
}
