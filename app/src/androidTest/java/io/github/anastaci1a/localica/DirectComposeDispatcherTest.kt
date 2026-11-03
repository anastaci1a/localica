package io.github.anastaci1a.localica

import android.os.Looper
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DirectComposeDispatcherTest {
    @Test fun directRunnerDrainsWorkerEmissionsWithoutAdvancingPausedClock() = runComposeUiTest {
        val state = MutableStateFlow(0)
        val observations = mutableListOf<Pair<Int, Looper?>>()
        setContent { LaunchedEffect(Unit) { state.collect { observations += it to Looper.myLooper() } } }
        waitForIdle()
        mainClock.autoAdvance = false
        val before = mainClock.currentTime
        runBlocking(Dispatchers.Default) { state.value = 1 }
        runOnIdle {
            assertEquals(listOf(0, 1), observations.map { it.first })
            observations.forEach { assertSame(Looper.getMainLooper(), it.second) }
            assertEquals(before, mainClock.currentTime)
        }
    }
}
