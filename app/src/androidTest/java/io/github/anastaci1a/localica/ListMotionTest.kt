package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ListMotionTest {
    @get:Rule val compose = createComposeRule()
    private val heights = List(100) { if (it % 7 == 0) 180 else if (it % 3 == 0) 80 else 30 }

    @Test fun initialLyricsFollowWaitsForLayout() = initialFollow(grid = false, reducedMotion = false)
    @Test fun initialReducedMotionLyricsFollowWaitsForLayout() = initialFollow(grid = false, reducedMotion = true)
    @Test fun initialQueueFollowWaitsForLayout() = initialFollow(grid = true, reducedMotion = false)
    @Test fun initialReducedMotionQueueFollowWaitsForLayout() = initialFollow(grid = true, reducedMotion = true)

    private fun initialFollow(grid: Boolean, reducedMotion: Boolean) {
        val listState = LazyListState()
        val gridState = LazyGridState()
        lateinit var scope: CoroutineScope
        var showList by mutableStateOf(false)
        lateinit var job: Job
        compose.setContent { LocalicaTheme {
            scope = rememberCoroutineScope()
            if (showList) {
                if (grid) LazyVerticalGrid(GridCells.Fixed(1), Modifier.height(300.dp), state = gridState) {
                    items(50) { Text("Song $it", Modifier.height(48.dp)) }
                } else LazyColumn(Modifier.height(300.dp), state = listState) {
                    items(50) { Text("Line $it", Modifier.height(48.dp)) }
                }
            }
        } }
        // Request before any layout exists, independently of test/device scheduling.
        compose.runOnIdle {
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (grid) gridState.easeToItem(25, reducedMotion)
                else listState.easeToItem(25, reducedMotion)
            }
            assertFalse("Initial request must wait for layout", job.isCompleted)
            showList = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(job.isCompleted)
            assertEquals(25, if (grid) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex)
        }
    }

    @Test fun returningFromThousandsOfAlbumsHasABoundedDuration() {
        lateinit var grid: LazyGridState
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme {
            grid = rememberLazyGridState(9000); scope = rememberCoroutineScope()
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(360.dp), state = grid) {
                items(10000) { Text("Album $it", Modifier.height(120.dp)) }
            }
        } }
        compose.mainClock.autoAdvance = false
        lateinit var job: Job
        compose.runOnIdle { job = scope.launch { grid.easeToTop(false) } }
        repeat(36) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle {
            assertTrue("Large collections must finish within about half a second", job.isCompleted)
            assertEquals(0, grid.firstVisibleItemIndex)
            assertEquals(0, grid.firstVisibleItemScrollOffset)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun distantReturnJumpsOnceThenGlidesTheFinalViewport() {
        lateinit var grid: LazyGridState
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme {
            grid = rememberLazyGridState(9000); scope = rememberCoroutineScope()
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(360.dp), state = grid) {
                items(10000) { Text("Album $it", Modifier.height(120.dp)) }
            }
        } }
        compose.mainClock.autoAdvance = false
        val positions = mutableListOf<Float>()
        lateinit var job: Job
        compose.runOnIdle { job = scope.launch { grid.easeToTop(false) } }
        repeat(36) {
            compose.runOnIdle { positions += grid.firstVisibleItemIndex / 3f + grid.firstVisibleItemScrollOffset.toFloat() / grid.layoutInfo.visibleItemsInfo.first().size.height }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        }
        compose.runOnIdle {
            assertTrue(job.isCompleted)
            // A frame-by-frame flyby composed a fresh viewport every frame. The journey is one cold jump
            // to about a viewport before the target (3 rows here), then a gradual multi-frame glide.
            val travel = positions.zipWithNext { a, b -> a - b }
            assertTrue("At most one cold jump (the journey is not a teleport train): $travel", travel.count { it > 4f } <= 1)
            assertTrue("The final viewport should glide over multiple frames: $travel", travel.count { it > .01f } >= 10)
            assertEquals(0, grid.firstVisibleItemIndex)
        }
        compose.mainClock.autoAdvance = true
    }

    private fun follow(scope: CoroutineScope, position: () -> Float, destination: suspend () -> Unit) {
        compose.mainClock.autoAdvance = false
        lateinit var job: Job
        var previous = 0f
        var direction = 0f
        compose.runOnIdle { previous = position(); job = scope.launch { destination() } }
        try {
            var frames = 0
            while (!job.isCompleted && frames++ < 600) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {
                    val next = position()
                    val delta = next - previous
                    if (kotlin.math.abs(delta) > 1f) {
                        if (direction == 0f) direction = kotlin.math.sign(delta)
                        assertTrue("Scroll reversed: $previous -> $next", delta * direction >= -1f)
                    }
                    previous = next
                }
            }
            assertTrue("Scroll must settle", job.isCompleted)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun variableHeightLyricsApproachBothDirectionsWithoutCorrectionPasses() {
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        var density = 1f
        compose.setContent { LocalicaTheme {
            list = rememberLazyListState(); scope = rememberCoroutineScope(); density = LocalDensity.current.density
            LazyColumn(Modifier.height(360.dp), state = list, contentPadding = PaddingValues(top = 100.dp, bottom = 180.dp)) {
                items(heights.size) { index -> Text("Line $index", Modifier.height(heights[index].dp)) }
            }
        } }
        fun position() = heights.take(list.firstVisibleItemIndex).sum() * density + list.firstVisibleItemScrollOffset
        for (target in listOf(65, 4, 92, 0)) {
            follow(scope, ::position) { list.easeToItem(target, false) }
            compose.runOnIdle { assertEquals(0f, list.layoutInfo.visibleItemsInfo.first { it.index == target }.offset.toFloat(), 1f) }
        }
    }

    @Test fun queueReturnAccountsForInsetAndStopsAtTheEndWithoutReversing() {
        lateinit var grid: LazyGridState
        lateinit var scope: CoroutineScope
        var density = 1f
        compose.setContent { LocalicaTheme {
            grid = rememberLazyGridState(); scope = rememberCoroutineScope(); density = LocalDensity.current.density
            LazyVerticalGrid(GridCells.Fixed(1), Modifier.height(360.dp), state = grid) {
                items(heights.size) { index -> Text("Song $index", Modifier.height(heights[index].dp)) }
            }
        } }
        fun position() = heights.take(grid.firstVisibleItemIndex).sum() * density + grid.firstVisibleItemScrollOffset
        for (target in listOf(65, 4, 99)) {
            follow(scope, ::position) { grid.easeToItem(target, false, 56f * density) }
            compose.runOnIdle {
                if (target == 99) assertFalse(grid.canScrollForward)
                else assertEquals(56f * density, grid.layoutInfo.visibleItemsInfo.first { it.index == target }.offset.y.toFloat(), 1f)
            }
        }
    }
}
