package io.github.anastaci1a.localica

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native MOVE history and a duplicate-time UP exercise both real artwork recognizers. */
class ArtworkVelocityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun viewerBatchedPanAndTiedTimeUpFlingInTheFingerDirection() = viewer()
    @Test fun viewerHeldReleaseDoesNotFling() = viewer(held = true)
    @Test fun swipeBatchedMovesAndTiedTimeUpCommitPrevious() = swipe()
    @Test fun swipeBatchedMovesAndTiedTimeUpCommitNext() = swipe(direction = -1f)
    @Test fun swipeHeldReleaseBelowDistanceThresholdDoesNotNavigate() = swipe(held = true)

    private fun viewer(held: Boolean = false) {
        lateinit var view: View
        val state = ArtworkViewerState()
        compose.setContent {
            view = LocalView.current
            val scope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize().artworkViewerGestures(state, scope, { false }, 1f))
        }
        compose.runOnIdle {
            state.updateGeometry(Size(1000f, 1000f), Size(1000f, 1000f))
            state.zoomTo(4f)
        }
        compose.mainClock.autoAdvance = false
        replay(view, held, 1f)
        var released = 0f
        compose.runOnIdle { released = state.transform.offset.x }
        assertEquals("All native displacement reaches the viewer", 240f, released, .001f)
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {
            if (held) assertEquals("A held release cannot reuse pan velocity", released, state.transform.offset.x, 0f)
            else assertTrue("Rightward native history must fling right: ${state.transform.offset.x}",
                state.transform.offset.x > released)
        }
    }

    private fun swipe(held: Boolean = false, direction: Float = 1f) {
        lateinit var view: View
        lateinit var state: ArtworkSwipe
        val calls = mutableListOf<String>()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            view = LocalView.current
            val visual = PlayerVisual()
            val (swipe, modifier) = rememberArtworkSwipe("current", visual,
                ArtworkNeighbor("previous", visual), ArtworkNeighbor("next", visual),
                1400f, true, InterfaceSettings(), { visit, _ -> calls += visit })
            state = swipe
            Box(Modifier.fillMaxSize().then(modifier))
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        replay(view, held, direction)
        compose.runOnIdle {
            assertFalse("The contact must release", state.dragging)
            // At density 1, 240px exceeds 24px minimum travel but stays below 336px distance admission.
            assertEquals("Navigation must follow physical velocity, once", if (held) emptyList<String>()
                else listOf(if (direction > 0) "previous" else "next"), calls)
        }
    }

    private fun replay(view: View, held: Boolean, direction: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        val origin = if (direction > 0) 80f else 300f
        fun dispatch(action: Int, vararg samples: Pair<Long, Float>) {
            val remaining = downTime + samples.last().first - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(remaining)
            instrumentation.runOnMainSync {
                val first = samples.first()
                val event = MotionEvent.obtain(downTime, downTime + first.first, action,
                    origin + direction * first.second, 200f, 0)
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    for ((time, distance) in samples.drop(1))
                        event.addBatch(downTime + time, origin + direction * distance, 200f, 1f, 1f, 0)
                    view.dispatchTouchEvent(event)
                } finally { event.recycle() }
            }
        }
        dispatch(MotionEvent.ACTION_DOWN, 0L to 0f)
        val end = maxOf(64L, SystemClock.uptimeMillis() - downTime + 64L)
        dispatch(MotionEvent.ACTION_MOVE, (end - 48) to 60f, (end - 32) to 120f,
            (end - 16) to 180f, end to 240f)
        // The final MOVE and UP share time and coordinates, matching #676's degenerate fit.
        dispatch(MotionEvent.ACTION_MOVE, (end + 15) to 240f)
        dispatch(MotionEvent.ACTION_UP, (end + 15 + if (held) 100L else 0L) to 240f)
    }
}
