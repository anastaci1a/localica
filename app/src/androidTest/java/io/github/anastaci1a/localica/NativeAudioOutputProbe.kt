package io.github.anastaci1a.localica

import android.content.Context
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/** Observes real default AudioTrack writes; never substitutes a fake sink or changes buffers. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class NativeAudioOutputProbe(context: Context) {
    data class ClockSample(val observedNs: Long, val head: Long, val frame: Long?, val timestampNs: Long?,
        val underruns: Int, val playState: Int)
    data class Write(val atNs: Long, val startByte: Int, val bytes: Int, val presentationUs: Long)
    class Epoch(val id: Int, val rate: Int, val frameBytes: Int, val createdNs: Long) {
        val pcm = ByteArrayOutputStream()
        val writes = mutableListOf<Write>()
        val samples = mutableListOf<ClockSample>()
        val events = mutableListOf<Pair<String, Long>>()
        fun bytes(): ByteArray = synchronized(this) { pcm.toByteArray() }
    }
    class Output(val id: Int, val native: AudioTrackAudioOutput, val config: AudioOutputProvider.OutputConfig) {
        val epochs = CopyOnWriteArrayList<Epoch>()
        @Volatile var released = false
        @Volatile var routeType: Int? = null
        init { nextEpoch() }
        fun nextEpoch() = Epoch(epochs.size, config.sampleRate,
            Integer.bitCount(config.channelMask) * 2, System.nanoTime()).also { epochs.add(it) }
        val current: Epoch get() = epochs.last()
    }
    private val delegate = AudioTrackAudioOutputProvider.Builder(context).build()
    val outputs = CopyOnWriteArrayList<Output>()
    val provider = object : AudioOutputProvider by delegate {
        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
            check(config.encoding == C.ENCODING_PCM_16BIT && !config.isOffload && !config.isTunneling) {
                "Diagnostic requires decoded 16-bit PCM, encoding=${config.encoding} offload=${config.isOffload} tunneling=${config.isTunneling}"
            }
            val native = delegate.getAudioOutput(config)
            val output = Output(outputs.size, native, config)
            outputs.add(output)
            native.addListener(object : AudioOutput.Listener {
                override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) { event(output, "positionAdvancing") }
                override fun onOffloadDataRequest() {}
                override fun onOffloadPresentationEnded() {}
                override fun onUnderrun() { event(output, "underrun") }
                override fun onReleased() { event(output, "released") }
            })
            return object : AudioOutput by native {
                override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
                    val view = buffer.duplicate()
                    val before = buffer.position()
                    try { return native.write(buffer, encodedAccessUnitCount, presentationTimeUs) }
                    finally {
                        val accepted = buffer.position() - before
                        check(accepted in 0..view.remaining())
                        if (accepted > 0) {
                            val bytes = ByteArray(accepted)
                            view.get(bytes)
                            val epoch = output.current
                            check(accepted % epoch.frameBytes == 0) { "Native write ended within a PCM frame" }
                            synchronized(epoch) {
                                epoch.writes.add(Write(System.nanoTime(), epoch.pcm.size(), accepted, presentationTimeUs))
                                epoch.pcm.write(bytes)
                            }
                        }
                    }
                }
                override fun flush() {
                    event(output, "flush")
                    native.flush()
                    output.nextEpoch()
                }
                override fun play() { event(output, "play"); native.play() }
                override fun pause() { event(output, "pause"); native.pause() }
                override fun stop() { event(output, "stop"); native.stop() }
                override fun release() {
                    event(output, "release")
                    output.released = true
                    native.release()
                }
            }
        }
    }
    private fun event(output: Output, name: String) {
        val epoch = output.current
        synchronized(epoch) { epoch.events.add(name to System.nanoTime()) }
    }
    fun sample() {
        for (output in outputs) {
            if (output.released) continue
            val epoch = output.current
            val track = output.native.audioTrack
            val stamp = AudioTimestamp()
            try {
                val hasStamp = track.getTimestamp(stamp)
                output.routeType = track.routedDevice?.type
                val sample = ClockSample(System.nanoTime(), track.playbackHeadPosition.toLong() and 0xffffffffL,
                    stamp.framePosition.takeIf { hasStamp }, stamp.nanoTime.takeIf { hasStamp },
                    track.underrunCount, track.playState)
                synchronized(epoch) {
                    // A flush/recreation changes the coordinate system, never combine its clocks.
                    if (output.current === epoch && !output.released) epoch.samples.add(sample)
                }
            } catch (_: IllegalStateException) {
                // A release can race this read-only sampler. It is missing evidence, never zero latency.
            }
        }
    }
    fun pcm(): ByteArray = ByteArrayOutputStream().also { all ->
        outputs.forEach { output -> output.epochs.forEach { all.write(it.bytes()) } }
    }.toByteArray()

    fun report(label: String) {
        outputs.forEach { output -> output.epochs.forEach { epoch -> synchronized(epoch) {
            val valid = stableTimestamps(epoch)
            val underruns = epoch.samples.maxOfOrNull { it.underruns } ?: -1
            Log.i("GaplessProbe", "case=$label output=${output.id} epoch=${epoch.id} rate=${epoch.rate} channels=${epoch.frameBytes / 2} encoding=${output.config.encoding} frameBytes=${epoch.frameBytes} acceptedBytes=${epoch.pcm.size()} writes=${epoch.writes.size} timestampSamples=${valid.size} underruns=$underruns events=${epoch.events} route=${output.routeType}")
        } } }
    }

    /** A local timestamp slope around a known frame seam; unavailable or stale clocks remain unmeasured. */
    fun boundary(label: String, byteOffset: Int, requestedNs: Long? = null) {
        var remaining = byteOffset
        for (output in outputs) for (epoch in output.epochs) synchronized(epoch) {
            val size = epoch.pcm.size()
            if (remaining >= size) { remaining -= size; return@synchronized }
            val frame = remaining.toLong() / epoch.frameBytes
            val valid = stableTimestamps(epoch)
            val before = valid.lastOrNull { it.frame!! <= frame }
            val after = valid.firstOrNull { it.frame!! > frame }
            val write = epoch.writes.firstOrNull { remaining >= it.startByte && remaining < it.startByte + it.bytes }
            val bracketed = before != null && after != null && after.frame!! - before.frame!! <= epoch.rate / 2
            val excessUs = if (bracketed) (after!!.timestampNs!! - before!!.timestampNs!!) / 1000.0 -
                (after.frame!! - before.frame!!) * 1_000_000.0 / epoch.rate else null
            val boundaryUnderruns = if (bracketed) after!!.underruns - before!!.underruns else null
            val requestWriteUs = if (requestedNs != null && write != null) (write.atNs - requestedNs) / 1000.0 else null
            val presentationLowerUs = if (bracketed && requestedNs != null) (before!!.timestampNs!! - requestedNs) / 1000.0 else null
            val presentationUpperUs = if (bracketed && requestedNs != null) (after!!.timestampNs!! - requestedNs) / 1000.0 else null
            val bracketSpanUs = if (bracketed) (after!!.timestampNs!! - before!!.timestampNs!!) / 1000.0 else null
            Log.i("GaplessProbe", "case=$label boundaryByte=$byteOffset output=${output.id} epoch=${epoch.id} frame=$frame timestampBracket=$bracketed clockExcessUs=$excessUs boundaryUnderruns=$boundaryUnderruns requestToAcceptedUs=$requestWriteUs requestToFrameLowerUs=$presentationLowerUs requestToFrameUpperUs=$presentationUpperUs bracketSpanUs=$bracketSpanUs beforeFrame=${before?.frame} beforeNs=${before?.timestampNs} afterFrame=${after?.frame} afterNs=${after?.timestampNs}")
            return
        }
        Log.i("GaplessProbe", "case=$label boundaryByte=$byteOffset measurement=unmeasured-no-accepted-frame")
    }
    private fun stableTimestamps(epoch: Epoch): List<ClockSample> {
        val valid = mutableListOf<ClockSample>()
        for (sample in epoch.samples) {
            val frame = sample.frame ?: continue
            val time = sample.timestampNs ?: continue
            if (sample.playState != AudioTrack.PLAYSTATE_PLAYING || frame <= 0 || time <= 0 ||
                kotlin.math.abs(sample.observedNs - time) > 1_000_000_000L) continue
            val previous = valid.lastOrNull()
            if (previous == null || (frame > previous.frame!! && time > previous.timestampNs!!)) valid.add(sample)
        }
        return valid
    }
}
