package io.github.anastaci1a.localica

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.util.Util
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import java.io.OutputStream
import java.nio.ByteBuffer

/** Opt-in private diagnosis. Accepted bytes precede physical playout; file I/O perturbs timing. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PrivateBoundaryOutputProvider(
    context: Context,
    private val event: (String, Array<out Pair<String, Any?>>) -> Unit,
    private val accepted: OutputStream,
    private val native: AudioOutputProvider = AudioTrackAudioOutputProvider.Builder(context).build(),
) : AudioOutputProvider by native {
    private var nextId = 0
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = native.getAudioOutput(config)
        val id = nextId++
        return object : AudioOutput by output {
            private var writtenBytes = 0L
            private val frameSize = Util.getPcmFrameSize(config.encoding, Integer.bitCount(config.channelMask))
            private fun emit(kind: String, vararg fields: Pair<String, Any?>) = event(kind, arrayOf(
                "outputId" to id, "timeNs" to SystemClock.elapsedRealtimeNanos(),
                "acceptedFrames" to writtenBytes / frameSize, *fields))
            init {
                emit("output-created", "rate" to config.sampleRate, "encoding" to config.encoding,
                    "channelMask" to config.channelMask, "capacityBytes" to config.bufferSize)
                output.addListener(object : AudioOutput.Listener {
                    override fun onUnderrun() { emit("output-underrun") }
                    override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
                        emit("output-position-advancing", "playoutStartSystemTimeMs" to playoutStartSystemTimeMs)
                    }
                    override fun onReleased() { emit("output-released") }
                    override fun onOffloadDataRequest() { emit("output-offload-request") }
                    override fun onOffloadPresentationEnded() { emit("output-offload-ended") }
                })
            }
            override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
                val start = SystemClock.elapsedRealtimeNanos()
                val copy = buffer.duplicate()
                val before = buffer.position()
                val result = output.write(buffer, encodedAccessUnitCount, presentationTimeUs)
                val end = SystemClock.elapsedRealtimeNanos()
                val count = buffer.position() - before
                if (count > 0) {
                    copy.limit(buffer.position())
                    val bytes = ByteArray(count)
                    copy.get(bytes)
                    accepted.write(bytes)
                    writtenBytes += count
                }
                emit("output-write", "entryNs" to start, "returnNs" to end,
                    "ptsUs" to presentationTimeUs, "acceptedBytes" to count, "handled" to result)
                return result
            }
            override fun play() { emit("output-play"); output.play() }
            override fun stop() { emit("output-stop"); output.stop() }
            override fun flush() {
                emit("output-flush")
                output.flush()
                writtenBytes = 0
            }
        }
    }
}
