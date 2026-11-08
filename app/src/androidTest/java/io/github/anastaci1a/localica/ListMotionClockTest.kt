package io.github.anastaci1a.localica

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.easeToItem
import io.github.anastaci1a.localica.ui.engine.LocalUiClock
import io.github.anastaci1a.localica.ui.engine.UiClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses only pre-retirement APIs so the independent-clock witness also runs on frozen Stage2. */
class ListMotionClockTest {
    @get:Rule val compose = localicaComposeRule()
    private val state = LazyListState()
    private lateinit var scope: CoroutineScope
    private lateinit var clock: UiClock
    private fun host() {
        compose.setContent { LocalicaTheme {
            clock = LocalUiClock.current
            scope = rememberCoroutineScope()
            LazyColumn(Modifier.height(300.dp), state = state) {
                items(1000) { Text("Row $it", Modifier.height(40.dp)) }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }
    @Test fun foundationScrollLeasesTheExistingRootAndReleasesItAtRest() {
        host()
        lateinit var job: Job
        compose.runOnIdle { job = scope.launch { state.easeToItem(900, false) } }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals("The scroll must use the existing root clock", 1, clock.activeCount) }
        repeat(30) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { assertTrue(job.isCompleted); assertEquals(900, state.firstVisibleItemIndex); assertEquals(0, clock.activeCount) }
        compose.mainClock.autoAdvance = true
    }
    @Test fun userMutationCancelsScrollAndReleasesTheRootLease() {
        host()
        lateinit var job: Job
        compose.runOnIdle { job = scope.launch { state.easeToItem(900, false) } }
        repeat(5) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle {
            assertEquals(1, clock.activeCount)
            scope.launch { state.scroll(MutatePriority.UserInput) {} }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(job.isCancelled); assertEquals(0, clock.activeCount) }
        compose.mainClock.autoAdvance = true
    }
    @Test fun reducedRequestDoesNotLeaseTheClock() {
        host()
        compose.runOnIdle { scope.launch { state.easeToItem(900, true) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(900, state.firstVisibleItemIndex); assertEquals(0, clock.activeCount) }
        compose.mainClock.autoAdvance = true
    }
}
