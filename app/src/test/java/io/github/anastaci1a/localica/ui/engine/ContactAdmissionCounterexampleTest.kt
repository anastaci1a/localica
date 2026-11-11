package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class ContactAdmissionCounterexampleTest {
    @Test fun liftedContactAtAnAdmittedOuterPeakMustHaveAContinuation() {
        val proposed=StockUiSpec.expansion.copy(core=EasingSpec(.25f,1.75f,.75f,.75f),referenceDurationMs=500f,band=.0625f)
        val result=uiSpec(InterfaceSettings(),UiSpecData(expansion=proposed))
        assertTrue(result is SpecResult.Rejected)
        assertTrue((result as SpecResult.Rejected).reasons.any { "core extrema" in it })
    }

    @Test fun tinyPositiveGeometryMustNotAdmitAnUnrepresentableRelease() {
        EngineHarness().use { h ->
            val c=ExpandCollapse(h.clock,StockUiSpec.expansion,false)
            c.setBasis(ExpansionBasis(Rect(0f,0f,100f,100f),Rect(1e-40f,0f,100f,100f),1f))
            assertFalse(c.canContact); assertThrows(IllegalStateException::class.java) { c.contactBegin(h.clock.stampNow) }; assertFalse(c.inContact)
            c.target(true); h.advance(1.0); assertTrue(c.phase.settled)
            c.dispose()
        }
    }
    @Test fun halfBandCoreStillFailsWhenCatchingAnAutomaticStartupPeak() {
        val candidate=StockUiSpec.expansion.copy(core=EasingSpec(.25f,-.15f,.75f,.8f))
        val extrema=curveExtrema(candidate.core)
        assertTrue(extrema.start>=-candidate.band/2 && extrema.endInclusive<=1+candidate.band/2)
        val result=uiSpec(InterfaceSettings(),UiSpecData(expansion=candidate))
        assertTrue(result is SpecResult.Rejected)
        assertTrue((result as SpecResult.Rejected).reasons.any { "minimum >= 0" in it })
    }

}
