package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** D46: one measured flight, sampled at both display cadences. The old preparation
 * window is a deliberately wrong projection of that same phase, never another owner.
 */
class PreparedOverlapTest {
    /** The rc3 #608 law that D46 was written against. Its front-loaded first 60 Hz frame after admission passes
     * phase .4, which is what made the old window fail; the #846 capped law moves slower there, so the red control keeps this law. */
    private val rc3Law = ExpansionSpec(EasingSpec(.242192f,.999967f,.511935f,.997652f),426.086832f,.500401615f,1.41952128f,.73607278f,.5f,140f,.25f,.5f,.05f)

    /** Returns the visible overlap counts of the shipped and the old preparation window over two seconds, which covers the
     * restored v0.33.21 popup spring (929 ms) after its native gate (#846). */
    private fun flight(hz: Int, law: ExpansionSpec?): Pair<Int, Int> {
        val spec = (uiSpec(InterfaceSettings(), law?.let { UiSpecData(expansion = it) },
            ExpansionGeometryDomain(1000f, 4000f, 1f / 30)) as SpecResult.Accepted).spec
        var overlaps = 0
        var oldOverlaps = 0
        EngineHarness(spec = spec).use { h ->
            h.clock.configure(EngineContext(1f / hz, 1000f, MotionAccess.Standard), spec)
            val motion = PopupWindowMotion(h.clock, spec.expansion, "prepared-$hz", true, false)
            val home = Rect(40f, 50f, 88f, 98f)
            val envelope = Rect(10f, 20f, 310f, 620f)
            val old = Curve.window(0f, .4f)
            motion.show(true)
            motion.place(home, envelope, 1f)
            var admitted = false
            var firstAdmission = -1
            repeat(2 * hz) { frame ->
                h.advance(1.0 / hz)
                val phase = motion.expansion.phase.valueAt(h.clock.stampNow)
                val preparation = motion.preparationCurve.at(phase)
                val oldPreparation = old.at(phase)
                val popup = motion.popupCurve.at(phase)
                val bounds = requireNotNull(motion.bounds).valueAt(h.clock.stampNow)
                if (admitted && popup > 0f && preparation < 1f) overlaps++
                if (admitted && popup > 0f && oldPreparation < 1f) oldOverlaps++
                println("D46_FRAME,${if (law == null) "stock" else "rc3"},$hz,$frame,$phase,$preparation,$oldPreparation,$popup,$admitted," +
                    "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                if (!admitted && motion.expansion.awaitingNativeGate) {
                    assertEquals(.25f, phase, 0f)
                    assertEquals("Admission/rejoin shape stays exact", .68359375f, preparation, 0f)
                    assertEquals(home, bounds)
                    assertEquals(0f, motion.expansion.phase.velocityAt(h.clock.stampNow), 0f)
                    motion.expansion.acknowledgeNativeGate(motion.expansion.nativeGeneration)
                    admitted = true
                    firstAdmission = frame
                }
            }
            assertTrue("The native gate must admit the opening at $hz Hz", firstAdmission >= 0)
            assertEquals(envelope, requireNotNull(motion.bounds).valueAt(h.clock.stampNow))
            assertEquals(1f, motion.preparationCurve.at(motion.expansion.phase.valueAt(h.clock.stampNow)), 0f)
            motion.dispose()
        }
        return overlaps to oldOverlaps
    }

    @Test fun preparationOverlapsVisibleOpeningAtSixtyAndOneTwentyHertz() {
        for (hz in listOf(60, 120)) {
            assertTrue("D46 requires visible split/window overlap at $hz Hz", flight(hz, null).first > 0)
            // The shipped window must also hold under the faster rc3 law it was designed against.
            assertTrue("D46 overlap under the rc3 law at $hz Hz", flight(hz, rc3Law).first > 0)
        }
        assertThrows(AssertionError::class.java) {
            assertTrue("Red: the old preparation window must fail the same overlap predicate", flight(60, rc3Law).second > 0)
        }
    }
}
