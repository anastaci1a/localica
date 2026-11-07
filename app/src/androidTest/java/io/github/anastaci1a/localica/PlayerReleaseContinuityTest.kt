package io.github.anastaci1a.localica

import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** #600 U10.01 / #608: the real player's presented top edge follows the measured expand/collapse law from every
 * intent (tap, Back, contact release, reversal, interruption, lyrics toggle), and every phase join is C1. Physical
 * frames are compared with an independent implementation of the #608 equations, not with the Engine's own plan. */
@OptIn(ExperimentalTestApi::class)
class PlayerReleaseContinuityTest {
    private class Scene { var clock: UiClock? = null; val recorder = JoinRecorder("player") }

    private fun ComposeUiTest.start(scene: Scene) {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("interface", 0).edit().clear().commit()
        val track = Track("continuity", "Evening", fileName = "song.mp3", relativePath = "song.mp3")
        val queue = QueueEdits.create(listOf(track), 0, "Evening queue")
        setContent {
            val clock = remember { UiClock(object : FrameSource { override suspend fun awaitFrame() = withFrameNanos { it }
                override suspend fun <R> awaitFrame(onFrame: (Long) -> R) = withFrameNanos(onFrame) }, { mainClock.currentTime * 1_000_000 }) }
            SideEffect { if (scene.clock == null) { clock.events = scene.recorder; scene.clock = clock } }
            LaunchedEffect(clock) { clock.run() }
            CompositionLocalProvider(LocalClockOwner provides clock, LocalUiClock provides clock) { LocalicaTheme { LocalicaScreen(
                LibraryUiState(LibrarySnapshot(tracks = listOf(track)), loading = false),
                PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title),
                {}, {}, {}, {}, {}, {}, queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)) } }
        }
        waitForIdle(); mainClock.autoAdvance = false
    }
    private fun ComposeUiTest.top() = onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot.top.toDouble()
    private fun ComposeUiTest.frames(count: Int, travel: Double, into: MutableList<PhaseFrame>) =
        repeat(count) { mainClock.advanceTimeByFrame(); into += PhaseFrame(mainClock.currentTime * 1_000_000, 1 - top() / travel) }
    private fun ComposeUiTest.drag(from: Offset, step: Float, count: Int, hold: Int = 0) {
        onRoot().performTouchInput { down(from) }
        repeat(count) { onRoot().performTouchInput { moveBy(Offset(0f, -step), delayMillis = 16) } }
        repeat(hold) { onRoot().performTouchInput { moveBy(Offset.Zero, delayMillis = 16) } }
        onRoot().performTouchInput { up() }
    }

    @Test fun tapBackAndReverseFollowTheLawWithC1Joins() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        val travel = top(); val frames = mutableListOf<PhaseFrame>()
        val t0 = mainClock.currentTime * 1_000_000
        onNodeWithTag("player-surface").performClick(); frames(6, travel, frames)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); frames(4, travel, frames)
        onNodeWithTag("player-surface").performClick(); frames(40, travel, frames)
        verifyReleaseLaw("tap/Back/tap", frames, scene.recorder.joins, fallbackJoin(t0, 0.0, 0.0, true))
        assertEquals("tap/Back/tap settles expanded", 1.0, frames.last().p, 1e-3)
    }

    private fun releaseCase(label: String, step: Float, count: Int, expanded: Boolean, hold: Int = 0, expectOpen: Boolean) = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        val travel = top(); val frames = mutableListOf<PhaseFrame>()
        if (expanded) { onNodeWithTag("player-surface").performClick(); frames(60, travel, frames); frames.clear() }
        val from = if (expanded) Offset(onRoot().fetchSemanticsNode().boundsInRoot.width / 2, travel.toFloat() * .3f)
            else onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        drag(from, step, count, hold)
        val release = mainClock.currentTime * 1_000_000; val p0 = 1 - top() / travel
        frames(45, travel, frames)
        val v = if (hold > 0) 0.0 else step / .016 / travel
        verifyReleaseLaw(label, frames, scene.recorder.joins, fallbackJoin(release, p0, v, expectOpen))
        assertEquals("$label settles at the release decision", if (expectOpen) 1.0 else 0.0, frames.last().p, 1e-3)
    }
    @Test fun slowOpeningReleaseBelowDeadBandUsesHalfway() = releaseCase("slow", 3f, 30, false, expectOpen = false)
    @Test fun mediumOpeningRelease() = releaseCase("medium", 20f, 14, false, expectOpen = true)
    @Test fun fastOpeningRelease() = releaseCase("fast", 60f, 6, false, expectOpen = true)
    @Test fun mediumClosingRelease() = releaseCase("closing", -20f, 14, true, expectOpen = false)
    @Test fun stationaryJustAboveHalfOpens() = localicaRunComposeUiTest { halfway(this, .53, true) }
    @Test fun stationaryJustBelowHalfCloses() = localicaRunComposeUiTest { halfway(this, .47, false) }
    private fun halfway(test: ComposeUiTest, fraction: Double, open: Boolean) = with(test) {
        val scene = Scene(); start(scene)
        val travel = top(); val frames = mutableListOf<PhaseFrame>()
        val from = onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        drag(from, (travel * fraction / 24).toFloat(), 24, hold = 8)
        val release = mainClock.currentTime * 1_000_000; val p0 = 1 - top() / travel
        frames(45, travel, frames)
        verifyReleaseLaw("halfway $fraction", frames, scene.recorder.joins, fallbackJoin(release, p0, 0.0, open))
        assertEquals("near-50% stationary release at $p0", if (open) 1.0 else 0.0, frames.last().p, 1e-3)
    }

    @Test fun catchingAMovingPlayerAndReversingKeepsTheSurfaceUnderTheFinger() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        val travel = top(); val frames = mutableListOf<PhaseFrame>()
        onNodeWithTag("player-surface").performClick(); frames(8, travel, frames)
        val caught = top()
        onRoot().performTouchInput { down(Offset(width / 2f, caught.toFloat() + 40f)) }
        mainClock.advanceTimeByFrame()
        assertEquals("contact catches the moving surface without a jump", caught, top(), 3.0)
        repeat(8) { onRoot().performTouchInput { moveBy(Offset(0f, -16f), delayMillis = 16) } }
        repeat(10) { onRoot().performTouchInput { moveBy(Offset(0f, 24f), delayMillis = 16) } }
        onRoot().performTouchInput { up() }
        frames(45, travel, frames)
        verifyReleaseLaw("catch/reverse", frames, scene.recorder.joins, fallbackJoin(0, 0.0, 0.0, true))
        assertEquals("reversed release follows its downward velocity", 0.0, frames.last().p, 1e-3)
    }

    @Test fun lyricsToggleDuringCollapseKeepsOnePlayerTimeline() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        val travel = top(); val frames = mutableListOf<PhaseFrame>()
        onNodeWithTag("player-surface").performClick(); frames(60, travel, frames)
        onNodeWithContentDescription("Show lyrics", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick); frames(40, travel, frames)
        frames.clear(); val t0 = mainClock.currentTime * 1_000_000
        onNodeWithContentDescription("Collapse player", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick); frames(5, travel, frames)
        onAllNodesWithContentDescription("Hide lyrics", useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        frames(40, travel, frames)
        verifyReleaseLaw("lyrics during collapse", frames, scene.recorder.joins, fallbackJoin(t0, 1.0, 0.0, false))
        assertEquals("collapse completes through the lyrics toggle", 0.0, frames.last().p, 1e-3)
    }
}
