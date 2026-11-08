package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.SearchMorphFlight
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Controlled static return: the old phase oracle and new edge oracle both hold.
 * The red producer returns the previous Engine frame's vacancy, with no second motion.
 * Native placement and IME behavior remain covered by the original instrumented tests.
 */
class SearchReturnOracleSensitivityTest {
    @Test fun bothOraclesRejectAOneFrameLateReturn() {
        val spec = (uiSpec(InterfaceSettings(), null,
            ExpansionGeometryDomain(1000f, 4000f, 1f / 30)) as SpecResult.Accepted).spec
        EngineHarness(spec = spec).use { h ->
            val home = Rect(40f, 50f, 88f, 98f)
            val envelope = Rect(10f, 20f, 310f, 620f)
            val motion = PopupWindowMotion(h.clock, spec.expansion, "search-oracle", false, false)
            motion.show(true)
            motion.place(home, envelope, 1f)
            motion.expansion.acknowledgeNativeGate(motion.expansion.nativeGeneration)
            h.advance(2.0)
            val rect = requireNotNull(motion.bounds)
            assertEquals(envelope, rect.valueAt(h.clock.stampNow))
            val slot = SearchSlotMotion()
            slot.settle(slot.begin(), 1f)
            val token = slot.begin()
            val reference = SearchSlotTravel(1f, envelope.bottom, home.bottom, 0f)
            val flight = SearchMorphFlight(envelope, 1f, false)
            motion.show(false)
            slot.bindBottom(token, rect.bottom, home.bottom, 0f)
            var previous = slot.fraction
            var reds = 0
            repeat(60) {
                h.advance(1.0 / 60)
                val fraction = slot.fraction
                val oldExpected = 1f - flight.fraction(motion.expansion.phase.valueAt(h.clock.stampNow))
                val newExpected = reference.fraction(rect.bottom.valueAt(h.clock.stampNow))
                // A red is meaningful only after BOTH unmutated controls pass.
                assertEquals("Old oracle control", oldExpected, fraction, .0001f)
                assertEquals("New oracle control", newExpected, fraction, .0001f)
                if (abs(previous - fraction) > .0002f) {
                    assertThrows(AssertionError::class.java) {
                        assertEquals("Old oracle rejects one-frame-late vacancy", oldExpected, previous, .0001f)
                    }
                    assertThrows(AssertionError::class.java) {
                        assertEquals("New oracle rejects one-frame-late vacancy", newExpected, previous, .0001f)
                    }
                    reds++
                }
                previous = fraction
            }
            assertTrue("Several distinct actual Engine frames must expose the delayed producer: $reds", reds >= 4)
            println("Search return sensitivity: old/new controls=60, one-frame-late rejected by both=$reds, tolerance=0.0001")
            motion.dispose()
        }
    }
}
