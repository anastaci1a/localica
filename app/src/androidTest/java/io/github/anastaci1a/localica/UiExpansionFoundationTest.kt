package io.github.anastaci1a.localica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class UiExpansionFoundationTest {
    private class Scene { var clock: UiClock?=null; var expansion: ExpandCollapse?=null; var rect: MotionRect?=null; var evidence: BoundedEvidence?=null; var observer: EngineObservation?=null }
    @Composable private fun Fixture(now: ()->Long,access: MotionAccess,scene: Scene) {
        val clock=remember { UiClock(object: FrameSource { override suspend fun awaitFrame()=withFrameNanos { it } },now) }
        val evidence=remember { BoundedEvidence(200) }; val observer=remember { EngineObservation(clock,evidence) }
        DisposableEffect(observer) { onDispose { observer.close() } }; LaunchedEffect(clock) { clock.run() }
        val snapshot=remember { ResolvedUiSpec((uiSpec(InterfaceSettings(),geometry=ExpansionGeometryDomain(1000f,3000f,1f/60)) as SpecResult.Accepted).spec,Density(1f)) }
        CompositionLocalProvider(LocalClockOwner provides clock,LocalUiClock provides clock,LocalEngineContext provides EngineContext(1f/120,1000f,access),LocalUiSpec provides snapshot) {
            val rect=rememberMotionRect("native-expansion-rect",Rect(0f,0f,100f,100f),EdgeRanges(1000f,1000f,1000f,1000f))
            val expansion=rememberExpandCollapse("native-expansion",false)
            SideEffect { scene.clock=clock; scene.rect=rect; scene.expansion=expansion; scene.evidence=evidence; scene.observer=observer }
            Canvas(Modifier.size(20.dp)) { expansion.phase.value(); rect.value() }
        }
    }
    private fun basis(end: Rect=Rect(0f,0f,600f,600f))=ExpansionBasis(Rect(0f,0f,100f,100f),end,1f)
    @Test fun actualFactoryRebaseContactAndSharedFinishKeepPersistentEdges() = localicaRunComposeUiTest {
        val scene=Scene(); setContent { Fixture({ mainClock.currentTime*1_000_000 },MotionAccess.Standard,scene) }
        mainClock.autoAdvance=false
        var ids: List<Long> = emptyList()
        runOnIdle { val c=scene.expansion!!; c.setBasis(basis()); c.bind(scene.rect!!); ids=scene.rect!!.channels.map { it.id }; c.target(true) }
        mainClock.advanceTimeBy(64)
        runOnIdle {
            val c=scene.expansion!!; val stamp=scene.clock!!.stampNow; val before=c.phase.sampleAt(stamp); val edges=scene.rect!!.channels.map { it.sampleAt(stamp) }
            assertEquals(BasisChange.Rebase,c.setBasis(basis(Rect(20f,30f,650f,700f)))); assertEquals(before,c.phase.sampleAt(stamp)); assertEquals(edges,scene.rect!!.channels.map { it.sampleAt(stamp) })
            c.contactBegin(stamp); c.contactMove(50f,stamp); c.contactRelease(100f,stamp)
        }
        mainClock.advanceTimeBy(2000)
        runOnIdle {
            assertTrue(scene.expansion!!.phase.settled); assertTrue(scene.rect!!.channels.all { it.settled }); assertEquals(ids,scene.rect!!.channels.map { it.id }); assertEquals(0,scene.clock!!.activeCount)
            assertTrue(scene.evidence!!.violations.toString(),scene.evidence!!.violations.all { it.code=="ReleaseDisagreement" })
            assertTrue(scene.observer!!.subFrameReleases>=1)
        }
    }
    @Test fun liveReducedEndsContactAtLogicalTargetAndAppliesPendingGeometry() = localicaRunComposeUiTest {
        val scene=Scene(); var access by mutableStateOf(MotionAccess.Standard)
        setContent { Fixture({ mainClock.currentTime*1_000_000 },access,scene) }
        val next=basis(Rect(10f,20f,700f,800f))
        runOnIdle { val c=scene.expansion!!; c.setBasis(basis()); c.bind(scene.rect!!); c.target(true); c.contactBegin(scene.clock!!.stampNow); c.target(false); c.setBasis(next); access=MotionAccess.Reduced }
        runOnIdle {
            val c=scene.expansion!!; assertFalse(c.inContact); assertTrue(c.expanded); assertNull(c.pendingTarget); assertNull(c.pendingBasis); assertTrue(c.phase.settled)
            assertEquals(next.endBounds,scene.rect!!.valueAt(scene.clock!!.stampNow)); assertEquals(0,scene.clock!!.activeCount)
        }
    }
    @Test fun tinyGeometryDisablesContactBeforeBeginAndStillAnimatesProgrammatically() = localicaRunComposeUiTest {
        val scene=Scene(); setContent { Fixture({ mainClock.currentTime*1_000_000 },MotionAccess.Standard,scene) }
        runOnIdle {
            val c=scene.expansion!!; c.setBasis(basis(Rect(.5f,0f,100f,100f))); c.bind(scene.rect!!); assertFalse(c.canContact)
            assertThrows(IllegalStateException::class.java) { c.contactBegin(scene.clock!!.stampNow) }; c.target(true)
        }
        mainClock.advanceTimeBy(1000)
        runOnIdle { assertTrue(scene.expansion!!.phase.settled); assertTrue(scene.expansion!!.expanded); assertTrue(scene.rect!!.channels.all { it.settled }) }
    }
}
