package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.MotionDurationScale
import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** The pre-adoption BrowseScrollbar Animatables (c5cac7d3) are the reference: release snap+180 ms tween, idle snap to one
 * and a 1200 ms delayed 220 ms fade, both under the platform duration scale captured before each pending frame. */
class ScrollbarMotionParityTest {
    private class Rig(val h: EngineHarness) {
        var scale = 1f
        val frames = LockstepFrames(h)
        val context = frames + object : MotionDurationScale { override val scaleFactor: Float get() = scale }
        val release = Animatable(0f)
        val opacity = Animatable(1f)
        val owner = ScrollbarMotion(h.clock) { scale }
        fun legacyRelease(scope: CoroutineScope, gap: Float) = scope.launch { release.snapTo(gap); release.animateTo(0f, tween(180)) }
        /** The legacy launch replaced a running Animatable through its mutex; give it the dispatches that took. */
        suspend fun settleDispatch() { repeat(6) { yield() } }
        fun legacyWake(scope: CoroutineScope) = scope.launch { opacity.snapTo(1f); opacity.animateTo(0f, tween(durationMillis = 220, delayMillis = 1200)) }
        suspend fun compare(label: String) {
            assertEquals("$label release", release.value, owner.releaseValue(), 0f)
            assertEquals("$label displayed", (.5f + release.value).coerceIn(0f, 1f), owner.displayed(.5f, false, 0f), 0f)
            assertEquals("$label opacity", opacity.value, owner.opacity(), 0f)
        }
    }

    private fun observed(block: suspend Rig.(CoroutineScope) -> Unit) = EngineHarness().use { h ->
        val rig = Rig(h)
        val evidence = BoundedEvidence(600)
        EngineObservation(h.clock, evidence).use { observer ->
            runBlocking(rig.context) { rig.block(this); rig.owner.dispose(); repeat(20) { rig.frames.tick(100.0) }; assertEquals(0, h.clock.activeCount) }
            assertEquals(0, observer.continuity.registeredCount)
            assertEquals(emptyList<Violation>(), evidence.violations)
        }
        rig.frames.release()
    }

    @Test fun releaseMatchesTheLegacySnapAndTweenFrameForFrameIncludingRapidReRelease() = observed { scope ->
        var job = legacyRelease(scope, .4f); owner.release(.4f); settleDispatch()
        compare("after snap")
        repeat(6) { frames.tick(1000.0 / 60); compare("release frame $it") }
        assertTrue(release.value > 0f && release.value < .4f)
        // A new drag ends two frames later: the legacy launch replaced the running tween.
        job = legacyRelease(scope, -.25f); owner.release(-.25f); settleDispatch()
        compare("after re-release")
        repeat(16) { frames.tick(1000.0 / 60); compare("re-release frame $it") }
        job.join()
        assertEquals(0f, owner.displayed(.5f, false, 0f) - .5f, 0f)
        assertEquals(.2f, owner.displayed(.2f, false, 0f), 0f)
        assertEquals(.9f, owner.displayed(.5f, true, .9f), 0f)
    }

    @Test fun platformScaleIsCapturedBeforeEachPendingFrameAndZeroScaleFinishesAtOnce() = observed { scope ->
        scale = .5f
        var job = legacyRelease(scope, .6f); owner.release(.6f); settleDispatch()
        repeat(3) { frames.tick(1000.0 / 60); compare("half scale frame $it") }
        scale = 2f // Changed between frames: both already captured the old scale for the pending frame.
        repeat(2) { frames.tick(1000.0 / 60); compare("scale change frame $it") }
        scale = 1f
        repeat(30) { frames.tick(1000.0 / 60); compare("restored scale frame $it") }
        job.join()
        scale = 0f
        job = legacyRelease(scope, .3f); owner.release(.3f); settleDispatch()
        compare("zero scale before frame")
        frames.tick(1000.0 / 60); compare("zero scale first frame")
        assertEquals(0f, release.value, 0f)
        job.join()
        scale = 1f
    }

    @Test fun idleFadeKeepsTheDelayBoundaryAndRestartsOnWake() = observed { scope ->
        var job = legacyWake(scope); owner.wake(); settleDispatch()
        compare("woken")
        var elapsed = 0.0
        while (elapsed < 1500.0) { frames.tick(1000.0 / 60); elapsed += 1000.0 / 60; compare("fade at ${elapsed}ms") }
        job.join()
        assertEquals(0f, owner.opacity(), 0f)
        job = legacyWake(scope); owner.wake(); settleDispatch()
        repeat(71) { frames.tick(1000.0 / 60) }
        compare("held through the delay")
        assertEquals("Still fully shown at 1167 ms", 1f, owner.opacity(), 0f)
        repeat(10) { frames.tick(1000.0 / 60); compare("fading frame $it") }
        assertTrue(owner.opacity() < 1f && owner.opacity() > 0f)
        job.cancel(); job = legacyWake(scope); owner.wake(); settleDispatch()
        compare("re-woken mid fade")
        assertEquals(1f, owner.opacity(), 0f)
        repeat(90) { frames.tick(1000.0 / 60); compare("second fade frame $it") }
        job.join()
    }

    @Test fun reducedAccessDoesNotReduceScrollbarChannelsAndFreshIntentsGetFreshEpochs() = observed { scope ->
        val job = legacyRelease(scope, .5f); owner.release(.5f); settleDispatch()
        frames.tick(1000.0 / 60)
        h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Reduced), StockUiSpec)
        assertEquals(1, h.clock.activeCount)
        repeat(14) { frames.tick(1000.0 / 60); compare("reduced frame $it") }
        job.join()
        h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Standard), StockUiSpec)
    }
}
