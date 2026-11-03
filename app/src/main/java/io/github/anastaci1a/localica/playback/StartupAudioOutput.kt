package io.github.anastaci1a.localica.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer

/** Small decoded buffers need not become the entire native startup runway. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StartupAudioOutputProvider(private val native: AudioOutputProvider) : AudioOutputProvider by native {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = native.getAudioOutput(config)
        // Encoded, offloaded and tunneled output retain their existing start protocol.
        if (!Util.isEncodingLinearPcm(config.encoding) || config.isOffload || config.isTunneling) return output
        return StartupAudioOutput(output,
            Util.getPcmFrameSize(config.encoding, Integer.bitCount(config.channelMask)),
            minOf(output.bufferSizeInFrames, config.sampleRate / 20L).coerceAtLeast(1L))
    }
}

/** Playback-looper owned. Keep PCM/write results exact; defer only native play, for at most one 30ms timer. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StartupAudioOutput(
    private val output: AudioOutput,
    private val frameSize: Int,
    private val targetFrames: Long,
    private val handler: Handler = Handler(requireNotNull(Looper.myLooper())),
) : AudioOutput by output {
    private var acceptedBytes = 0L
    private var pending = false
    private var running = false
    private var asynchronousFailure: RuntimeException? = null
    private val deadline = Runnable {
        if (pending) try {
            start()
        } catch (failure: RuntimeException) {
            // A Runnable is outside ExoPlayer's renderer exception boundary. Deliver the
            // original error through its next position/write poll instead of crashing this Looper.
            asynchronousFailure = failure
        }
    }

    private fun checkFailure() { asynchronousFailure?.let { throw it } }
    private fun cancelPending() { pending = false; handler.removeCallbacks(deadline) }
    private fun start() {
        cancelPending()
        output.play()
        running = true
    }

    override fun play() {
        checkFailure()
        if (running || acceptedBytes / frameSize >= targetFrames) start()
        else if (!pending) {
            pending = true
            // A stopped Looper cannot service a deadline; preserve immediate playback there.
            if (!handler.postDelayed(deadline, 30)) start()
        }
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        checkFailure()
        val before = buffer.position()
        val result = output.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        acceptedBytes += buffer.position() - before
        // On resume the retained native buffer can already be full. Start on backpressure
        // rather than wait for more data that cannot fit or estimate a hardware playback head.
        if (pending && (acceptedBytes / frameSize >= targetFrames || (!result && buffer.hasRemaining()))) start()
        return result
    }

    override fun getPositionUs(): Long { checkFailure(); return output.positionUs }
    override fun pause() {
        cancelPending()
        running = false
        acceptedBytes = 0
        output.pause()
    }
    override fun flush() {
        cancelPending()
        running = false
        acceptedBytes = 0
        asynchronousFailure = null
        output.flush()
    }
    override fun stop() {
        // End-of-stream may contain fewer than the target frames. Drain that exact tail now.
        if (pending) start()
        running = false
        acceptedBytes = 0
        output.stop()
    }
    override fun release() {
        cancelPending()
        running = false
        output.release()
    }
}
