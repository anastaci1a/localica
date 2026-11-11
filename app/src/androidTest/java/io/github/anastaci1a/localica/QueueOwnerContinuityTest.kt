package io.github.anastaci1a.localica

import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import org.junit.Test

/** #600 U10.02 / #608: the Queue drawer's presented lip top follows the measured expand/collapse law from tap, Back,
 * lip drag, list pull and the expanded player's upward drag, with C1 joins, and a contact catches the moving drawer. */
@OptIn(ExperimentalTestApi::class)
class QueueOwnerContinuityTest {
    private class Scene { var clock: UiClock? = null; val recorder = JoinRecorder("queue"); var closed = 0.0; var travel = 1.0
        /** The drawer's own #608 object time: its edge travel against the full-player reference travel. */
        fun objectTime() = SheetLaw.objectTime(travel, clock!!.context!!.fullPlayerReferenceTravelPx.toDouble()) }

    private fun ComposeUiTest.start(scene: Scene) {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("interface", 0).edit().clear().commit()
        val tracks = (1..3).map { Track("queue-$it", "Evening $it", fileName = "song$it.mp3", relativePath = "song$it.mp3") }
        val queue = QueueEdits.create(tracks, 0, "Evening queue")
        setContent {
            val clock = remember { UiClock(object : FrameSource { override suspend fun awaitFrame() = withFrameNanos { it }
                override suspend fun <R> awaitFrame(onFrame: (Long) -> R) = withFrameNanos(onFrame) }, { mainClock.currentTime * 1_000_000 }) }
            SideEffect { if (scene.clock == null) { clock.events = scene.recorder; scene.clock = clock } }
            LaunchedEffect(clock) { clock.run() }
            CompositionLocalProvider(LocalClockOwner provides clock, LocalUiClock provides clock) { LocalicaTheme { LocalicaScreen(
                LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false),
                PlaybackState(connected = true, trackId = tracks[0].id, trackUri = tracks[0].uri, title = tracks[0].title),
                {}, {}, {}, {}, {}, {}, queues = QueueUiState(QueueLibrary(listOf(queue), queue.id), loading = false)) } }
        }
        waitForIdle(); mainClock.autoAdvance = false
        onNodeWithTag("player-surface").performClick(); repeat(60) { mainClock.advanceTimeByFrame() }
        // Measure both endpoints from layout: closed lip top, then the fully open top.
        scene.closed = top()
        click("Open queue"); repeat(60) { mainClock.advanceTimeByFrame() }
        scene.travel = scene.closed - top()
        back(); repeat(60) { mainClock.advanceTimeByFrame() }
        assertEquals("closed endpoint is stable", scene.closed, top(), 1.0)
        scene.recorder.joins.clear()
    }
    /** Unclipped drawer top: the surface is placed above its shelf-sized host, so clipped bounds would pin it. */
    private fun ComposeUiTest.top() = onNodeWithTag("queue-surface").fetchSemanticsNode().positionInRoot.y.toDouble()
    private fun ComposeUiTest.phase(scene: Scene) = (scene.closed - top()) / scene.travel
    private fun ComposeUiTest.now() = mainClock.currentTime * 1_000_000
    private fun ComposeUiTest.frames(scene: Scene, count: Int, into: MutableList<PhaseFrame>) =
        repeat(count) { mainClock.advanceTimeByFrame(); into += PhaseFrame(now(), phase(scene)) }
    private fun ComposeUiTest.click(description: String) =
        onAllNodesWithContentDescription(description, useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnClick)
    private fun back() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    /** Positive [step] moves the finger up (toward the open Queue). */
    private fun ComposeUiTest.drag(from: Offset, step: Float, count: Int) {
        onRoot().performTouchInput { down(from) }
        repeat(count) { onRoot().performTouchInput { moveBy(Offset(0f, -step), delayMillis = 16) } }
        onRoot().performTouchInput { up() }
    }
    private fun ComposeUiTest.release(scene: Scene, label: String, from: Offset, step: Float, count: Int, expectOpen: Boolean) {
        val frames = mutableListOf<PhaseFrame>()
        drag(from, step, count)
        val release = now(); val p0 = phase(scene)
        frames(scene, 45, frames)
        verifyReleaseLaw(SheetLaw, label, frames, scene.recorder.joins, fallbackJoin(release, p0, step / .016 / scene.travel, expectOpen), scene.objectTime())
        assertEquals("$label settles at the release decision", if (expectOpen) 1.0 else 0.0, frames.last().p, 1e-3)
    }
    private fun ComposeUiTest.center() = onRoot().fetchSemanticsNode().boundsInRoot.width / 2

    @Test fun tapThenBackFollowTheLawWithC1Joins() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene); val frames = mutableListOf<PhaseFrame>()
        val t0 = now()
        click("Open queue"); frames(scene, 6, frames)
        back(); frames(scene, 45, frames)
        verifyReleaseLaw(SheetLaw, "tap/Back", frames, scene.recorder.joins, fallbackJoin(t0, 0.0, 0.0, true), scene.objectTime())
        assertEquals("Back closes the drawer", 0.0, frames.last().p, 1e-3)
    }
    @Test fun lipDragReleaseOpens() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        release(scene, "lip", Offset(center(), scene.closed.toFloat() + 24f), 20f, 14, expectOpen = true)
    }
    @Test fun listPullReleaseCloses() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        click("Open queue"); repeat(60) { mainClock.advanceTimeByFrame() }; scene.recorder.joins.clear()
        release(scene, "list pull", Offset(center(), (scene.closed - scene.travel * .5).toFloat()), -20f, 14, expectOpen = false)
    }
    @Test fun expandedPlayerUpwardDragOpensTheQueue() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        release(scene, "player path", Offset(center(), (scene.closed * .5).toFloat()), 20f, 14, expectOpen = true)
    }
    @Test fun catchingAMovingDrawerAndReversingKeepsItUnderTheFinger() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene); val frames = mutableListOf<PhaseFrame>()
        click("Open queue"); frames(scene, 8, frames)
        val caught = top()
        onRoot().performTouchInput { down(Offset(center(), caught.toFloat() + 24f)) }
        mainClock.advanceTimeByFrame()
        assertEquals("contact catches the moving drawer without a jump", caught, top(), 3.0)
        repeat(6) { onRoot().performTouchInput { moveBy(Offset(0f, -16f), delayMillis = 16) } }
        repeat(10) { onRoot().performTouchInput { moveBy(Offset(0f, 24f), delayMillis = 16) } }
        onRoot().performTouchInput { up() }
        frames(scene, 45, frames)
        verifyReleaseLaw(SheetLaw, "catch/reverse", frames, scene.recorder.joins, fallbackJoin(0, 0.0, 0.0, true), scene.objectTime())
        assertEquals("reversed release follows its downward velocity", 0.0, frames.last().p, 1e-3)
    }
}
