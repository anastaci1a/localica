package io.github.anastaci1a.localica

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.components.verticalSurfaceDrag
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #676: exercise the real modifier with native historical samples and a tied-time UP. */
class PlayerDragVelocityTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun batchedClosingMovesAndTiedTimeUpKeepTheClosingVelocity() = replay()
    @Test fun heldReleaseHasZeroVelocity() = replay(held = true)
    @Test fun batchedReversalUsesTheLastFingerDirection() = replay(reversed = true)
    @Test fun translatingSurfaceDoesNotChangeFingerVelocity() = replay(translated = true)

    private fun replay(held: Boolean = false, reversed: Boolean = false, translated: Boolean = false) {
        lateinit var view: View
        var translation by mutableIntStateOf(0)
        var starts = 0
        var stops = 0
        var cancellations = 0
        var displacement = 0f
        var release = Float.NaN
        compose.setContent {
            view = LocalView.current
            Box(Modifier.fillMaxSize().offset { IntOffset(0, translation) }.verticalSurfaceDrag(
                onStart = { starts++ }, onDelta = { displacement += it },
                onStop = { release = it; stops++ }, onCancel = { cancellations++ }))
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        fun dispatch(action: Int, vararg samples: Pair<Long, Float>) {
            val remaining = downTime + samples.last().first - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(remaining)
            instrumentation.runOnMainSync {
                val first = samples.first()
                val event = MotionEvent.obtain(downTime, downTime + first.first, action, 80f, first.second, 0)
                try {
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    for ((time, y) in samples.drop(1)) event.addBatch(downTime + time, 80f, y, 1f, 1f, 0)
                    view.dispatchTouchEvent(event)
                } finally { event.recycle() }
            }
        }
        dispatch(MotionEvent.ACTION_DOWN, 0L to 80f)
        if (translated) {
            compose.runOnIdle { translation = 40 }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val moveEnd = maxOf(64L, SystemClock.uptimeMillis() - downTime + 64L)
        // One delivered MOVE contains four native samples. A manual tracker sees only its endpoint.
        dispatch(MotionEvent.ACTION_MOVE, (moveEnd - 48) to 110f, (moveEnd - 32) to 140f,
            (moveEnd - 16) to 170f, moveEnd to 200f)
        val lastTime: Long
        val lastY: Float
        if (reversed) {
            dispatch(MotionEvent.ACTION_MOVE, (moveEnd + 16) to 170f, (moveEnd + 32) to 140f,
                (moveEnd + 48) to 110f, (moveEnd + 64) to 80f)
            lastTime = moveEnd + 64; lastY = 80f
        } else {
            // Repeated endpoint at a distinct time, then UP at that exact same timestamp.
            dispatch(MotionEvent.ACTION_MOVE, (moveEnd + 15) to 200f)
            lastTime = moveEnd + 15; lastY = 200f
        }
        dispatch(MotionEvent.ACTION_UP, (lastTime + if (held) 100L else 0L) to lastY)
        compose.runOnIdle {
            assertEquals("The modifier must retain one contact", 1, starts)
            assertEquals("The contact must release exactly once", 1, stops)
            assertEquals("No cancellation may replace release", 0, cancellations)
            assertEquals(if (reversed) 0f else 120f, displacement, .001f)
            assertTrue("Release velocity must be finite: $release", release.isFinite())
            when {
                held -> assertEquals("A still hold must not fling", 0f, release, 0f)
                reversed -> assertTrue("The last physical direction is upward: $release", release < 0f)
                else -> assertTrue("A downward finger must not produce an opening velocity: $release", release > 0f)
            }
        }
    }
}
