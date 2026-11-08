package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** U04.01a (D25, D27–D31, D37): one node per key per host, a bounded continuous ownership ledger, readiness receipts and retained retirement. */
class CompositorRegistryTest {
    private val profile=motionProfileId(MotionProfile.Control)
    private val geometry=(uiSpec(InterfaceSettings(),null,ExpansionGeometryDomain(1000f,2000f,1f/60)) as SpecResult.Accepted).spec
    private fun shape(h: EngineHarness,key: Any="shape")=PersistentShape(h.clock,ShapeTarget(listOf(RoundRect(0f,0f,200f,64f,CornerRadius(32f))),JoinPolicy.Union),key)
    private fun plane(h: EngineHarness,window: Any=Any())=ShadowPlane(h.clock,HostKey.next(window))
    private val shown=SurfaceTarget(Stratum.Floating,1f,ShadowRole.Glass)
    private fun shares(node: SurfaceNode,planes: List<ShadowPlane>,stamp: FrameStamp)=planes.map { node.shareAt(it,stamp) }

    /** Lead condition (4:50 AM): the host's draw-pass receipt writes no Compose snapshot state; the queued transfer starts at [ShadowPlane.admit] on the next frame. */
    @Test fun drawReceiptWritesNoSnapshotStateAndAdmitsOnTheNextFrame() = EngineHarness(spec=geometry).use { h ->
        val a=plane(h); val b=plane(h); val node=SurfaceNode(h.clock,"pill",shape(h),shown,a)
        node.transfer(b,profile); assertTrue(node.waiting); assertEquals(0f,node.shadowOwnership.target)
        val draw=androidx.compose.runtime.snapshots.Snapshot.takeMutableSnapshot()
        val waiting=try { draw.enter { b.receipt() }.also { assertFalse("A draw receipt must not write snapshot state",draw.hasPendingChanges()) } } finally { draw.dispose() }
        assertTrue(waiting); assertTrue(node.waiting); assertEquals(0f,node.shadowOwnership.target); assertEquals(1f,a.contributionAt(node,h.clock.stamp))
        b.admit(); assertFalse(node.waiting); assertEquals(1f,node.shadowOwnership.target); assertEquals(1f,b.contributionAt(node,h.clock.stamp)); assertEquals(0f,a.contributionAt(node,h.clock.stamp))
        assertFalse("Without a queued transfer nothing is posted",a.receipt())
        node.dispose()
    }
    @Test fun registryAndOwnershipChangesInvalidateAStationaryHost() = EngineHarness(spec=geometry).use { h ->
        val a=plane(h); val b=plane(h)
        val observer=androidx.compose.runtime.snapshots.SnapshotStateObserver { it() }; var changed=false
        observer.start(); observer.observeReads("registration",{ _: String -> changed=true }) { assertTrue(a.casters.isEmpty()) }
        val node=SurfaceNode(h.clock,"stationary",shape(h),shown,a)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); assertTrue("Registration redraws the host",changed)
        observer.clear(); changed=false
        observer.observeReads("ownership",{ _: String -> changed=true }) { assertTrue(node.owns(a)) }
        node.transfer(b,profile); b.acknowledge()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); assertTrue("Transfer redraws a stationary source",changed)
        assertFalse(node.owns(a)); assertTrue(node.owns(b))
        observer.clear(); changed=false
        observer.observeReads("removal",{ _: String -> changed=true }) { assertEquals(listOf(node),b.casters) }
        node.dispose(); androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertTrue("Removal redraws the old field",changed); assertTrue(b.casters.isEmpty())
        observer.stop(); observer.clear()
    }

    @Test fun oneKeyOneNodeOnePlaneBinding() = EngineHarness(spec=geometry).use { h ->
        val a=plane(h); val b=plane(h); val s=shape(h)
        val node=SurfaceNode(h.clock,"pill",s,shown,a)
        assertEquals(listOf(node),a.casters); assertTrue(b.casters.isEmpty()); assertSame(s,node.shape); assertEquals("pill",node.key)
        assertEquals(listOf(1f,0f),shares(node,listOf(a,b),h.clock.stamp)); assertEquals(1f,a.contributionAt(node,h.clock.stamp)); assertEquals(0f,b.contributionAt(node,h.clock.stamp))
        // A second node for the same key on the same host is rejected before it registers; another host may hold that key.
        assertThrows(IllegalArgumentException::class.java) { SurfaceNode(h.clock,"pill",shape(h,"other"),shown,a) }
        assertEquals(listOf(node),a.casters)
        val elsewhere=SurfaceNode(h.clock,"pill",shape(h,"third"),shown,b); assertEquals(listOf(elsewhere),b.casters)
        // Host keys are identities with monotone route epochs (D37).
        val w=Any(); val k1=HostKey.next(w); val k2=HostKey.next(w); assertTrue(k2.routeEpoch>k1.routeEpoch); assertNotEquals(k1,k2); assertSame(w,k1.window)
        // Registration order is stable inside a stratum: later registration draws above.
        val later=SurfaceNode(h.clock,"later",shape(h,"l"),shown,a); assertTrue(later.order>node.order && later.order<Stratum.Floating.ordinal+1)
        node.request(shown.copy(stratum=Stratum.Overlay),profile); assertTrue(node.order>=Stratum.Overlay.ordinal); assertEquals(Stratum.Overlay,node.stratum)
        // Idempotent intents do not join again.
        val epoch=node.opacity.let { (it as MotionFloat).epoch.intValue }; node.request(shown.copy(stratum=Stratum.Overlay),profile); assertEquals(epoch,(node.opacity as MotionFloat).epoch.intValue)
        node.dispose(); assertEquals(listOf(later),a.casters)
    }

    @Test fun transferWaitsForReceiptReversesAndQueuesAThirdPlane() = EngineHarness(spec=geometry).use { h ->
        val a=plane(h); val b=plane(h); val c=plane(h); val node=SurfaceNode(h.clock,"popup",shape(h),shown,a)
        node.transfer(a,profile); assertTrue(node.shadowOwnership.settled); assertEquals(listOf(node),a.casters)
        // Registration precedes readiness; the source keeps the whole field until b draws once (D28).
        node.transfer(b,profile); assertEquals(listOf(node),b.casters); h.advance(.1); assertEquals(listOf(1f,0f),shares(node,listOf(a,b),h.clock.stamp))
        b.acknowledge(); h.advance(.05); val mid=shares(node,listOf(a,b),h.clock.stamp); assertTrue(mid[1]>0f && mid[1]<1f); assertEquals(1f,mid.sum(),1e-6f)
        // D27: the destination owns the whole field from the start; w is only its exclusion weight.
        assertEquals(0f,a.contributionAt(node,h.clock.stamp)); assertEquals(1f,b.contributionAt(node,h.clock.stamp))
        // A→B→A reverses the same motion: value and velocity are continuous at the reversal stamp.
        val before=node.shadowOwnership.sampleAt(h.clock.stampNow); node.transfer(a,profile); val after=node.shadowOwnership.sampleAt(h.clock.stampNow)
        assertEquals(before.value,after.value,1e-4f); assertEquals(before.velocity,after.velocity,1e-3f*maxOf(1f,abs(before.velocity))); assertEquals(0f,node.shadowOwnership.target); assertTrue(node.owns(b) && !node.owns(a))
        node.transfer(b,profile); h.advance(.03)
        // A third plane is queued, never a reset: the in-flight pair continues, c starts from rest after b owns and c drew.
        node.transfer(c,profile); assertEquals(1f,node.shadowOwnership.target); assertEquals(0f,node.shareAt(c,h.clock.stampNow))
        c.acknowledge(); h.advance(.03); assertEquals(0f,node.shareAt(c,h.clock.stamp)); assertFalse(node.shadowOwnership.settled)
        h.advance(2.0); assertTrue(a.casters.isEmpty()); assertEquals(0f,node.shadowOwnership.target); assertFalse(node.shadowOwnership.settled)
        h.advance(2.0); assertEquals(listOf(0f,0f,1f),shares(node,listOf(a,b,c),h.clock.stamp)); assertTrue(b.casters.isEmpty()); assertEquals(listOf(node),c.casters)
    }

    @Test fun dismissRetainsUntilOpacitySettlesAndReopenContinues() = EngineHarness(spec=geometry).use { h ->
        val a=plane(h); val node=SurfaceNode(h.clock,"panel",shape(h),shown,a)
        node.dismiss(); assertTrue(node.retained); assertEquals(listOf(node),a.casters)
        node.request(shown.copy(opacity=0f),profile); h.advance(.05); assertTrue(node.retained); assertEquals(listOf(node),a.casters)
        // Reopen during the drain continues the current opacity and rescinds retirement.
        val before=node.opacity.sampleAt(h.clock.stampNow); node.request(shown,profile); val after=node.opacity.sampleAt(h.clock.stampNow)
        assertEquals(before.value,after.value,1e-4f); assertEquals(before.velocity,after.velocity,1e-3f*maxOf(1f,abs(before.velocity))); assertFalse(node.retained)
        node.dismiss(); node.request(shown.copy(opacity=0f),profile); h.advance(2.0); assertFalse(node.retained); assertTrue(a.casters.isEmpty()); assertEquals(0f,a.contributionAt(node,h.clock.stamp))
        assertThrows(IllegalStateException::class.java) { node.transfer(plane(h),profile) }
        // A later reopen re-registers the same node.
        node.request(shown,profile); assertEquals(listOf(node),a.casters)
    }

    @Test fun invalidIntentsFailBeforeMutationAndOffMainIsRejected(): Unit = EngineHarness(spec=geometry).use { h ->
        val a=plane(h); val node=SurfaceNode(h.clock,"x",shape(h),shown,a); val epoch=(node.opacity as MotionFloat).epoch.intValue
        assertThrows(IllegalArgumentException::class.java) { SurfaceTarget(Stratum.Page,Float.NaN,ShadowRole.None) }
        assertThrows(IllegalArgumentException::class.java) { SurfaceTarget(Stratum.Page,1.01f,ShadowRole.None) }
        assertThrows(IllegalArgumentException::class.java) { SurfaceTransform(scale=0f) }
        assertThrows(IllegalArgumentException::class.java) { SurfaceTransform(translation=Offset(Float.NaN,0f)) }
        assertThrows(IllegalArgumentException::class.java) { node.request(shown,MotionProfileId("NoSuchProfile")) }
        assertEquals(epoch,(node.opacity as MotionFloat).epoch.intValue); assertEquals(Stratum.Floating,node.stratum)
        EngineHarness(spec=geometry).use { foreign ->
            val other=plane(foreign)
            assertThrows(IllegalArgumentException::class.java) { node.transfer(other,profile) }
            assertTrue(other.casters.isEmpty()); assertEquals(listOf(node),a.casters)
        }
        var main=true; val off=UiClock(object: FrameSource { override suspend fun awaitFrame()=error("unused") },{ 1L },{ check(main) { "Engine mutation requires Main" } }).apply { configure(EngineContext(1f/120,1000f,MotionAccess.Standard),geometry) }
        val p=ShadowPlane(off,HostKey.next(Any())); val n=SurfaceNode(off,"n",PersistentShape(off,ShapeTarget(emptyList(),JoinPolicy.Union)),shown,p); main=false
        assertThrows(IllegalStateException::class.java) { n.request(shown.copy(opacity=.5f),profile) }; assertThrows(IllegalStateException::class.java) { n.transfer(p,profile) }; assertThrows(IllegalStateException::class.java) { p.acknowledge() }
    }

    @Test fun reducedTransfersAndOpacityJumpWithoutLosingOwnership() = EngineHarness(MotionAccess.Reduced,geometry).use { h ->
        val a=plane(h); val b=plane(h); val node=SurfaceNode(h.clock,"r",shape(h),shown,a)
        node.transfer(b,profile); b.acknowledge(); assertEquals(listOf(0f,1f),shares(node,listOf(a,b),h.clock.stampNow)); assertTrue(a.casters.isEmpty())
        node.dismiss(); node.request(shown.copy(opacity=0f),profile); assertTrue(b.casters.isEmpty()); assertFalse(node.retained)
    }

    @Test fun nativeLeasesKeepTheSameNodeAfterTheTriggerUnmountsUntilTheLastWindowReleases() = EngineHarness(spec=geometry).use { h ->
        val a=plane(h); val b=plane(h); val original=shape(h); val node=SurfaceNode(h.clock,"native",original,shown,a)
        val release=node.retainWindow(); val releaseOther=node.retainWindow()
        node.transfer(b,profile); b.acknowledge(); h.advance(2.0)
        node.dispose(); node.dispose()
        assertEquals(listOf(node),b.casters); assertSame(original,node.shape)
        assertThrows(IllegalStateException::class.java) { node.retainWindow() }
        node.request(shown.copy(opacity=.5f),profile); h.advance(2.0)
        assertEquals(.5f,b.contributionAt(node,h.clock.stampNow),0f)
        release(); release(); assertEquals(listOf(node),b.casters)
        node.request(shown.copy(opacity=.75f),profile); assertTrue(h.clock.activeCount>0)
        releaseOther(); releaseOther(); assertTrue(a.casters.isEmpty()); assertTrue(b.casters.isEmpty())
        assertFalse(node.owns(b)); assertEquals(0f,b.contributionAt(node,h.clock.stampNow),0f)
        assertEquals(0f,node.shareAt(b,h.clock.stampNow),0f); assertEquals(0,h.clock.activeCount)
        assertThrows(IllegalStateException::class.java) { node.request(shown,profile) }
    }

    /** 10,000 request/transfer/receipt/dismiss/reopen/advance steps: shares stay a partition, the ledger is bounded, and every join is C0/C1. */
    @Test fun tenThousandRequestSequencesKeepPartitionBoundAndContinuity() = EngineHarness(spec=geometry).use { h ->
        val r=Random(60401); val planes=List(4) { plane(h) }; val node=SurfaceNode(h.clock,"fuzz",shape(h),shown,planes[0]); var dismissed=false; var retired=false
        repeat(10000) { step ->
            val s=h.clock.stampNow; val o0=node.opacity.sampleAt(s); val w0=node.shadowOwnership.sampleAt(s); val e0=node.transform.valueAt(s)
            when(r.nextInt(7)) {
                0 -> { val t=SurfaceTarget(Stratum.entries[r.nextInt(Stratum.entries.size)],if(r.nextInt(4)==0) 0f else r.nextFloat(),ShadowRole.entries[r.nextInt(4)],SurfaceTransform(Offset(r.nextFloat()*400-200,r.nextFloat()*400-200),.5f+r.nextFloat(),Offset(r.nextFloat()*100,0f)))
                    node.request(t,profile); if(t.opacity>0f) { dismissed=false; retired=false } }
                1,2 -> if(!retired) node.transfer(planes[r.nextInt(4)],profile)
                3 -> planes[r.nextInt(4)].acknowledge()
                4 -> if(r.nextInt(5)==0) { node.dismiss(); dismissed=true }
                else -> h.advance(r.nextDouble()*.06)
            }
            retired=dismissed && !node.retained
            val t=h.clock.stampNow; val label="step $step"
            if(t.nanos==s.nanos) { // Same stamp: every intent joins in value and velocity.
                val o1=node.opacity.sampleAt(t); val w1=node.shadowOwnership.sampleAt(t); val e1=node.transform.valueAt(t)
                assertEquals(label,o0.value,o1.value,1e-4f); assertEquals(label,w0.value,w1.value,1e-4f); assertEquals(label,o0.velocity,o1.velocity,1e-3f*maxOf(1f,abs(o0.velocity))); assertEquals(label,w0.velocity,w1.velocity,1e-3f*maxOf(1f,abs(w0.velocity)))
                assertEquals(label,e0.left,e1.left,1e-2f); assertEquals(label,e0.bottom,e1.bottom,1e-2f)
            }
            val sh=shares(node,planes,t); assertTrue(label,sh.all { it.isFinite() && it>=0f && it<=1f })
            if(retired) assertEquals(label,0f,sh.sum(),0f) else assertEquals(label,1f,sh.sum(),1e-5f)
            val holding=planes.count { node in it.casters }; assertTrue("$label ledger $holding",holding<=3); assertTrue(label,planes.all { p -> p.casters.count { it.key=="fuzz" }<=1 })
            assertTrue(label,planes.filter { node.shareAt(it,t)>0f }.all { node in it.casters })
            val owners=planes.filter { node.owns(it) }; if(retired) assertTrue(label,owners.isEmpty()) else { assertEquals(label,1,owners.size); assertTrue(label,node in owners[0].casters); assertEquals(label,node.opacity.valueAt(t),planes.sumOf { it.contributionAt(node,t).toDouble() }.toFloat(),0f) }
        }
    }
}
