package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import io.github.anastaci1a.localica.ui.spec.MotionAccess
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import org.junit.Assert.*
import org.junit.Test

class ScrollbarMotionTest {
    @Test fun fadeAndReleaseUseTheExactIncomingEvaluatorAndAdmissionFrame() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            val fade = TargetBasedAnimation(tween<Float>(220, 1200), Float.VectorConverter, 1f, 0f)
            val release = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .3f, 0f)
            motion.reveal()
            motion.release(.3f)
            assertEquals(2, h.clock.activeCount)
            h.advance(.016)
            repeat(91) { frame ->
                val nanos = frame * 16_000_000L
                assertEquals("fade frame $frame", fade.getValueFromNanos(nanos), motion.opacity(), 0f)
                assertEquals("release frame $frame", release.getValueFromNanos(nanos), motion.releaseOffset(), 0f)
                h.advance(.016)
            }
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test fun activityFadesInFromThePresentedOpacityAndReleaseStartsAtTheNewGap() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal()
            h.advance(.016)
            h.advance(1.3)
            val shown = motion.opacity()
            assertTrue(shown in 0f..1f && shown < 1f)
            motion.release(.2f)
            motion.reveal()
            assertEquals("activity never pops to full opacity (#704)", shown, motion.opacity(), 0f)
            assertEquals(.2f, motion.releaseOffset(), 0f)
            assertEquals(2, h.clock.activeCount)
            h.advance(.016)
            h.advance(.08)
            val release = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .2f, 0f)
            assertEquals(release.getValueFromNanos(80_000_000), motion.releaseOffset(), 0f)
            val fadeIn = TargetBasedAnimation(tween<Float>((220f * (1f - shown)).toInt()), Float.VectorConverter, shown, 1f)
            assertEquals(fadeIn.getValueFromNanos(80_000_000), motion.opacity(), 0f)
            motion.dispose()
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test fun gripRetargetsAtThePresentedFrameAndPreservesTheIncomingCurve() {
        for (speed in listOf(1f, 4f / 3f)) EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            val easing = CubicBezierEasing(0f, 0f, .2f, 1f)
            val incoming = TargetBasedAnimation(tween<Float>((240f / speed).toInt(), easing = easing),
                Float.VectorConverter, 0f, 1f)
            motion.contact(true, speed)
            h.advance(.016)
            h.advance(.064)
            val value = incoming.getValueFromNanos(64_000_000)
            assertEquals(value, motion.grip(), 0f)
            val returnCurve = TargetBasedAnimation(tween<Float>((240f / speed).toInt(), easing = easing),
                Float.VectorConverter, value, 0f,
                incoming.getVelocityVectorFromNanos(64_000_000).value)
            motion.contact(false, speed)
            h.advance(.016)
            assertEquals(returnCurve.getValueFromNanos(16_000_000), motion.grip(), 0f)
            h.advance(.3)
            assertEquals(0f, motion.grip(), 0f)
            assertEquals(0, h.clock.activeCount)
        }
    }


    @Test fun repeatedPresentedReadsAllocateNoScalarObjectsAfterIntentWarmup() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal(); motion.release(.3f); motion.contact(true, 1f)
            h.advance(.016); h.advance(.064)
            val api = Class.forName("com.sun.management.ThreadMXBean")
            val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
            api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
            val allocated = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            var total = 0f
            repeat(100_000) { total += motion.opacity() + motion.grip() + motion.releaseOffset() }
            val thread = Thread.currentThread().id
            // One loop site measured five times: a JIT transition of that site (OSR, deoptimization) is a one-off
            // allocation inside a single window, never in steady state, so the least window is the steady cost.
            fun window(): Long {
                val before = allocated.invoke(bean, thread) as Long
                repeat(1_000_000) { total += motion.opacity() + motion.grip() + motion.releaseOffset() }
                return (allocated.invoke(bean, thread) as Long) - before
            }
            val bytes = List(5) { window() }.min()
            println("Scrollbar scalar reads: 3000000 reads, $bytes allocated bytes including measurement overhead")
            assertTrue(total.isFinite())
            assertTrue("Scalar reads allocated $bytes bytes", bytes <= 1024)
            motion.dispose()
        }
    }

    @Test fun delayAndDurationEndpointsKeepTheExactIncomingFloatBits() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            val incoming = TargetBasedAnimation(tween<Float>(220, 1200), Float.VectorConverter, 1f, 0f)
            motion.reveal()
            h.advance(.016)
            var last = 0L
            for (nanos in listOf(0L, 1L, 1_199_999_999L, 1_200_000_000L, 1_200_000_001L,
                1_419_999_999L, 1_420_000_000L, 1_420_000_001L)) {
                if (nanos > last) h.advance((nanos - last) / 1e9)
                assertEquals("fade bits at $nanos", incoming.getValueFromNanos(nanos).toRawBits(),
                    motion.opacity().toRawBits())
                last = nanos
            }
            assertEquals(0, h.clock.activeCount)
        }
        for (gap in listOf(-.3f, 0f, .3f)) EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            val incoming = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, gap, 0f)
            motion.release(gap)
            h.advance(.016)
            var last = 0L
            for (nanos in listOf(0L, 1L, 179_999_999L, 180_000_000L, 180_000_001L)) {
                if (nanos > last) h.advance((nanos - last) / 1e9)
                assertEquals("release bits $gap at $nanos", incoming.getValueFromNanos(nanos).toRawBits(),
                    motion.releaseOffset().toRawBits())
                last = nanos
            }
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test fun constantRevealDelayDoesNotInvalidatePresentationOnSharedFrames() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal()
            h.advance(.016)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            var invalidations = 0
            val scope = Any()
            val changed: (Any) -> Unit = { invalidations++ }
            val observer = androidx.compose.runtime.snapshots.SnapshotStateObserver { it() }
            observer.start()
            try {
                repeat(10) { frame ->
                    observer.observeReads(scope, changed) { assertEquals(1f, motion.opacity(), 0f) }
                    h.advance(.016)
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                    assertEquals("Unchanged reveal delay at frame $frame", 0, invalidations)
                }
                h.advance(1.04)
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                assertTrue("The real fade boundary invalidates the held value", invalidations > 0)
            } finally {
                observer.stop()
                observer.clear()
                motion.dispose()
            }
        }
    }

    /** Opacity sampled every 8 ms frame for [seconds]. */
    private fun samples(h: EngineHarness, motion: ScrollbarMotion, seconds: Double) =
        List((seconds / .008).toInt()) { h.advance(.008); motion.opacity() }

    /** No 1→0 (or 0→1) jump: every frame moves at most the fade's steepest per-frame step. */
    private fun assertGradual(name: String, values: List<Float>) {
        val steepest = values.zipWithNext { a, b -> kotlin.math.abs(b - a) }.max()
        assertTrue("$name jumped $steepest in one frame", steepest <= .12f)
    }

    @Test fun scrollStartFadesInAndIdleFadesOutMonotonicallyOverTheFadeDuration() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal()
            h.advance(.016); h.advance(1.5)
            assertEquals(0f, motion.opacity(), 0f)
            assertEquals(0, h.clock.activeCount)
            motion.reveal()
            assertEquals("no pop-in on scroll start", 0f, motion.opacity(), 0f)
            val values = samples(h, motion, 2.0)
            val top = values.indexOfFirst { it >= 1f }
            assertTrue("fade in reached full opacity", top > 0)
            val rising = values.subList(0, top + 1)
            assertTrue("fade in is monotonic", rising.zipWithNext().all { (a, b) -> b >= a })
            assertTrue("fade in spans the fade duration: ${rising.count { it > 0f && it < 1f }} frames",
                rising.count { it > 0f && it < 1f } >= 220 / 8 - 2)
            val fall = values.withIndex().first { it.index > top && it.value < 1f }.index
            assertTrue("held at full opacity for the idle delay", (fall - top) * 8 >= 1200 - 16)
            val falling = values.drop(fall - 1)
            assertTrue("fade out is monotonic", falling.zipWithNext().all { (a, b) -> b <= a })
            assertTrue("fade out spans the fade duration", falling.count { it > 0f && it < 1f } >= 220 / 8 - 2)
            assertEquals(0f, values.last(), 0f)
            assertGradual("fade", values)
            motion.dispose()
        }
    }

    @Test fun aHeldFingerKeepsFullOpacityPastTheTimeoutAndTheFadeStartsOnlyAfterRelease() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal()
            h.advance(.016)
            motion.hold(true)
            assertEquals("nothing is scheduled while held", 0, h.clock.activeCount)
            // The harness queues frames sent while nothing is registered; one step leaves one frame at "now".
            h.advance(4.0)
            assertEquals("held: full opacity past the timeout", 1f, motion.opacity(), 0f)
            assertEquals(0, h.clock.activeCount)
            motion.hold(false)
            val after = samples(h, motion, 2.0)
            val fall = after.indexOfFirst { it < 1f }
            assertTrue("idle delay restarts at release: $fall", fall * 8 >= 1200 - 16)
            assertTrue(after.drop(fall).zipWithNext().all { (a, b) -> b <= a })
            assertEquals(0f, after.last(), 0f)
            assertGradual("release fade", after)
            motion.dispose()
        }
    }

    @Test fun activityOnEveryFrameOfAScrollFadesInAsOneUninterruptedTimeline() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal()
            h.advance(.016); h.advance(1.5)
            assertEquals(0f, motion.opacity(), 0f)
            val incoming = TargetBasedAnimation(tween<Float>(220), Float.VectorConverter, 0f, 1f)
            val seen = List(20) { motion.reveal(); h.advance(.016); motion.opacity() }
            seen.forEachIndexed { frame, value -> assertEquals("fade-in frame $frame", incoming.getValueFromNanos(frame * 16_000_000L), value, 0f) }
            assertEquals("full opacity once the fade duration has elapsed", 1f, seen.last(), 0f)
            motion.dispose()
        }
    }

    @Test fun holdingAFadingScrollbarFadesItBackInInsteadOfPopping() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal()
            h.advance(.016); h.advance(1.3)
            val shown = motion.opacity()
            assertTrue(shown > 0f && shown < 1f)
            motion.hold(true)
            assertEquals(shown, motion.opacity(), 0f)
            val values = samples(h, motion, 1.0)
            assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
            assertEquals(1f, values.last(), 0f)
            assertEquals(0, h.clock.activeCount)
            motion.dispose()
        }
    }

    @Test fun recompositionAndSettleAtTheTimeoutDoNotSkipTheFade() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal()
            h.advance(.016); h.advance(1.2)
            // What a recomposition (SideEffect) and a scroll settle deliver exactly at the timeout.
            motion.access(false); motion.contact(false, 1f); motion.hold(false)
            assertEquals(1f, motion.opacity(), 0f)
            val values = samples(h, motion, .5)
            assertTrue(values.zipWithNext().all { (a, b) -> b <= a })
            assertTrue("fade not skipped", values.count { it > 0f && it < 1f } >= 220 / 8 - 2)
            assertEquals(0f, values.last(), 0f)
            assertGradual("timeout fade", listOf(1f) + values)
            motion.dispose()
        }
    }

    @Test(timeout = 5000) fun localAndRootReductionPreserveEveryInFlightBaseCurve() {
        for (global in listOf(false, true)) for (seconds in listOf(.064, .8, 1.3)) EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal(); motion.release(.2f); motion.contact(true, 1f)
            h.advance(.016); h.advance(seconds)
            val opacity = motion.opacity()
            val release = motion.releaseOffset()
            val grip = motion.grip()
            if (global) h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Reduced), StockUiSpec)
            motion.access(true)
            assertEquals(if (seconds < .18) 3 else 1, h.clock.activeCount)
            assertEquals(opacity.toRawBits(), motion.opacity().toRawBits())
            assertEquals(release.toRawBits(), motion.releaseOffset().toRawBits())
            assertEquals("App reduction does not retarget the running grip", grip.toRawBits(), motion.grip().toRawBits())
            h.advance(.016)
            val nanos = (seconds * 1e9).toLong() + 16_000_000L
            val incoming = TargetBasedAnimation(tween<Float>(240, easing = CubicBezierEasing(0f, 0f, .2f, 1f)),
                Float.VectorConverter, 0f, 1f)
            val fade = TargetBasedAnimation(tween<Float>(220, 1200), Float.VectorConverter, 1f, 0f)
            val offset = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .2f, 0f)
            assertEquals("In-flight grip keeps its exact curve", incoming.getValueFromNanos(nanos).toRawBits(), motion.grip().toRawBits())
            assertEquals("In-flight reveal keeps its exact curve", fade.getValueFromNanos(nanos).toRawBits(), motion.opacity().toRawBits())
            assertEquals("In-flight release keeps its exact curve", offset.getValueFromNanos(nanos).toRawBits(), motion.releaseOffset().toRawBits())
            motion.contact(false, 1f)
            val retargeted = if (nanos < 240_000_000L) 0f else incoming.getValueFromNanos(nanos)
            assertEquals("A running zero-duration retarget is immediate; fresh work waits for admission", retargeted.toRawBits(), motion.grip().toRawBits())
            h.advance(.016)
            assertEquals("Only the new Reduced grip target snaps on admission", 0f, motion.grip(), 0f)
            motion.access(false)
            if (global) h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Standard), StockUiSpec)
            h.advance(2.0)
            assertEquals(0, h.clock.activeCount)
            assertEquals(0f, motion.opacity(), 0f)
            motion.reveal()
            assertEquals(1, h.clock.activeCount)
            motion.dispose()
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test fun fixedReducedKeepsBaseCurvesAndCancellationDisposalRetireWork() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock, reduced = true)
            motion.reveal(); motion.release(.2f); motion.contact(true, 1f)
            assertEquals("Changed Reduced grip waits for shared-frame admission", 3, h.clock.activeCount)
            assertEquals(1f, motion.opacity(), 0f)
            assertEquals(.2f, motion.releaseOffset(), 0f)
            assertEquals("The base keeps its old grip before the next frame", 0f, motion.grip(), 0f)
            h.advance(.016)
            assertEquals(1f, motion.grip(), 0f)
            assertEquals("Zero-duration grip retires on its admission frame", 2, h.clock.activeCount)
            motion.contact(false, 1f)
            assertEquals("Release preserves the held grip until admission", 1f, motion.grip(), 0f)
            assertEquals(3, h.clock.activeCount)
            h.advance(.016)
            assertEquals(0f, motion.grip(), 0f)
            assertEquals(2, h.clock.activeCount)
            h.advance(1.5)
            assertEquals(0f, motion.opacity(), 0f)
            assertEquals(0f, motion.releaseOffset(), 0f)
            assertEquals(0, h.clock.activeCount)
            motion.access(false)
            motion.reveal(); motion.release(.2f); motion.contact(false, 1f)
            assertEquals("An unchanged released target does not restart grip", 2, h.clock.activeCount)
            motion.contact(true, 1f)
            assertEquals(3, h.clock.activeCount)
            assertThrows(IllegalStateException::class.java) { h.clock.observe { motion.cancel() } }
            motion.cancel()
            assertEquals(0, h.clock.activeCount)
            motion.reveal(); motion.dispose()
            assertEquals(0, h.clock.activeCount)
            assertThrows(IllegalStateException::class.java) { motion.reveal() }
        }
    }
    @Test fun reducedRevealAndReleaseKeepTheExactBaseCurvesAndIdleDelay() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock, reduced = true)
            motion.reveal()
            assertEquals("Reduced activity reveals the scrollbar immediately", 1f, motion.opacity(), 0f)
            assertEquals("Only the idle timer is registered", 1, h.clock.activeCount)
            h.advance(.016)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            var invalidations = 0
            val scope = Any()
            val observer = androidx.compose.runtime.snapshots.SnapshotStateObserver { it() }
            val changed: (Any) -> Unit = { invalidations++ }
            observer.start()
            try {
                repeat(10) {
                    observer.observeReads(scope, changed) { assertEquals(1f, motion.opacity(), 0f) }
                    h.advance(.016)
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                    assertEquals("The Reduced idle timer does not resample presentation", 0, invalidations)
                }
                h.advance(1.039999999)
                assertEquals("Visible until the idle deadline", 1f, motion.opacity(), 0f)
                assertEquals(1, h.clock.activeCount)
                h.advance(.000000001)
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                val incoming = TargetBasedAnimation(tween<Float>(220, 1200), Float.VectorConverter, 1f, 0f)
                assertEquals("The base fade begins at the idle deadline", incoming.getValueFromNanos(1_200_000_000).toRawBits(), motion.opacity().toRawBits())
                h.advance(.08)
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                assertEquals(incoming.getValueFromNanos(1_280_000_000).toRawBits(), motion.opacity().toRawBits())
                assertTrue("The real fade invalidates presentation", invalidations > 0)
                h.advance(.14)
                assertEquals(0f, motion.opacity(), 0f)
                assertEquals(0, h.clock.activeCount)
            } finally {
                observer.stop()
                observer.clear()
            }
            motion.reveal()
            h.advance(.016); h.advance(.8)
            motion.reveal()
            h.advance(.016); h.advance(.8)
            assertEquals("New activity replaces the prior idle deadline", 1f, motion.opacity(), 0f)
            h.advance(.62)
            assertEquals(0f, motion.opacity(), 0f)
            assertEquals(0, h.clock.activeCount)
            motion.release(.3f)
            assertEquals("Reduced release keeps the base starting offset", .3f, motion.releaseOffset(), 0f)
            h.advance(.016); h.advance(.08)
            val release = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .3f, 0f)
            assertEquals(release.getValueFromNanos(80_000_000).toRawBits(), motion.releaseOffset().toRawBits())
            motion.reveal(); motion.dispose()
            assertEquals(0, h.clock.activeCount)
        }
    }
}
