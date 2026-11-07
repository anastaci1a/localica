package io.github.anastaci1a.localica.ui.engine.diagnostics

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.*

class ObserverContractTest {
    private class Probe(override val target: Float = 1f) : MotionValue {
        var sample = Kinematic(1f,0f); var reads = 0; var explicit = 0
        val state = mutableFloatStateOf(1f)
        override fun value(): Float { reads++; return state.floatValue }
        override fun sampleAt(stamp: FrameStamp): Kinematic { explicit++; return sample }
    }
    private fun stamp(sequence: Long) = FrameStamp(sequence*1_000_000,sequence)
    private fun join(epoch: Long = 0, sequence: Long = 1, before: Kinematic = Kinematic(1f,0f),
        after: Kinematic = before, policy: JoinPolicy = JoinPolicy.Continuous, kind: SegmentKind = SegmentKind.Eased,
        tracker: Float? = null, fd: Float? = null, beforeKind: SegmentKind = SegmentKind.Eased) =
        MotionJoin(1,epoch,stamp(sequence),before,after,beforeKind,kind,policy,tracker,fd)
    private fun newObserver(probe: Probe = Probe(), sink: BoundedEvidence = BoundedEvidence(20)) =
        ContinuityObserver(sink).also { it.register(MotionRegistration(1,"player",1f,probe)) } to sink
    @Test fun exactJoinFindsVelocityJumpBetweenIdenticalSampledFrames() {
        val probe = Probe(); val (observer,sink) = newObserver(probe)
        val sparse = listOf(probe.sampleAt(stamp(1)),probe.sampleAt(stamp(3)))
        assertEquals(sparse[0],sparse[1]) // The old per-frame-only predicate accepts this corrupt trace.
        observer.onFrame(stamp(1),listOf(ActiveMotion(1)))
        observer.onJoin(join(sequence=2,before=Kinematic(1f,.2f),after=Kinematic(1f,0f)))
        observer.onFrame(stamp(3),listOf(ActiveMotion(1)))
        assertEquals(listOf("VelocityJump"),sink.violations.map { it.code })
        assertEquals("I1",sink.violations.single().invariant); assertEquals(2L,sink.violations.single().sequence)
    }
    @Test fun finalSettleCannotDisappearBeforeTheNextFrame() {
        val (observer,sink) = newObserver()
        observer.onJoin(join(before=Kinematic(1f,.1f),after=Kinematic(1f,0f),kind=SegmentKind.Rest))
        observer.retire(1,stamp(1)); observer.onFrame(stamp(2),emptyList())
        assertEquals("VelocityJump",sink.violations.single().code); assertEquals(0,observer.registeredCount)
        val (missing,evidence) = newObserver(); missing.retire(1,stamp(1))
        assertEquals("MissingFinalRest",evidence.violations.single().code); assertEquals(1,missing.registeredCount)
    }
    @Test fun duplicateOwnerAndInvalidAdmissionAreAtomic() {
        val (observer,sink) = newObserver()
        observer.register(MotionRegistration(2,"player",1f,Probe()))
        observer.register(MotionRegistration(3,"other",Float.NaN,Probe()))
        assertEquals(listOf("DuplicateOwner","InvalidRegistration"),sink.violations.map { it.code })
        assertEquals(listOf("I6","I10"),sink.violations.map { it.invariant }); assertEquals(1,observer.registeredCount)
    }
    @Test fun valueAndVelocityUseTheSameDeclaredRangeAndSeconds() {
        for (range in listOf(1f,100f)) {
            val sink = BoundedEvidence(10); val observer = ContinuityObserver(sink)
            observer.register(MotionRegistration(1,"player",range,Probe()))
            observer.onJoin(join(before=Kinematic(0f,0f),after=Kinematic(range*2e-4f,range*2e-3f)))
            assertEquals(listOf("ValueJump","VelocityJump"),sink.violations.map { it.code })
            observer.close()
        }
    }
    @Test fun validInitialContinuousRestAndReducedLogsAreClean() {
        val (observer,sink) = newObserver()
        observer.onJoin(join(policy=JoinPolicy.Initial,before=Kinematic(0f,0f),after=Kinematic(1f,0f)))
        observer.onFrame(stamp(1),listOf(ActiveMotion(1)))
        observer.onJoin(join(epoch=1,sequence=2))
        observer.onJoin(join(epoch=2,sequence=2,before=Kinematic(1f,1f),after=Kinematic(1f,0f),policy=JoinPolicy.Reduced,kind=SegmentKind.Rest))
        observer.retire(1,stamp(2)); assertTrue(sink.violations.isEmpty())
    }
    @Test fun initialExemptionEndsAtFirstPresentation() {
        val (observer,sink) = newObserver(); observer.onFrame(stamp(1),listOf(ActiveMotion(1,true)))
        observer.onJoin(join(sequence=2,policy=JoinPolicy.Initial))
        assertEquals("LateInitialExemption",sink.violations.single().code)
    }
    @Test fun drivenReleaseUsesTrackerAndKeepsFiniteDifferenceDiagnosticSeparate() {
        val (observer,sink) = newObserver()
        observer.onJoin(join(before=Kinematic(1f,9f),after=Kinematic(1f,.3f),beforeKind=SegmentKind.Driven,
            policy=JoinPolicy.DrivenRelease,tracker=.3f,fd=.3f))
        assertTrue(sink.violations.isEmpty())
        observer.onJoin(join(epoch=1,sequence=2,beforeKind=SegmentKind.Driven,policy=JoinPolicy.DrivenRelease,tracker=0f,fd=2f))
        assertEquals("ReleaseDisagreement",sink.violations.single().code)
        observer.onJoin(join(epoch=2,sequence=3,beforeKind=SegmentKind.Driven,policy=JoinPolicy.DrivenRelease))
        assertEquals("MissingReleaseEvidence",sink.violations.last().code)
    }
    @Test fun drivenContactIsC0ButUnclassifiedReleaseIsNotAnExemption() {
        val (observer,sink) = newObserver()
        observer.onJoin(join(before=Kinematic(1f,2f),after=Kinematic(1f,9f),beforeKind=SegmentKind.Driven,kind=SegmentKind.Driven))
        assertTrue(sink.violations.isEmpty())
        observer.onJoin(join(epoch=1,sequence=2,beforeKind=SegmentKind.Driven))
        assertEquals("MissingReleaseEvidence",sink.violations.single().code)
    }
    @Test fun closeFailureStillSealsReleasesAndUnavailableCountersStayNull() {
        val observer = ContinuityObserver(BoundedEvidence(1),detach={ error("detach failed") })
        observer.register(MotionRegistration(1,"player",1f,Probe()))
        assertThrows(IllegalStateException::class.java) { observer.close() }; observer.close()
        assertEquals(0,observer.registeredCount)
        val structural = StructuralSample(stamp(1),true,0,0,null,null,0,0)
        assertNull(structural.actualRenderNodes); assertNull(structural.scalarReadAllocatedBytes)
    }
    @Test fun explicitSamplingHasNoSnapshotReadsOrWrites() {
        val probe = Probe(); val (observer,sink) = newObserver(probe); var reads = 0; var writes = 0
        Snapshot.observe({ reads++ },{ writes++ }) { observer.onFrame(stamp(1),listOf(ActiveMotion(1))) }
        assertEquals(0,reads); assertEquals(0,writes); assertEquals(0,probe.reads); assertEquals(1,probe.explicit)
        assertTrue(sink.violations.isEmpty())
    }
    @Test fun ownerThreadGuardRejectsBeforePublicationAndCloseDetachesOnlyItsLease() {
        val probe = Probe(); val sink = BoundedEvidence(20); val callbacks = mutableListOf<Any>("other")
        lateinit var observer: ContinuityObserver
        observer = ContinuityObserver(sink,detach={ callbacks.remove(observer) }); callbacks.add(observer)
        observer.register(MotionRegistration(1,"player",1f,probe))
        val error = AtomicReference<Throwable>(); val worker = Thread { try { observer.onJoin(join()); observer.close() } catch (e: Throwable) { error.set(e) } }
        worker.start(); worker.join(); assertTrue(error.get() is IllegalStateException)
        assertEquals(0,probe.explicit); assertTrue(sink.violations.isEmpty()); assertEquals(1,observer.registeredCount)
        observer.close(); observer.close(); assertEquals(listOf("other"),callbacks); assertEquals(0,observer.registeredCount)
        assertThrows(IllegalStateException::class.java) { observer.onFrame(stamp(1),emptyList()) }
    }
    @Test fun overflowDuplicateFramesEpochAndNonfiniteDataCannotPass() {
        val (observer,sink) = newObserver(); observer.onJoin(join()); observer.onJoin(join())
        observer.onFrame(stamp(1),emptyList()); observer.onFrame(stamp(1),emptyList())
        observer.onJoin(join(epoch=1,sequence=2,after=Kinematic(Float.NaN,0f)))
        assertEquals(listOf("InvalidEpochOrStamp","DuplicateOrReversedFrame","NonFiniteKinematics"),sink.violations.map { it.code })
        val bounded = BoundedEvidence(1); sink.violations.forEach { bounded.accept(it) }
        assertTrue(bounded.incomplete); assertEquals("EvidenceOverflow",bounded.violations.single().code)
    }
    @Test fun disabledInstallationAndManualSourceCreateNoAutomaticClockWork() {
        var factories = 0; var callbacks = 0
        assertNull(installObservation(false,{ factories++; ManualFrameSource() },{ callbacks++ }))
        assertEquals(0,factories); assertEquals(0,callbacks)
        val source = ManualFrameSource(); var value: Long? = null
        (suspend { source.awaitFrame() }).startCoroutine(object : Continuation<Long> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Long>) { value = result.getOrThrow() }
        })
        assertNull(value); assertEquals(1L,source.awaits); assertEquals(1L,source.advance(42).sequence); assertEquals(42L,value)
        assertThrows(IllegalArgumentException::class.java) { source.advance(42) }; source.close(); source.close()
    }
    private fun surface(node: Long = 1, shape: Long = 10, host: String = "A", generation: Long = 1,
        opacity: Float = .5f, ownership: Float = .5f, leases: Int = 1, presented: Long? = null, retired: Boolean = false) =
        SurfaceObservation(node,shape,"popup",HostKey(host,1),generation,stamp(generation),opacity,ownership,leases,presented,retired)
    @Test fun identityAllowsSeveralTransferSharesButRejectsReplacementAndEarlyRetirement() {
        val sink = BoundedEvidence(20); val observer = IdentityObserver(sink)
        observer.observe(surface()); observer.observe(surface(host="B")); assertTrue(sink.violations.isEmpty())
        observer.observe(surface(shape=11)); observer.observe(surface(node=2)); observer.observe(surface(retired=true))
        assertEquals(listOf("ShapeIdentityChanged","DuplicateOwner","EarlyRetirement"),sink.violations.map { it.code })
        assertEquals(listOf("I4","I6","I5"),sink.violations.map { it.invariant })
    }
    @Test fun nativeGenerationIsNeverInferredFromTheClockAndRetiredGenerationsStayDead() {
        val sink = BoundedEvidence(20); val observer = IdentityObserver(sink)
        observer.observe(surface(opacity=0f,ownership=0f,leases=0,retired=true))
        assertEquals("EarlyRetirement",sink.violations.single().code)
        observer.observe(surface(opacity=0f,ownership=0f,leases=0,presented=1,retired=true))
        observer.observe(surface()); assertEquals("RetiredGenerationResurrected",sink.violations.last().code)
        observer.close(); observer.close(); assertThrows(IllegalStateException::class.java) { observer.observe(surface()) }
    }
}
