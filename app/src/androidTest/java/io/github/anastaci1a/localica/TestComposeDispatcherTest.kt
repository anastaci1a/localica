package io.github.anastaci1a.localica

import android.os.Looper
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TestComposeDispatcherTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backgroundReturnKeepsEffectsAndRecompositionOnUi() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val ready = mutableStateOf(false)
        compose.setContent {
            BasicText(if (ready.value) "Worker completed" else "Waiting for worker")
            SideEffect { assertSame("Composition stays on Main", Looper.getMainLooper(), Looper.myLooper()) }
            LaunchedEffect(Unit) {
                withContext(Dispatchers.Default) { started.complete(Unit); release.await() }
                assertSame("Returning from worker preparation resumes on Main", Looper.getMainLooper(), Looper.myLooper())
                ready.value = true
                finished.complete(Unit)
            }
        }
        compose.waitUntil(5000) { started.isCompleted }
        compose.mainClock.autoAdvance = false
        val before = compose.mainClock.currentTime
        try {
            runBlocking(Dispatchers.Default) { release.complete(Unit) }
            compose.waitUntil(5000) { finished.isCompleted }
            compose.runOnIdle {
                assertTrue(ready.value)
                assertEquals("Worker completion cannot advance a paused frame", before, compose.mainClock.currentTime)
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("Worker completed").assertExists()
        } finally {
            release.complete(Unit)
            compose.mainClock.autoAdvance = true
        }
    }

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
