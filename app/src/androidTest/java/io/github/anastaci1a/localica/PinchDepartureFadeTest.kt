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
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** #559 on the real browser: what the pinch overlay paints, frame by frame, while the fingers
 * travel. Departures fade in the order they leave the screen, finish as they leave, keep moving
 * while drawn, and items leaving together fade together. Arrivals are the same in reverse. */
class PinchDepartureFadeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun galleryToListDepartures() = pinch(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5), outward = false)
    @Test fun listToGalleryArrivals() = pinch(BrowseStyle(listSize = ListSize.Large), outward = true)
    @Test fun listToLargerListDepartures() = pinch(BrowseStyle(listSize = ListSize.Normal), outward = true)
    @Test fun galleryToLargerGalleryDepartures() = pinch(BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 3), outward = true)

    private fun pinch(start: BrowseStyle, outward: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        prefs.write(route, start, false)
        val frames = mutableListOf<PinchDrawnFrame>()
        try {
            val tracks = (0..160).map { Track("fade:$it", "Song ${it.toString().padStart(3, '0')}", fileName = "$it.mp3", relativePath = "$it.mp3") }
            compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            } } }
            val list = compose.onNodeWithTag("browser-list")
            compose.waitForIdle()
            // Content above and below the fingers in both layouts.
            list.performScrollToIndex(70)
            compose.waitForIdle()
            pinchDrawProbe = { frames += it }
            compose.mainClock.autoAdvance = false
            val begin = if (outward) 150f else 300f
            val end = if (outward) begin * 1.85f else begin * .52f
            list.performTouchInput { down(0, Offset(centerX, centerY - begin)); down(1, Offset(centerX, centerY + begin)) }
            compose.mainClock.advanceTimeByFrame()
            val steps = 110
            for (frame in 1..steps) {
                val half = begin + (end - begin) * frame / steps
                list.performTouchInput {
                    updatePointerTo(0, Offset(centerX, centerY - half))
                    updatePointerTo(1, Offset(centerX, centerY + half))
                    move(delayMillis = 0)
                }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                // Paint this frame: the witness records what the overlay actually draws.
                list.captureToImage()
            }
            pinchDrawProbe = null
            list.performTouchInput { up(0); up(1) }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val held = frames.filter { it.held && it.destinationReady }
            assertTrue("The pinch reached its destination while held: ${held.lastOrNull()?.progress}",
                held.size > 40 && held.last().progress > .97f)
            val label = "${start.presentation}/${start.listSize}/${start.itemsPerRow} ${if (outward) "out" else "in"}"
            val departures = audit(held.map { Sample(it.progress, it.viewport, it.items) }, "$label departures")
            val arrivals = audit(held.reversed().map { Sample(1f - it.progress, it.viewport, it.items) }, "$label arrivals")
            assertTrue("$label has departures or arrivals to fade ($departures/$arrivals)", departures + arrivals >= 3)
        } finally {
            pinchDrawProbe = null
            compose.mainClock.autoAdvance = true
            prefs.write(route, old, false)
        }
    }

    private class Sample(val p: Float, val viewport: Rect, val items: Map<Any, PinchDrawnItem>)

    private fun Rect.visibleIn(viewport: Rect) =
        width > 0f && height > 0f && left < viewport.right && right > viewport.left && top < viewport.bottom && bottom > viewport.top

    /** Audits the items that leave the screen along [samples]; returns how many there were. */
    private fun audit(samples: List<Sample>, label: String): Int {
        val first = samples.first()
        val last = samples.last()
        val leaving = first.items.filter { (_, item) -> item.bounds.visibleIn(first.viewport) && item.alpha > .95f }.keys
            .filter { key -> last.items[key]?.let { !it.bounds.visibleIn(last.viewport) || it.alpha < .02f } ?: true }
        class Departure(val key: Any, val exit: Float, val start: Float, val minimum: Float, val partial: Boolean)
        val audited = leaving.map { key ->
            val series = samples.map { it.items[key] }
            val gone = series.indices.firstOrNull { i -> series[i]?.bounds?.visibleIn(samples[i].viewport) != true }
                ?: samples.size
            val shown = (0 until gone).map { samples[it] to requireNotNull(series[it]) }
            for (i in 1 until shown.size) {
                val (before, a) = shown[i - 1]
                val (after, b) = shown[i]
                val still = abs(a.bounds.left - b.bounds.left) < .25f && abs(a.bounds.top - b.bounds.top) < .25f &&
                    abs(a.bounds.width - b.bounds.width) < .25f && abs(a.bounds.height - b.bounds.height) < .25f
                assertFalse("$label: $key stops at ${b.bounds} while drawn at alpha ${b.alpha} " +
                    "(p ${before.p} -> ${after.p})", still && after.p - before.p > .004f && b.alpha > .1f)
            }
            val exit = samples.getOrElse(gone) { last }.p
            val start = shown.firstOrNull { it.second.alpha < .97f }?.first?.p ?: exit
            Departure(key, exit, start, shown.minOf { it.second.alpha },
                shown.any { it.second.alpha in .15f.. .85f })
        }
        val timed = audited.filter { it.exit > .1f }
        timed.forEach {
            assertTrue("$label: ${it.key} leaves the screen at ${it.exit} still drawn at ${it.minimum}", it.minimum <= .5f)
        }
        if (timed.isNotEmpty()) assertTrue("$label: the fade is visible, not clipped " +
            "(${timed.count { it.partial }} of ${timed.size} show partial alpha)", timed.count { it.partial } * 2 >= timed.size)
        for (a in timed) for (b in timed) {
            if (abs(a.exit - b.exit) <= .01f) assertEquals("$label: ${a.key} and ${b.key} leave together (${a.exit}, ${b.exit}) " +
                "and must fade together", a.start, b.start, .03f)
            if (a.exit + .05f < b.exit) assertTrue("$label: ${a.key} leaves at ${a.exit} before ${b.key} at ${b.exit} " +
                "but starts fading later (${a.start} > ${b.start})", a.start <= b.start + .03f)
        }
        // Evenly spaced starts: the distinct starts (one per exit group) advance by one step.
        val starts = timed.map { it.start }.sorted().fold(mutableListOf<Float>()) { groups, start ->
            groups.apply { if (isEmpty() || start - last() > .03f) add(start) }
        }
        val gaps = starts.zipWithNext { a, b -> b - a }
        if (gaps.size >= 2) assertTrue("$label: fade starts are evenly spaced: $starts",
            gaps.max() - gaps.min() <= .05f + .25f * gaps.max())
        android.util.Log.i("PinchDepartureFadeTest", "$label: " + audited.sortedBy { it.exit }.joinToString {
            "${it.key}@exit %.3f start %.3f min %.2f".format(it.exit, it.start, it.minimum) })
        return audited.size
    }
}
