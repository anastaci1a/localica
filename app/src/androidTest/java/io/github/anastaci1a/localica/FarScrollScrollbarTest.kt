package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.LongScrollStyle
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FarScrollScrollbarTest {
    @get:Rule val compose = createComposeRule()
    private val items = List(60) { BrowserItem(MediaRef(MediaKind.Album, "a$it"), "T$it", null, emptyList()) }

    @Test fun flyThroughComposesFewRegionsAndEndsExactly() = longReturn(LongScrollStyle.FlyThrough, maxJumps = 11, maxComposed = 270)
    @Test fun jumpGlideComposesAboutTwoRegionsAndEndsExactly() = longReturn(LongScrollStyle.JumpGlide, maxJumps = 1, maxComposed = 90)
    @Test fun fadeComposesAboutTwoRegionsEndsExactlyAndRestoresAlpha() = longReturn(LongScrollStyle.Fade, maxJumps = 1, maxComposed = 90)

    private fun longReturn(style: LongScrollStyle, maxJumps: Int, maxComposed: Int) {
        lateinit var grid: LazyGridState
        lateinit var scope: CoroutineScope
        val composed = mutableSetOf<Int>()
        compose.setContent { LocalicaTheme {
            grid = rememberLazyGridState(9000); scope = rememberCoroutineScope()
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(360.dp).longScrollFade(grid), state = grid) {
                items(10000) { composed += it; Text("Album $it", Modifier.height(120.dp)) }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        lateinit var job: Job
        val firsts = mutableListOf<Int>()
        var minAlpha = 1f
        compose.runOnIdle { composed.clear(); job = scope.launch { grid.easeToTop(false, style) } }
        repeat(40) {
            compose.runOnIdle { firsts += grid.firstVisibleItemIndex; minAlpha = minOf(minAlpha, fadeFor(grid).alpha) }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        }
        compose.runOnIdle {
            assertTrue(job.isCompleted)
            assertEquals(0, grid.firstVisibleItemIndex)
            assertEquals(0, grid.firstVisibleItemScrollOffset)
            val jumps = firsts.zipWithNext { a, b -> a - b }.count { it > 30 }
            assertTrue("Distinct cold regions $jumps: $firsts", jumps <= maxJumps)
            assertTrue("Fresh items composed during the return: ${composed.size}", composed.size <= maxComposed)
            assertEquals(1f, fadeFor(grid).alpha, 0f)
            assertFalse(fadeFor(grid).fading)
            if (style == LongScrollStyle.Fade) assertTrue("The list must dim while it jumps: $minAlpha", minAlpha < .2f)
            else assertEquals(1f, minAlpha, 0f)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun longScrollPreferenceDefaultsToFlyThroughAndRoundTrips() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().remove("long-scroll").commit()
        val preferences = io.github.anastaci1a.localica.navigation.InterfacePreferences(context)
        assertEquals(LongScrollStyle.FlyThrough, io.github.anastaci1a.localica.navigation.InterfaceSettings().longScroll)
        assertEquals(LongScrollStyle.FlyThrough, preferences.read().longScroll)
        try {
            for (style in LongScrollStyle.entries) {
                preferences.write(preferences.read().copy(longScroll = style))
                assertEquals(style, io.github.anastaci1a.localica.navigation.InterfacePreferences(context).read().longScroll)
            }
            context.getSharedPreferences("interface", 0).edit().putString("long-scroll", "Bogus").commit()
            assertEquals(LongScrollStyle.FlyThrough, preferences.read().longScroll)
        } finally { context.getSharedPreferences("interface", 0).edit().remove("long-scroll").commit() }
    }

    private fun host(state: LazyGridState, transit: ArtworkTransit, rows: List<BrowserItem> = items,
        content: @Composable () -> Unit = {}) = compose.setContent { LocalicaTheme {
        CompositionLocalProvider(LocalArtworkTransit provides transit) {
            Box(Modifier.height(500.dp).width(300.dp)) {
                LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize(), state = state) {
                    items(rows.size + 1) { Text("Row $it", Modifier.height(100.dp)) }
                }
                BrowseScrollbar(state, rows, TrackSort.Title, Modifier.align(androidx.compose.ui.Alignment.CenterEnd))
                content()
            }
        }
    } }

    @Test fun scrollbarDragDefersArtworkOnlyWhileDragging() {
        lateinit var state: LazyGridState
        val transit = ArtworkTransit()
        host(LazyGridState().also { state = it }, transit)
        val bar = compose.onNodeWithTag("browse-scrollbar")
        compose.runOnIdle { assertFalse(transit.active) }
        // #704: only the visible thumb takes contact, so reveal it and press its current centre.
        compose.revealScrollbar(state)
        fun grab() = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot.center.y -
            bar.fetchSemanticsNode().boundsInRoot.top
        val first = grab()
        bar.performTouchInput { down(Offset(centerX, first)); moveTo(Offset(centerX, centerY), delayMillis = 300) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertTrue("Deferral must engage during the drag", transit.active) }
        bar.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertFalse("Release ends the deferral", transit.active) }
        val second = grab()
        bar.performTouchInput { down(Offset(centerX, second)); moveTo(Offset(centerX, centerY + 40f), delayMillis = 100) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertTrue("The second drag also defers", transit.active) }
        bar.performTouchInput { cancel() }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertFalse("Cancel ends the deferral", transit.active) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun scrollbarRequestsWithinOneFrameCollapseToOneJump() {
        lateinit var state: LazyGridState
        val seen = mutableListOf<Int>()
        host(LazyGridState().also { state = it }, ArtworkTransit(), items) {
            LaunchedEffect(Unit) { snapshotFlow { state.firstVisibleItemIndex }.collect { seen += it } }
        }
        compose.waitForIdle()
        val set = compose.onNodeWithTag("browse-scrollbar").fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        compose.runOnIdle { for (n in 1..30) set(n / 30f) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Distinct firstVisibleItemIndex values: $seen", seen.size <= 3) ; assertTrue(seen.last() > 40) }
    }

    @Test fun thumbPositionAndProgressSemanticsTrackTheList() {
        lateinit var state: LazyGridState
        lateinit var scope: CoroutineScope
        host(LazyGridState().also { state = it }, ArtworkTransit(), items) { scope = rememberCoroutineScope() }
        fun range(): ProgressBarRangeInfo = compose.onNodeWithTag("browse-scrollbar").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        fun thumbTop() = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot.top
        assertEquals(0f, range().current, .001f)
        val top = thumbTop()
        compose.runOnIdle { scope.launch { state.scrollToItem(30) } }
        compose.waitForIdle()
        assertEquals(3000f / 5600f, range().current, .01f)
        val middle = thumbTop()
        assertTrue("Thumb moves with the list: $top -> $middle", middle > top)
        compose.runOnIdle { scope.launch { state.scrollToItem(items.size) } }
        compose.waitForIdle()
        assertEquals(1f, range().current, .001f)
        assertTrue(thumbTop() > middle)
        assertEquals(0f..1f, range().range)
    }

    /** #774 at operator scale (13.4k and 50k rows): fast full-length thumb drags never crash, end exactly at both
     * ends, and leave no size-dependent heap. Heap and GC are written to logcat as Scrollbar774. */
    @Test fun fastDragsOver13400RowsEndExactlyWithBoundedHeap() = fastFullLengthDrags(13_400)
    @Test fun fastDragsOver50000RowsEndExactlyWithBoundedHeap() = fastFullLengthDrags(50_000)

    private fun fastFullLengthDrags(size: Int) {
        val rows = List(size) { BrowserItem(MediaRef(MediaKind.Album, "a$it"), "T$it", null, emptyList()) }
        lateinit var state: LazyGridState
        host(LazyGridState().also { state = it }, ArtworkTransit(), rows)
        val bar = compose.onNodeWithTag("browse-scrollbar")
        fun heap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        fun gcs() = android.os.Debug.getRuntimeStat("art.gc.gc-count")
        compose.revealScrollbar(state)
        Runtime.getRuntime().gc()
        val before = heap(); val gcBefore = gcs()
        repeat(4) {
            val grab = compose.thumbGrabY()
            bar.performTouchInput { down(Offset(centerX, grab)); moveTo(Offset(centerX, height - 1f), delayMillis = 40); moveTo(Offset(centerX, 1f), delayMillis = 40); moveTo(Offset(centerX, height - 1f), delayMillis = 40); up() }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            compose.runOnIdle { assertFalse("$size rows: a full-length drag ends exactly at the end", state.canScrollForward) }
            compose.revealScrollbar(state)
        }
        val grab = compose.thumbGrabY()
        bar.performTouchInput { down(Offset(centerX, grab)); moveTo(Offset(centerX, 1f), delayMillis = 40); up() }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("$size rows: dragging to the top ends exactly", 0, state.firstVisibleItemIndex) }
        Runtime.getRuntime().gc()
        val growth = heap() - before
        android.util.Log.i("Scrollbar774", "rows=$size heapBefore=$before heapAfter=${heap()} growth=$growth gc=${gcBefore}->${gcs()}")
        assertTrue("$size rows: heap growth $growth B after drags", growth < 8L shl 20)
        compose.mainClock.autoAdvance = true
    }
}
