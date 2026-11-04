package io.github.anastaci1a.localica

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.playback.LegacyMp3AudioRenderer
import io.github.anastaci1a.localica.playback.MediaEditAudioSink
import io.github.anastaci1a.localica.playback.PlaybackExtractors
import io.github.anastaci1a.localica.playback.PlaybackRenderersFactory
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LegacyMp3BoundaryTest {
    @Test fun mp3BoundariesPreserveIntentionalTrailingSilence() {
        val result = decode("boundary-mp3-silent-tail.mp3")
        assertEquals(132300 * 2, result.bytes.size)
        val pcm = ByteBuffer.wrap(result.bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (offset in listOf(0, 44100)) {
            for (start in listOf(2048, 30000)) {
                val rms = kotlin.math.sqrt((start until start + 2048).sumOf { i ->
                    val error = pcm[offset + i].toDouble() - ((i % 441) - 220) * 80
                    error * error
                } / 2048)
                assertTrue("Unshifted MP3 waveform at ${offset + start}: $rms", rms < 2500)
            }
            // Leave room for lossy ringing at the tone/silence join; retain the source's tail.
            assertTrue((36000 until 44100).all { kotlin.math.abs(pcm[offset + it].toInt()) <= 2 })
        }
        assertArrayEquals(tailPcm(), result.bytes.copyOfRange(88200 * 2, result.bytes.size))
    }

    @Test fun legacyCorrectionLeavesModernDecoderByteIdentical() {
        for (asset in listOf("boundary.mp3", "boundary-mp3-silent-tail.mp3")) {
            val native = decode(asset, native = true)
            val application = decode(asset)
            assertEquals(native.decoder, application.decoder)
            if (android.os.Build.VERSION.SDK_INT == 26 && native.decoder == "OMX.google.mp3.decoder") {
                assertEquals("Legacy output must retain only the source samples", 132300 * 2, application.bytes.size)
            } else assertArrayEquals("Application renderer changed modern PCM for $asset", native.bytes, application.bytes)
        }
    }

    @Test fun realSeekAcrossBoundaryFlushesImmediately() {
        val result = decode("boundary.mp3", seekAtBoundary = true)
        assertNotNull("The test must exercise decoded MP3", result.decoder)
        val reset = result.events.indexOf("seek-reset")
        assertTrue("The player must perform a real position reset: ${result.events}", reset >= 0)
        assertEquals("flush", result.events[reset + 1])
        assertFalse("A stale deferred transition must not survive the seek: ${result.events}",
            result.events.drop(reset + 1).contains("discontinuity"))
        assertArrayEquals("Seeking to the PCM item must discard both MP3 tails immediately", tailPcm(), result.bytes)
    }

    private data class Decoded(val bytes: ByteArray, val decoder: String?, val events: List<String>)

    private fun tailPcm() = ByteBuffer.allocate(44100 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(44100) { putShort(((it % 441 - 220) * 80).toShort()) }
    }.array()

    private fun decode(asset: String, native: Boolean = false, seekAtBoundary: Boolean = false): Decoded {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File.createTempFile("legacy-mp3-", ".mp3", context.cacheDir)
        val tail = File.createTempFile("legacy-mp3-tail-", ".wav", context.cacheDir)
        instrumentation.context.assets.open(asset).use { source -> input.outputStream().use(source::copyTo) }
        val tailBytes = tailPcm()
        val wav = ByteBuffer.allocate(44 + tailBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + tailBytes.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(tailBytes.size).put(tailBytes)
        tail.writeBytes(wav.array())
        val captured = ByteArrayOutputStream()
        val ended = AtomicBoolean(false)
        val seekRequested = AtomicBoolean(false)
        val seekReset = AtomicBoolean(false)
        val events = Collections.synchronizedList(mutableListOf<String>())
        var decoder: String? = null
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            val processor = object : BaseAudioProcessor() {
                override fun onConfigure(inputAudioFormat: AudioFormat) = inputAudioFormat
                override fun queueInput(inputBuffer: ByteBuffer) {
                    val bytes = ByteArray(inputBuffer.remaining()); inputBuffer.get(bytes)
                    synchronized(captured) { captured.write(bytes) }
                    replaceOutputBuffer(bytes.size).put(bytes).flip()
                }
            }
            val sink = object : ForwardingAudioSink(MediaEditAudioSink(DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(processor)).build())) {
                override fun flush() {
                    events += "flush"
                    super.flush()
                    if (seekRequested.get()) synchronized(captured) { captured.reset() }
                }
                override fun handleDiscontinuity() { events += "discontinuity"; super.handleDiscontinuity() }
            }
            val factory = if (native) object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean): AudioSink = sink
            } else object : PlaybackRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean): AudioSink = sink
                override fun buildAudioRenderers(context: Context, extensionRendererMode: Int,
                    mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean, audioSink: AudioSink,
                    eventHandler: Handler, eventListener: AudioRendererEventListener, out: ArrayList<Renderer>) {
                    if (!seekAtBoundary) {
                        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector,
                            enableDecoderFallback, audioSink, eventHandler, eventListener, out)
                        return
                    }
                    out += object : LegacyMp3AudioRenderer(context, codecAdapterFactory, mediaCodecSelector,
                        enableDecoderFallback, eventHandler, eventListener, audioSink) {
                        override fun onProcessedStreamChange() {
                            super.onProcessedStreamChange()
                            if (codecInfo != null &&
                                lastProcessedOutputBufferTimeUs != C.TIME_UNSET && seekRequested.compareAndSet(false, true)) {
                                events += "deferred-boundary"
                                Handler(Looper.getMainLooper()).post { player.seekTo(2, 0) }
                            }
                        }
                        override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
                            // Hold the following format/PCM until ExoPlayer processes the real seek.
                            // This deterministically exercises a reset with a transition deferred.
                            if (!seekRequested.get() || seekReset.get()) super.onOutputFormatChanged(format, mediaFormat)
                        }
                        override fun processOutputBuffer(positionUs: Long, elapsedRealtimeUs: Long,
                            codec: MediaCodecAdapter?, buffer: ByteBuffer?, bufferIndex: Int, bufferFlags: Int,
                            sampleCount: Int, bufferPresentationTimeUs: Long, isDecodeOnlyBuffer: Boolean,
                            isLastBuffer: Boolean, format: Format): Boolean {
                            if (seekRequested.get() && !seekReset.get()) return false
                            return super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex,
                                bufferFlags, sampleCount, bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format)
                        }
                        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
                            if (seekRequested.get()) events += "seek-reset"
                            super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
                            if (seekRequested.get()) seekReset.set(true)
                        }
                    }
                }
            }
            player = ExoPlayer.Builder(context, factory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context), PlaybackExtractors())).build()
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long) { decoder = decoderName }
            })
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended.set(true) }
            })
            player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(5_000_000L))
            player.setMediaItems(listOf(input, input, tail).map { MediaItem.fromUri(it.toURI().toString()) })
            player.prepare(); player.play()
        }
        try {
            await { ended.get() }
            if (seekAtBoundary) assertTrue("Decoded boundary was not exercised", seekRequested.get() && seekReset.get())
            return Decoded(synchronized(captured) { captured.toByteArray() }, decoder, synchronized(events) { events.toList() })
        } finally {
            instrumentation.runOnMainSync { player.release() }
            input.delete(); tail.delete()
        }
    }
}
