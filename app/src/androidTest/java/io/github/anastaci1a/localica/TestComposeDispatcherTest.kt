package io.github.anastaci1a.localica

import android.os.Looper
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TestComposeDispatcherTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun idleDrainsWorkerEmissionsOnUiWithoutAdvancingPausedClock() {
        val state = MutableStateFlow(0)
        val observations = mutableListOf<Pair<Int, Looper?>>()
        compose.setContent {
            LaunchedEffect(Unit) { state.collect { observations += it to Looper.myLooper() } }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val before = compose.mainClock.currentTime
        runBlocking(Dispatchers.Default) { state.value = 1 }
        compose.runOnIdle {
            assertEquals(listOf(0, 1), observations.map { it.first })
            observations.forEach { assertSame(Looper.getMainLooper(), it.second) }
            assertEquals(before, compose.mainClock.currentTime)
        }
    }

    @Test fun idleLeavesDelayedEffectsPendingUntilClockAdvances() {
        var finished = false
        compose.mainClock.autoAdvance = false
        val before = compose.mainClock.currentTime
        compose.setContent { LaunchedEffect(Unit) { delay(1000); finished = true } }
        compose.runOnIdle {
            assertFalse(finished)
            assertEquals(before, compose.mainClock.currentTime)
        }
        compose.mainClock.advanceTimeBy(1000, ignoreFrameDuration = true)
        compose.runOnIdle { assertTrue(finished) }
    }
}
