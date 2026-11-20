package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

/** #1083: the staged through timeline and installed property spring run on the shared UiClock, never a second timer. */
class FluidPopupClockTest {
    private class Rig(access: MotionAccess = MotionAccess.Standard, durationScale: Float = 1f) {
        val channel = Channel<Long>(Channel.UNLIMITED); var now = 0L
        val clock = UiClock(object : FrameSource { override suspend fun awaitFrame() = channel.receive() }, { now }, {})
        private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        init { configure(access, durationScale); scope.launch { clock.run() } }
        fun configure(access: MotionAccess, durationScale: Float = 1f) = clock.configure(EngineContext(1f / 120, 1000f, access, durationScale), StockUiSpec)
        fun frame(nanos: Long) { now = nanos; channel.trySend(nanos) }
        fun close() = scope.cancel()
    }
    private val queue = fluidThroughQueue(true, FluidMainEject, FluidContent.copy(lensingEnabled = false)) { emptyList() }
    private fun timeline(rig: Rig, ran: MutableList<Pair<FluidTimelineAction, Double>>) =
        FluidTimelineMotion(rig.clock) { step, speed -> ran += step.action to speed }.also { it.replace(queue) }

    @Test fun startDrainsTheZeroDelayPrefixThenEachFrameAdmitsDueActions() {
        val rig = Rig(); val ran = mutableListOf<Pair<FluidTimelineAction, Double>>(); val motion = timeline(rig, ran)
        rig.now = 1_000_000_000L; motion.start(0.7, false, false)
        val zero = queue.takeWhile { it.timing.storedDelay == 0.0 }.map { it.action }
        assertEquals(zero, ran.map { it.first }); assertTrue(ran.all { it.second == 0.7 }); assertEquals(1, rig.clock.activeCount)
        motion.start(0.7, false, false); assertEquals("a running timeline ignores a new start", zero.size, ran.size)
        rig.frame(1_000_000_000L + 69_000_000L); assertEquals(zero.size, ran.size)
        rig.frame(1_000_000_000L + 71_000_000L)
        assertEquals(zero + queue.filter { it.timing.storedDelay == 0.1 }.map { it.action }, ran.map { it.first })
        rig.frame(1_000_000_000L + 211_000_000L)
        assertEquals(queue.map { it.action }, ran.map { it.first }); assertTrue(motion.settled); assertEquals(0, rig.clock.activeCount)
        rig.close()
    }

    @Test fun dragScalesEveryThresholdAndAHoldTakesOnlyTheFirstAction() {
        val rig = Rig(durationScale = 2f); val ran = mutableListOf<Pair<FluidTimelineAction, Double>>(); val motion = timeline(rig, ran)
        motion.start(1.0, false, false); val prefix = ran.size
        rig.frame(199_000_000L); assertEquals(prefix, ran.size)
        rig.frame(201_000_000L); assertTrue(ran.size > prefix)
        val held = mutableListOf<Pair<FluidTimelineAction, Double>>(); val hold = timeline(rig, held)
        hold.start(1.0, false, true)
        assertEquals(listOf(queue.first().action to 1.0), held); assertTrue(hold.holding); assertFalse(hold.settled)
        rig.frame(10_000_000_000L); assertEquals(1, held.size)
        hold.start(1.0, false, false); assertEquals("a holding timeline ignores a new start", 1, held.size)
        rig.close()
    }

    @Test fun aHoldStopsTheTimerAndItsReleaseDrainsFromANewOrigin() {
        val rig = Rig(); val ran = mutableListOf<Pair<FluidTimelineAction, Double>>(); val motion = timeline(rig, ran)
        motion.start(0.7, false, false); val prefix = ran.size
        motion.hold(); assertTrue(motion.holding); assertEquals(0, rig.clock.activeCount)
        rig.frame(1_000_000_000L); assertEquals(prefix, ran.size)
        rig.now = 2_000_000_000L; motion.release(); assertFalse(motion.holding); assertEquals(1, rig.clock.activeCount)
        assertEquals(prefix, ran.size)
        rig.frame(2_069_000_000L); assertEquals("thresholds count from the release", prefix, ran.size)
        rig.frame(2_071_000_000L); assertTrue(ran.size > prefix)
        rig.close()
    }

    @Test fun replacingTheQueueResetsARunningOrHeldTimeline() {
        val rig = Rig(); val ran = mutableListOf<Pair<FluidTimelineAction, Double>>(); val motion = timeline(rig, ran)
        motion.start(0.7, false, true); motion.replace(queue); assertFalse(motion.holding); assertEquals(0, rig.clock.activeCount)
        motion.start(0.7, false, false); val prefix = ran.size - 1
        assertEquals(queue.takeWhile { it.timing.storedDelay == 0.0 }.map { it.action }, ran.drop(1).map { it.first })
        motion.replace(queue); assertEquals(0, rig.clock.activeCount)
        motion.start(0.7, false, false); assertEquals("a replaced timeline admits a new start", 1 + 2 * prefix, ran.size)
        rig.close()
    }

    @Test fun aPropertyRequestWithAnimationsOffLandsOnItsTarget() {
        val rig = Rig(MotionAccess.Reduced); val written = mutableListOf<FluidScalarSpringBody>(); var completed = 0
        val motion = FluidPropertyMotion(rig.clock, { a, b -> a == b }, { written += it }) { completed++ }
        motion.animate(0.0, 4.0, FluidPropertySpringControls(0.0, null, false, false), FluidPopupSpringSettings(1.0, 0.3), 1e-6)
        assertEquals(listOf(FluidScalarSpringBody(4.0, 0.0)), written); assertEquals(1, completed); assertEquals(0, rig.clock.activeCount)
        rig.close()
    }

    @Test fun reducedAccessRunsTheWholeQueueAtEffectiveSpeedZero() {
        val rig = Rig(MotionAccess.Reduced); val ran = mutableListOf<Pair<FluidTimelineAction, Double>>(); val motion = timeline(rig, ran)
        motion.start(0.7, true, false)
        assertEquals(queue.map { it.action to 0.0 }, ran); assertEquals(0, rig.clock.activeCount)
        val live = Rig(); val later = mutableListOf<Pair<FluidTimelineAction, Double>>(); timeline(live, later).start(0.7, false, false)
        live.configure(MotionAccess.Reduced); assertEquals(queue.map { it.action }, later.map { it.first }); assertEquals(0, live.clock.activeCount)
        rig.close(); live.close()
    }

    @Test fun aPropertyAdvancesTheInstalledSpringByFrameDeltaOverDragAndSnapsToTarget() {
        val rig = Rig(durationScale = 2f); val written = mutableListOf<FluidScalarSpringBody>(); var completed = 0
        val spring = FluidPopupSpringSettings(0.8, 0.45); val controls = FluidPropertySpringControls(0.0, null, false, false)
        val epsilon = 0.001 * 0.001
        val motion = FluidPropertyMotion(rig.clock, { a, b -> a == b }, { written += it }) { completed++ }
        rig.now = 1_000_000_000L; motion.animate(0.0, 4.0, controls, spring, epsilon); assertEquals(1, rig.clock.activeCount)
        val interval = (1f / 120).toDouble() * Double.fromBits(0x3ff004189374bc6aL)
        var previous = 1.0 + interval * -0.4; var expected = FluidScalarSpringEntry(FluidScalarSpringBody(0.0, 0.0), 4.0, FluidScalarSpringBody(0.0, 0.0))
        var nanos = 1_000_000_000L
        while (rig.clock.activeCount > 0) {
            nanos += 8_333_333L; rig.frame(nanos); val seconds = nanos / 1e9
            expected = spring.advanceUnsmoothed(expected, (seconds - previous) / 2.0); previous = seconds
            val done = expected.body.finished(4.0, spring.velocityScalingFactor, epsilon, false)
            assertEquals(if (done) FluidScalarSpringBody(4.0, 0.0) else expected.body, written.last())
            assertTrue("bounded settle", nanos < 5_000_000_000L)
        }
        assertEquals(1, completed); assertTrue(motion.settled)
        rig.close()
    }

    @Test fun aRetargetKeepsTheRetainedBodyAndAddsTheImpulse() {
        val rig = Rig(); val written = mutableListOf<FluidScalarSpringBody>()
        val spring = FluidPopupSpringSettings(1.0, 0.3, retargetImpulse = 0.02); val controls = FluidPropertySpringControls(0.0, null, false, false)
        val motion = FluidPropertyMotion(rig.clock, { a, b -> a == b }, { written += it })
        motion.animate(0.0, 1.0, controls, spring, 1e-6); rig.frame(16_000_000L); rig.frame(32_000_000L)
        val body = written.last(); motion.animate(9.0, 2.0, controls, spring, 1e-6); rig.frame(48_000_000L)
        val retained = FluidScalarSpringBody(body.value, body.velocity + (2.0 - 1.0) * (0.02 * spring.stiffness))
        val advanced = spring.advanceUnsmoothed(FluidScalarSpringEntry(retained, 2.0, FluidScalarSpringBody(1.0, 0.0)), 48_000_000L / 1e9 - 32_000_000L / 1e9)
        assertEquals(advanced.body, written.last())
        rig.configure(MotionAccess.Reduced); assertEquals(FluidScalarSpringBody(2.0, 0.0), written.last()); assertEquals(0, rig.clock.activeCount)
        rig.close()
    }
}
