package io.github.anastaci1a.localica.ui

import org.junit.Assert.*
import org.junit.Test

class MarqueeMotionTest {
    private fun MarqueeMotion.at(time: Long, allowed: Boolean = true, width: Float = 120f) =
        advance(time, textWidth = 300f, viewportWidth = width, speed = 100f, allowed = allowed)

    private fun scrollingAt(offset: Float): Pair<MarqueeMotion, Long> {
        val motion = MarqueeMotion()
        motion.at(0)
        motion.at(MarqueePauseMillis)
        motion.at(MarqueePauseMillis + MarqueeFadeMillis)
        val now = MarqueePauseMillis + MarqueeFadeMillis + (offset * 10).toLong()
        motion.at(now)
        return motion to now
    }

    @Test fun leadingFadeFinishesBeforeTheFirstScrollingFrame() {
        val motion = MarqueeMotion()
        assertEquals(MarqueePhase.Rest, motion.at(0).phase)
        assertEquals(0f, motion.at(1799).leadingFade, 0f)
        assertEquals(MarqueePhase.LeadingIn, motion.at(1800).phase)
        val fading = motion.at(1910)
        assertEquals(.5f, fading.leadingFade, .001f)
        assertEquals(0f, fading.offset, 0f)
        val ready = motion.at(2020)
        assertEquals(1f, ready.leadingFade, 0f)
        assertEquals(0f, ready.offset, 0f)
        assertTrue(motion.at(2036).offset > 0f)
    }

    @Test fun restingDelayReportsOnlyItsRemainingTime() {
        val motion = MarqueeMotion()
        motion.at(100)
        assertEquals(1800L, motion.pauseRemaining(100))
        assertEquals(800L, motion.pauseRemaining(1100))
        assertEquals(0L, motion.pauseRemaining(1901))
    }

    @Test fun backwardReturnStartsWithoutSnapAndFadesOnlyAfterArrival() {
        val (motion, now) = scrollingAt(80f)
        val start = motion.at(now, allowed = false)
        assertEquals(80f, start.offset, .001f)
        assertEquals(MarqueePhase.Returning, start.phase)
        for (elapsed in 16L until MarqueeReturnMillis step 16) {
            val frame = motion.at(now + elapsed, allowed = false)
            assertTrue(frame.offset > 0f)
            assertTrue(frame.offset < 80f)
            assertEquals(1f, frame.leadingFade, 0f)
        }
        val arrived = motion.at(now + MarqueeReturnMillis, allowed = false)
        assertEquals(0f, arrived.offset, 0f)
        assertEquals(1f, arrived.leadingFade, 0f)
        val fading = motion.at(now + MarqueeReturnMillis + MarqueeFadeMillis / 2, allowed = false)
        assertEquals(0f, fading.offset, 0f)
        assertEquals(.5f, fading.leadingFade, .001f)
        assertEquals(0f, motion.at(now + MarqueeReturnMillis + MarqueeFadeMillis, false).leadingFade, 0f)
    }

    @Test fun shortestReturnCanTravelForwardThroughTheWrap() {
        val (motion, now) = scrollingAt(270f)
        assertEquals(340f, motion.frame.repeatDistance, 0f)
        assertEquals(270f, motion.at(now, false).offset, 0f)
        val middle = motion.at(now + MarqueeReturnMillis / 2, false)
        assertEquals(305f, middle.offset, .001f)
        assertEquals(1f, middle.leadingFade, 0f)
        val arrived = motion.at(now + MarqueeReturnMillis, false)
        assertEquals(0f, arrived.offset, 0f)
        assertEquals(1f, arrived.leadingFade, 0f)
    }

    @Test fun returnEasesAtBothEndsWithoutOvershoot() {
        assertEquals(0f, marqueeEase(-1f), 0f)
        assertEquals(1f, marqueeEase(2f), 0f)
        assertTrue(marqueeEase(.1f) < .1f)
        assertTrue(marqueeEase(.9f) > .9f)
        assertEquals(.5f, marqueeEase(.5f), 0f)
    }

    @Test fun resizingDoesNotRestartOrSnapAReturnEvenWhenTheLineNowFits() {
        val (motion, now) = scrollingAt(270f)
        motion.at(now, false)
        val first = motion.at(now + 65, false, width = 260f)
        val second = motion.at(now + 130, false, width = 420f)
        // The incoming copy continues its shortest journey regardless of widening.
        assertTrue(second.repeatDistance - second.offset < first.repeatDistance - first.offset)
        assertEquals(35f, second.repeatDistance - second.offset, .001f)
        assertEquals(1f, second.leadingFade, 0f)
        assertEquals(0f, motion.at(now + 260, false, width = 480f).offset, 0f)
    }

    @Test fun bothCopiesMoveContinuouslyWhileTheViewportWidensThroughAForwardReturn() {
        val (motion, now) = scrollingAt(270f)
        var previous = motion.at(now, false)
        for (elapsed in 1L until MarqueeReturnMillis) {
            val width = 120f + 360f * elapsed / MarqueeReturnMillis
            val current = motion.at(now + elapsed, false, width)
            val incoming = current.repeatDistance - current.offset
            val previousIncoming = previous.repeatDistance - previous.offset
            assertTrue("Incoming copy never reverses", incoming <= previousIncoming)
            assertTrue("Outgoing glyphs do not jump when repeat spacing grows", current.offset - previous.offset < 2f)
            assertTrue("Incoming glyphs do not jump", previousIncoming - incoming < 1f)
            assertEquals(1f, current.leadingFade, 0f)
            previous = current
        }
        assertTrue("Outgoing copy is fully outside before normalization", previous.offset > 300f)
        assertTrue("Incoming copy has smoothly reached the leading edge", previous.repeatDistance - previous.offset < .01f)
        assertEquals(0f, motion.at(now + MarqueeReturnMillis, false, 480f).offset, 0f)
    }

    @Test fun revealingAlbumMetadataKeepsWrapCopiesSeparatedDuringReturn() {
        val (motion, now) = scrollingAt(270f)
        motion.at(now, false)
        val middle = motion.advance(now + 130, textWidth = 600f, viewportWidth = 260f,
            speed = 100f, allowed = false)
        assertTrue(middle.repeatDistance >= 600f)
        assertEquals(35f, middle.repeatDistance - middle.offset, .001f)
        assertEquals(1f, middle.leadingFade, 0f)
    }

    @Test fun reversalOrSettlingCannotCancelAnAlreadyStartedReturn() {
        val (motion, now) = scrollingAt(80f)
        motion.at(now, false)
        val reversing = motion.at(now + 130, true, width = 90f)
        assertEquals(MarqueePhase.Returning, reversing.phase)
        assertEquals(40f, reversing.offset, .001f)
        assertEquals(1f, reversing.leadingFade, 0f)
        val arrived = motion.at(now + 260, true)
        assertEquals(0f, arrived.offset, 0f)
        assertEquals(1f, arrived.leadingFade, 0f)
        motion.at(now + 480, true)
        assertEquals(MarqueePhase.Rest, motion.at(now + 2279, true).phase)
        assertEquals(MarqueePhase.LeadingIn, motion.at(now + 2280, true).phase)
    }

    @Test fun heldTransitionNeverStartsOrdinaryMarqueeAndResumeGetsAFullDelay() {
        val (motion, now) = scrollingAt(80f)
        motion.at(now, false)
        motion.at(now + 260, false)
        motion.at(now + 480, false)
        for (time in now + 1000..now + 10000 step 1000) {
            val paused = motion.at(time, false)
            assertEquals(0f, paused.offset, 0f)
            assertEquals(0f, paused.leadingFade, 0f)
        }
        motion.at(now + 11000)
        assertEquals(MarqueePhase.Rest, motion.at(now + 12799).phase)
        assertEquals(MarqueePhase.LeadingIn, motion.at(now + 12800).phase)
    }

    @Test fun pausingDuringLeadingPreparationDoesNotMoveTheText() {
        val motion = MarqueeMotion()
        motion.at(0)
        motion.at(1800)
        val preparation = motion.at(1910)
        val interrupted = motion.at(1910, false)
        assertEquals(preparation.leadingFade, interrupted.leadingFade, 0f)
        assertEquals(MarqueePhase.LeadingOut, interrupted.phase)
        assertEquals(0f, motion.at(2020, false).offset, 0f)
        assertEquals(0f, motion.at(2130, false).leadingFade, 0f)
    }

    @Test fun titleAndArtistHaveIndependentOverflowAndResetClocks() {
        val (title, now) = scrollingAt(80f)
        val artist = MarqueeMotion()
        artist.at(0, false)
        assertEquals(0f, artist.at(now, false).offset, 0f)
        assertEquals(MarqueePhase.Returning, title.at(now, false).phase)
        assertEquals(MarqueePhase.Rest, artist.at(now, false).phase)
        artist.at(now + 2000, true)
        assertEquals(MarqueePhase.LeadingIn, artist.at(now + 3800, true).phase)
        assertEquals(0f, title.at(now + 3800, false).offset, 0f)
    }

    @Test fun trailingFadeRespondsContinuouslyToTheClippedExtent() {
        val rest = MarqueeFrame()
        assertEquals(0f, marqueeTrailingFade(100f, 120f, 24f, rest), 0f)
        assertEquals(0f, marqueeTrailingFade(120f, 120f, 24f, rest), 0f)
        assertEquals(.5f, marqueeTrailingFade(132f, 120f, 24f, rest), .001f)
        assertEquals(1f, marqueeTrailingFade(144f, 120f, 24f, rest), 0f)
        val wrap = MarqueeFrame(offset = 330f, repeatDistance = 340f, leadingFade = 1f)
        assertEquals(1f, marqueeTrailingFade(300f, 120f, 24f, wrap), 0f)
    }

    @Test fun outgoingLeftMaskRampsAtFirstPixelAndTailAndStaysOffDuringWrapLanding() {
        fun mask(offset: Float) = marqueeLeftFade(500f, 20f,
            MarqueeFrame(offset = offset, repeatDistance = 566.6667f, phase = MarqueePhase.Scrolling))
        assertEquals(0f, mask(0f), 0f)
        assertTrue(mask(.01f) in 0f.. .00001f)
        assertEquals(.5f, mask(10f), .00001f)
        assertEquals(1f, mask(20f), 0f)
        assertEquals(1f, mask(480f), 0f)
        assertEquals(.5f, mask(490f), .00001f)
        assertEquals(0f, mask(500f), 0f)
        assertEquals(0f, mask(550f), 0f)
        for (phase in listOf(MarqueePhase.Rest, MarqueePhase.LeadingIn, MarqueePhase.LeadingOut)) {
            assertEquals(0f, marqueeLeftFade(500f, 20f,
                MarqueeFrame(offset = 10f, leadingFade = 1f, phase = phase)), 0f)
        }
    }

    @Test fun recordedDrawAmountSurvivesFlipGeometryAndReleaseGeometryChanges() {
        val (motion, now) = scrollingAt(10f)
        // The same helper/result handoff used by the production LTR draw callback.
        val drawn = marqueeLeftFade(300f, 20f, motion.frame)
        assertEquals(.5f, drawn, .00001f)
        motion.recordRenderedLeftFade(drawn)
        val flip = motion.at(now, allowed = false, width = 200f)
        assertEquals(.5f, marqueeLeftFade(5f, 40f, flip), .00001f)
        assertEquals(.25f, marqueeLeftFade(5f, 80f,
            motion.at(now + 75, allowed = false, width = 260f)), .00001f)
        assertEquals(0f, marqueeLeftFade(500f, 20f,
            motion.at(now + 150, allowed = false)), 0f)
        assertEquals(0f, marqueeLeftFade(500f, 20f,
            motion.at(now + 200, allowed = false)), 0f)
    }

    @Test fun forwardReturnKeepsOutgoingTailThenUnmasksIncomingLanding() {
        val (motion, now) = scrollingAt(250f)
        motion.recordRenderedLeftFade(1f)
        val flip = motion.at(now, allowed = false)
        assertEquals(1f, marqueeLeftFade(300f, 20f, flip), 0f)
        var sawTailRamp = false
        for (elapsed in 1L..260L) {
            val frame = motion.at(now + elapsed, allowed = false)
            val amount = marqueeLeftFade(300f, 20f, frame)
            if (frame.offset in 280.001f..299.999f) {
                assertTrue(amount > 0f && amount < 1f)
                sawTailRamp = true
            }
            if (frame.offset >= 300f || frame.phase != MarqueePhase.Returning)
                assertEquals(0f, amount, 0f)
        }
        assertTrue("Forward return must traverse the outgoing tail ramp", sawTailRamp)
    }

    @Test fun absentDrawMaskCannotAppearDuringBackwardCancellation() {
        val (motion, now) = scrollingAt(10f)
        motion.recordRenderedLeftFade(0f)
        for (elapsed in listOf(0L, 75L, 150L, 260L)) {
            assertEquals(0f, marqueeLeftFade(300f, 20f,
                motion.at(now + elapsed, allowed = false)), 0f)
        }
    }

    @Test fun laterCancellationCapturesFreshDrawAndNaturalCycleNeverMasksLanding() {
        val motion = MarqueeMotion()
        var sawOutgoing = false
        var sawLanding = false
        for (time in 0L..6000L step 10L) {
            val frame = motion.at(time)
            val amount = marqueeLeftFade(300f, 20f, frame)
            motion.recordRenderedLeftFade(amount)
            if (amount > 0f) sawOutgoing = true
            if (frame.phase == MarqueePhase.Scrolling && frame.offset >= 300f) {
                assertEquals(0f, amount, 0f)
                sawLanding = true
            }
            if (frame.phase != MarqueePhase.Scrolling) assertEquals(0f, amount, 0f)
        }
        assertTrue(sawOutgoing)
        assertTrue(sawLanding)
        val (cancel, now) = scrollingAt(10f)
        cancel.recordRenderedLeftFade(.5f)
        cancel.recordRenderedLeftFade(.15625f)
        assertEquals(.15625f, marqueeLeftFade(300f, 20f,
            cancel.at(now, allowed = false)), 0f)
    }

    @Test fun pauseCoversBothDirectionsHeldEndpointsAndUnstartedSettles() {
        assertFalse(playerMarqueePaused(0f, false, false, false))
        assertFalse(playerMarqueePaused(1f, true, false, false))
        for (expanded in listOf(false, true)) {
            assertTrue(playerMarqueePaused(.4f, expanded, false, false))
            assertTrue(playerMarqueePaused(if (expanded) 1f else 0f, expanded, true, false))
            assertTrue(playerMarqueePaused(if (expanded) 1f else 0f, expanded, false, true))
            assertTrue(playerMarqueePaused(if (expanded) 0f else 1f, expanded, false, false))
        }
    }
}
