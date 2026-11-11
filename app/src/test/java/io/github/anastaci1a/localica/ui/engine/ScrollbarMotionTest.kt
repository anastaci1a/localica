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
    @Test fun activityReplacesOnlyTheRevealAndReleaseStartsAtTheNewGap() {
        EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal()
            h.advance(.016)
            h.advance(1.3)
            assertTrue(motion.opacity() in 0f..1f && motion.opacity() < 1f)
            motion.release(.2f)
            motion.reveal()
            assertEquals(1f, motion.opacity(), 0f)
            assertEquals(.2f, motion.releaseOffset(), 0f)
            assertEquals(2, h.clock.activeCount)
            h.advance(.016)
            h.advance(.08)
            val release = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .2f, 0f)
            assertEquals(release.getValueFromNanos(80_000_000), motion.releaseOffset(), 0f)
            motion.dispose()
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test fun gripRetargetsAtThePresentedFrameAndPreservesTheIncomingCurve() {
        for (speed in listOf(1f, 4f / 3f)) EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            val easing = CubicBezierEasing(0f, 0f, .2f, 1f)
            val incoming = TargetBasedAnimation(tween<Float>((240f / speed).toInt(), easing = easing),
                Float.VectorConverter, 1f, 1.5f)
            motion.contact(true, speed)
            h.advance(.016)
            h.advance(.064)
            val value = incoming.getValueFromNanos(64_000_000)
            assertEquals(value, motion.scale(), 0f)
            val returnCurve = TargetBasedAnimation(tween<Float>((240f / speed).toInt(), easing = easing),
                Float.VectorConverter, value, 1f,
                incoming.getVelocityVectorFromNanos(64_000_000).value)
            motion.contact(false, speed)
            h.advance(.016)
            assertEquals(returnCurve.getValueFromNanos(16_000_000), motion.scale(), 0f)
            h.advance(.3)
            assertEquals(1f, motion.scale(), 0f)
            assertEquals(0, h.clock.activeCount)
        }
    }
    @Test(timeout = 5000) fun localAndRootReductionPreserveEveryInFlightBaseCurve() {
        for (global in listOf(false, true)) for (seconds in listOf(.064, .8, 1.3)) EngineHarness().use { h ->
            val motion = ScrollbarMotion(h.clock)
            motion.reveal(); motion.release(.2f); motion.contact(true, 1f)
            h.advance(.016); h.advance(seconds)
            val opacity = motion.opacity()
            val release = motion.releaseOffset()
            val scale = motion.scale()
            if (global) {
                h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Reduced), StockUiSpec)
            }
            motion.access(true)
            assertEquals(if (seconds < .18) 3 else 1, h.clock.activeCount)
            assertEquals(opacity.toRawBits(), motion.opacity().toRawBits())
            assertEquals(release.toRawBits(), motion.releaseOffset().toRawBits())
            assertEquals("App reduction does not retarget the running grip", scale.toRawBits(), motion.scale().toRawBits())
            h.advance(.016)
            val nanos = (seconds * 1e9).toLong() + 16_000_000L
            val incoming = TargetBasedAnimation(tween<Float>(240, easing = CubicBezierEasing(0f, 0f, .2f, 1f)),
                Float.VectorConverter, 1f, 1.5f)
            val fade = TargetBasedAnimation(tween<Float>(220, 1200), Float.VectorConverter, 1f, 0f)
            val offset = TargetBasedAnimation(tween<Float>(180), Float.VectorConverter, .2f, 0f)
            assertEquals("In-flight grip keeps its exact curve", incoming.getValueFromNanos(nanos).toRawBits(), motion.scale().toRawBits())
            assertEquals("In-flight reveal keeps its exact curve", fade.getValueFromNanos(nanos).toRawBits(), motion.opacity().toRawBits())
            assertEquals("In-flight release keeps its exact curve", offset.getValueFromNanos(nanos).toRawBits(), motion.releaseOffset().toRawBits())
            motion.contact(false, 1f)
            val retargeted = if (nanos < 240_000_000L) 1f else incoming.getValueFromNanos(nanos)
            assertEquals("A running zero-duration retarget is immediate; fresh work waits for admission", retargeted.toRawBits(), motion.scale().toRawBits())
            h.advance(.016)
            assertEquals("Only the new Reduced grip target snaps on admission", 1f, motion.scale(), 0f)
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
            assertEquals("The base keeps its old grip before the next frame", 1f, motion.scale(), 0f)
            h.advance(.016)
            assertEquals(1.5f, motion.scale(), 0f)
            assertEquals("Zero-duration grip retires on its admission frame", 2, h.clock.activeCount)
            motion.contact(false, 1f)
            assertEquals("Release preserves the held grip until admission", 1.5f, motion.scale(), 0f)
            assertEquals(3, h.clock.activeCount)
            h.advance(.016)
            assertEquals(1f, motion.scale(), 0f)
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
            repeat(10_000) { total = readScalars(motion, 10, total) }
            val thread = Thread.currentThread().id
            // Inflate the shared reflective accessor before measuring, as the sibling allocation tests do.
            repeat(64) { allocated.invoke(bean, thread) }
            // Touch the compilation and collector beans first too: without this, hosted runs charged a one-time
            // 1,032 bytes to the measured interval while every later round of reads measured 0 (#838).
            val management = Class.forName("java.lang.management.ManagementFactory")
            val compilation = management.getMethod("getCompilationMXBean").invoke(null)
            Class.forName("java.lang.management.CompilationMXBean").getMethod("getTotalCompilationTime").invoke(compilation)
            val collections = Class.forName("java.lang.management.GarbageCollectorMXBean").getMethod("getCollectionCount")
            for (collector in management.getMethod("getGarbageCollectorMXBeans").invoke(null) as List<*>) collections.invoke(collector)
            val before = allocated.invoke(bean, thread) as Long
            total = readScalars(motion, 1_000_000, total)
            val bytes = (allocated.invoke(bean, thread) as Long) - before
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
    private fun readScalars(motion: ScrollbarMotion, repetitions: Int, initial: Float): Float {
        var total = initial
        repeat(repetitions) { total += motion.opacity() + motion.scale() + motion.releaseOffset() }
        return total
    }
}
