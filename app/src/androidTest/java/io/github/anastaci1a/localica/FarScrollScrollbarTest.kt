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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.BrowserItem
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

    @Test fun longReturnComposesAboutTwoFreshViewportsNotOnePerFrame() {
        lateinit var grid: LazyGridState
        lateinit var scope: CoroutineScope
        val composed = mutableSetOf<Int>()
        compose.setContent { LocalicaTheme {
            grid = rememberLazyGridState(9000); scope = rememberCoroutineScope()
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(360.dp), state = grid) {
                items(10000) { composed += it; Text("Album $it", Modifier.height(120.dp)) }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        lateinit var job: Job
        val firsts = mutableListOf<Int>()
        compose.runOnIdle { composed.clear(); job = scope.launch { grid.easeToTop(false) } }
        repeat(40) {
            compose.runOnIdle { firsts += grid.firstVisibleItemIndex }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        }
        compose.runOnIdle {
            assertTrue(job.isCompleted)
            assertEquals(0, grid.firstVisibleItemIndex)
            assertEquals(0, grid.firstVisibleItemScrollOffset)
            val jumps = firsts.zipWithNext { a, b -> a - b }.count { it > 30 }
            assertTrue("Distinct cold regions: $firsts", jumps <= 1)
            assertTrue("Fresh items composed during the return: ${composed.size}", composed.size <= 90)
        }
        compose.mainClock.autoAdvance = true
    }

    private fun host(state: LazyGridState, transit: ArtworkTransit, content: @Composable () -> Unit = {}) = compose.setContent { LocalicaTheme {
        CompositionLocalProvider(LocalArtworkTransit provides transit) {
            Box(Modifier.height(500.dp).width(300.dp)) {
                LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize(), state = state) {
                    items(items.size + 1) { Text("Row $it", Modifier.height(100.dp)) }
                }
                BrowseScrollbar(state, items, TrackSort.Title, Modifier.align(androidx.compose.ui.Alignment.CenterEnd))
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
        bar.performTouchInput { down(Offset(centerX, 30f)); moveTo(Offset(centerX, centerY), delayMillis = 300) }
        compose.runOnIdle { assertTrue("Deferral must engage during the drag", transit.active) }
        bar.performTouchInput { up() }
        compose.runOnIdle { assertFalse("Release ends the deferral", transit.active) }
        bar.performTouchInput { down(Offset(centerX, 30f)); moveTo(Offset(centerX, centerY), delayMillis = 100); cancel() }
        compose.runOnIdle { assertFalse("Cancel ends the deferral", transit.active) }
    }

    @Test fun scrollbarRequestsWithinOneFrameCollapseToOneJump() {
        lateinit var state: LazyGridState
        val seen = mutableListOf<Int>()
        host(LazyGridState().also { state = it }, ArtworkTransit()) {
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
        host(LazyGridState().also { state = it }, ArtworkTransit()) { scope = rememberCoroutineScope() }
        fun range(): ProgressBarRangeInfo = compose.onNodeWithTag("browse-scrollbar").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        fun thumbTop() = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot.top
        assertEquals(0f, range().current, .001f)
        val top = thumbTop()
        compose.runOnIdle { scope.launch { state.scrollToItem(30) } }
        compose.waitForIdle()
        assertEquals(29f / 59f, range().current, .001f)
        val middle = thumbTop()
        assertTrue("Thumb moves with the list: $top -> $middle", middle > top)
        compose.runOnIdle { scope.launch { state.scrollToItem(items.size) } }
        compose.waitForIdle()
        assertEquals(1f, range().current, .001f)
        assertTrue(thumbTop() > middle)
        assertEquals(0f..1f, range().range)
    }
}
