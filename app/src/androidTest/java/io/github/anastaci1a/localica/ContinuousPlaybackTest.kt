package io.github.anastaci1a.localica

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Inspect decoded samples after trimming as well as the player's buffering state at the boundary. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ContinuousPlaybackTest {
    @Test fun splitPcmPlaysWithoutInsertedOrMissingSamplesOrRebuffering() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sampleRate = 44100
        val samples = ShortArray(sampleRate * 2) { ((it % 441 - 220) * 80).toShort() }
        val files = listOf(samples.copyOfRange(0, sampleRate), samples.copyOfRange(sampleRate, samples.size))
            .mapIndexed { index, pcm -> File(context.cacheDir, "continuous-$index.wav").also { file ->
                val data = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                data.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
                    .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size * 2)
                pcm.forEach(data::putShort); file.writeBytes(data.array())
            } }
        val expected = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(expected::putShort)
        play(files) { assertArrayEquals("Decoded audio must retain every sample across the split", expected.array(), it) }
    }
    @Test fun encodedTracksRespectGaplessTrimmingAtTheBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (extension in listOf("flac", "mp3", "m4a")) {
            val file = File(instrumentation.targetContext.cacheDir, "boundary." + extension)
            instrumentation.context.assets.open(file.name).use { input -> file.outputStream().use(input::copyTo) }
            // A PCM tail makes both encoded end boundaries observable, without a final encoder pad.
            val tail = File(instrumentation.targetContext.cacheDir, "boundary-tail.wav")
            val pcm = ByteBuffer.allocate(44 + 44100 * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.put("RIFF".toByteArray()).putInt(36 + 44100 * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(88200)
            repeat(44100) { pcm.putShort(((it % 441 - 220) * 80).toShort()) }; tail.writeBytes(pcm.array())
            play(listOf(file, file, tail)) { decoded ->
                assertEquals(extension + " must trim encoder delay/padding", 44100 * 3 * 2, decoded.size)
                if (extension == "flac") {
                    val expected = ByteBuffer.allocate(44100 * 3 * 2).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(44100 * 3) { expected.putShort(((it % 441 - 220) * 80).toShort()) }
                    assertArrayEquals(expected.array(), decoded)
                }
            }
        }
    }
    private fun play(files: List<File>, check: (ByteArray) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val captured = ByteArrayOutputStream()
        val ended = AtomicBoolean(false)
        val rebuffered = AtomicBoolean(false)
        var transitions = 0
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
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean): AudioSink = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor)).build()
            }
            player = ExoPlayer.Builder(context, renderers).build()
            player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(5_000_000L))
            player.addListener(object : Player.Listener {
                var ready = false
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) ready = true
                    if (state == Player.STATE_BUFFERING && ready) rebuffered.set(true)
                    if (state == Player.STATE_ENDED) ended.set(true)
                }
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) transitions++
                }
            })
            player.setMediaItems(files.map { MediaItem.fromUri(it.toURI().toString()) })
            player.prepare(); player.play()
        }
        try {
            await { ended.get() }
            assertFalse("Natural transition rebuffered", rebuffered.get())
            assertEquals(files.size - 1, transitions)
            check(synchronized(captured) { captured.toByteArray() })
        } finally {
            instrumentation.runOnMainSync { player.release() }
            files.forEach(File::delete)
        }
    }
}
