package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** #560: a slow frame (the destination's first composition on a phone) must not finish an ease in one step. */
class BrowsePinchProgressTest {
    /** Frame times as a device reports them; records the shown value after each frame. */
    private class Frames(private val intervalsMillis: List<Double>, private val shown: () -> Float) : MonotonicFrameClock {
        val values = mutableListOf<Float>()
        val times = mutableListOf<Double>()
        private var now = 0.0
        private var index = 0
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            if (index > 0) now += intervalsMillis.getOrElse(index - 1) { intervalsMillis.last() }
            index++
            return onFrame((now * 1_000_000).toLong()).also { values += shown(); times += now }
        }
    }

    private fun catchUp(intervals: List<Double>, from: Float = 0f, fingers: Float = .3f): Frames {
        val progress = BrowsePinchProgress(from)
        val frames = Frames(intervals) { progress.value }
        var caughtUp = false
        runBlocking(frames) {
            progress.follow(fingers)
            progress.catchUp { caughtUp = true }
        }
        assertTrue(caughtUp)
        assertEquals(fingers, progress.value, 0f)
        return frames
    }

    private fun Frames.largestStep(from: Float, to: Float) =
        (listOf(from) + values).zipWithNext { a, b -> (b - a) / (to - from) }.max()

    @Test fun catchUpEasesOverItsHundredMillisecondsOnRegularFrames() {
        for (interval in listOf(1000.0 / 60, 1000.0 / 120)) {
            val frames = catchUp(listOf(interval))
            assertEquals("The ease starts at the shown progress", 0f, frames.values.first(), 0f)
            val end = frames.times[frames.values.indexOfFirst { it >= .3f }]
            assertTrue("Catch-up takes 80-120 ms, not $end at $interval ms frames", end in 80.0..120.0)
            assertTrue("No 60 Hz frame closes half the gap: ${frames.largestStep(0f, .3f)}", frames.largestStep(0f, .3f) < .5f)
        }
    }

    @Test fun aSlowDestinationFrameDoesNotCollapseTheCatchUp() {
        // Frame 0 starts the ease and composes the destination for 150 ms; then regular 120 Hz frames.
        for (stall in listOf(40.0, 80.0, 150.0, 400.0)) {
            val frames = catchUp(listOf(stall) + List(40) { 1000.0 / 120 })
            val first = frames.values[1] / .3f
            assertTrue("A $stall ms frame jumps $first of the gap in one frame", first < .2f)
            assertTrue("The ease still spans several frames after a $stall ms frame: ${frames.values}",
                frames.values.indexOfFirst { it >= .3f } >= 4)
            assertTrue("No frame closes more than a third of the gap after a $stall ms frame",
                frames.largestStep(0f, .3f) < .34f)
        }
    }

    @Test fun releaseSettleSurvivesASlowFirstFrameAndKeepsTheSystemAnimationScale() {
        fun settle(intervals: List<Double>, scale: Float): Frames {
            val progress = BrowsePinchProgress(.3f)
            val frames = Frames(intervals) { progress.value }
            val context = if (scale == 1f) frames else frames + object : MotionDurationScale {
                override val scaleFactor = scale
            }
            runBlocking(context) { progress.animateTo(0f, tween(220)) }
            assertEquals(0f, progress.value, 0f)
            return frames
        }
        val regular = settle(listOf(1000.0 / 60), 1f)
        assertEquals("Ordinary frames keep the 220 ms settle", 220.0, regular.times.last(), 17.0)
        val stalled = settle(listOf(150.0) + List(40) { 1000.0 / 60 }, 1f)
        assertTrue("A slow release frame must not snap back: ${stalled.values}", stalled.values[1] > .3f * .8f)
        assertTrue(stalled.largestStep(.3f, 0f) < .25f)
        val fast = settle(listOf(1000.0 / 60), .5f)
        assertEquals("Half animation scale halves the settle", 110.0, fast.times.last(), 17.0)
        val off = settle(listOf(1000.0 / 60), 0f)
        assertEquals("Animations off settle on the first frame", 1, off.values.size)
    }

    /** #641 train × #560: the API 36 emulator manages ~5 fps under the pinch anchor loop. Capping every
     * frame at 60 Hz animation time stretched the 220 ms settle to 14 frames (2.6 s), which held the
     * header transaction past SubviewFlingPinchTimelineTest's 2.4 s retirement deadline. */
    @Test fun sustainedSlowFramesKeepTheSettleWithinAFewDisplayedFrames() {
        for (interval in listOf(100.0, 200.0, 400.0)) {
            val progress = BrowsePinchProgress(.3f)
            val frames = Frames(listOf(interval)) { progress.value }
            runBlocking(frames) { progress.animateTo(0f, tween(220)) }
            assertEquals(0f, progress.value, 0f)
            val displayed = frames.values.size
            assertTrue("A settle at $interval ms frames shows $displayed frames, more than ${PinchEaseMinimumFrames + 2}",
                displayed <= PinchEaseMinimumFrames + 2)
            assertTrue("A settle at $interval ms frames is still an ease, not a snap: ${frames.values}",
                displayed >= PinchEaseMinimumFrames + 1)
            assertTrue("The settle ends by ${(PinchEaseMinimumFrames + 1) * interval} ms, not ${frames.times.last()}",
                frames.times.last() <= (PinchEaseMinimumFrames + 1) * interval + 1)
            assertTrue("The first slow frame still cannot snap back: ${frames.values}", frames.values[1] > .3f * .8f)
            assertTrue("No frame closes more than half the gap: ${frames.largestStep(.3f, 0f)}", frames.largestStep(.3f, 0f) < .5f)
        }
    }

    /** PinchSlowFrameTest stalls two consecutive frames on the device; the later-frame floor must
     * still keep each frame under half the gap across the steep middle of the ease. */
    @Test fun twoConsecutiveSlowFramesCloseLessThanHalfTheGap() {
        for (stall in listOf(120.0, 150.0, 400.0)) {
            val frames = catchUp(listOf(stall, stall) + List(40) { 1000.0 / 120 })
            assertTrue("Two $stall ms frames: a frame closes ${frames.largestStep(0f, .3f)} of the catch-up: ${frames.values}",
                frames.largestStep(0f, .3f) < .5f)
            val progress = BrowsePinchProgress(.3f)
            val settle = Frames(listOf(stall, stall) + List(40) { 1000.0 / 60 }) { progress.value }
            runBlocking(settle) { progress.animateTo(0f, tween(220)) }
            assertTrue("Two $stall ms frames: a frame returns ${settle.largestStep(.3f, 0f)} of the settle: ${settle.values}",
                settle.largestStep(.3f, 0f) < .5f)
        }
    }
}
