package io.github.anastaci1a.localica

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.NativeReaders
import io.github.anastaci1a.localica.playback.track
import io.github.anastaci1a.localica.queue.QueueController
import io.github.anastaci1a.localica.queue.QueueDatabase
import io.github.anastaci1a.localica.queue.QueuePreferences
import io.github.anastaci1a.localica.queue.QueueSettings
import io.github.anastaci1a.localica.queue.queueMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compare a static playlist with the real queue actor/timeline using identical split PCM.
 * Capture is before AudioTrack: exact samples and no reported underruns are not speaker-output
 * proof. Synthetic file URIs do not reproduce a user's codec, document provider or audio route.
 * Both engines disable focus handling because this fixture has no foreground Activity/service;
 * audio-focus and MediaSession lifecycle behavior are outside this boundary test.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueContinuousPlaybackTest {
    @Test fun staticPlaylistPreservesSplitPcmControl() = playSplitPcm(throughQueue = false)

    @Test fun queueActorPreservesSplitPcmAcrossNaturalTransitions() = playSplitPcm(throughQueue = true)

    @Test fun captureAcceptsEmptyInputAndPassesPcmThroughUnchanged() {
        val capture = ContinuityCapture()
        capture.configure(AudioFormat(SAMPLE_RATE, 1, C.ENCODING_PCM_16BIT))
        capture.flush()
        try {
            capture.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertFalse(capture.output.hasRemaining())
            assertTrue(capture.snapshot().isEmpty())
            val bytes = byteArrayOf(1, 2, 3, 4)
            val input = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { flip() }
            capture.queueInput(input)
            assertFalse(input.hasRemaining())
            val output = ByteArray(bytes.size)
            capture.output.get(output)
            assertArrayEquals(bytes, output)
            capture.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertFalse(capture.output.hasRemaining())
            assertArrayEquals(bytes, capture.snapshot())
        } finally { capture.reset() }
    }

    private fun playSplitPcm(throughQueue: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val readers = NativeReaders()
        val capture = ContinuityCapture()
        val probe = ContinuityEvents()
        val files = mutableListOf<File>()
        var player: ExoPlayer? = null
        var queues: QueueController? = null
        // The period does not divide the split length: duplicated/reordered segments are observable.
        // An intentional interior silence must survive, rather than being removed to mask a seam.
        val samples = ShortArray(SAMPLE_RATE * 3) { index ->
            if (index in SAMPLE_RATE / 2 until SAMPLE_RATE / 2 + 256) 0
            else (((index % 997) - 498) * 40).toShort()
        }
        val expected = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(expected::putShort)
        fun checkPlayback() {
            probe.error?.let { throw AssertionError("Split PCM playback failed", it) }
            queues?.state?.value?.problem?.let { throw AssertionError("Queue playback failed: $it") }
        }
        fun awaitMain(condition: () -> Boolean) = await(timeoutMs = 15_000) {
            var result = false
            instrumentation.runOnMainSync { checkPlayback(); result = condition() }
            result
        }
        try {
            repeat(3) { index ->
                val file = File.createTempFile("queue-continuity-", ".wav", context.cacheDir)
                files += file
                writeWav(file, samples.copyOfRange(index * SAMPLE_RATE, (index + 1) * SAMPLE_RATE))
            }
            val tracks = files.mapIndexed { index, file ->
                Track(file.toURI().toString(), "Split $index", durationMs = 1000,
                    fileName = file.name, relativePath = file.name)
            }
            if (throughQueue) preferences.write(QueueSettings())
            instrumentation.runOnMainSync {
                val renderers = object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean): AudioSink = DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(capture)).build()
                }
                // Match PlaybackService's source ownership and audio attributes. Test seams are
                // pass-through PCM capture, muting, isolated storage and disabled focus handling.
                val engine = ExoPlayer.Builder(context, renderers)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(readers.track(DefaultDataSource.Factory(context))))
                    .build()
                player = engine
                engine.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                engine.setHandleAudioBecomingNoisy(true)
                engine.setWakeMode(C.WAKE_MODE_LOCAL)
                engine.repeatMode = Player.REPEAT_MODE_OFF
                engine.shuffleModeEnabled = false
                engine.volume = 0f
                engine.addListener(probe)
                engine.addAnalyticsListener(probe)
                if (throughQueue) {
                    queues = QueueController(db.queues(), preferences,
                        (context.applicationContext as LocalicaApplication).library, scope, readers)
                    queues!!.attach(engine)
                } else {
                    engine.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(5_000_000L))
                    engine.setMediaItems(tracks.mapIndexed { index, track -> queueMediaItem("control-$index", track) })
                    engine.prepare()
                    engine.play()
                }
            }
            if (throughQueue) {
                awaitMain { !queues!!.state.value.loading }
                instrumentation.runOnMainSync { queues!!.tap(tracks, 0, sourceName = "Continuous PCM") }
            }
            // Let all three tracks play from their beginnings. No seek-to-end shortcuts.
            awaitMain { probe.ended }
            if (throughQueue) awaitMain {
                val queue = queues!!.state.value.library.active ?: return@awaitMain false
                queue.journey?.history?.map { it.entryId } == queue.entries.map { it.id } &&
                    queue.entries.size == tracks.size && queue.currentEntryId == queue.entries.last().id
            }
            instrumentation.runOnMainSync {
                checkPlayback()
                assertArrayEquals("Decoded PCM must retain every sample and intentional silence",
                    expected.array(), capture.snapshot())
                assertEquals("Natural transition source order", tracks.drop(1).map { it.id }, probe.arrivals.map { it.first })
                assertEquals("Exactly two automatic discontinuities", List(2) { Player.DISCONTINUITY_REASON_AUTO_TRANSITION },
                    probe.discontinuities)
                assertTrue("Unexpected transitions after initial readiness: ${probe.unexpectedTransitions}",
                    probe.unexpectedTransitions.isEmpty())
                assertEquals("Natural transition rebuffered", 0, probe.rebuffers)
                assertTrue("Reported AudioTrack underruns: ${probe.underruns}", probe.underruns.isEmpty())
                if (throughQueue) {
                    val queue = queues!!.state.value.library.active!!
                    assertEquals(queue.journey!!.history.drop(1).map { it.token }, probe.arrivals.map { it.second })
                    assertEquals(queue.journey.current!!.token,
                        player!!.currentMediaItem!!.mediaMetadata.extras!!.getString("queueVisit"))
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                player?.let { engine ->
                    queues?.detach(engine)
                    engine.release()
                }
                job.cancel()
            }
            runBlocking { job.join() }
            db.close()
            files.forEach(File::delete)
            if (throughQueue) preferences.write(original)
        }
    }

    private fun writeWav(file: File, samples: ShortArray) {
        val pcm = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach(pcm::putShort)
        file.writeBytes(pcm.array())
    }

    private companion object { const val SAMPLE_RATE = 44_100 }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ContinuityCapture : BaseAudioProcessor() {
    private val bytes = ByteArrayOutputStream()
    override fun onConfigure(inputAudioFormat: AudioFormat) = inputAudioFormat
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val input = ByteArray(inputBuffer.remaining())
        inputBuffer.get(input)
        synchronized(bytes) { bytes.write(input) }
        replaceOutputBuffer(input.size).put(input).flip()
    }
    fun snapshot(): ByteArray = synchronized(bytes) { bytes.toByteArray() }
}

/** Player and analytics callbacks, and assertions reading these fields, all run on Main. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ContinuityEvents : Player.Listener, AnalyticsListener {
    var error: PlaybackException? = null
    var ended = false
    var rebuffers = 0
    private var ready = false
    val arrivals = mutableListOf<Pair<String?, String?>>()
    val discontinuities = mutableListOf<Int>()
    val unexpectedTransitions = mutableListOf<Int>()
    val underruns = mutableListOf<String>()
    override fun onPlayerError(error: PlaybackException) { this.error = error }
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_BUFFERING && ready) rebuffers++
        if (playbackState == Player.STATE_READY) ready = true
        if (playbackState == Player.STATE_ENDED) ended = true
    }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            val extras = mediaItem?.mediaMetadata?.extras
            arrivals += extras?.getString("trackId") to extras?.getString("queueVisit")
        } else if (ready) unexpectedTransitions += reason
    }
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (ready) discontinuities += reason
    }
    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int,
        bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        underruns += "bufferBytes=$bufferSize bufferMs=$bufferSizeMs sinceFeedMs=$elapsedSinceLastFeedMs"
    }
}
