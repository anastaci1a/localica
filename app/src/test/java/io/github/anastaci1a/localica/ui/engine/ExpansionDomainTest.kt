package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class ExpansionDomainTest {
    private val geometry=ExpansionGeometryDomain(1000f,2000f,1f/60)
    @Test fun ordinaryGeometryRejectsOverflowWithoutAnExponentCap() {
        val invalid=StockUiSpec.expansion.copy(sizeExponent=Float.MAX_VALUE)
        val result=uiSpec(InterfaceSettings(),UiSpecData(expansion=invalid),geometry)
        assertTrue(result is SpecResult.Rejected); assertTrue((result as SpecResult.Rejected).reasons.any { "device geometry" in it })
        assertTrue(uiSpec(InterfaceSettings(),UiSpecData(expansion=StockUiSpec.expansion.copy(sizeExponent=2f)),geometry) is SpecResult.Accepted)
        // A high exponent is valid when every possible object is smaller than the reference;
        // the 140 ms floor applies. There is no arbitrary exponent ceiling.
        assertTrue(uiSpec(InterfaceSettings(),UiSpecData(expansion=invalid),ExpansionGeometryDomain(1000f,500f,1f/60)) is SpecResult.Accepted)
    }
    @Test fun defensiveAutomaticPreflightRejectsBeforeAnyStateChange() {
        EngineHarness().use { h ->
            val invalid=StockUiSpec.expansion.copy(sizeExponent=Float.MAX_VALUE)
            val c=ExpandCollapse(h.clock,invalid,false)
            c.setBasis(ExpansionBasis(Rect(0f,0f,100f,100f),Rect(2000f,0f,2100f,100f),1f))
            assertFalse(c.canContact); val before=c.phase.sampleAt(h.clock.stampNow)
            assertThrows(IllegalArgumentException::class.java) { c.target(true) }
            assertEquals(before,c.phase.sampleAt(h.clock.stampNow)); assertFalse(c.expanded); assertEquals(0,h.clock.activeCount); c.dispose()
        }
        assertThrows(IllegalArgumentException::class.java) { EngineContext(Float.MAX_VALUE,1000f,MotionAccess.Standard) }
    }
    @Test fun contactKeepsItsAdmittedSpecAndReferenceWhileAccessAndDisplayStayLive() {
        EngineHarness().use { h ->
            val c=ExpandCollapse(h.clock,StockUiSpec.expansion,false)
            c.setBasis(ExpansionBasis(Rect(0f,0f,100f,100f),Rect(1000f,0f,1100f,100f),1f))
            c.contactBegin(h.clock.stampNow); h.now+=10_000_000; c.contactMove(500f,h.clock.stampNow)
            c.updateSpec(StockUiSpec.expansion.copy(sizeExponent=Float.MAX_VALUE))
            h.clock.configure(EngineContext(1f/60,500f,MotionAccess.Faster),StockUiSpec)
            val d=c.contactRelease(0f,h.clock.stampNow)
            val expected=expansionDuration(StockUiSpec.expansion,1000.0,1000.0,.5,0.0,1.0,2.0/3,(1f/60).toDouble())
            assertEquals(expected,d.durationSeconds.toDouble(),1e-7); assertTrue(c.expanded); c.dispose()
        }
    }
    @Test fun restoreInitializesOnlyBeforePresentation() {
        EngineHarness().use { h ->
            val c=ExpandCollapse(h.clock,StockUiSpec.expansion,false)
            c.setBasis(ExpansionBasis(Rect(0f,0f,100f,100f),Rect(1000f,0f,1100f,100f),1f))
            c.target(true,ExpansionReason.Restore); assertTrue(c.phase.settled); assertEquals(1f,c.phase.value(),0f)
            c.target(false,ExpansionReason.Restore); assertFalse(c.phase.settled); assertEquals(1f,c.phase.valueAt(h.clock.stampNow),0f); c.dispose()
        }
    }
}
