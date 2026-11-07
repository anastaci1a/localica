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
    private class Join(val nanos: Long, val before: Kinematic, val after: Kinematic, val target: Float, val policy: String, val release: Float?)
    private class Recorder : MotionEvents {
        val joins = mutableListOf<Join>()
        private fun player(m: MotionFloat) = m.key == ("player" to "phase")
        override fun created(motion: MotionFloat) {}
        override fun joined(motion: MotionFloat, join: MotionJoin) { if (player(motion)) joins += Join(join.stamp.nanos, join.before, join.after, motion.target, join.policy, join.releaseVelocity) }
        override fun retired(motion: MotionFloat, stamp: FrameStamp) {}
    }
    /** Independent #608 oracle: fitted core, position/velocity duration law, C1 startup and terminal Hermite bridges. */
    private object Law {
        const val T = .426086832
        private fun bezier(u: Double): Pair<Double, Double> {
            val (x1, y1, x2, y2) = listOf(.242192, .999967, .511935, .997652)
            fun c(s: Double, a: Double, b: Double) = 3 * (1 - s) * (1 - s) * s * a + 3 * (1 - s) * s * s * b + s * s * s
            fun d(s: Double, a: Double, b: Double) = 3 * (1 - s) * (1 - s) * a + 6 * (1 - s) * s * (b - a) + 3 * s * s * (1 - b)
            var s = u; repeat(30) { s = (s - (c(s, x1, x2) - u) / max(d(s, x1, x2), 1e-9)).coerceIn(0.0, 1.0) }
            return c(s, y1, y2) to d(s, y1, y2) / max(d(s, x1, x2), 1e-9)
        }
        private fun hermite(t: Double, tau: Double, a: Double, va: Double, b: Double, vb: Double): Double {
            val u = t / tau; return (2 * u * u * u - 3 * u * u + 1) * a + (u * u * u - 2 * u * u + u) * tau * va + (-2 * u * u * u + 3 * u * u) * b + (u * u * u - u * u) * tau * vb
        }
        fun duration(p: Double, v: Double, q: Double) = max(T * (.500401615 + .499598385 * abs(q - p)) / max(1.0, abs(v) / 1.41952128).pow(.73607278), 2 / 120.0)
        fun at(p: Double, v: Double, q: Double, t: Double): Double {
            val d = duration(p, v, q); if (t >= d) return q
            fun core(s: Double) = bezier(s / d).let { (e, slope) -> p + (q - p) * e to (q - p) * slope / d }
            val tin = min(1 / 120.0, d / 4); val tout = min(d / 8, .064)
            return when {
                t < tin -> core(tin).let { (b, vb) -> hermite(t, tin, p, v, b, vb) }
                t > d - tout -> core(d - tout).let { (a, va) -> hermite(t - (d - tout), tout, a, va, q, 0.0) }
                else -> core(t).first
            }
        }
    }
    private class Frame(val nanos: Long, val p: Double)
    private class Scene { var clock: UiClock? = null; val recorder = Recorder() }

    private fun ComposeUiTest.start(scene: Scene) {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("interface", 0).edit().clear().commit()
        val track = Track("continuity", "Evening", fileName = "song.mp3", relativePath = "song.mp3")
        val queue = QueueEdits.create(listOf(track), 0, "Evening queue")
        setContent {
            val clock = remember { UiClock(object : FrameSource { override suspend fun awaitFrame() = withFrameNanos { it } }, { mainClock.currentTime * 1_000_000 }) }
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
    private fun ComposeUiTest.frames(count: Int, travel: Double, into: MutableList<Frame>) =
        repeat(count) { mainClock.advanceTimeByFrame(); into += Frame(mainClock.currentTime * 1_000_000, 1 - top() / travel) }
    private fun ComposeUiTest.drag(from: Offset, step: Float, count: Int, hold: Int = 0) {
        onRoot().performTouchInput { down(from) }
        repeat(count) { onRoot().performTouchInput { moveBy(Offset(0f, -step), delayMillis = 16) } }
        repeat(hold) { onRoot().performTouchInput { moveBy(Offset.Zero, delayMillis = 16) } }
        onRoot().performTouchInput { up() }
    }
    /** Every presented frame after an automatic join matches the oracle from that join; joins keep value and velocity. */
    private fun verify(label: String, frames: List<Frame>, joins: List<Join>, fallback: Join) {
        val automatic = joins.filter { it.policy != "DrivenInput" && it.policy != "Initial" }.ifEmpty { listOf(fallback) }
        for (j in joins.filter { it.policy == "Continuous" || it.policy == "DrivenRelease" }) {
            assertEquals("$label join value", j.before.value.toDouble(), j.after.value.toDouble(), 1e-4)
            if (j.policy == "Continuous") assertEquals("$label join velocity", j.before.velocity.toDouble(), j.after.velocity.toDouble(), 1e-3)
            else assertEquals("$label release hand-off is the accepted tracker velocity", j.release!!.toDouble(), j.after.velocity.toDouble(), 1e-4)
        }
        var worst = 0.0
        for (f in frames) {
            val j = automatic.lastOrNull { it.nanos <= f.nanos } ?: continue
            if (joins.any { it.policy == "DrivenInput" && it.nanos > j.nanos && it.nanos <= f.nanos }) continue
            val expected = Law.at(j.after.value.toDouble(), j.after.velocity.toDouble(), j.target.toDouble(), (f.nanos - j.nanos) / 1e9)
            worst = max(worst, abs(expected - f.p))
        }
        assertTrue("$label: presented phase deviates from the #608 law by $worst", worst <= .008)
    }
    private fun join(nanos: Long, p: Double, v: Double, q: Boolean) = Join(nanos, Kinematic(p.toFloat(), v.toFloat()), Kinematic(p.toFloat(), v.toFloat()), if (q) 1f else 0f, "Fallback", null)

    @Test fun tapBackAndReverseFollowTheLawWithC1Joins() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        val travel = top(); val frames = mutableListOf<Frame>()
        val t0 = mainClock.currentTime * 1_000_000
        onNodeWithTag("player-surface").performClick(); frames(6, travel, frames)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); frames(4, travel, frames)
        onNodeWithTag("player-surface").performClick(); frames(40, travel, frames)
        verify("tap/Back/tap", frames, scene.recorder.joins, join(t0, 0.0, 0.0, true))
        assertEquals("tap/Back/tap settles expanded", 1.0, frames.last().p, 1e-3)
    }

    private fun releaseCase(label: String, step: Float, count: Int, expanded: Boolean, hold: Int = 0, expectOpen: Boolean) = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        val travel = top(); val frames = mutableListOf<Frame>()
        if (expanded) { onNodeWithTag("player-surface").performClick(); frames(60, travel, frames); frames.clear() }
        val from = if (expanded) Offset(onRoot().fetchSemanticsNode().boundsInRoot.width / 2, travel.toFloat() * .3f)
            else onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        drag(from, step, count, hold)
        val release = mainClock.currentTime * 1_000_000; val p0 = 1 - top() / travel
        frames(45, travel, frames)
        val v = if (hold > 0) 0.0 else step / .016 / travel
        verify(label, frames, scene.recorder.joins, join(release, p0, v, expectOpen))
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
        val travel = top(); val frames = mutableListOf<Frame>()
        val from = onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        drag(from, (travel * fraction / 24).toFloat(), 24, hold = 8)
        val release = mainClock.currentTime * 1_000_000; val p0 = 1 - top() / travel
        frames(45, travel, frames)
        verify("halfway $fraction", frames, scene.recorder.joins, join(release, p0, 0.0, open))
        assertEquals("near-50% stationary release at $p0", if (open) 1.0 else 0.0, frames.last().p, 1e-3)
    }

    @Test fun catchingAMovingPlayerAndReversingKeepsTheSurfaceUnderTheFinger() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        val travel = top(); val frames = mutableListOf<Frame>()
        onNodeWithTag("player-surface").performClick(); frames(8, travel, frames)
        val caught = top()
        onRoot().performTouchInput { down(Offset(width / 2f, caught.toFloat() + 40f)) }
        mainClock.advanceTimeByFrame()
        assertEquals("contact catches the moving surface without a jump", caught, top(), 3.0)
        repeat(8) { onRoot().performTouchInput { moveBy(Offset(0f, -16f), delayMillis = 16) } }
        repeat(10) { onRoot().performTouchInput { moveBy(Offset(0f, 24f), delayMillis = 16) } }
        onRoot().performTouchInput { up() }
        frames(45, travel, frames)
        verify("catch/reverse", frames, scene.recorder.joins, join(0, 0.0, 0.0, true))
        assertEquals("reversed release follows its downward velocity", 0.0, frames.last().p, 1e-3)
    }

    @Test fun lyricsToggleDuringCollapseKeepsOnePlayerTimeline() = localicaRunComposeUiTest {
        val scene = Scene(); start(scene)
        val travel = top(); val frames = mutableListOf<Frame>()
        onNodeWithTag("player-surface").performClick(); frames(60, travel, frames)
        onNodeWithContentDescription("Show lyrics", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick); frames(40, travel, frames)
        frames.clear(); val t0 = mainClock.currentTime * 1_000_000
        onNodeWithContentDescription("Collapse player", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick); frames(5, travel, frames)
        onAllNodesWithContentDescription("Hide lyrics", useUnmergedTree = true).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        frames(40, travel, frames)
        verify("lyrics during collapse", frames, scene.recorder.joins, join(t0, 1.0, 0.0, false))
        assertEquals("collapse completes through the lyrics toggle", 0.0, frames.last().p, 1e-3)
    }
}
