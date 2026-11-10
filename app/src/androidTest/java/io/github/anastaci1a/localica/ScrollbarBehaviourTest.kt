package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.spec.ScrollbarSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** #704 on the slice C scrollbar owner: tap to engage, colour and expansion in lockstep, full visibility for
 * a held or still finger, gradual fades, relative drag over cold rows at operator scale, and the track bounds.
 * Evidence goes to the native evidence directory "scrollbar-704". Witness values are captured on Main and
 * returned; every file write happens here on the instrumentation thread (#772).
 */
class ScrollbarBehaviourTest {
    @get:Rule val compose = localicaComposeRule()
    private lateinit var state: LazyGridState
    private lateinit var scope: CoroutineScope
    private var density = 1f
    private var safeTop = 0
    private var restColour = Color.Unspecified
    private var heldColour = Color.Unspecified
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir by lazy { nativeEvidenceDirectory("scrollbar-704", context.getExternalFilesDir(null)) }
    private val api = android.os.Build.VERSION.SDK_INT

    /** [count] rows over a white ground. [cold] rows are short placeholders until their first frame has passed,
     * so the model's estimates for rows not yet reached are wrong, as for unloaded rows (#704). */
    private fun host(count: Int, rowDp: Int, cold: Boolean = false, top: Dp = 0.dp, bottom: Dp = 0.dp) {
        val items = List(count) { BrowserItem(MediaRef(MediaKind.Album, "a$it"), "T$it", null, emptyList()) }
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current.density
            safeTop = WindowInsets.safeDrawing.getTop(LocalDensity.current)
            restColour = MaterialTheme.colorScheme.outlineVariant
            heldColour = MaterialTheme.colorScheme.primary
            state = rememberLazyGridState(); scope = rememberCoroutineScope()
            Box(Modifier.safeDrawingPadding().height(640.dp).width(360.dp).background(Color.White).testTag("shot")) {
                LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize().testTag("rows"), state = state) {
                    items(count + 1) { index ->
                        var loaded by remember { mutableStateOf(!cold) }
                        if (!loaded) LaunchedEffect(Unit) { withFrameNanos { }; loaded = true }
                        Text("Row $index", Modifier.height((if (loaded) rowDp + index % 5 * 9 else 56).dp))
                    }
                }
                BrowseScrollbar(state, items, TrackSort.Title, Modifier.align(Alignment.CenterEnd).padding(top = top, bottom = bottom))
            }
        } }
        compose.waitForIdle()
        assertTrue("Captures exclude the status bar", compose.onNodeWithTag("shot").fetchSemanticsNode().boundsInRoot.top >= safeTop - .5f)
    }

    private fun bar() = compose.onNodeWithTag("browse-scrollbar")
    private fun position() = compose.runOnIdle { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
    private fun frames(n: Int) = repeat(n) { compose.mainClock.advanceTimeByFrame() }

    private class Sample(val millis: Long, val bounds: Rect, val colour: Color)
    private fun sample(): Sample {
        val node = compose.onNodeWithTag("browse-scrollbar-thumb")
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val pixels = node.captureToImage().toPixelMap()
        return Sample(compose.mainClock.currentTime, bounds, pixels[pixels.width / 2, pixels.height / 2])
    }
    private fun channels(c: Color) = floatArrayOf(c.red, c.green, c.blue)
    /** Opacity of an unheld thumb over the white ground, from its most distinct channel. */
    private fun opacity(s: Sample): Float {
        val rest = channels(restColour); val seen = channels(s.colour)
        val k = rest.indices.maxBy { abs(1f - rest[it]) }
        return ((1f - seen[k]) / (1f - rest[k])).coerceIn(0f, 1f)
    }
    private fun shot(name: String) {
        val image = compose.onNodeWithTag("shot").captureToImage()
        File(dir, "$name-api$api.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun csv(name: String, header: String, rows: List<String>) =
        File(dir, "$name-api$api.csv").writeText((listOf(header) + rows).joinToString("\n", postfix = "\n"))
    private fun row(s: Sample, extra: String = "") = "${s.millis},${s.bounds.width},${s.bounds.height},${s.colour.red},${s.colour.green},${s.colour.blue}$extra"

    /** The progress interval a quantized observation allows: value [v] = [from] + ([to] - [from]) * p, within +-[q]. */
    private fun interval(v: Float, from: Float, to: Float, q: Float): ClosedFloatingPointRange<Float> {
        val a = (v - q - from) / (to - from) - 1e-4f; val b = (v + q - from) / (to - from) + 1e-4f
        return minOf(a, b)..maxOf(a, b)
    }
    private fun ClosedFloatingPointRange<Float>.meets(o: ClosedFloatingPointRange<Float>) = start <= o.endInclusive && o.start <= endInclusive

    private class Endpoints(val colour: Pair<Color, Color>, val width: Pair<Float, Float>, val length: Pair<Float, Float>)

    /** Colour, width and length progress agree within one frame: each frame's colour interval meets the width and
     * length intervals of that frame or an adjacent one. Colour and width run between their exact spec values
     * (theme colours, dp widths); length between the observed resting and held lengths. Readbacks are quantized:
     * 8-bit colour (+-1 level), whole-pixel width (+-0.5 px, so a true 10.5 px end reads 10 or 11) and length
     * (+-1 px, covering the observed endpoints). */
    private fun assertLockstep(name: String, run: List<Sample>, end: Endpoints) {
        val k = (0..2).maxBy { abs(channels(end.colour.second)[it] - channels(end.colour.first)[it]) }
        fun colour(s: Sample) = interval(channels(s.colour)[k], channels(end.colour.first)[k], channels(end.colour.second)[k], 1f / 255f)
        fun length(s: Sample) = interval(s.bounds.height, end.length.first, end.length.second, 1f)
        fun width(s: Sample) = interval(s.bounds.width, end.width.first, end.width.second, .5f)
        for (i in run.indices) {
            val near = (i - 1..i + 1).filter { it in run.indices }
            assertTrue("$name frame $i: colour ${colour(run[i])} vs length ${near.map { length(run[it]) }}",
                near.any { colour(run[i]).meets(length(run[it])) })
            assertTrue("$name frame $i: colour ${colour(run[i])} vs width ${near.map { width(run[it]) }}",
                near.any { colour(run[i]).meets(width(run[it])) })
        }
        val eased = run.count { colour(it).start > .05f && colour(it).endInclusive < .95f }
        assertTrue("$name eases over several frames, not a snap: $eased", eased >= 4)
    }

    @Test fun colourEasesInLockstepWithTheExpansionOnPressAndRelease() {
        // A short list has a long resting thumb, so the length growth reaches its cap.
        host(40, 20)
        compose.revealScrollbar(state)
        val rest = sample()
        val y = compose.thumbGrabY()
        bar().performTouchInput { down(Offset(centerX, y)) }
        val press = List(30) { frames(1); sample() }
        bar().performTouchInput { up() }
        val release = List(30) { frames(1); sample() }
        compose.mainClock.autoAdvance = true
        val held = press.last()
        csv("lockstep", "millis,width,height,red,green,blue,phase",
            press.map { row(it, ",press") } + release.map { row(it, ",release") })
        assertEquals("constant extra width", rest.bounds.width + ScrollbarSpec.heldExtraWidth.value * density, held.bounds.width, 1f)
        assertEquals("capped extra length", rest.bounds.height + ScrollbarSpec.heldExtraLength(rest.bounds.height,
            ScrollbarSpec.heldMaxExtraLength.value * density), held.bounds.height, 1f)
        assertEquals(heldColour.red, held.colour.red, 1.5f / 255f)
        val restWidth = ScrollbarSpec.thumbWidth.value * density
        val heldWidth = (ScrollbarSpec.thumbWidth.value + ScrollbarSpec.heldExtraWidth.value) * density
        assertLockstep("press", press, Endpoints(restColour to heldColour, restWidth to heldWidth, rest.bounds.height to held.bounds.height))
        assertLockstep("release", release, Endpoints(heldColour to restColour, heldWidth to restWidth, held.bounds.height to rest.bounds.height))
        assertEquals(rest.bounds.height, release.last().bounds.height, .5f)
    }

    @Test fun nativeStripOnAShortList() = strip("short", 40, 20)
    @Test fun nativeStripAtOperatorScale() = strip("long", 13_400, 64)

    /** Tap, hold still past the idle timeout, drag, hold mid-drag, release; PNG frames plus a timeline. */
    private fun strip(name: String, count: Int, rowDp: Int) {
        host(count, rowDp)
        val timeline = mutableListOf<String>()
        fun note(phase: String) { val s = sample(); timeline += row(s, ",$phase,${opacity(s)}") }
        shot("$name-01-idle-faded")
        compose.revealScrollbar(state)
        shot("$name-02-revealed")
        val start = position()
        // Tap: selects at once, never moves the list, and deselects on release.
        bar().performTouchInput { down(Offset(centerX, compose.thumbGrabY())) }
        repeat(6) { frames(1); note("tap-down") }
        shot("$name-03-tap-down")
        bar().performTouchInput { up() }
        repeat(20) { frames(1); note("tap-released") }
        shot("$name-04-tap-released")
        assertEquals("A tap never moves the list", start, position())
        // Hold still past the idle timeout and the fade: fully visible and selected throughout.
        bar().performTouchInput { down(Offset(centerX, compose.thumbGrabY())) }
        frames(20)
        val holdUntil = compose.mainClock.currentTime + ScrollbarSpec.idleMillis + ScrollbarSpec.fadeMillis + 600
        while (compose.mainClock.currentTime < holdUntil) {
            compose.mainClock.advanceTimeBy(100); val s = sample(); timeline += row(s, ",hold-still,")
            assertEquals("Held still: selected colour at full opacity", heldColour.red, s.colour.red, 1.5f / 255f)
            assertEquals(heldColour.green, s.colour.green, 1.5f / 255f)
        }
        shot("$name-05-hold-still")
        assertEquals("Holding still never moves the list", start, position())
        // Drag in 4 dp steps; the list follows the finger's travel.
        val stepPx = 4f * density
        for (k in 1..60) {
            bar().performTouchInput { moveBy(Offset(0f, stepPx)) }
            frames(1); timeline += row(sample(), ",drag,")
            if (k % 20 == 0) shot("$name-06-drag-$k")
        }
        val dragged = position()
        assertTrue("The drag moved the list: $start -> $dragged", dragged.first > start.first || dragged.second > start.second)
        // Hold still mid-drag past the timeout: still fully visible and selected.
        val midUntil = compose.mainClock.currentTime + ScrollbarSpec.idleMillis + ScrollbarSpec.fadeMillis + 300
        while (compose.mainClock.currentTime < midUntil) {
            compose.mainClock.advanceTimeBy(100); val s = sample(); timeline += row(s, ",drag-hold,")
            assertEquals("Mid-drag hold: selected colour at full opacity", heldColour.red, s.colour.red, 1.5f / 255f)
        }
        shot("$name-07-drag-hold")
        assertEquals("A still finger never moves the list", dragged, position())
        // Release: collapse, idle, then a gradual fade to nothing.
        bar().performTouchInput { up() }
        val released = compose.mainClock.currentTime
        frames(4); shot("$name-08-released")
        val fade = mutableListOf<Pair<Long, Float>>()
        while (compose.mainClock.currentTime < released + ScrollbarSpec.idleMillis + ScrollbarSpec.fadeMillis + 400) {
            frames(1); val s = sample(); val a = opacity(s); timeline += row(s, ",release,$a")
            if (compose.mainClock.currentTime - released > ScrollbarSpec.gripMillis + 60) fade += compose.mainClock.currentTime - released to a
        }
        shot("$name-09-faded")
        compose.mainClock.autoAdvance = true
        csv("$name-strip", "millis,width,height,red,green,blue,phase,opacity", timeline)
        assertAppearsThenFades("$name release", fade, visibleUntil = ScrollbarSpec.idleMillis.toLong())
    }

    /** Full opacity until [visibleUntil] ms, then monotonic decrease to zero over at least most of the fade. */
    private fun assertAppearsThenFades(name: String, fade: List<Pair<Long, Float>>, visibleUntil: Long) {
        val visible = fade.filter { it.first < visibleUntil }
        assertTrue("$name: visible through the idle delay ${visible.map { it.second }.min()}", visible.all { it.second >= .98f })
        val falling = fade.dropWhile { it.second >= .98f }.map { it.second }
        assertTrue("$name: fade-out is monotonic $falling", falling.zipWithNext().all { (a, b) -> b <= a + 1.5f / 255f })
        assertTrue("$name: fade-out ends invisible", falling.last() <= .02f)
        val partial = falling.count { it > .02f && it < .98f }
        assertTrue("$name: no single-frame vanish; $partial partial frames", partial >= ScrollbarSpec.fadeMillis / 16 - 4)
    }

    @Test fun scrollStartFadesInGraduallyAndAStillFingerOrFlingKeepsItVisible() {
        host(2000, 64)
        compose.mainClock.autoAdvance = false
        val timeline = mutableListOf<String>()
        assertTrue("Idle fixture starts faded", opacity(sample()) <= .02f)
        // Finger down on the list, scroll, then hold still for longer than the idle timeout.
        compose.onNodeWithTag("rows").performTouchInput { down(center) }
        val appear = mutableListOf<Float>()
        val appearAt = mutableListOf<Long>()
        repeat(16) {
            compose.onNodeWithTag("rows").performTouchInput { moveBy(Offset(0f, -10f * density)) }
            frames(1); val s = sample(); appear += opacity(s); appearAt += s.millis; timeline += row(s, ",scroll,${appear.last()}")
        }
        assertTrue("Fade-in is monotonic: $appear", appear.zipWithNext().all { (a, b) -> b >= a - 1.5f / 255f })
        // Each sample also follows an injected move that advances the test clock, so samples are not one frame
        // apart: judge the fade by elapsed time. No pop-in means the rise spans most of the fade duration, and no
        // interval rises faster than the easing's steepest slope allows (2.6 x the average rate, plus 8-bit readback).
        val first = appear.indexOfFirst { it > .02f }
        val full = appear.indexOfFirst { it >= .98f }
        assertTrue("Fade-in reached full opacity: $appear", first in 0 until full)
        assertTrue("The rise spans the fade duration, not one frame: ${appearAt[full] - appearAt[first]} ms of ${ScrollbarSpec.fadeMillis}",
            appearAt[full] - appearAt[first] >= ScrollbarSpec.fadeMillis * .6f)
        appear.indices.drop(1).forEach { i ->
            val allowed = 2.6f * (appearAt[i] - appearAt[i - 1]) / ScrollbarSpec.fadeMillis + 3f / 255f
            assertTrue("Fade-in jumped ${appear[i] - appear[i - 1]} in ${appearAt[i] - appearAt[i - 1]} ms (> $allowed): $appear", appear[i] - appear[i - 1] <= allowed)
        }
        val holdUntil = compose.mainClock.currentTime + ScrollbarSpec.idleMillis + ScrollbarSpec.fadeMillis + 1000
        while (compose.mainClock.currentTime < holdUntil) {
            compose.mainClock.advanceTimeBy(100); val s = sample(); timeline += row(s, ",finger-still,${opacity(s)}")
            assertTrue("A still finger keeps it fully visible: ${opacity(s)}", opacity(s) >= .98f)
        }
        compose.onNodeWithTag("rows").performTouchInput { up() }
        val released = compose.mainClock.currentTime
        val fade = mutableListOf<Pair<Long, Float>>()
        while (compose.mainClock.currentTime < released + ScrollbarSpec.idleMillis + ScrollbarSpec.fadeMillis + 400) {
            frames(1); val s = sample(); fade += compose.mainClock.currentTime - released to opacity(s); timeline += row(s, ",released,${fade.last().second}")
        }
        assertAppearsThenFades("finger release", fade, ScrollbarSpec.idleMillis.toLong())
        // A fling: visible while it runs; the idle delay starts only once it settles.
        compose.onNodeWithTag("rows").performTouchInput { swipeUp(durationMillis = 80) }
        var settled = -1L
        var movingSince = -1L
        val flingFade = mutableListOf<Pair<Long, Float>>()
        val flingStart = compose.mainClock.currentTime
        while (compose.mainClock.currentTime < flingStart + 8000) {
            frames(1)
            val moving = compose.runOnIdle { state.isScrollInProgress }
            val s = sample()
            timeline += row(s, ",fling-$moving,${opacity(s)}")
            // The fling starts from a faded bar, so it fades in first (as one timeline); once that has run it stays full.
            if (moving) {
                if (movingSince < 0) movingSince = compose.mainClock.currentTime
                if (compose.mainClock.currentTime - movingSince > ScrollbarSpec.fadeMillis + 50) assertTrue("Visible throughout the fling after its fade-in: ${opacity(s)}", opacity(s) >= .98f)
            }
            else {
                if (settled < 0) settled = compose.mainClock.currentTime
                flingFade += compose.mainClock.currentTime - settled to opacity(s)
                if (compose.mainClock.currentTime - settled > ScrollbarSpec.idleMillis + ScrollbarSpec.fadeMillis + 300) break
            }
        }
        compose.mainClock.autoAdvance = true
        csv("finger-and-fling", "millis,width,height,red,green,blue,phase,opacity", timeline)
        assertTrue("The fling settled", settled > flingStart)
        assertAppearsThenFades("fling settle", flingFade, ScrollbarSpec.idleMillis.toLong() - 16)
    }

    @Test fun relativeDragOverColdRowsMovesMonotonicallyAtOperatorScale() {
        val runtime = Runtime.getRuntime()
        fun heap() = runtime.totalMemory() - runtime.freeMemory()
        fun gcs() = android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
        host(20_000, 120, cold = true)
        val heapBefore = heap(); val gcBefore = gcs()
        compose.revealScrollbar(state)
        val start = position()
        var fingerY = compose.thumbGrabY()
        bar().performTouchInput { down(Offset(centerX, fingerY)) }
        repeat(25) { frames(1) }
        assertEquals("Grabbing the thumb never jumps the list", start, position())
        val stepPx = 3f * density
        fun run(direction: Int): List<Pair<Int, Int>> {
            val path = mutableListOf(position())
            for (k in 0 until 240) {
                // A monotonic finger path with pauses, across rows that load (and grow) as they arrive.
                if (k % 40 < 30) {
                    fingerY += direction * stepPx
                    bar().performTouchInput { moveTo(Offset(centerX, fingerY)) }
                }
                frames(1)
                path += position()
            }
            return path
        }
        val down = run(1)
        val up = run(-1)
        bar().performTouchInput { up() }
        frames(30)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val heapAfter = heap(); val gcAfter = gcs()
        fun key(p: Pair<Int, Int>) = p.first.toLong() * 1_000_000L + p.second
        csv("cold-drag", "direction,index,offset", down.map { "down,${it.first},${it.second}" } + up.map { "up,${it.first},${it.second}" })
        File(dir, "cold-drag-memory-api$api.txt").writeText("items=20000 heapBeforeBytes=$heapBefore heapAfterBytes=$heapAfter " +
            "heapDeltaBytes=${heapAfter - heapBefore} gcBefore=$gcBefore gcAfter=$gcAfter gcDelta=${gcAfter - gcBefore} " +
            "maxHeapBytes=${runtime.maxMemory()}\n")
        assertTrue("Down drag never reverses: $down", down.zipWithNext().all { (a, b) -> key(b) >= key(a) })
        assertTrue("Down drag crossed cold rows: ${down.last()}", down.last().first > start.first + 100)
        assertTrue("Up drag never reverses: $up", up.zipWithNext().all { (a, b) -> key(b) <= key(a) })
        assertTrue("Up drag moved back", up.last().first < down.last().first)
    }

    @Test fun trackStartsAndEndsAtTheHostBoundsPlusStandardPadding() {
        val top = 72.dp; val bottom = 96.dp
        host(2000, 64, top = top, bottom = bottom)
        compose.revealScrollbar(state)
        val shot = compose.onNodeWithTag("shot").fetchSemanticsNode().boundsInRoot
        val pad = ScrollbarSpec.trackPadding.value * density
        val strip = bar().fetchSemanticsNode().boundsInRoot
        assertEquals("Track top", shot.top + top.value * density + pad, strip.top, 1f)
        assertEquals("Track bottom", shot.bottom - bottom.value * density - pad, strip.bottom, 1f)
        val first = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot
        assertTrue("Thumb starts in the track: $first vs $strip", first.top >= strip.top - .5f)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { scope.launch { state.scrollToItem(2001); state.scrollBy(1e7f) } }
        compose.waitForIdle()
        compose.revealScrollbar(state)
        val last = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot
        assertTrue("Thumb ends in the track: $last vs $strip", last.bottom <= strip.bottom + .5f && last.bottom >= strip.bottom - 3f)
        shot("bounds-fixture-end")
        compose.mainClock.autoAdvance = true
        File(dir, "bounds-fixture-api$api.txt").writeText("host=$shot strip=$strip thumbTop=$first thumbEnd=$last pad=$pad\n")
    }

    @Test fun realBrowseTrackStartsBelowTheHeaderAndEndsAboveTheCornerButton() {
        listOf("browse-style", "library-query", "library-filters").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        val tracks = (0 until 400).map { n ->
            val id = n.toString().padStart(3, '0')
            Track("song:$id", "Song $id", artist = "Artist $id", album = "Album $id", fileName = "$id.mp3", relativePath = "$id.mp3")
        }
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current.density
            BrowserScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false), PlaylistState(loading = false),
                BrowseRoute(MainTab.Library), {}, {}, {}, { _, _, _ -> }, null, null, true, false)
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("browse-scrollbar").fetchSemanticsNodes().isNotEmpty() }
        val pad = ScrollbarSpec.trackPadding.value * density
        val header = compose.onNodeWithTag("library-filters").fetchSemanticsNode().boundsInRoot
        val corner = compose.onNodeWithTag("playlist-add-module").fetchSemanticsNode().boundsInRoot
        val strip = bar().fetchSemanticsNode().boundsInRoot
        compose.revealScrollbar("browser-list")
        val first = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("browser-list").performScrollToIndex(tracks.size)
        compose.revealScrollbar("browser-list")
        val last = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = true
        File(dir, "bounds-browser-api$api.txt").writeText("header=$header corner=$corner strip=$strip thumbTop=$first thumbEnd=$last pad=$pad\n")
        assertTrue("Track starts below the header buttons plus padding: $strip vs $header", strip.top >= header.bottom + pad - 1f)
        assertTrue("Thumb starts in the track", first.top >= strip.top - .5f)
        assertTrue("Track ends above the corner button plus padding: $strip vs $corner", strip.bottom <= corner.top - pad + 1f)
        assertTrue("Thumb ends in the track", last.bottom <= strip.bottom + .5f)
    }
}
