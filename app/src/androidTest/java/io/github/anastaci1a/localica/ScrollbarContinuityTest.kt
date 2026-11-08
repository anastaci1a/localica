package io.github.anastaci1a.localica

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Frame-sequence tests of the scrollbar thumb over a real lazy grid with a header and uneven rows. */
class ScrollbarContinuityTest {
    @get:Rule val compose = createComposeRule()
    private val items = List(80) { BrowserItem(MediaRef(MediaKind.Album, "a$it"), "T$it", null, emptyList()) }
    private lateinit var state: LazyGridState
    private lateinit var scope: CoroutineScope
    private var density = 1f
    private val transit = ArtworkTransit()

    private fun host() {
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalArtworkTransit provides transit) {
                density = LocalDensity.current.density
                state = rememberLazyGridState(); scope = rememberCoroutineScope()
                Box(Modifier.height(500.dp).width(300.dp)) {
                    LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize(), state = state) {
                        item { Text("Header", Modifier.height(180.dp)) }
                        items(items.size) { Text("Row $it", Modifier.height(if (it % 7 == 0) 90.dp else 60.dp)) }
                    }
                    BrowseScrollbar(state, items, TrackSort.Title, Modifier.align(androidx.compose.ui.Alignment.CenterEnd))
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun thumb() = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot
    // The point of the thumb at fraction f of the track is where the finger or scroll position really is.
    private fun covered(): Float {
        val t = thumb(); val b = compose.onNodeWithTag("browse-scrollbar").fetchSemanticsNode().boundsInRoot
        val f = ((t.top - b.top) / (b.height - t.height)).coerceIn(0f, 1f)
        return t.top + f * t.height
    }
    private fun bar() = compose.onNodeWithTag("browse-scrollbar")

    private fun sweep(step: Float, forward: Boolean) {
        if (!forward) compose.runOnIdle { scope.launch { state.scrollBy(1e7f) } }.also { compose.waitForIdle() }
        // #678 instrumentation (log only): the track bounds before and after, every sampled top, and the sweep time.
        val label = "step=$step ${if (forward) "forward" else "backward"}"
        val started = System.nanoTime()
        val trackBefore = bar().fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        val tops = mutableListOf<Float>()
        var guard = 0
        while (guard++ < 4000) {
            compose.runOnIdle { scope.launch { state.scrollBy(if (forward) step else -step) } }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            tops += covered()
            val done = compose.runOnIdle { if (forward) !state.canScrollForward else !state.canScrollBackward }
            if (done) break
        }
        compose.mainClock.autoAdvance = true
        val trackAfter = bar().fetchSemanticsNode().boundsInRoot
        android.util.Log.i("Trim678", "$label samples=${tops.size} ms=${(System.nanoTime() - started) / 1_000_000} track=$trackBefore -> $trackAfter")
        tops.chunked(120).forEachIndexed { i, chunk -> android.util.Log.i("Trim678", "$label tops[$i] ${chunk.joinToString(" ")}") }
        val deltas = tops.zipWithNext { a, b -> if (forward) b - a else a - b }
        val travel = 500f * density
        assertTrue("monotonic: ${deltas.min()}", deltas.min() >= -.5f)
        assertTrue("continuous (step $step): ${deltas.max()}", deltas.max() <= travel * (step / 12000f * 2.5f + .01f) + 2f)
    }

    @Test fun slowScrollToBothEndsIsMonotonicAndContinuous() { host(); sweep(6f * density, true); sweep(6f * density, false) }
    @Test fun fastScrollToBothEndsIsMonotonicAndContinuous() { host(); sweep(300f * density, true); sweep(300f * density, false) }

    @Test fun firstPixelsMoveTheThumbProportionally() {
        host()
        val rest = thumb().top
        compose.runOnIdle { scope.launch { state.scrollBy(3f) } }; compose.waitForIdle()
        assertTrue("thumb must respond to 3 px", thumb().top > rest)
    }

    @Test fun heldThumbGrowsUniformlyInwardStaysInTheTrackAndReturns() {
        host()
        val rest = thumb()
        bar().performTouchInput { down(Offset(centerX, 10f)) }
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        val held = thumb()
        assertEquals("width x1.5", rest.width * 1.5f, held.width, 1.5f)
        assertEquals("height x1.5", rest.height * 1.5f, held.height, 1.5f)
        assertEquals("anchored to the edge", rest.right, held.right, 1.5f)
        assertTrue(held.top >= bar().fetchSemanticsNode().boundsInRoot.top - 1f)
        bar().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        assertEquals(rest.width, thumb().width, 1.5f)
        assertEquals(rest.height, thumb().height, 1.5f)
    }

    @Test fun pressInsideTheEnlargedHitboxButOutsideTheDrawnThumbGrabsIt() {
        host()
        val drawn = thumb()
        val barBounds = bar().fetchSemanticsNode().boundsInRoot
        assertTrue("hit strip >= 32dp", barBounds.width >= 32f * density - 1f)
        // Left of the 4dp drawn thumb, still inside the 32dp strip.
        val x = drawn.left - barBounds.left - 8f * density
        bar().performTouchInput { down(Offset(x, drawn.center.y - barBounds.top)); moveBy(Offset(0f, 120f * density)) }
        compose.runOnIdle { assertTrue("grabbed", transit.active) }
        bar().performTouchInput { up() }
    }

    @Test fun dragFollowsTheFingerContinuouslyAcrossPressAndRelease() {
        host()
        val barBounds = bar().fetchSemanticsNode().boundsInRoot
        val tops = mutableListOf<Float>()
        val grabY = thumb().center.y - barBounds.top
        bar().performTouchInput { down(Offset(centerX, grabY)) }
        compose.mainClock.autoAdvance = false
        var y = grabY
        repeat(60) {
            y += 4f * density
            bar().performTouchInput { moveTo(Offset(centerX, y)) }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            tops += covered()
        }
        bar().performTouchInput { up() }
        for (k in 0 until 20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); tops += covered() }
        compose.mainClock.autoAdvance = true
        val deltas = tops.zipWithNext { a, b -> b - a }
        assertTrue("thumb never jumps while dragging or releasing: ${deltas.max()}", deltas.max() <= 12f * density)
        assertTrue("thumb moved with the finger", tops[59] - tops[0] > 100f * density)
        assertTrue(tops.all { it >= barBounds.top - 1f && it <= barBounds.bottom + 1f })
    }
}
