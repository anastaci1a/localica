package io.github.anastaci1a.localica.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BoundaryDeclickAudioProcessorTest {
    private val format = AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)
    private fun pcm(level: Int, frames: Int = 64) = ShortArray(frames) { level.toShort() }
    private fun start(processor: BoundaryDeclickAudioProcessor) {
        processor.nextStream()
        processor.configure(format)
        processor.flush()
    }
    private fun feed(processor: BoundaryDeclickAudioProcessor, samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        assertFalse(input.hasRemaining())
        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(output.remaining()).also { output.get(it) }
    }
    private fun expected(tail: ShortArray, head: ShortArray): ShortArray {
        val f = BoundaryDeclick.Pcm16Format(48_000, 1)
        return BoundaryDeclick.plan(tail, head, f, f, true).also { assertTrue(it.applied) }.output
    }

    @Test fun nextNotificationDoesNotStealOldTailBeforeDrainAndFlush() {
        val p = BoundaryDeclickAudioProcessor { true }
        start(p)
        assertArrayEquals(pcm(800), feed(p, pcm(800)))
        p.nextStream()
        assertArrayEquals(pcm(1000, 8), feed(p, pcm(1000, 8)))
        p.queueEndOfStream()
        p.flush()
        assertArrayEquals(expected(pcm(1000, 8), pcm(1200)), feed(p, pcm(1200)))
        assertArrayEquals(pcm(1400), feed(p, pcm(1400)))
    }

    @Test fun repeatedDrainAndFlushBeforeFirstInputPreservesOnePendingJoin() {
        val p = BoundaryDeclickAudioProcessor { true }
        start(p)
        feed(p, pcm(1000))
        p.nextStream()
        p.queueEndOfStream()
        repeat(2) { p.flush(); p.queueEndOfStream() }
        p.flush()
        assertArrayEquals(shortArrayOf(), feed(p, shortArrayOf()))
        assertArrayEquals(expected(pcm(1000, 8), pcm(1200)), feed(p, pcm(1200)))
    }

    @Test fun defaultOffAndContinuousJoinsRemainExactAndRetainFrameCount() {
        for (enabled in listOf(false, true)) {
            val p = BoundaryDeclickAudioProcessor { enabled }
            start(p)
            assertArrayEquals(pcm(1000), feed(p, pcm(1000)))
            p.nextStream(); p.queueEndOfStream(); p.flush()
            val head = pcm(if (enabled) 1000 else 1200)
            assertArrayEquals(head, feed(p, head))
        }
    }

    @Test fun explicitResetAndUnannouncedFlushCannotInventContinuation() {
        for (explicit in listOf(false, true)) {
            val p = BoundaryDeclickAudioProcessor { true }
            start(p); feed(p, pcm(1000)); p.queueEndOfStream()
            if (explicit) p.resetContinuity() else p.flush()
            start(p)
            assertArrayEquals(pcm(1200), feed(p, pcm(1200)))
        }
    }

    @Test fun missingDrainAndDuplicateNotificationsFailClosed() {
        for (duplicate in listOf(false, true)) {
            val p = BoundaryDeclickAudioProcessor { true }
            start(p); feed(p, pcm(1000)); p.nextStream()
            if (duplicate) { p.queueEndOfStream(); p.nextStream() }
            p.flush()
            assertArrayEquals(pcm(1200), feed(p, pcm(1200)))
        }
    }
}
