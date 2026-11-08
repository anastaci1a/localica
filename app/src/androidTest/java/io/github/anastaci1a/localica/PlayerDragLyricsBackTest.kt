package io.github.anastaci1a.localica

import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import org.junit.Test

/** #676: native Back may close lyrics, but it must not retire an already owned closing contact. */
@OptIn(ExperimentalTestApi::class)
class PlayerDragLyricsBackTest {
    @Test fun backEarlyInClosingDragKeepsItsReleaseAndCommitsCollapse() = closingDrag(6)
    @Test fun backMidClosingDragKeepsItsReleaseAndCommitsCollapse() = closingDrag(18)
    @Test fun backLateInClosingDragKeepsItsReleaseAndCommitsCollapse() = closingDrag(30)
    @Test fun queuedMovesAcrossBackKeepTheirReleaseAndCommitCollapse() = closingDrag(18, 4)

    private fun closingDrag(backStep: Int, movesPerFrame: Int = 1) = localicaRunComposeUiTest {
        fun checkpoint(stage: String) = android.util.Log.i("BackCollapse676", "step=$backStep batch=$movesPerFrame $stage")
        checkpoint("start")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.targetContext.getSharedPreferences("interface", 0).edit().clear().commit()
        val track = Track("back-drag", "Back during drag", fileName = "missing.mp3", relativePath = "missing.mp3")
        lateinit var clock: UiClock
        val recorder = JoinRecorder("player")
        var releases = 0
        setContent {
            val root = remember {
                UiClock(object : FrameSource {
                    override suspend fun awaitFrame() = withFrameNanos { it }
                    override suspend fun <R> awaitFrame(onFrame: (Long) -> R) = withFrameNanos(onFrame)
                }, { mainClock.currentTime * 1_000_000 })
            }
            SideEffect {
                clock = root
                root.events = object : MotionEvents by recorder {
                    override fun releaseBridge(motion: MotionFloat, tau: Double, approvedTau: Double, displayInterval: Double) {
                        if (motion.key == ("player" to "phase")) releases++
                    }
                }
            }
            LaunchedEffect(root) { root.run() }
            CompositionLocalProvider(LocalClockOwner provides root, LocalUiClock provides root) {
                LocalicaTheme {
                    LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
                        PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title),
                        {}, {}, {}, {}, {}, {})
                }
            }
        }
        waitForIdle()
        checkpoint("content ready")
        mainClock.autoAdvance = false
        fun settleOwners() {
            // Drain pending composition intents, then follow owner lifetime, not an old tween duration.
            repeat(2) { mainClock.advanceTimeByFrame() }
            var frames = 0
            while (clock.activeCount > 0 && frames++ < 240) mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals("All admitted owners must reach rest", 0, clock.activeCount)
        }
        val collapsed = onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
        // Each ordering has its own bounded test lifetime on the software emulator.
        run {
            onNodeWithTag("player-surface").performClick()
            settleOwners()
            checkpoint("player open")
            onNodeWithContentDescription("Show lyrics").performClick()
            settleOwners()
            checkpoint("lyrics open")
            onNodeWithContentDescription("Hide lyrics").assertExists()
            val handle = onNodeWithContentDescription("Player handle").fetchSemanticsNode().boundsInRoot.center
            val full = onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
            val step = (collapsed.top - full.top) * .65f / 40
            val before = releases
            onRoot().performTouchInput { down(handle) }
            for (i in 1..40) {
                onRoot().performTouchInput { moveBy(Offset(0f, step), delayMillis = 16) }
                // The A35 failure delivered several MOVE samples between presented frames.
                // Keep that ordering fixed, including Back between two queued move groups.
                if (i % movesPerFrame == 0 || i == 40) mainClock.advanceTimeByFrame()
                if (i == backStep) {
                    checkpoint("before Back at move $i")
                    val moving = onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
                    assertTrue("Back must arrive during an admitted closing drag", moving.top > full.top + 1f && moving.top < collapsed.top)
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    mainClock.advanceTimeByFrame()
                    checkpoint("after Back")
                }
            }
            checkpoint("before UP; joins=${recorder.joins.map { it.target to it.policy }}")
            onRoot().performTouchInput { advanceEventTime(16); up() }
            settleOwners()
            checkpoint("released; releases=$releases")
            assertEquals("Back must preserve exactly one release (step=$backStep; joins=${recorder.joins.map { it.target to it.policy }})",
                before + 1, releases)
            onNodeWithContentDescription("Collapse player").assertDoesNotExist()
            val closed = onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
            assertEquals("The released player must return to its compact seat", collapsed.top, closed.top, 1f)
            onNodeWithTag("player-surface").performClick()
            settleOwners()
            checkpoint("complete")
            onNodeWithContentDescription("Show lyrics").assertExists()
            onNodeWithContentDescription("Hide lyrics").assertDoesNotExist()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            settleOwners()
        }
    }
}
