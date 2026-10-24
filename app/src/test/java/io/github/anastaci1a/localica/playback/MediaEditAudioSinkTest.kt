package io.github.anastaci1a.localica.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/** Forwarding/binding contract only; actual trimming is covered by MediaEditSinkLifecycleTest. */
@androidx.annotation.OptIn(UnstableApi::class)
class MediaEditAudioSinkTest {
    private data class Configuration(val format: Format, val size: Int, val channels: IntArray?)
    private data class BufferCall(val buffer: ByteBuffer, val pts: Long, val accessUnits: Int)

    private class Spy {
        val configurations = mutableListOf<Configuration>()
        val buffers = mutableListOf<BufferCall>()
        val capabilityQueries = mutableListOf<Pair<String, Format>>()
        val offsets = mutableListOf<Long>()
        val lifecycle = mutableListOf<String>()
        val offload = AudioOffloadSupport.Builder().setIsFormatSupported(true).build()
        var configureFailure: AudioSink.ConfigurationException? = null
        var acceptBuffer = true
        val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            val arguments = args ?: emptyArray()
            when (method.name) {
                "supportsFormat" -> { capabilityQueries += method.name to arguments[0] as Format; true }
                "getFormatSupport" -> { capabilityQueries += method.name to arguments[0] as Format; AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY }
                "getFormatOffloadSupport" -> { capabilityQueries += method.name to arguments[0] as Format; offload }
                "configure" -> {
                    configureFailure?.let { throw it }
                    configurations += Configuration(arguments[0] as Format, arguments[1] as Int, arguments[2] as IntArray?)
                    null
                }
                "handleBuffer" -> {
                    val buffer = arguments[0] as ByteBuffer
                    buffers += BufferCall(buffer, arguments[1] as Long, arguments[2] as Int)
                    if (acceptBuffer) buffer.position(buffer.limit())
                    acceptBuffer
                }
                "setOutputStreamOffsetUs" -> { offsets += arguments[0] as Long; null }
                "flush", "reset", "release" -> { lifecycle += method.name; null }
                else -> throw AssertionError("Unexpected delegated method: ${method.name}")
            }
        } as AudioSink
    }

    private fun raw(rate: Int = 44100, marker: Any? = MediaEditTrim(44100, 1600)) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(rate).setChannelCount(2).setEncoderDelay(123).setEncoderPadding(9)
        .setCustomData(marker).build()

    private fun buffer() = ByteBuffer.allocateDirect(16)

    private fun assertDelay(spy: Spy, frames: Int, rate: Int) {
        val bound = spy.configurations.last().format
        assertEquals(rate, bound.sampleRate)
        assertEquals(frames, bound.encoderDelay)
        assertEquals(0, bound.encoderPadding)
    }

    @Test fun markedEncodedFormatsRejectEveryDirectCapabilityAndConfigure() {
        val spy = Spy()
        val sink = MediaEditAudioSink(spy.delegate)
        val encoded = raw().buildUpon().setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        assertFalse(sink.supportsFormat(encoded))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(encoded))
        assertSame(AudioOffloadSupport.DEFAULT_UNSUPPORTED, sink.getFormatOffloadSupport(encoded))
        assertTrue(spy.capabilityQueries.isEmpty())
        val failure = assertThrows(AudioSink.ConfigurationException::class.java) { sink.configure(encoded, 0, null) }
        assertSame(encoded, failure.format)
        assertTrue(spy.configurations.isEmpty())
    }

    @Test fun unmarkedAndForeignFormatsForwardIdentityArgumentsAndCapabilities() {
        for (marker in listOf(null, Any())) for (mime in listOf(MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_AAC)) {
            val spy = Spy()
            val sink = MediaEditAudioSink(spy.delegate)
            val format = raw(marker = marker).buildUpon().setSampleMimeType(mime).build()
            val channels = intArrayOf(1, 0)
            assertTrue(sink.supportsFormat(format))
            assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(format))
            assertSame(spy.offload, sink.getFormatOffloadSupport(format))
            assertEquals(listOf("supportsFormat", "getFormatSupport", "getFormatOffloadSupport"), spy.capabilityQueries.map { it.first })
            spy.capabilityQueries.forEach { assertSame(format, it.second) }
            sink.configure(format, 4096, channels)
            assertSame(format, spy.configurations.single().format)
            assertSame(channels, spy.configurations.single().channels)
            assertEquals(4096, spy.configurations.single().size)
            val data = buffer()
            assertTrue(sink.handleBuffer(data, 50_000, 7))
            assertSame(data, spy.buffers.single().buffer)
            assertEquals(50_000L, spy.buffers.single().pts)
            assertEquals(7, spy.buffers.single().accessUnits)
            assertEquals(1, spy.configurations.size)
        }
    }

    @Test fun firstBufferBindsExactDoubleHalfAndFractionalRatesWithStreamOffset() {
        for ((rate, delay) in listOf(44100 to 1600, 88200 to 3200, 22050 to 800, 48000 to 1741)) {
            val spy = Spy()
            val sink = MediaEditAudioSink(spy.delegate)
            val format = raw(rate)
            val channels = intArrayOf(1, 0)
            sink.setOutputStreamOffsetUs(5_000_000)
            sink.configure(format, 2048, channels)
            assertDelay(spy, 0, rate)
            channels[0] = 0 // The deferred configuration must retain its own channel-map copy.
            sink.handleBuffer(buffer(), 5_000_000, 1)
            assertDelay(spy, delay, rate)
            assertSame(format.customData, spy.configurations.last().format.customData)
            assertArrayEquals(intArrayOf(1, 0), spy.configurations.last().channels)
            assertEquals(2048, spy.configurations.last().size)
            assertEquals(listOf(5_000_000L), spy.offsets)
        }
    }

    @Test fun configureBeforeDataUsesOnlyLatestRateAndBackpressureDoesNotRebind() {
        val spy = Spy()
        val sink = MediaEditAudioSink(spy.delegate)
        sink.configure(raw(44100), 0, null)
        sink.configure(raw(88200), 0, null)
        assertTrue(spy.configurations.all { it.format.encoderDelay == 0 })
        val data = buffer()
        spy.acceptBuffer = false
        assertFalse(sink.handleBuffer(data, 0, 1))
        assertDelay(spy, 3200, 88200)
        val count = spy.configurations.size
        spy.acceptBuffer = true
        assertTrue(sink.handleBuffer(data, 0, 1))
        assertEquals(count, spy.configurations.size)
        assertSame(data, spy.buffers[0].buffer)
        assertSame(data, spy.buffers[1].buffer)
    }

    @Test fun partialAndCompletedTrimReconfigureFromPtsRatherThanRestartingTheEdit() {
        val spy = Spy()
        val sink = MediaEditAudioSink(spy.delegate)
        val marker = MediaEditTrim(44100, 1600)
        sink.setOutputStreamOffsetUs(1_000_000)
        sink.configure(raw(44100, marker), 0, null)
        sink.handleBuffer(buffer(), 1_000_000, 1)
        assertDelay(spy, 1600, 44100)
        sink.configure(raw(88200, marker), 0, null)
        sink.handleBuffer(buffer(), 1_023_219, 1) // 1024 old-rate frames; 2048 new-rate frames elapsed.
        assertDelay(spy, 1152, 88200)
        sink.configure(raw(22050, marker), 0, null)
        sink.handleBuffer(buffer(), 1_046_439, 1)
        assertDelay(spy, 0, 22050)
    }

    @Test fun flushSeekZeroMiddleAndPartialRecomputeAgainstUnchangedStreamOffset() {
        val spy = Spy()
        val sink = MediaEditAudioSink(spy.delegate)
        sink.setOutputStreamOffsetUs(2_000_000)
        sink.configure(raw(), 0, null)
        sink.handleBuffer(buffer(), 2_000_000, 1)
        for ((pts, delay) in listOf(3_000_000L to 0, 2_000_000L to 1600, 2_023_219L to 576)) {
            sink.flush()
            sink.handleBuffer(buffer(), pts, 1)
            assertDelay(spy, delay, 44100)
        }
        assertEquals(listOf("flush", "flush", "flush"), spy.lifecycle)
    }

    @Test fun newMarkersAndUnmarkedTransitionsDoNotRetainPreviousBinding() {
        val spy = Spy()
        val sink = MediaEditAudioSink(spy.delegate)
        val first = MediaEditTrim(44100, 1600)
        val second = MediaEditTrim(44100, 800)
        sink.configure(raw(marker = first), 0, null)
        sink.handleBuffer(buffer(), 0, 1)
        sink.setOutputStreamOffsetUs(9_000_000)
        sink.configure(raw(marker = second), 0, null)
        sink.handleBuffer(buffer(), 9_000_000, 1)
        assertDelay(spy, 800, 44100)
        assertSame(second, spy.configurations.last().format.customData)
        val foreign = raw(marker = Any())
        sink.configure(foreign, 42, null)
        val count = spy.configurations.size
        sink.flush()
        sink.handleBuffer(buffer(), 9_000_000, 1)
        assertEquals(count, spy.configurations.size)
        assertSame(foreign, spy.configurations.last().format)
        sink.reset()
        val plain = raw(marker = null)
        sink.configure(plain, 0, null)
        sink.handleBuffer(buffer(), 0, 1)
        assertSame(plain, spy.configurations.last().format)
        assertEquals(count + 1, spy.configurations.size)
        sink.release()
        assertEquals(listOf("flush", "reset", "release"), spy.lifecycle)
    }

    @Test fun malformedConfigureAndDeferredFailuresUseTheAudioSinkExceptionContracts() {
        val invalid = listOf(raw(0), raw(-1), raw(1_000_001), raw().buildUpon().setChannelCount(0).build(),
            raw().buildUpon().setChannelCount(-1).build(), raw(marker = MediaEditTrim(0, 1)),
            raw(marker = MediaEditTrim(44100, -1)), raw(marker = MediaEditTrim(1, Long.MAX_VALUE)),
            raw().buildUpon().setPcmEncoding(C.ENCODING_INVALID).build())
        for (format in invalid) {
            val spy = Spy()
            val sink = MediaEditAudioSink(spy.delegate)
            val error = assertThrows(AudioSink.ConfigurationException::class.java) { sink.configure(format, 0, null) }
            assertSame(format, error.format)
            assertTrue(spy.configurations.isEmpty())
        }
        val spy = Spy()
        val sink = MediaEditAudioSink(spy.delegate)
        val format = raw()
        sink.configure(format, 0, null)
        val rejection = AudioSink.ConfigurationException("injected deferred failure", format)
        spy.configureFailure = rejection
        val error = assertThrows(AudioSink.WriteException::class.java) { sink.handleBuffer(buffer(), 0, 1) }
        assertSame(format, error.format)
        assertSame(rejection, error.cause)
        assertFalse(error.isRecoverable)
        assertTrue(spy.buffers.isEmpty())
    }

    @Test fun unmarkedConfigureBeforeFirstDataCancelsThePendingMarkedBinding() {
        val spy = Spy()
        val sink = MediaEditAudioSink(spy.delegate)
        sink.configure(raw(), 0, null)
        val unmarked = raw(marker = null)
        sink.configure(unmarked, 19, null)
        val count = spy.configurations.size
        sink.handleBuffer(buffer(), 0, 1)
        assertEquals(count, spy.configurations.size)
        assertSame(unmarked, spy.configurations.last().format)
        assertEquals(123, spy.configurations.last().format.encoderDelay)
        assertEquals(9, spy.configurations.last().format.encoderPadding)
    }

    @Test fun overflowingPtsOffsetSubtractionCannotForwardAnUnboundBuffer() {
        val spy = Spy()
        val sink = MediaEditAudioSink(spy.delegate)
        val format = raw()
        sink.configure(format, 0, null)
        sink.setOutputStreamOffsetUs(Long.MIN_VALUE)
        val error = assertThrows(AudioSink.WriteException::class.java) { sink.handleBuffer(buffer(), 0, 1) }
        assertTrue(error.cause is ArithmeticException)
        assertTrue(spy.buffers.isEmpty())
    }

    @Test fun emptyZeroPtsLeavesPendingForTheFirstRealMiddleBuffer() {
        val spy = Spy()
        val sink = MediaEditAudioSink(spy.delegate)
        sink.configure(raw(), 0, null)
        val prepared = spy.configurations.size
        assertTrue(sink.handleBuffer(ByteBuffer.allocateDirect(0), 0, 1))
        assertEquals("Empty PCM must not bind the pending edit", prepared, spy.configurations.size)
        assertTrue("Empty pending PCM must not forward a phantom native timestamp", spy.buffers.isEmpty())
        val real = buffer()
        assertTrue(sink.handleBuffer(real, 1_000_000, 1))
        assertEquals(prepared + 1, spy.configurations.size)
        assertDelay(spy, 0, 44100)
        assertSame(real, spy.buffers.single().buffer)
        assertEquals(1_000_000L, spy.buffers.single().pts)
        val emptyAfterBinding = ByteBuffer.allocateDirect(0)
        assertTrue(sink.handleBuffer(emptyAfterBinding, 2_000_000, 1))
        assertSame("An otherwise empty buffer retains native forwarding", emptyAfterBinding, spy.buffers.last().buffer)
        assertEquals(prepared + 1, spy.configurations.size)
    }

    @Test fun emptyMiddlePtsLeavesPendingForTheFirstRealZeroBuffer() {
        val spy = Spy()
        val sink = MediaEditAudioSink(spy.delegate)
        sink.configure(raw(), 0, null)
        val prepared = spy.configurations.size
        assertTrue(sink.handleBuffer(ByteBuffer.allocateDirect(0), 1_000_000, 1))
        assertEquals("Empty PCM must not consume the pending full leading edit", prepared, spy.configurations.size)
        assertTrue("Empty pending PCM must not reach the native sink", spy.buffers.isEmpty())
        val real = buffer()
        assertTrue(sink.handleBuffer(real, 0, 1))
        assertEquals(prepared + 1, spy.configurations.size)
        assertDelay(spy, 1600, 44100)
        assertSame(real, spy.buffers.single().buffer)
        assertEquals(0L, spy.buffers.single().pts)
    }
}
