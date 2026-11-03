package io.github.anastaci1a.localica

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import io.github.anastaci1a.localica.playback.StartupAudioOutput
import io.github.anastaci1a.localica.playback.StartupAudioOutputProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StartupAudioOutputTest {
    @Test fun smallFirstWritesWaitForPrefillWithoutChangingPcmOrWriteResults() = runBlocking {
        for (first in listOf(68, 448)) withContext(Dispatchers.Main) {
            val old = OutputProbe()
            val native = OutputProbe()
            val output = StartupAudioOutput(native, 2, 2205)
            try {
                val samples = ByteArray(2205 * 2) { (it % 127).toByte() }
                // The previous direct provider starts with only this first decoded buffer.
                assertTrue(old.write(ByteBuffer.wrap(samples.copyOfRange(0, first * 2)), 1, 0))
                old.play()
                assertEquals(listOf(first), old.framesAtPlay)
                assertTrue(output.write(ByteBuffer.wrap(samples.copyOfRange(0, first * 2)), 1, 0))
                output.play()
                assertTrue(native.framesAtPlay.isEmpty())
                assertTrue(output.write(ByteBuffer.wrap(samples.copyOfRange(first * 2, samples.size)), 1, 1000))
                assertEquals(listOf(2205), native.framesAtPlay)
                assertArrayEquals(samples, native.samples.toByteArray())
                assertEquals(listOf(0L, 1000L), native.timestamps)
            } finally { output.release(); old.release() }
        }
    }

    @Test fun pauseFlushAndReleaseCancelPendingStartsAndResumeRefills() = fixture {
        onMain {
            output.write(pcm(68), 1, 0)
            output.play()
            output.pause()
        }
        delay(80)
        onMain {
            assertTrue(native.framesAtPlay.isEmpty())
            native.drain()
            output.play()
            output.write(pcm(68), 1, 0)
            assertTrue(native.framesAtPlay.isEmpty())
            output.write(pcm(2205 - 68), 1, 0)
            assertEquals(listOf(2205), native.framesAtPlay)
            output.pause()
            native.drain()
            output.play()
            output.flush()
        }
        delay(80)
        onMain {
            assertEquals(1, native.framesAtPlay.size)
            output.play()
            output.release()
            released = true
        }
        delay(80)
        onMain { assertEquals(1, native.framesAtPlay.size) }
    }

    @Test fun fullRetainedBufferCanResumeWithoutWaitingForMoreSpace() = fixture(capacity = 1000, target = 1000) {
        onMain {
            assertTrue(output.write(pcm(1000), 1, 0))
            output.play()
            output.pause()
            output.play()
            assertEquals(1, native.framesAtPlay.size)
            val next = pcm(20)
            assertFalse(output.write(next, 1, 1000))
            assertEquals(0, next.position())
            assertEquals(listOf(1000, 1000), native.framesAtPlay)
            native.drain()
            assertTrue(output.write(next, 1, 1000))
            assertEquals(40, next.position())
        }
    }

    @Test fun deadlineAndShortTailCannotHoldPlaybackIndefinitely() = fixture {
        onMain {
            output.write(pcm(68), 1, 0)
            output.play()
            assertTrue(native.framesAtPlay.isEmpty())
        }
        withTimeout(2000) { native.playAttempt.await() }
        onMain {
            assertEquals(listOf(68), native.framesAtPlay)
            output.flush()
            output.write(pcm(2), 1, 0)
            output.play()
            assertEquals(1, native.framesAtPlay.size)
            output.stop()
            assertEquals(listOf(68, 2), native.framesAtPlay)
            assertEquals(listOf("play", "stop"), native.events.takeLast(2))
        }
    }

    @Test fun asynchronousStartFailureReachesTheNextRendererPollWithoutLosingCleanup() = fixture {
        val failure = IllegalStateException("Controlled native start failure")
        onMain { native.playFailure = failure; output.play() }
        withTimeout(2000) { native.playAttempt.await() }
        onMain {
            assertSame(failure, runCatching { output.positionUs }.exceptionOrNull())
            assertSame(failure, runCatching { output.write(pcm(1), 1, 0) }.exceptionOrNull())
            output.release()
            released = true
            assertEquals("release", native.events.last())
        }
    }

    @Test fun providerRetainsEncodedOffloadedAndTunneledOwnershipAndCapsPcmTarget() = runBlocking {
        withContext(Dispatchers.Main) {
            val native = OutputProbe(capacity = 500)
            var received: AudioOutputProvider.OutputConfig? = null
            val provider = object : AudioOutputProvider {
                override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput { received = config; return native }
                override fun getFormatSupport(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport = error("Unexpected format query")
                override fun getOutputConfig(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig = error("Unexpected configuration query")
                override fun addListener(listener: AudioOutputProvider.Listener) = Unit
                override fun removeListener(listener: AudioOutputProvider.Listener) = Unit
                override fun release() = Unit
            }
            val wrapped = StartupAudioOutputProvider(provider)
            val configPcm = AudioOutputProvider.OutputConfig.Builder().setEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setBufferSize(1000).build()
            for (config in listOf(configPcm.buildUpon().setEncoding(C.ENCODING_AC3).build(),
                configPcm.buildUpon().setIsOffload(true).build(), configPcm.buildUpon().setIsTunneling(true).build())) {
                assertSame(native, wrapped.getAudioOutput(config))
                assertSame(config, received)
            }
            val output = wrapped.getAudioOutput(configPcm)
            try {
                assertNotSame(native, output)
                assertSame(configPcm, received)
                output.play()
                assertTrue(output.write(pcm(500), 1, 0))
                assertEquals(listOf(500), native.framesAtPlay)
            } finally { output.release() }
        }
    }

    private class Fixture(capacity: Int, target: Long) {
        val native = OutputProbe(capacity)
        val output = StartupAudioOutput(native, 2, target)
        var released = false
        suspend fun onMain(body: Fixture.() -> Unit) = withContext(Dispatchers.Main) { body() }
    }
    private fun fixture(capacity: Int = 4096, target: Long = 2205, body: suspend Fixture.() -> Unit) = runBlocking {
        val f = withContext(Dispatchers.Main) { Fixture(capacity, target) }
        try { f.body() } finally { withContext(NonCancellable + Dispatchers.Main) { if (!f.released) f.output.release() } }
    }
    private fun pcm(frames: Int) = ByteBuffer.wrap(ByteArray(frames * 2) { (it % 127).toByte() })
}

/** Native boundary model: finite capacity and exact consumed bytes, with no automatic draining. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class OutputProbe(private val capacity: Int = 4096) : AudioOutput {
    val samples = ByteArrayOutputStream()
    val timestamps = mutableListOf<Long>()
    val framesAtPlay = mutableListOf<Int>()
    val events = mutableListOf<String>()
    val playAttempt = CompletableDeferred<Unit>()
    var playFailure: RuntimeException? = null
    private var queuedBytes = 0
    fun drain() { queuedBytes = 0 }
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val bytes = ByteArray(minOf(buffer.remaining(), capacity * 2 - queuedBytes))
        buffer.get(bytes); samples.write(bytes); queuedBytes += bytes.size
        timestamps += presentationTimeUs
        return !buffer.hasRemaining()
    }
    override fun play() {
        playAttempt.complete(Unit)
        playFailure?.let { throw it }
        framesAtPlay += queuedBytes / 2; events += "play"
    }
    override fun pause() { events += "pause" }
    override fun flush() { queuedBytes = 0; events += "flush" }
    override fun stop() { events += "stop" }
    override fun release() { events += "release" }
    override fun getBufferSizeInFrames() = capacity.toLong()
    override fun getPositionUs() = 0L
    override fun getSampleRate() = 44100
    override fun getAudioSessionId() = 1
    override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
    override fun isOffloadedPlayback() = false
    override fun isStalled() = false
    override fun addListener(listener: AudioOutput.Listener) = Unit
    override fun removeListener(listener: AudioOutput.Listener) = Unit
    override fun setPlaybackParameters(parameters: PlaybackParameters) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun setOffloadDelayPadding(delay: Int, padding: Int) = Unit
    override fun setOffloadEndOfStream() = Unit
    override fun attachAuxEffect(effectId: Int) = Unit
    override fun setAuxEffectSendLevel(level: Float) = Unit
    override fun setPreferredDevice(device: AudioDeviceInfo?) = Unit
}
