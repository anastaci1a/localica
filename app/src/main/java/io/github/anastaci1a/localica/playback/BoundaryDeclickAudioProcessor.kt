package io.github.anastaci1a.localica.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Post-trim PCM adapter. Stream notifications are identities, never timestamp comparisons. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class BoundaryDeclickAudioProcessor(
    private val enabled: () -> Boolean = { false },
) : BaseAudioProcessor() {
    private val session = BoundaryDeclickSession()
    private var generation = 0L
    private var active: String? = null
    private var pending: String? = null
    private var pendingReady = false

    val hasPendingStream: Boolean get() = pending != null

    /** Called for each renderer stream notification, including equal-valued offsets. */
    fun nextStream() {
        // More than one notification before consumption cannot prove which stream drained.
        if (pending != null) resetContinuity()
        generation = Math.addExact(generation, 1L)
        pending = generation.toString()
        pendingReady = false
    }

    /** Explicit seek/reset/cancel invalidates every previous boundary and retained tail. */
    fun resetContinuity() {
        session.reset()
        active = null
        pending = null
        pendingReady = false
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            resetContinuity()
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val bytes = inputBuffer.remaining()
        val channels = inputAudioFormat.channelCount
        if (channels <= 0 || bytes % (channels * 2) != 0) {
            resetContinuity()
            replaceOutputBuffer(bytes).put(inputBuffer).flip()
            return
        }
        // Old trimmer tail may still arrive AFTER the next-stream notification. Only the
        // subsequent native pipeline flush admits new-stream PCM, after the old EOS drain.
        if (pendingReady) {
            val identity = checkNotNull(pending)
            session.beginStream(identity,
                BoundaryDeclick.Pcm16Format(inputAudioFormat.sampleRate, channels), true)
            active = identity
            pending = null
            pendingReady = false
        }
        val samples = ShortArray(bytes / 2)
        inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        inputBuffer.position(inputBuffer.limit())
        val result = session.process(samples, enabled())
        val output = replaceOutputBuffer(bytes).order(ByteOrder.LITTLE_ENDIAN)
        result.forEach { output.putShort(it) }
        output.flip()
    }

    override fun onQueueEndOfStream() {
        active?.let(session::endStream)
        active = null
    }

    override fun onFlush() {
        if (pending != null) {
            // A native configuration change and PTS resync can flush twice before any PCM.
            // Do not begin/end an empty replacement stream and lose the completed tail.
            pendingReady = true
        } else {
            resetContinuity()
        }
    }

    override fun onReset() = resetContinuity()
}

/** Renderer callbacks qualify lifecycle only; the processor receives native post-trim PCM. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class BoundaryDeclickAudioSink(
    sink: AudioSink,
    private val processor: BoundaryDeclickAudioProcessor,
) : ForwardingAudioSink(sink) {
    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || format.pcmEncoding != C.ENCODING_PCM_16BIT)
            processor.resetContinuity()
        super.configure(format, specifiedBufferSize, outputChannels)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        processor.nextStream()
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleDiscontinuity() {
        if (!processor.hasPendingStream) processor.resetContinuity()
        super.handleDiscontinuity()
    }

    override fun flush() { processor.resetContinuity(); super.flush() }
    override fun reset() { processor.resetContinuity(); super.reset() }
    override fun release() { processor.resetContinuity(); super.release() }
}
