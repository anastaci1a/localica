package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test

class PopupDurationPolicyTest {
    private val spec = (uiSpec(InterfaceSettings(), null,
        ExpansionGeometryDomain(1000f, 3000f, 1f / 60)) as SpecResult.Accepted).spec
    private val envelope = Rect(0f, 0f, 1000f, 1000f)
    private val home = envelope.translate(28f, 0f)
    private class Joins : MotionEvents {
        val values = mutableListOf<MotionJoin>()
        override fun created(motion: MotionFloat) = Unit
        override fun retired(motion: MotionFloat, stamp: FrameStamp) = Unit
        override fun joined(motion: MotionFloat, join: MotionJoin) {
            if ((motion.key as? Pair<*, *>)?.second == "phase") values += join
        }
        fun duration() = requireNotNull(values.last().finishSeconds)
    }

    @Test fun presentedExtentControlsDurationWithoutChangingTheParallaxRectangle() = EngineHarness(spec = spec).use { h ->
        val joins = Joins()
        h.clock.events = joins
        val popup = PopupWindowMotion(h.clock, spec.expansion, "page", false, false)
        popup.updateSpeed(4f / 3f)
        popup.show(true)
        popup.place(home, envelope, 1f, detached = true)
        assertEquals(home, popup.bounds!!.valueAt(h.clock.stampNow))
        assertTrue(popup.expansion.acknowledgeNativeGate(popup.expansion.nativeGeneration))
        assertEquals(spec.expansion.referenceDurationMs / 1000.0, joins.duration(), 1e-7)
        h.advance(2.0)
        assertEquals(envelope, popup.bounds!!.valueAt(h.clock.stampNow))
        popup.show(false)
        assertEquals(spec.expansion.referenceDurationMs / 1000.0, joins.duration(), 1e-7)
        h.advance(2.0)
        assertEquals(home, popup.bounds!!.valueAt(h.clock.stampNow))
        popup.dispose()
    }

    @Test fun localSpeedIsAppliedOnceIncludingTheFasterRoot() {
        for (speed in listOf(.5f, 1f, 4f / 3f, 2f)) {
            EngineHarness(spec = spec).use { h ->
                h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Faster), spec)
                val joins = Joins()
                h.clock.events = joins
                val owner = ExpandCollapse(h.clock, spec.expansion, false)
                owner.setBasis(ExpansionBasis(home, envelope, 1f, 1000f))
                owner.updateSpeed(speed)
                owner.target(true)
                val scale = when (speed) { 4f / 3f -> 1.0; 2f -> 2.0 / 3; else -> (4.0 / 3) / speed }
                assertEquals(spec.expansion.referenceDurationMs / 1000.0 * scale, joins.duration(), 1e-7)
                owner.dispose()
                assertEquals(0, h.clock.activeCount)
            }
        }
    }

    @Test fun durationOnlyBasisChangeAndLiveSpeedChangeInvalidateDeduplicationWithoutJumps() = EngineHarness(spec = spec).use { h ->
        val joins = Joins()
        h.clock.events = joins
        val owner = ExpandCollapse(h.clock, spec.expansion, false)
        val basis = ExpansionBasis(home, envelope, 1f, 1000f)
        owner.setBasis(basis)
        val rect = MotionRect(h.clock, home, EdgeRanges(3000f, 3000f, 3000f, 3000f))
        owner.bind(rect)
        owner.updateSpeed(1f)
        owner.target(true)
        h.advance(.04)
        val stamp = h.clock.stampNow
        val phase = owner.phase.sampleAt(stamp)
        val edges = rect.channels.map { it.sampleAt(stamp) }
        val before = joins.values.size
        assertEquals(BasisChange.Rescale, owner.setBasis(basis.copy(durationTravelPx = 2000f)))
        assertTrue(joins.values.size > before)
        assertEquals(phase, owner.phase.sampleAt(stamp))
        assertEquals(edges, rect.channels.map { it.sampleAt(stamp) })
        val changed = joins.values.size
        owner.updateSpeed(2f)
        assertTrue(joins.values.size > changed)
        assertEquals(phase, owner.phase.sampleAt(stamp))
        assertEquals(edges, rect.channels.map { it.sampleAt(stamp) })
        owner.dispose()
    }

    @Test fun contactCapturesSpeedAndTheFollowingIntentUsesTheNewSpeed() = EngineHarness(spec = spec).use { h ->
        val joins = Joins()
        h.clock.events = joins
        val owner = ExpandCollapse(h.clock, spec.expansion, false)
        owner.setBasis(ExpansionBasis(Rect(0f, 0f, 100f, 100f), Rect(0f, 0f, 1100f, 100f), 1f))
        owner.updateSpeed(.5f)
        owner.contactBegin(h.clock.stampNow)
        h.now += 1_000_000_000
        owner.contactMove(700f, h.clock.stampNow)
        owner.updateSpeed(2f)
        val release = owner.contactRelease(0f, h.clock.stampNow)
        assertTrue(release.expanded)
        val expected = expansionDuration(spec.expansion, 1000.0, 1000.0, .7, 0.0, 1.0, (4.0 / 3) / .5, 1.0 / 120)
        assertEquals(expected, release.durationSeconds.toDouble(), 1e-6)
        val before = joins.values.size
        owner.target(true)
        assertTrue(joins.values.size > before)
        owner.dispose()
    }

    @Test fun heldSpeedAndDurationChangesWaitForReleaseAndReducedKeepsItsNativeGates() = EngineHarness(spec = spec).use { h ->
        val owner = ExpandCollapse(h.clock, spec.expansion, false)
        owner.nativeGate(0f)
        owner.setBasis(ExpansionBasis(home, envelope, 1f, 1000f))
        owner.bind(MotionRect(h.clock, home, EdgeRanges(3000f, 3000f, 3000f, 3000f)))
        owner.target(true)
        assertTrue(owner.acknowledgeNativeGate(owner.nativeGeneration))
        h.advance(.04)
        owner.hold()
        owner.target(false)
        val value = owner.phase.valueAt(h.clock.stampNow)
        owner.updateSpeed(.5f)
        owner.setBasis(ExpansionBasis(home, envelope, 1f, 2000f))
        h.advance(.2)
        assertEquals(value, owner.phase.valueAt(h.clock.stampNow), 0f)
        h.clock.configure(EngineContext(1f / 120, 1000f, MotionAccess.Reduced), spec)
        assertEquals(value, owner.phase.valueAt(h.clock.stampNow), 0f)
        assertFalse(owner.awaitingNativeGate)
        owner.release()
        assertTrue(owner.awaitingNativeGate)
        assertTrue(owner.acknowledgeNativeGate(owner.nativeGeneration))
        assertTrue(owner.retirementReady)
        owner.dispose()
        assertEquals(0, h.clock.activeCount)
    }

    @Test fun invalidSpeedAndUnadmittedDurationCannotPartiallyCommit() = EngineHarness(spec = spec).use { h ->
        val owner = ExpandCollapse(h.clock, spec.expansion, false)
        owner.setBasis(ExpansionBasis(home, envelope, 1f, 1000f))
        owner.target(true)
        h.advance(.04)
        val sample = owner.phase.sampleAt(h.clock.stampNow)
        for (speed in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { owner.updateSpeed(speed) }
            assertEquals(sample, owner.phase.sampleAt(h.clock.stampNow))
        }
        assertThrows(IllegalArgumentException::class.java) {
            owner.setBasis(ExpansionBasis(home, envelope, 1f, 3001f))
        }
        assertEquals(sample, owner.phase.sampleAt(h.clock.stampNow))
        owner.dispose()
    }
    /** The same replacement-window sequence on an envelope whose extent is exactly the admitted diagonal. */
    private fun admittedEdgeDuration() = EngineHarness(spec = spec).use { h ->
        val joins = Joins()
        h.clock.events = joins
        val popup = PopupWindowMotion(h.clock, spec.expansion, "replacement-page", false, false)
        popup.place(home, envelope, 1f, detached = true)
        popup.attached(Any(), ShadowPlane(h.clock, HostKey.next(Any())))
        popup.show(true)
        popup.place(home, Rect(0f, 0f, 1000f, 3000f), 1f, detached = true)
        assertTrue(popup.expansion.acknowledgeNativeGate(popup.expansion.nativeGeneration))
        joins.duration().also { popup.dispose() }
    }

    @Test fun replacementNativeWindowUsesTheSameDurationAdmissionBeforeAnyCommit() = EngineHarness(spec = spec).use { h ->
        val joins = Joins()
        h.clock.events = joins
        val popup = PopupWindowMotion(h.clock, spec.expansion, "replacement-page", false, false)
        popup.place(home, envelope, 1f, detached = true)
        val native = ShadowPlane(h.clock, HostKey.next(Any()))
        popup.attached(Any(), native)
        popup.show(true)
        assertTrue(popup.expansion.awaitingNativeGate)
        val before = popup.bounds!!.valueAt(h.clock.stampNow)
        val phase = popup.expansion.phase.sampleAt(h.clock.stampNow)
        val generation = popup.expansion.nativeGeneration
        val count = joins.values.size
        // An envelope beyond the admitted viewport (a detached window larger than its root) must not throw in
        // measurement: its duration travel is clamped to the admitted diagonal while the geometry keeps its extent.
        val oversized = Rect(0f, 0f, 1000f, 3001f)
        popup.place(home, oversized, 1f, detached = true)
        assertTrue(joins.values.size > count)
        assertEquals(before, popup.bounds!!.valueAt(h.clock.stampNow))
        assertEquals(phase, popup.expansion.phase.sampleAt(h.clock.stampNow))
        assertEquals(generation, popup.expansion.nativeGeneration)
        assertTrue(popup.expansion.awaitingNativeGate)
        assertTrue(popup.expansion.acknowledgeNativeGate(popup.expansion.nativeGeneration))
        assertEquals(admittedEdgeDuration(), joins.duration(), 0.0)
        h.advance(4.0)
        assertEquals(oversized, popup.bounds!!.valueAt(h.clock.stampNow))
        popup.dispose()
        assertEquals(0, h.clock.activeCount)
    }

    @Test fun detachedLayerOpacityKeepsExactEndpointsAndFadesLateInTheSamePhase() = EngineHarness(spec = spec).use { h ->
        val popup = PopupWindowMotion(h.clock, spec.expansion, "page-fade", false, false)
        val opacity = popup.detachedOpacity
        val band = spec.expansion.band
        // Rest, Reduced Motion and band overshoot keep the exact endpoint opacities.
        assertEquals(0f, opacity.at(0f), 0f); assertEquals(1f, opacity.at(1f), 0f)
        assertEquals(0f, opacity.at(-band), 0f); assertEquals(1f, opacity.at(1f + band), 0f)
        var previous = 0f
        for (i in 0..1000) {
            val p = i / 1000f; val value = opacity.at(p)
            assertTrue("monotone at $p", value >= previous); previous = value
            if (p >= .6f) assertEquals("opaque from .6 at $p", 1f, value, 0f) else if (p > 0f) assertTrue("fading below .6 at $p", value > 0f && value < 1f)
        }
        // The actual Page close: opacity starts at exactly 1, never rises and reaches exactly 0 when the phase rests.
        val page = Rect(0f, 0f, 1080f, 1920f)
        popup.updateSpeed(4f / 3f); popup.show(true); popup.place(page.translate(73.5f, 0f), page, 2.625f, detached = true)
        assertTrue(popup.expansion.acknowledgeNativeGate(popup.expansion.nativeGeneration)); h.advance(4.0)
        assertEquals(1f, opacity.at(popup.expansion.phase.valueAt(h.clock.stampNow)), 0f)
        popup.show(false); previous = 1f
        repeat(60) {
            h.advance(1.0 / 60); val value = opacity.at(popup.expansion.phase.valueAt(h.clock.stampNow))
            assertTrue(value <= previous); previous = value
        }
        assertTrue(popup.expansion.phase.settled); assertEquals(0f, previous, 0f)
        popup.dispose()
        assertEquals(0, h.clock.activeCount)
    }
}
