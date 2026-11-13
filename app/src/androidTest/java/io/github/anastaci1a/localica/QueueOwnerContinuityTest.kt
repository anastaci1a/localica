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
    private class Scene { var clock: UiClock? = null; val recorder = JoinRecorder("queue"); var closed = 0.0; var travel = 1.0; var playerProgress = Float.NaN
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
            CompositionLocalProvider(LocalClockOwner provides clock, LocalUiClock provides clock,
                LocalPlayerWorkObserver provides { kind, p -> if (kind == "root" && p != null) scene.playerProgress = p }) { LocalicaTheme { LocalicaScreen(
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

    @Test fun downwardSwipeOutsideReturningQueueMinimizesPlayer() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        assertEquals("The player starts expanded", 1f, scene.playerProgress, .001f)
        click("Open queue"); repeat(60) { mainClock.advanceTimeByFrame() }
        back(); mainClock.advanceTimeByFrame()
        var frames = 0
        while (phase(scene) > .6 && frames++ < 30) mainClock.advanceTimeByFrame()
        val returningTop = top()
        assertTrue("The queue must still be returning", phase(scene) > 0.0 && phase(scene) < 1.0)
        val root = onRoot()
        val bounds = root.fetchSemanticsNode().boundsInRoot
        val start = Offset(bounds.width * .75f, returningTop.toFloat() * .5f)
        assertTrue("Second contact starts outside the moving queue hitbox", start.y < returningTop)
        root.performTouchInput {
            down(start)
            repeat(6) { moveBy(Offset(0f, bounds.height * .07f), delayMillis = 16) }
        }
        mainClock.advanceTimeByFrame()
        assertTrue("The exposed player must follow the second downward swipe immediately",
            scene.playerProgress < .9f)
        root.performTouchInput { up() }
        repeat(60) { mainClock.advanceTimeByFrame() }
        assertEquals("The player must settle minimized", 0f, scene.playerProgress, .001f)
    }

    /** No motion before slop: the queue must finish independently of this fresh parent contact. */
    private fun ComposeUiTest.holdOutsideUntilQueueCloses(scene: Scene) {
        click("Open queue"); repeat(60) { mainClock.advanceTimeByFrame() }
        back(); mainClock.advanceTimeByFrame()
        var frames = 0
        while (phase(scene) > .6 && frames++ < 30) mainClock.advanceTimeByFrame()
        assertTrue("Contact begins during queue return", phase(scene) > 0.0 && phase(scene) < 1.0)
        val returningTop = top()
        onRoot().performTouchInput { down(Offset(center(), returningTop.toFloat() * .5f)) }
        repeat(60) { mainClock.advanceTimeByFrame() }
        assertEquals("The queue settles between DOWN and touch slop", 0.0, phase(scene), 1e-3)
    }

    @Test fun queueClosingBetweenDownAndSlopStillAllowsPlayerMinimize() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        assertEquals("The player starts expanded", 1f, scene.playerProgress, .001f)
        holdOutsideUntilQueueCloses(scene)
        assertEquals("A still contact must not move the player", 1f, scene.playerProgress, .001f)
        val root = onRoot()
        val height = root.fetchSemanticsNode().boundsInRoot.height
        root.performTouchInput { repeat(6) { moveBy(Offset(0f, height * .07f), delayMillis = 16) } }
        mainClock.advanceTimeByFrame()
        assertTrue("The retained contact must drive the player after the queue closes",
            scene.playerProgress < .9f)
        root.performTouchInput { up() }
        repeat(60) { mainClock.advanceTimeByFrame() }
        assertEquals("The player settles minimized", 0f, scene.playerProgress, .001f)
    }

    @Test fun returningScrimReleasesTapHitTestingAfterUp() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        assertEquals("The player starts expanded", 1f, scene.playerProgress, .001f)
        holdOutsideUntilQueueCloses(scene)
        onRoot().performTouchInput { up() }
        // Apply the contact-end state once; do not advance a settling delay.
        mainClock.advanceTimeByFrame()
        assertEquals("UP without a swipe keeps the player open", 1f, scene.playerProgress, .001f)
        onNodeWithContentDescription("Player handle", useUnmergedTree = true).performTouchInput { click() }
        repeat(60) { mainClock.advanceTimeByFrame() }
        assertEquals("The real tap must reach the underlying player handle", 0f, scene.playerProgress, .001f)
        onNodeWithTag("tab-option-1").assertIsNotSelected().performTouchInput { click() }
        repeat(60) { mainClock.advanceTimeByFrame() }
        onNodeWithTag("tab-option-1").assertIsSelected()
    }

    /** Opens the Queue, then Back, and holds the clock while it returns above [phase]. */
    private fun ComposeUiTest.returnQueue(scene: Scene, phase: Double) {
        click("Open queue"); repeat(60) { mainClock.advanceTimeByFrame() }
        back(); mainClock.advanceTimeByFrame()
        var frames = 0
        while (phase(scene) > phase && frames++ < 30) mainClock.advanceTimeByFrame()
    }

    @Test fun queueFaceContactWhilePlayerAndQueueReturnCatchesTheQueue() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene); returnQueue(scene, .95)
        val root = onRoot(); val bounds = root.fetchSemanticsNode().boundsInRoot
        // A short contact outside the face, held still before UP, springs the displaced player back toward expanded.
        root.performTouchInput { down(Offset(bounds.width * .75f, top().toFloat() * .5f))
            repeat(4) { moveBy(Offset(0f, bounds.height * .03f), delayMillis = 8) }
            moveBy(Offset.Zero, delayMillis = 45); up() }
        mainClock.advanceTimeByFrame()
        val player = scene.playerProgress
        assertTrue("Both sheets must still move", player < 1f && phase(scene) > 0.0 && phase(scene) < 1.0)
        val caught = top()
        root.performTouchInput { down(Offset(center(), caught.toFloat() + 24f)) }
        mainClock.advanceTimeByFrame()
        assertEquals("The Queue face must catch its own moving drawer", caught, top(), 3.0)
        repeat(30) { mainClock.advanceTimeByFrame() }
        assertEquals("The contact must not hold the player", 1f, scene.playerProgress, .001f)
        root.performTouchInput { up() }
    }

    @Test fun browseTapAboveMinimizingPlayerWhileQueueReturnsLeavesThePlayer() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene); returnQueue(scene, .95)
        val root = onRoot(); val bounds = root.fetchSemanticsNode().boundsInRoot
        // Both targets collapse together, as openCollection requests (LocalicaScreen.kt:215).
        root.performTouchInput { down(Offset(bounds.width * .75f, top().toFloat() * .5f))
            repeat(6) { moveBy(Offset(0f, bounds.height * .07f), delayMillis = 8) }; up() }
        var frames = 0
        while (scene.playerProgress > .6f && frames++ < 30) mainClock.advanceTimeByFrame()
        val pressed = scene.playerProgress
        assertTrue("Both sheets must still move", pressed > 0f && phase(scene) > 0.0)
        // Browse above the shrinking player's top edge, outside the clipped Queue scrim.
        root.performTouchInput { down(Offset(bounds.width / 2, bounds.height * .1f)) }
        mainClock.advanceTimeByFrame()
        assertTrue("The browse contact must not catch the minimizing player", scene.playerProgress < pressed)
        root.performTouchInput { up() }
        repeat(60) { mainClock.advanceTimeByFrame() }
        assertEquals("The player settles minimized", 0f, scene.playerProgress, .001f)
    }
}
