package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

/** #854 F-I5-2 (a): an opening held at its native gate begins at its first admitted frame's completion, so the first acknowledged
 * frame catches up instead of starting late; only that intent, only while every channel rested unchanged, and at most
 * min(3 display intervals, 25 ms of law time) back. Every other intent still may not precede a presented frame. */
class HeldOpeningBeginTest {
    private val geometry = (uiSpec(InterfaceSettings(), null, ExpansionGeometryDomain(1000f, 2000f, 1f / 60)) as SpecResult.Accepted).spec
    private val frame = 1.0 / 120
    /** durationScale x speed: the benign slower scale, the faster animator scale and the faster speed. */
    private val scales = listOf(1f to 1f, 1.5f to 1f, .5f to 1f, 1f to 1.5f)

    private fun harness(scale: Pair<Float, Float>) = EngineHarness(spec = geometry).also {
        it.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Standard, scale.first, scale.second), geometry)
    }
    private fun popup(h: EngineHarness, key: String) = PopupWindowMotion(h.clock, geometry.expansion, key, false, false, PopupKind.BottomMenu)
        .also { it.show(true); it.place(Rect(0f, 0f, 48f, 48f), Rect(20f, 30f, 400f, 600f), 1f); assertTrue(it.expansion.awaitingNativeGate) }
    private fun PopupWindowMotion.value(h: EngineHarness) = expansion.phase.valueAt(h.clock.stampNow)
    private fun PopupWindowMotion.acknowledge(begin: FrameStamp? = null) = assertTrue(expansion.acknowledgeNativeGate(expansion.nativeGeneration, begin))
    private fun capNanos(scale: Pair<Float, Float>) = (minOf(3.0 / 120, .025 * scale.first / scale.second) * 1e9).toLong()

    /** The held opening, acknowledged a frame late with its begin, is on every later frame where an opening acknowledged at that
     * begin is: it is evaluated at now - begin. Its first acknowledged frame steps at most .35. */
    @Test fun aHeldOpeningIsEvaluatedFromItsBegin() { for (scale in scales) harness(scale).use { h ->
        val prompt = popup(h, "prompt"); val held = popup(h, "held")
        h.advance(frame); val begin = h.clock.stampNow
        prompt.acknowledge()
        h.advance(frame); val rest = held.value(h)
        assertEquals("$scale: one frame back is inside the cap", begin, held.expansion.heldOpeningBegin(begin))
        held.acknowledge(begin)
        h.advance(frame); val step = held.value(h) - rest
        assertTrue("$scale: first acknowledged step $step in (0, .35]", step > 0f && step <= .35f)
        repeat(60) {
            assertEquals("$scale frame $it", prompt.value(h), held.value(h), 1e-6f)
            h.advance(frame)
        }
        prompt.dispose(); held.dispose()
    } }

    /** A begin before the opening's last intent (a reversal while waiting) or before its channels came to rest is refused: the
     * opening begins now, exactly as one acknowledged without a begin. */
    @Test fun aBeginBeforeTheLastIntentOrRestBeginsNow() { for (scale in scales) harness(scale).use { h ->
        h.advance(frame); val early = h.clock.stampNow
        h.advance(frame)
        val now = popup(h, "now"); val rested = popup(h, "rested"); val reversed = popup(h, "reversed")
        h.advance(frame); val begin = h.clock.stampNow
        reversed.show(false); reversed.show(true); assertTrue(reversed.expansion.awaitingNativeGate)
        h.advance(frame)
        assertNull(rested.expansion.heldOpeningBegin(early)); assertNull(reversed.expansion.heldOpeningBegin(begin))
        now.acknowledge(); rested.acknowledge(early); reversed.acknowledge(begin)
        repeat(30) {
            h.advance(frame)
            assertEquals("$scale rested $it", now.value(h), rested.value(h), 1e-6f)
            assertEquals("$scale reversed $it", now.value(h), reversed.value(h), 1e-6f)
        }
        now.dispose(); rested.dispose(); reversed.dispose()
    } }

    /** C-a2: a begin taken while the prepared ramp was still heading to its seam spans frames that showed it moving: refused, and
     * the opening matches one acknowledged without a begin. */
    @Test fun aBeginWhileTheRampMovedIsRefused() { for (scale in scales) harness(scale).use { h ->
        val spec = (uiSpec(InterfaceSettings(), null, ExpansionGeometryDomain(1000f, 4000f, 1f / 30)) as SpecResult.Accepted).spec
        val start = Rect(40f, 50f, 88f, 98f); val end = Rect(10f, 20f, 310f, 620f)
        fun owner() = ExpandCollapse(h.clock, spec.expansion, false).also { e ->
            e.nativeGate(.25f); e.setBasis(ExpansionBasis(start, end, 1f)); e.bind(MotionRect(h.clock, start, EdgeRanges(4000f, 4000f, 4000f, 4000f)))
            e.target(true)
        }
        val plain = owner(); val moved = owner()
        h.advance(frame); val begin = h.clock.stampNow
        assertFalse("$scale: the ramp is still moving at the begin", moved.awaitingNativeGate || moved.phase.settled)
        var frames = 0
        while (!moved.awaitingNativeGate && frames++ < 600) h.advance(frame)
        assertTrue(moved.awaitingNativeGate && plain.awaitingNativeGate)
        assertNull("$scale", moved.heldOpeningBegin(begin))
        assertTrue(plain.acknowledgeNativeGate(plain.nativeGeneration)); assertTrue(moved.acknowledgeNativeGate(moved.nativeGeneration, begin))
        repeat(30) {
            h.advance(frame)
            assertEquals("$scale moved $it", plain.phase.valueAt(h.clock.stampNow), moved.phase.valueAt(h.clock.stampNow), 1e-6f)
        }
        plain.dispose(); moved.dispose()
    } }

    /** A receipt delayed by main-thread jank lifts its begin to now - cap, the cap shortened in real time by a faster speed or
     * animator scale: the first step stays within .35 at every scale. A presentation time in the future is now. */
    @Test fun aDelayedReceiptIsLiftedToTheCatchUpCap() { for (scale in scales) harness(scale).use { h ->
        val p = popup(h, "delayed")
        h.advance(frame); val admitted = h.clock.stampNow
        repeat(12) { h.advance(frame) }
        assertEquals(h.clock.stampNow.nanos, h.clock.beginStamp(h.clock.stampNow.nanos + 5_000_000, admitted).nanos)
        val begin = h.clock.beginStamp(admitted.nanos + 1_000_000, admitted)
        assertEquals(admitted.nanos + 1_000_000, begin.nanos)
        assertEquals("$scale", h.clock.stampNow.nanos - capNanos(scale), p.expansion.heldOpeningBegin(begin)!!.nanos)
        val rest = p.value(h); p.acknowledge(begin)
        h.advance(frame); val step = p.value(h) - rest
        assertTrue("$scale: capped first step $step in (0, .35]", step > 0f && step <= .35f)
        p.dispose()
    } }

    /** At an instant animator scale the cap is zero: the opening begins now. */
    @Test fun anInstantScaleBeginsNow() = harness(0f to 1f).use { h ->
        val p = popup(h, "instant")
        h.advance(frame); val begin = h.clock.stampNow
        h.advance(frame)
        assertEquals(h.clock.stampNow.nanos, p.expansion.heldOpeningBegin(begin)!!.nanos)
        p.dispose()
    }

    /** Every other intent still may not precede the last presented frame. */
    @Test fun otherIntentsBeforeAPresentedFrameStillThrow() = harness(1f to 1f).use { h ->
        val p = popup(h, "other"); p.acknowledge()
        h.advance(frame); val stale = h.clock.stampNow; h.advance(frame)
        val failure = assertThrows(IllegalArgumentException::class.java) { p.expansion.target(false, at = stale) }
        assertEquals("Stale expansion input", failure.message)
        p.dispose()
    }
}
