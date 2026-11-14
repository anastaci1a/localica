package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** #960 D: the vacancy is a read of the takeover phase and the actual bound bottom edge of the shared popup
 * rectangle (Dialog law). It rests exactly on 0/1, follows a reversal from its current value, and a one-frame-late
 * producer is rejected. Native placement and IME behavior remain covered by the original instrumented tests. */
class SearchReturnOracleSensitivityTest {
    private class Owner : PopupTakeoverOwner { override var takeover = PopupTakeover.Closed }

    @Test fun vacancyRestsExactlyFollowsReversalAndRejectsAOneFrameLateProducer() {
        val spec = (uiSpec(InterfaceSettings(), null,
            ExpansionGeometryDomain(1000f, 4000f, 1f / 30)) as SpecResult.Accepted).spec
        EngineHarness(spec = spec).use { h ->
            val home = Rect(40f, 50f, 88f, 98f)
            val envelope = Rect(10f, 20f, 310f, 620f)
            val motion = PopupWindowMotion(h.clock, spec.expansion, "search-oracle", false, false, PopupKind.Dialog)
            val owner = Owner()
            val slot = SearchSlotMotion(owner)
            fun place() { motion.place(home, envelope, 1f); slot.bindBottom(motion.bounds!!.bottom,
                if (motion.shown) envelope.bottom else home.bottom, if (motion.shown) 1f else 0f) }
            motion.show(true)
            owner.takeover = PopupTakeover.Admitting
            place()
            assertEquals("Admitting vacates nothing", 0f, slot.fraction, 0f)
            motion.expansion.acknowledgeNativeGate(motion.expansion.nativeGeneration)
            owner.takeover = PopupTakeover.InWindow
            h.advance(.12)
            place()
            val partial = slot.fraction
            assertTrue("Opening travel is partial: $partial", partial in .05f..0.99f)
            // Reversal: the return rebinds from the current vacancy, with no jump.
            motion.show(false)
            place()
            assertEquals("Reversal keeps the current vacancy", partial, slot.fraction, 1e-4f)
            motion.show(true)
            place()
            h.advance(3.0)
            place()
            assertEquals(envelope, motion.bounds!!.valueAt(h.clock.stampNow))
            assertEquals("Open rest is exactly 1", 1f, slot.fraction, 0f)
            val reference = SearchSlotTravel(1f, envelope.bottom, home.bottom, 0f)
            motion.show(false)
            place()
            val rect = requireNotNull(motion.bounds)
            var previous = slot.fraction
            var reds = 0
            repeat(60) {
                h.advance(1.0 / 60)
                val fraction = slot.fraction
                val expected = reference.fraction(rect.bottom.valueAt(h.clock.stampNow))
                // A red is meaningful only after the unmutated control passes.
                assertEquals("Edge oracle control", expected, fraction, .0001f)
                if (abs(previous - fraction) > .0002f) {
                    assertThrows(AssertionError::class.java) {
                        assertEquals("Edge oracle rejects one-frame-late vacancy", expected, previous, .0001f)
                    }
                    reds++
                }
                previous = fraction
            }
            h.advance(3.0)
            assertEquals("Closed rest is exactly 0 while still in the window", 0f, slot.fraction, 0f)
            owner.takeover = PopupTakeover.Restoring
            assertEquals(0f, slot.fraction, 0f)
            assertTrue("Several distinct actual Engine frames must expose the delayed producer: $reds", reds >= 4)
            println("Search vacancy sensitivity: controls=60, one-frame-late rejected=$reds, tolerance=0.0001")
            motion.dispose()
        }
    }

    @Test fun travelEndpointsAreExactAndBounded() {
        for (from in listOf(0f, .1f, .2f, .3f, .7f, 1f)) {
            assertEquals(1f, SearchSlotTravel(from, 100f, 500f).fraction(500f), 0f)
            assertEquals(0f, SearchSlotTravel(from, 500f, 100f, 0f).fraction(100f), 0f)
        }
        assertEquals(1f, SearchSlotTravel(.2f, 100f, 100f).fraction(100f), 0f)
        assertEquals(0f, SearchSlotTravel(0f, 100f, 500f).fraction(50f), 0f)
        assertEquals(1f, SearchSlotTravel(0f, 100f, 500f).fraction(550f), 0f)
    }
}
