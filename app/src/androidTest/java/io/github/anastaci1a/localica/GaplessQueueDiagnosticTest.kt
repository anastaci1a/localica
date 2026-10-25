package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.PcmAudioUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.NativeReaders
import io.github.anastaci1a.localica.playback.track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic fixtures only. Native write/clock evidence is not acoustic or Bluetooth loopback capture. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GaplessQueueDiagnosticTest {
    @Test fun automaticPcmQueuePreservesTheSameNativeSamplesAsBarePlayer() = files { directory ->
        val segments = pcmSegments(directory, List(3) { 44100 to 44100 })
        compare("pcm-three", segments, expected = nativePcm(segments))
    }

    @Test fun automaticPcmCrossesTheBoundedQueueWindowWithoutChangingNativeSamples() = files { directory ->
        val segments = pcmSegments(directory, List(70) { 44100 to 6615 })
        compare("pcm-window-70", segments, expected = nativePcm(segments))
    }

    @Test fun automaticMp3QueueHasTheSameNativeTrimmingAsBarePlayer() = files { directory ->
        compare("mp3", encodedSegments(directory, "mp3"))
    }

    @Test fun automaticAacQueueHasTheSameNativeTrimmingAsBarePlayer() = files { directory ->
        compare("aac", encodedSegments(directory, "m4a"))
    }

    @Test fun sampleRateChangesPreservePcmAndExposeNativeOutputRecreation() = files { directory ->
        val segments = pcmSegments(directory, listOf(44100 to 44100, 48000 to 48000, 44100 to 44100))
        compare("rate-change", segments, expected = nativePcm(segments), sameFormat = false)
    }

    @Test fun disabledPreloadingReportsItsOwnNativeBoundaryEvidence() = files { directory ->
        val segments = pcmSegments(directory, List(3) { 44100 to 44100 })
        compare("preload-off", segments, expected = nativePcm(segments), preload = false)
    }

    @Test fun manualNextAndPreviousReportAcceptedSamplesAndNativeClockSeparately() = files { directory ->
        val segments = pcmSegments(directory, List(3) { 44100 to 132300 })
        for (controlled in listOf(false, true)) runPlayer("manual-${if (controlled) "queue" else "bare"}", segments, controlled) {
            awaitMain { player.isPlaying && player.currentPosition >= 500 }
            suspend fun move(name: String, target: Int, command: () -> Unit) {
                lateinit var priorEpochs: Set<NativeAudioOutputProbe.Epoch>
                val request = withContext(Dispatchers.Main) {
                    priorEpochs = probe.outputs.flatMap { it.epochs }.toSet()
                    System.nanoTime().also { command() }
                }
                awaitMain { player.currentMediaItem?.mediaId == target.toString() && player.isPlaying && player.currentPosition >= 500 }
                val epochs = probe.outputs.flatMap { it.epochs }
                val fresh = epochs.filter { it !in priorEpochs && it.bytes().isNotEmpty() }
                assertEquals("$label $name requires one attributable new written output/flush epoch", 1, fresh.size)
                val epoch = fresh.single()
                val segment = segments[target]
                assertEquals(segment.rate, epoch.rate)
                assertEquals(segment.channels * 2, epoch.frameBytes)
                val accepted = epoch.bytes()
                val expected = rampPcm(requireNotNull(segment.pcm), segment.rate, segment.channels)
                assertTrue("$label $name target epoch must contain at least 500 ms and no later source",
                    accepted.size >= segment.rate * epoch.frameBytes / 2 && accepted.size <= expected.size)
                assertArrayEquals("$label $name must begin at target frame zero with only the pinned startup ramp",
                    expected.copyOf(accepted.size), accepted)
                // Fixed 100 ms source marker is beyond the 20 ms ramp. Never scan arbitrary offsets
                // or remove accepted silence to make a source match or shorten its measured delay.
                val markerAt = segment.rate * epoch.frameBytes / 10
                val markerEnd = markerAt + 256 * epoch.frameBytes
                assertArrayEquals(requireNotNull(segment.pcm).copyOfRange(markerAt, markerEnd),
                    accepted.copyOfRange(markerAt, markerEnd))
                val targetAt = epochs.takeWhile { it !== epoch }.sumOf { it.bytes().size }
                probe.boundary("$label-$name-first-target", targetAt, request)
                // A later authored marker can have stable native timestamps when frame zero does
                // not. Report its bounds separately; neither proves acoustic output continuity.
                probe.boundary("$label-$name-target-plus-250ms", targetAt + segment.rate * epoch.frameBytes / 4, request)
                Log.i("GaplessProbe", "case=$label action=$name target=$target acceptedPrefixByte=$targetAt sourceMarkerFrame=${segment.rate / 10} startupRampMs=20 newWrittenEpochs=${fresh.size} noAcousticCapture=true")
            }
            move("next", 1) { if (controlled) queues!!.next() else player.seekToNextMediaItem() }
            move("previous", 0) { if (controlled) queues!!.previous(alwaysSong = true) else player.seekToPreviousMediaItem() }
        }
    }

    private suspend fun compare(label: String, segments: List<Segment>, expected: ByteArray? = null,
        sameFormat: Boolean = true, preload: Boolean = true) {
        var bare = byteArrayOf()
        for (controlled in listOf(false, true)) runPlayer("$label-${if (controlled) "queue" else "bare"}", segments, controlled, preload) {
            awaitMain { player.playbackState == Player.STATE_ENDED }
            val accepted = probe.pcm()
            val runLabel = this.label
            Log.i("GaplessProbe", "case=$runLabel acceptedBytes=${accepted.size} sha256=${sha256(accepted)} expectedBytes=${expected?.size} transitions=${automatic.get()} rebufferEvents=${rebuffer.get()} decoders=$decoders")
            if (!controlled) bare = accepted else assertArrayEquals("Queue controller changed native accepted PCM for $label", bare, accepted)
            expected?.let { assertArrayEquals("Native accepted PCM must retain every authored frame with only the pinned output startup ramp for $label", it, accepted) }
            assertEquals(segments.size - 1, automatic.get())
            if (sameFormat) {
                val writtenOutputs = probe.outputs.filter { output -> output.epochs.any { it.bytes().isNotEmpty() } }
                assertEquals("Same-format natural playback must reuse its written AudioTrack", 1, writtenOutputs.size)
                val writtenEpochs = writtenOutputs.single().epochs.filter { it.bytes().isNotEmpty() }
                assertEquals("Natural playback must not flush already accepted PCM between streams", 1, writtenEpochs.size)
                assertFalse("No native flush is expected after natural playback has accepted samples",
                    synchronized(writtenEpochs.single()) { writtenEpochs.single().events.any { it.first == "flush" } })
            }
            if (segments.all { it.pcm != null }) {
                var byteOffset = 0
                segments.dropLast(1).forEachIndexed { index, segment ->
                    byteOffset += segment.frames * segment.channels * 2
                    probe.boundary("$runLabel-exact-pcm-seam-${index + 1}", byteOffset)
                }
            } else {
                // Encoder padding means authored durations cannot locate native source seams.
                // Keep #28/#30 visible; do not assign a presentation clock to nominal frame counts.
                Log.i("GaplessProbe", "case=$runLabel encodedSourceSeams=unmapped authoredFrames=${segments.map { it.frames }}")
            }
        }
    }

    @Test fun nativeStartupRampExpectationMatchesPinnedSignedPcmMath() {
        val values = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, -1, 1, -1001, 1001, -2000, 2000)
        val input = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach(::putShort)
        }.array()
        // 100 Hz gives two ramp frames. Both channels share frame index; negative values retain
        // Java's signed 32-bit conversion, truncating division, then arithmetic shift semantics.
        val expected = shortArrayOf(0, 0, -1, 0, -1001, 1001, -2000, 2000)
        val actual = rampPcm(input, rate = 100, channels = 2)
        val actualShorts = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertArrayEquals(expected, ShortArray(values.size).also { actualShorts.get(it) })
        for (channels in listOf(1, 2)) for (rate in listOf(100, 44100, 48000)) {
            val frames = rate / 50 + 3
            val pcm = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(frames * channels) { putShort(values[it % values.size]) }
            }.array()
            val official = PcmAudioUtil.rampUpVolume(ByteBuffer.wrap(pcm), C.ENCODING_PCM_16BIT,
                channels * 2, 0, rate / 50)
            assertArrayEquals("Pinned Media3 ramp rate=$rate channels=$channels",
                ByteArray(official.remaining()).also { official.get(it) }, rampPcm(pcm, rate, channels))
        }
    }

    private fun nativePcm(segments: List<Segment>): ByteArray = segments.flatMapIndexed { index, segment ->
        val pcm = requireNotNull(segment.pcm)
        val previous = segments.getOrNull(index - 1)
        // A rate/channel change recreates the sink. Same-format automatic seams must retain the
        // authored samples exactly: never normalize a per-track fade or unexpected extra reset.
        (if (previous == null || previous.rate != segment.rate || previous.channels != segment.channels)
            rampPcm(pcm, segment.rate, segment.channels) else pcm).asIterable()
    }.toByteArray()

    /** Exact PCM16 envelope applied by DefaultAudioSink 1.10.1 before AudioOutput.write.
     * https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/PcmAudioUtil.java
     */
    private fun rampPcm(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        val rampFrames = rate / 50 // DefaultAudioSink.AUDIO_OUTPUT_VOLUME_RAMP_TIME_MS = 20.
        val output = pcm.copyOf()
        val values = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        repeat(minOf(rampFrames, pcm.size / (channels * 2))) { frame ->
            repeat(channels) { channel ->
                val offset = (frame * channels + channel) * 2
                val pcm32 = values.getShort(offset).toLong() shl 16
                values.putShort(offset, ((pcm32 * frame / rampFrames) shr 16).toShort())
            }
        }
        return output
    }

    private data class Segment(val file: File, val rate: Int, val frames: Int, val pcm: ByteArray?, val channels: Int)
    private fun pcmSegments(directory: File, sizes: List<Pair<Int, Int>>, channels: Int = 2): List<Segment> {
        var seed = 0x13579bdf
        return sizes.mapIndexed { index, (rate, frames) ->
            val pcm = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames * channels) {
                // One deterministic wideband signal split into files; long prefixes identify sources.
                seed = seed * 1664525 + 1013904223
                pcm.putShort(((seed ushr 17) - 16384).toShort())
            }
            val file = File(directory, "segment-$index.wav")
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
                .putShort((channels * 2).toShort()).putShort(16)
                .put("data".toByteArray()).putInt(pcm.capacity())
            file.outputStream().use { it.write(header.array()); it.write(pcm.array()) }
            Segment(file, rate, frames, pcm.array(), channels)
        }
    }
    private fun encodedSegments(directory: File, extension: String): List<Segment> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val encoded = List(2) { index -> File(directory, "encoded-$index.$extension").also { file ->
            assets.open("boundary.$extension").use { input -> file.outputStream().use(input::copyTo) }
        } }
        val tail = pcmSegments(directory, listOf(44100 to 44100), channels = 1).single()
        return encoded.map { Segment(it, 44100, 44100, null, channels = 1) } + tail
    }
    private fun files(test: suspend (File) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "gapless-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        var completed = false
        try { withTimeout(120000) { test(directory) }; completed = true }
        // Failed cleanup may leave a reader open; retain this private fixture rather than unlinking it.
        finally { if (completed) directory.deleteRecursively() }
    }
    private class Session(val label: String, val player: ExoPlayer, val queues: QueueController?,
        val probe: NativeAudioOutputProbe, val automatic: AtomicInteger, val rebuffer: AtomicInteger,
        val decoders: List<String>) {
        suspend fun awaitMain(condition: () -> Boolean) = withTimeout(45000) {
            while (!withContext(Dispatchers.Main) {
                player.playerError?.let { throw AssertionError("Native playback failed for $label", it) }
                condition()
            }) delay(10)
        }
    }
    private suspend fun runPlayer(label: String, segments: List<Segment>, controlled: Boolean,
        preload: Boolean = true, test: suspend Session.() -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = java.util.UUID.randomUUID().toString()
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("gapless-$id", mode)
        }).apply { write(QueueSettings(preloadNext = preload)) }
        val database = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val tracks = segments.mapIndexed { index, segment -> Track(Uri.fromFile(segment.file).toString(), "Segment $index",
            fileName = segment.file.name, relativePath = segment.file.name) }
        val entries = tracks.mapIndexed { index, track -> QueueEntry(id = index.toString(), track = track) }
        val queue = ListeningQueue(name = "Gapless diagnostic", entries = entries, currentEntryId = entries.first().id)
        withContext(Dispatchers.IO) { database.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id)) }
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val readers = NativeReaders()
        val automatic = AtomicInteger()
        val rebuffer = AtomicInteger()
        val decoders = CopyOnWriteArrayList<String>()
        val probe = NativeAudioOutputProbe(context)
        val sources = DefaultDataSource.Factory(context)
        val observedSources = DataSource.Factory {
            val source = sources.createDataSource()
            object : DataSource by source {
                override fun open(dataSpec: DataSpec): Long = source.open(dataSpec).also {
                    Log.i("GaplessProbe", "case=$label event=sourceOpen file=${dataSpec.uri.lastPathSegment} atNs=${System.nanoTime()}")
                }
            }
        }
        val player = withContext(Dispatchers.Main) {
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean): AudioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioOutputProvider(probe.provider).build()
            }
            ExoPlayer.Builder(context, renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(readers.track(observedSources)))
                .build().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                    volume = 0f
                    setPreloadConfiguration(if (preload) ExoPlayer.PreloadConfiguration(5_000_000L) else ExoPlayer.PreloadConfiguration.DEFAULT)
                    addListener(object : Player.Listener {
                        var ready = false
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) ready = true
                            if (state == Player.STATE_BUFFERING && ready) rebuffer.incrementAndGet()
                        }
                        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) automatic.incrementAndGet()
                            Log.i("GaplessProbe", "case=$label event=transition item=${item?.mediaId} reason=$reason atNs=${System.nanoTime()}")
                        }
                        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                            Log.i("GaplessProbe", "case=$label event=timeline windows=${timeline.windowCount} reason=$reason atNs=${System.nanoTime()}")
                        }
                    })
                    addAnalyticsListener(object : AnalyticsListener {
                        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime,
                            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                            decoders.add(decoderName)
                        }
                    })
                }
        }
        val queues = if (controlled) QueueController(database.queues(), preferences,
            (context.applicationContext as LocalicaApplication).library, actor, readers) else null
        val sampler = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sampling = sampler.launch { while (isActive) { probe.sample(); delay(5) } }
        var primary: Throwable? = null
        try {
            withContext(Dispatchers.Main) {
                if (queues != null) { queues.attach(player); queues.playbackRequested(true) }
                else {
                    player.setMediaItems(segments.mapIndexed { index, segment -> MediaItem.Builder()
                        .setMediaId(index.toString()).setUri(Uri.fromFile(segment.file)).build() })
                    player.prepare(); player.play()
                }
            }
            Session(label, player, queues, probe, automatic, rebuffer, decoders).test()
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally { withContext(NonCancellable) {
            val blocks = tracks.map { readers.block(it.id) }
            try {
                probe.report(label)
                withTimeout(10000) { sampling.cancelAndJoin(); actor.coroutineContext[Job]!!.cancelAndJoin() }
            } catch (cleanup: Throwable) {
                if (primary == null) primary = cleanup else primary!!.addSuppressed(cleanup)
            } finally {
                sampler.cancel()
                try {
                    withContext(Dispatchers.Main) { player.release() }
                    withTimeout(10000) { blocks.forEach { it.awaitClosed() } }
                } catch (cleanup: Throwable) {
                    if (primary == null) primary = cleanup else primary!!.addSuppressed(cleanup)
                } finally { blocks.forEach { it.release() }; database.close(); context.deleteSharedPreferences("gapless-$id") }
            }
            primary?.let { throw it }
        } }
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
