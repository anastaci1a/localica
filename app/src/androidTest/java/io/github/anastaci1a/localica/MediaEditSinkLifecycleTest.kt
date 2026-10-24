package io.github.anastaci1a.localica

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.playback.MediaEditAudioSink
import io.github.anastaci1a.localica.playback.MediaEditTrim
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Actual pinned sink/processor lifecycle with synthetic PCM only. Capture follows native trimming;
 * these checks do not establish extractor/codec marker propagation, acoustic output or underruns.
 * Native AudioTrack volume is zero; captured nonzero samples are never modified or filtered.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class MediaEditSinkLifecycleTest {
    private class Capture : BaseAudioProcessor() {
        private val bytes = ByteArrayOutputStream()
        override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 1)
                throw UnhandledAudioFormatException(inputAudioFormat)
            return inputAudioFormat
        }
        override fun queueInput(inputBuffer: ByteBuffer) {
            val input = ByteArray(inputBuffer.remaining())
            inputBuffer.get(input)
            bytes.write(input)
            replaceOutputBuffer(input.size).put(input).flip()
        }
        fun snapshot(): ShortArray {
            val data = bytes.toByteArray()
            check(data.size % 2 == 0)
            val shorts = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return ShortArray(shorts.remaining()).also { shorts.get(it) }
        }
    }

    private class Harness : Closeable {
        private val thread = HandlerThread("media-edit-sink-test").apply { start() }
        private val handler = Handler(thread.looper)
        private val capture = Capture()
        private var offsetUs = 0L
        // Accessed only on the sink Looper; callbacks belong to this sink, not other tests.
        private var initializedTracks = 0
        private var releasedTracks = 0
        private var closing = false
        private val releasesComplete = CountDownLatch(1)
        private val sink = onSink {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            MediaEditAudioSink(DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(capture)).build())
                .also { sink ->
                    sink.setListener(object : AudioSink.Listener {
                        override fun onPositionDiscontinuity() {}
                        override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {}
                        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {}
                        override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) {
                            initializedTracks++
                        }
                        override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
                            releasedTracks++
                            quitAfterOwnedReleases()
                        }
                    })
                    sink.setVolume(0f); sink.setSkipSilenceEnabled(false); sink.play()
                }
        }
        private fun <T> onSink(timeoutMs: Long = 15_000, block: () -> T): T {
            val task = FutureTask(Callable { block() })
            check(handler.post(task))
            return task.get(timeoutMs, TimeUnit.MILLISECONDS)
        }
        fun configure(rate: Int = 44100, marker: Any? = MediaEditTrim(44100, 1600)) = onSink {
            val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelCount(1)
                .setCustomData(marker).build()
            sink.configure(format, 0, null)
        }
        fun streamOffset(value: Long) = onSink {
            offsetUs = value
            sink.setOutputStreamOffsetUs(value)
            sink.handleDiscontinuity()
        }
        fun flush() = onSink { sink.flush() }
        private fun remaining(deadline: Long, message: String): Long {
            val millis = deadline - SystemClock.elapsedRealtime()
            assertTrue(message, millis > 0)
            return millis
        }
        fun feed(samples: ShortArray, positionUs: Long) {
            val data = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { data.putShort(it) }
            data.flip()
            val timestampUs = onSink { offsetUs + positionUs }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            val message = "Native sink must consume this exact buffer within 5s"
            // One native attempt per dispatch lets asynchronous release callbacks run between attempts.
            while (!onSink(remaining(deadline, message)) { sink.handleBuffer(data, timestampUs, 1) }) {
                remaining(deadline, message)
                Thread.sleep(2)
            }
            remaining(deadline, message)
            assertFalse("A handled buffer is completely consumed", data.hasRemaining())
        }
        fun finishAndRead(): ShortArray {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            val message = "Native processor/output drain must finish within 5s"
            while (true) {
                onSink(remaining(deadline, message)) { sink.playToEndOfStream() }
                val ended = onSink(remaining(deadline, message)) { sink.isEnded }
                remaining(deadline, message)
                if (ended) break
                Thread.sleep(2)
            }
            return onSink { capture.snapshot() }
        }
        // Called only on the sink Looper. Even if the outer close deadline fails, keep it
        // alive for outstanding callbacks, then quit automatically after the last owned release.
        private fun quitAfterOwnedReleases() {
            if (closing && initializedTracks == releasedTracks) {
                releasesComplete.countDown()
                thread.quitSafely()
            }
        }
        override fun close() {
            try {
                onSink {
                    try { sink.reset() }
                    finally {
                        try { sink.release() }
                        finally { closing = true; quitAfterOwnedReleases() }
                    }
                }
            } finally {
                // Public callbacks cover earlier flushes as well as the final reset.
                assertTrue("Every owned AudioTrack must finish releasing before quitting its Looper",
                    releasesComplete.await(3_000, TimeUnit.MILLISECONDS))
                thread.join(3_000)
                assertFalse("The released harness Looper must terminate", thread.isAlive)
            }
        }
    }

    private fun ramp(count: Int, seed: Int): ShortArray = ShortArray(count) { (seed + it + 1).toShort() }
    private fun pts(frame: Int, rate: Int = 44100) = frame * 1_000_000L / rate
    private fun tail(samples: ShortArray, drop: Int) = samples.copyOfRange(drop, samples.size)

    @Test fun preDataReconfigureBindsDoubleRateAndPreservesEveryTailSample() {
        Harness().use { h ->
            val edit = MediaEditTrim(44100, 1600)
            h.streamOffset(4_000_000)
            h.configure(44100, edit)
            h.configure(88200, edit)
            val input = ramp(3607, 100)
            h.feed(input, 0)
            assertArrayEquals(tail(input, 3200), h.finishAndRead())
        }
    }

    @Test fun halfRateUsesEightHundredFramesAndRetainsTheExactTail() {
        Harness().use { h ->
            h.configure(22050)
            val input = ramp(1207, 200)
            h.feed(input, 0)
            assertArrayEquals(tail(input, 800), h.finishAndRead())
        }
    }

    @Test fun repeatedConfigureDuringPartialTrimDropsOnlyTheRemainingFrames() {
        Harness().use { h ->
            val edit = MediaEditTrim(44100, 1600)
            h.streamOffset(2_000_000)
            h.configure(44100, edit)
            h.feed(ramp(1024, 100), 0)
            h.configure(44100, edit)
            val rest = ramp(1027, 5000)
            h.feed(rest, pts(1024))
            assertArrayEquals(tail(rest, 576), h.finishAndRead())
        }
    }

    @Test fun actualRateChangeDuringPartialTrimUsesRemainingTimeAtTheNewRate() {
        Harness().use { h ->
            val edit = MediaEditTrim(44100, 1600)
            h.configure(44100, edit)
            h.feed(ramp(1024, 100), 0)
            h.configure(88200, edit)
            val rest = ramp(1607, 7000)
            h.feed(rest, pts(1024))
            assertArrayEquals(tail(rest, 1152), h.finishAndRead())
        }
    }

    @Test fun actualRateChangeAfterCompletedTrimCannotCutASecondLeadingRegion() {
        Harness().use { h ->
            val edit = MediaEditTrim(44100, 1600)
            h.configure(44100, edit)
            val first = ramp(2048, 100)
            h.feed(first, 0)
            h.configure(88200, edit)
            val rest = ramp(307, 9000)
            h.feed(rest, pts(2048))
            assertArrayEquals(tail(first, 1600) + rest, h.finishAndRead())
        }
    }

    @Test fun sameRateReconfigureAfterCompletedTrimPreservesAllFollowingFrames() {
        Harness().use { h ->
            val edit = MediaEditTrim(44100, 1600)
            h.configure(44100, edit)
            val first = ramp(2000, 100)
            h.feed(first, 0)
            h.configure(44100, edit)
            val rest = ramp(137, 11000)
            h.feed(rest, pts(2000))
            assertArrayEquals(tail(first, 1600) + rest, h.finishAndRead())
        }
    }

    @Test fun flushSeeksToMiddleZeroAndPartialEditHaveExactLeadingAndTrailingSamples() {
        Harness().use { h ->
            h.streamOffset(3_000_000)
            h.configure()
            val initial = ramp(2000, 100)
            h.feed(initial, 0)
            h.flush()
            val middle = ramp(99, 5000)
            h.feed(middle, 1_000_000)
            h.flush()
            val zero = ramp(1700, 10000)
            h.feed(zero, 0)
            h.flush()
            val partial = ramp(701, 14000)
            h.feed(partial, pts(1024))
            assertArrayEquals(tail(initial, 1600) + middle + tail(zero, 1600) + tail(partial, 576), h.finishAndRead())
        }
    }

    @Test fun markedToForeignUnmarkedTransitionCannotLeakTheOldLeadingEdit() {
        Harness().use { h ->
            h.configure()
            val first = ramp(2000, 100)
            h.feed(first, 0)
            h.streamOffset(2_000_000)
            h.configure(marker = Any())
            val plain = ramp(177, 9000)
            h.feed(plain, 0)
            assertArrayEquals(tail(first, 1600) + plain, h.finishAndRead())
        }
    }

    @Test fun identicalRateStreamsHonorDifferentMarkersAndEqualValuedFreshIdentities() {
        Harness().use { h ->
            val firstMarker = MediaEditTrim(44100, 1600)
            val secondMarker = MediaEditTrim(44100, 800)
            val thirdMarker = MediaEditTrim(44100, 800)
            assertNotSame(secondMarker, thirdMarker)
            h.configure(marker = firstMarker)
            val first = ramp(2003, 100)
            h.feed(first, 0)
            h.streamOffset(1_000_000)
            h.configure(marker = secondMarker)
            val second = ramp(1007, 6000)
            h.feed(second, 0)
            h.streamOffset(2_000_000)
            h.configure(marker = thirdMarker)
            val third = ramp(1109, 10000)
            h.feed(third, 0)
            assertArrayEquals(tail(first, 1600) + tail(second, 800) + tail(third, 800), h.finishAndRead())
        }
    }

    @Test fun emptyZeroPtsThenRealMiddlePcmRetainsEverySampleAndTheExactTail() {
        Harness().use { h ->
            h.streamOffset(5_000_000)
            h.configure()
            h.feed(shortArrayOf(), 0)
            val middle = ramp(137, 5000)
            h.feed(middle, 1_000_000)
            assertArrayEquals(middle, h.finishAndRead())
        }
    }

    @Test fun emptyMiddlePtsThenRealZeroPcmAppliesTheFullLeadingEditAndKeepsTheTail() {
        Harness().use { h ->
            h.streamOffset(5_000_000)
            h.configure()
            h.feed(shortArrayOf(), 1_000_000)
            val beginning = ramp(1707, 10000)
            h.feed(beginning, 0)
            assertArrayEquals(tail(beginning, 1600), h.finishAndRead())
        }
    }
}
