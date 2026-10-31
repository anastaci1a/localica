package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/** Temporary measurement: fails on purpose so CI preserves the numbers in the failure message. */
class FarScrollMeasureTest {
    @get:Rule val compose = createComposeRule()
    private fun measure(every: Int) {
        LongScroll.flyJumpEvery = every
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
        val positions = mutableListOf<Float>()
        val firsts = mutableListOf<Int>()
        compose.runOnIdle { composed.clear(); job = scope.launch { grid.easeToTop(false, LongScrollStyle.FlyThrough) } }
        repeat(40) {
            compose.runOnIdle {
                firsts += grid.firstVisibleItemIndex
                positions += grid.firstVisibleItemIndex / 3f + grid.firstVisibleItemScrollOffset.toFloat() / grid.layoutInfo.visibleItemsInfo.first().size.height
            }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        }
        val travel = positions.zipWithNext { a, b -> a - b }
        val jumps = firsts.zipWithNext { a, b -> a - b }.count { it > 30 }
        fail("MEASURE every=$every composed=${composed.size} bigJumps=$jumps maxTravel=${travel.max()} movingOver1=${travel.count { it > 1f }} done=${job.isCompleted} final=${grid.firstVisibleItemIndex}")
    }
    @Test fun every1() = measure(1)
    @Test fun every2() = measure(2)
    @Test fun every3() = measure(3)
    @Test fun every4() = measure(4)
    @Test fun every5() = measure(5)
}
