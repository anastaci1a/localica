package io.github.anastaci1a.localica.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** U04.02a part A (D06(a), D09, D33): a face host's one ExpandCollapse waits for its measured faces, then runs the #608 greatest-edge law. */
class FaceExpansionTest {
    private val geometry = (uiSpec(InterfaceSettings(), null, ExpansionGeometryDomain(1000f, 2000f, 1f / 60)) as SpecResult.Accepted).spec
    // The selection pill at 2.75x: a 154 x 48 dp count pill and a 364 x 56 dp action row.
    private val compact = IntSize(424, 132)
    private val expanded = IntSize(1001, 154)

    @Test fun requestBeforeLayoutWaitsForTheMeasuredFacesThenTakesTheGreatestEdgeTime() = EngineHarness(spec = geometry).use { h ->
        val face = FaceExpansion(PopupMotion(h.clock, geometry.expansion, "pill", true), 2.75f)
        face.request(false)
        assertTrue("No basis, no intent", face.view.expanded); assertEquals(0, h.clock.activeCount)
        val basis = ExpansionBasis(centeredFrame(compact), centeredFrame(expanded), 2.75f)
        assertEquals((expanded.width - compact.width) / 2f, basis.edgeTravelPx, 0f)
        face.measured(centeredFrame(compact), centeredFrame(expanded))
        assertFalse(face.view.expanded); assertEquals(0f, face.view.phase.target, 0f)
        val duration = expansionDuration(geometry.expansion, basis.edgeTravelPx.toDouble(), 1000.0, 1.0, 0.0, 0.0, 1.0, 1.0 / 120)
        h.advance(duration / 2); val mid = face.view.phase.value()
        assertTrue("Mid flight: $mid", mid > .05f && mid < .95f)
        // Layout re-measures every frame of the morph; an unchanged basis is no new intent.
        val stamp = h.clock.stampNow; val sample = face.view.phase.sampleAt(stamp)
        face.measured(centeredFrame(compact), centeredFrame(expanded)); assertEquals(sample, face.view.phase.sampleAt(stamp))
        h.advance(duration / 2 + 1.0 / 60); assertTrue(face.view.phase.settled); assertEquals(0f, face.view.phase.value(), 0f)
        assertEquals(0, h.clock.activeCount)
    }

    @Test fun reducedAccessReachesTheRequestedFaceInTwoDisplayIntervals() = EngineHarness(MotionAccess.Reduced, geometry).use { h ->
        val face = FaceExpansion(PopupMotion(h.clock, geometry.expansion, "presence", false), 1f)
        face.measured(centeredFrame(IntSize(39, 39)), centeredFrame(IntSize(48, 48)))
        face.request(true); h.advance(2.0 / 120 + 1e-6)
        assertTrue(face.view.phase.settled); assertEquals(1f, face.view.phase.value(), 0f)
    }

    @Test fun sharedFrameEasesEveryEdgeLinearlyBetweenTheFaceSlots() {
        val from = Rect(10f, 20f, 30f, 40f); val to = Rect(100f, 0f, 160f, 24f)
        assertEquals(from, sharedFrame(from, to, 0f)); assertEquals(to, sharedFrame(from, to, 1f))
        val mid = sharedFrame(from, to, .25f)
        assertEquals(Rect(32.5f, 15f, 62.5f, 36f), mid)
    }
}
