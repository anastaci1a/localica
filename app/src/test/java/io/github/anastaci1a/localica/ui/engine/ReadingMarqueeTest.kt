package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import io.github.anastaci1a.localica.ui.spec.*
import io.github.anastaci1a.localica.toast.*
import org.junit.Assert.*
import org.junit.Test

class ReadingMarqueeTest {
    private val specification = StockUiSpec.motion.getValue(MotionProfile.Marquee) as MarqueeSpec

    @Test fun reducedPolicyPreservesObservedReadingWhileOptionalMotionStillSnaps() {
        EngineHarness().use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                var completed = 0
                val reading = ReadingMarquee(h.clock, "reading", 1000f, specification, 30f, {}, { completed++ })
                val optional = MotionFloat(h.clock, 0f, 1f, key = "optional")
                try {
                    reading.configure(300f, false); reading.waitFinished()
                    optional.retarget(1f, motionProfileId(MotionProfile.Control))
                    h.advance(.08)
                    val shown = reading.offset
                    assertTrue(shown > 0f && shown < 300f)
                    h.clock.configure(requireNotNull(h.clock.context).copy(access = MotionAccess.Reduced), h.clock.spec)
                    assertEquals(shown, reading.offset, .0001f)
                    assertTrue(optional.settled)
                    assertEquals(1f, optional.value(), 0f)
                    assertFalse(reading.finished)
                    h.advance(20.0)
                    assertEquals(300f, reading.offset, 0f)
                    assertEquals(1, completed)
                    assertEquals(0, h.clock.activeCount)
                } finally { reading.dispose(); optional.dispose() }
                assertEquals(0, observer.continuity.registeredCount)
                assertFalse(evidence.incomplete)
                assertEquals(emptyList<Violation>(), evidence.violations)
            }
        }
    }

    @Test fun modalPauseAndReflowKeepOneObservedCoordinateAndCompleteOnlyAtRealEnd() {
        EngineHarness().use { h ->
            val evidence = BoundedEvidence(100)
            EngineObservation(h.clock, evidence).use { observer ->
                var completed = 0; var pending = 0
                val reading = ReadingMarquee(h.clock, "reading", 1000f, specification, 30f, { pending++ }, { completed++ })
                try {
                    reading.configure(180f, false)
                    assertEquals("Initial unread overflow establishes the gate", 1, pending)
                    reading.waitFinished(); h.advance(.5)
                    val before = reading.offset
                    reading.configure(180f, true)
                    assertEquals(before, reading.offset, .0001f)
                    h.advance(1.0)
                    val held = reading.offset
                    h.advance(5.0)
                    assertEquals(held, reading.offset, 0f)
                    assertEquals(0, completed)
                    reading.configure(180f, false); h.advance(20.0)
                    assertEquals(1, completed)
                    assertEquals(180f, reading.offset, 0f)
                    reading.configure(300f, false)
                    assertEquals(2, pending)
                    assertFalse(reading.finished)
                    assertEquals(180f, reading.offset, 0f)
                    h.advance(20.0)
                    assertEquals(300f, reading.offset, 0f)
                    assertEquals(2, completed)
                } finally { reading.dispose() }
                assertEquals(0, observer.continuity.registeredCount)
                assertFalse(evidence.incomplete)
                assertEquals(emptyList<Violation>(), evidence.violations)
            }
        }
    }

    @Test fun initialReadingPauseReleasesFramesAndRetainsRemainingWaitAcrossBackground() {
        EngineHarness(MotionAccess.Reduced).use { h ->
            var completed = 0
            val reading = ReadingMarquee(h.clock, "reading", 1000f, specification, 30f, {}, { completed++ })
            try {
                reading.configure(300f, false)
                assertTrue(reading.waitingAllowed)
                val initial = reading.waitStarted()
                assertEquals(0, h.clock.activeCount)
                h.now += 500_000_000L
                reading.configure(300f, true); reading.waitCancelled()
                assertFalse(reading.waitingAllowed)
                h.now += 10_000_000_000L
                reading.configure(300f, false)
                assertEquals(initial - 500_000_000L, reading.waitStarted())
                reading.waitFinished()
                assertEquals(1, h.clock.activeCount)
                h.advance(20.0)
                assertEquals(1, completed)
                assertEquals(300f, reading.offset, 0f)
            } finally { reading.dispose() }
        }
    }

    @Test fun replacementReaderRevokesCompletedGateAndPreservesOnlyUnspentDwell() {
        EngineHarness().use { h ->
            val queue = ToastQueue(clockMillis = { h.now / 1_000_000 })
            val lease = queue.attachPresenter()
            queue.showToast(ToastMessage("An outcome"))
            val item = queue.state.value.current!!
            lease.presented(item.id, item.generation, 3000)
            lease.pause(ToastPause.Background, false)
            fun reader(key: String) = ReadingMarquee(h.clock, key, 1000f, specification, 30f,
                { lease.marqueePending(item.id, item.generation) },
                { lease.marqueeCompleted(item.id, item.generation) })
            val first = reader("first")
            first.configure(180f, false); first.waitFinished(); h.advance(20.0)
            assertTrue(first.finished)
            val old = requireNotNull(queue.state.value.deadline)
            h.advance(1.0)
            first.dispose()
            val replacement = reader("replacement")
            try {
                replacement.configure(300f, false)
                assertFalse("Replacement has unread text", replacement.finished)
                assertNull("Replacement revokes the completed reader's deadline", queue.state.value.deadline)
                assertFalse(queue.state.value.marqueeComplete)
                h.advance(5.0)
                assertTrue(h.now / 1_000_000 > old.atMillis)
                assertFalse("Old timeout cannot dismiss during required reading", lease.timeout(old))
                assertEquals(ToastPhase.Visible, queue.state.value.phase)
                replacement.waitFinished(); h.advance(20.0)
                assertTrue(replacement.finished)
                assertEquals(h.now / 1_000_000 + 2000, queue.state.value.deadline!!.atMillis)
            } finally { replacement.dispose(); lease.release() }
        }
    }
}
