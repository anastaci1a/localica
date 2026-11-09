package io.github.anastaci1a.localica

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ArtworkSwipeWidthBoundaryTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun releaseBeforeWidthKeyApplyUsesTheAppliedThresholdAndStride() = inspect(false)
    @Test fun widthKeyApplyCancelsTheOldRecognizerBeforeRelease() = inspect(true)

    private fun inspect(applyBeforeRelease: Boolean) {
        var width by mutableFloatStateOf(100f)
        val calls = mutableListOf<String>()
        var observed: ArtworkSwipe? = null
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            val (swipe, drag) = rememberArtworkSwipe("A", PlayerVisual(), null,
                ArtworkNeighbor("B", PlayerVisual()), 0f, true, InterfaceSettings(reducedMotion = true),
                { token, complete -> calls += token; complete(true) }, widthProvider = { width })
            SideEffect { observed = swipe }
            Box(Modifier.size(300.dp).then(drag).testTag("gesture"))
        } }
        try {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val origin = compose.onNodeWithTag("gesture").fetchSemanticsNode().layoutInfo.coordinates.localToWindow(Offset(150f, 80f))
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, at: Offset, delta: Long) {
                val event = MotionEvent.obtain(downTime, downTime + delta, action, at.x, at.y, 0)
                try { compose.activity.window.callback.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            compose.runOnUiThread {
                send(MotionEvent.ACTION_DOWN, origin, 0)
                send(MotionEvent.ACTION_MOVE, origin - Offset(30f, 0f), 500)
                assertEquals(-30f, requireNotNull(observed).offset.value, .01f)
                width = 200f
                if (!applyBeforeRelease) send(MotionEvent.ACTION_UP, origin - Offset(30f, 0f), 1000)
            }
            if (applyBeforeRelease) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.runOnUiThread { send(MotionEvent.ACTION_UP, origin - Offset(30f, 0f), 1000) }
            }
            compose.runOnIdle {
                assertEquals(if (applyBeforeRelease) emptyList<String>() else listOf("B"), calls)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
