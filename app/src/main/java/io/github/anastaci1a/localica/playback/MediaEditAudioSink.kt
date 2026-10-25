package io.github.anastaci1a.localica.playback

import android.content.Context
import java.nio.ByteBuffer
import androidx.media3.common.util.Util
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.audio.AudioProcessor

/** Authoritative edit units, independent of AAC core rate, SBR or decoded samples per access unit. */
internal class MediaEditTrim(val timescale: Long, val start: Long) {
    fun delayAt(outputSampleRate: Int, streamPositionUs: Long = 0): Int {
        require(timescale > 0 && start >= 0 && outputSampleRate in 1..1_000_000)
        val frames = Math.multiplyExact(start, outputSampleRate.toLong()) / timescale
        require(frames <= Int.MAX_VALUE)
        if (streamPositionUs <= 0) return frames.toInt()
        // PTS is truncated to microseconds. Ceiling recovers the first PCM frame represented
        // by that timestamp, avoiding a second leading cut after seek or output-rate changes.
        val endUs = Math.addExact(Math.multiplyExact(start, 1_000_000L), timescale - 1) / timescale
        if (streamPositionUs >= endUs) return 0
        val elapsed = Math.addExact(Math.multiplyExact(streamPositionUs, outputSampleRate.toLong()), 999_999L) / 1_000_000L
        return maxOf(0L, frames - elapsed).toInt()
    }
}

/** Bind corrected edits to the decoder's actual PCM rate on every output-format change. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MediaEditAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private fun needsDecode(format: Format) = format.customData is MediaEditTrim && format.sampleMimeType != MimeTypes.AUDIO_RAW
    override fun supportsFormat(format: Format) = !needsDecode(format) && super.supportsFormat(format)
    override fun getFormatSupport(format: Format) = if (needsDecode(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        if (needsDecode(format)) AudioOffloadSupport.DEFAULT_UNSUPPORTED else super.getFormatOffloadSupport(format)

    private var offsetUs = 0L
    private var current: Triple<Format, Int, IntArray?>? = null
    private var pending: Triple<Format, Int, IntArray?>? = null

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val edit = format.customData as? MediaEditTrim
        if (edit == null) {
            current = null; pending = null
            super.configure(format, specifiedBufferSize, outputChannels)
            return
        }
        try {
            require(format.sampleMimeType == MimeTypes.AUDIO_RAW && format.channelCount > 0 && Util.isEncodingLinearPcm(format.pcmEncoding))
            edit.delayAt(format.sampleRate)
        } catch (e: RuntimeException) {
            throw AudioSink.ConfigurationException(e, format)
        }
        val config = Triple(format, specifiedBufferSize, outputChannels?.copyOf())
        // Validate/prepare the native pipeline now. The first buffer's PTS determines whether
        // this is a new stream, a seek, or a mid-stream decoder output-format change.
        super.configure(format.buildUpon().setEncoderDelay(0).setEncoderPadding(0).build(), specifiedBufferSize, outputChannels)
        current = config; pending = config
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // An empty codec buffer does not identify the first PCM timestamp of this stream.
        if (pending != null && !buffer.hasRemaining()) return true
        pending?.let { (format, size, channels) ->
            try {
                val edit = format.customData as MediaEditTrim
                val delay = edit.delayAt(format.sampleRate, Math.subtractExact(presentationTimeUs, offsetUs))
                super.configure(format.buildUpon().setEncoderDelay(delay).setEncoderPadding(0).build(), size, channels)
                pending = null
            } catch (e: Exception) {
                throw AudioSink.WriteException(-2, format, false).apply { initCause(e) }
            }
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        offsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }
    override fun flush() { super.flush(); pending = current }
    override fun reset() { current = null; pending = null; offsetUs = 0; super.reset() }
    override fun release() { current = null; pending = null; super.release() }

}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal open class PlaybackRenderersFactory(
    context: Context,
    private val boundaryDeclickEnabled: () -> Boolean = { false },
    private val afterBoundaryProcessors: Array<AudioProcessor> = emptyArray(),
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val boundary = BoundaryDeclickAudioProcessor(boundaryDeclickEnabled)
        val native = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(boundary, *afterBoundaryProcessors))
            .build()
        return BoundaryDeclickAudioSink(MediaEditAudioSink(native), boundary)
    }
}
