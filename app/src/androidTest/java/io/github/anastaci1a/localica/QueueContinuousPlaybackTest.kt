package io.github.anastaci1a.localica

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.MediaEditAudioSink
import io.github.anastaci1a.localica.playback.NativeReaders
import io.github.anastaci1a.localica.playback.PlaybackExtractors
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

    @Test fun staticPlaylistTrimsFullFrameM4a() = playSplitPcm(false, "boundary.m4a")

    @Test fun queueActorTrimsFullFrameM4a() = playSplitPcm(true, "boundary.m4a")

    @Test fun staticPlaylistTrimsShortFinalSampleM4a() = playSplitPcm(false, "boundary-edit-list.m4a")

    @Test fun queueActorTrimsShortFinalSampleM4a() = playSplitPcm(true, "boundary-edit-list.m4a")

    @Test fun staticPlaylistPreservesM4aSilentTail() = playSplitPcm(false, "boundary-silent-tail.m4a")

    @Test fun queueActorPreservesM4aSilentTail() = playSplitPcm(true, "boundary-silent-tail.m4a")

    @Test fun staticPlaylistPreservesRoundedEditPreroll() = playSplitPcm(false, "boundary-rounded-edit.m4a")

    @Test fun queueActorPreservesRoundedEditPreroll() = playSplitPcm(true, "boundary-rounded-edit.m4a")

    @Test fun staticPlaylistBindsRoundedAmbiguousConfigToDecodedRate() = playSplitPcm(false, "boundary-rounded-short-config.m4a")

    @Test fun queueActorBindsRoundedAmbiguousConfigToDecodedRate() = playSplitPcm(true, "boundary-rounded-short-config.m4a")

    @Test fun staticPlaylistValidatesRoundedFastStartMediaRanges() = playSplitPcm(false, "boundary-rounded-streamable.m4a")

    @Test fun queueActorValidatesRoundedFastStartMediaRanges() = playSplitPcm(true, "boundary-rounded-streamable.m4a")

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

    private fun playSplitPcm(throughQueue: Boolean, encodedFixture: String? = null) {
        val rounded = encodedFixture?.startsWith("boundary-rounded-") == true
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val readers = NativeReaders()
        val capture = ContinuityCapture()
        val startup = ContinuityStartupDiagnostics(encodedFixture, throughQueue)
        val probe = ContinuityEvents(startup.id)
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
                val encoded = encodedFixture != null && index < 2
                val file = File.createTempFile("queue-continuity-", if (encoded) ".m4a" else ".wav", context.cacheDir)
                files += file
                if (encoded) {
                    instrumentation.context.assets.open(encodedFixture!!).use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                } else {
                    val pcm = if (encodedFixture == null) samples.copyOfRange(index * SAMPLE_RATE, (index + 1) * SAMPLE_RATE)
                        else ShortArray(SAMPLE_RATE) { sourceSample(it).toShort() }
                    writeWav(file, pcm)
                }
            }
            val tracks = files.mapIndexed { index, file ->
                Track(file.toURI().toString(), "Split $index", durationMs = if (rounded && index < 2) 1009 else 1000,
                    fileName = file.name, relativePath = file.name)
            }
            if (throughQueue) preferences.write(QueueSettings())
            instrumentation.runOnMainSync {
                val renderers = object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean): AudioSink = MediaEditAudioSink(DefaultAudioSink.Builder(context)
                        .setAudioOutputProvider(ContinuityOutputProvider(context, startup))
                        .setAudioProcessors(arrayOf(capture)).build())
                }
                // Match PlaybackService's source ownership and audio attributes. Test seams are
                // pass-through PCM capture, muting, isolated storage and disabled focus handling.
                val engine = ExoPlayer.Builder(context, renderers)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(readers.track(DefaultDataSource.Factory(context)), PlaybackExtractors(decodedEditBinding = rounded)))
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
                    startup.request("attach.begin")
                    queues!!.attach(engine)
                    startup.request("attach.return")
                } else {
                    engine.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(5_000_000L))
                    startup.request("setMediaItems.begin")
                    engine.setMediaItems(tracks.mapIndexed { index, track -> queueMediaItem("control-$index", track) })
                    startup.request("setMediaItems.return")
                    startup.request("prepare.begin")
                    engine.prepare()
                    startup.request("prepare.return")
                    startup.request("play.begin")
                    engine.play()
                    startup.request("play.return")
                }
            }
            if (throughQueue) {
                awaitMain { !queues!!.state.value.loading }
                instrumentation.runOnMainSync {
                    startup.request("tap.begin")
                    queues!!.tap(tracks, 0, sourceName = "Continuous PCM")
                    startup.request("tap.return")
                }
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
                val decoded = capture.snapshot()
                val waveformErrors = if (encodedFixture == null) emptyList() else encodedErrors(decoded, encodedFixture)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(decoded)
                    .joinToString("") { "%02x".format(it) }
                // Emit evidence before assertions, including on the known shortened-sample failure.
                Log.i("QueueContinuity", "fixture=$encodedFixture queue=$throughQueue bytes=${decoded.size} " +
                    "sha256=$digest arrivals=${probe.arrivals.size} discontinuities=${probe.discontinuities} " +
                    "rebuffers=${probe.rebuffers} underruns=${probe.underruns} " +
                    "unexpected=${probe.unexpectedTransitions} waveformErrors=$waveformErrors")
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
                if (encodedFixture == null) {
                    assertArrayEquals("Decoded PCM must retain every sample and intentional silence", expected.array(), decoded)
                } else {
                    assertTrue("$encodedFixture queue=$throughQueue: $waveformErrors", waveformErrors.isEmpty())
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
            // New verbose diagnostics are deferred until the player has been released.
            try { startup.dump() } catch (failure: RuntimeException) {
                // Diagnostic formatting must not mask an assertion or skip owned cleanup.
                Log.w("ContinuityStartup", "Diagnostic dump unavailable", failure)
            }
            runBlocking { job.join() }
            db.close()
            files.forEach(File::delete)
            if (throughQueue) preferences.write(original)
        }
    }

    // assets/README.txt defines the original one-second sawtooth. Both MP4 edit lists
    // declare 1,000 movie ticks at timescale 1,000; audio is mono 44,100 Hz, with
    // 1,024 priming samples. Expected duration comes from that provenance, not a decode.
    private fun sourceSample(index: Int) = ((index % 441) - 220) * 80

    private fun encodedErrors(decoded: ByteArray, fixture: String): List<String> {
        val errors = mutableListOf<String>()
        val rounded = fixture.startsWith("boundary-rounded-")
        val encodedFrames = if (rounded) 44480 else SAMPLE_RATE
        val expectedFrames = encodedFrames * 2 + SAMPLE_RATE
        if (decoded.size != expectedFrames * 2) errors += "expected $expectedFrames samples; got ${decoded.size / 2}"
        val pcm = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        // Lossy AAC cannot equal the original PCM byte-for-byte. Compare unshifted
        // source windows near both ends of each encoded track. A 25% source-amplitude
        // RMS budget allows codec ringing; never align the observed data to hide delay.
        for (start in listOf(2048, 40000, encodedFrames + 2048, encodedFrames + 40000)) {
            if (start + 2048 > pcm.limit()) { errors += "missing waveform window $start"; continue }
            val rms = kotlin.math.sqrt((start until start + 2048).sumOf { i ->
                val sourceIndex = if (rounded) i % encodedFrames + 576 else i
                val source = if (fixture == "boundary-silent-tail.m4a" && i % SAMPLE_RATE >= 33075) 0 else sourceSample(sourceIndex)
                val error = pcm[i].toDouble() - source
                error * error
            } / 2048)
            Log.i("QueueContinuity", "waveform start=$start rms=$rms limit=2546")
            if (rms >= 2546) errors += "unaligned/distorted waveform at $start RMS=$rms"
        }
        if (fixture == "boundary-silent-tail.m4a") {
            for (track in 0..1) {
                val silenceStart = track * SAMPLE_RATE + 36000
                val silenceEnd = track * SAMPLE_RATE + 40000
                if (silenceEnd > pcm.limit() || (silenceStart until silenceEnd).any { kotlin.math.abs(pcm[it].toInt()) > 8 })
                    errors += "intentional AAC silence lost or shifted in track $track"
            }
        }
        // The lossless tail must start at the independently expected encoded endpoint.
        val tailStart = encodedFrames * 2
        val mismatch = (0 until SAMPLE_RATE).firstOrNull { i ->
            tailStart + i >= pcm.limit() || pcm[tailStart + i].toInt() != sourceSample(i)
        }
        if (mismatch != null) errors += "PCM tail differs at expected sample ${tailStart + mismatch}"
        // This periodic fixture cannot identify shifts by a whole period inside an
        // encoded window. Exact duration and the tail add independent boundary checks.
        return errors
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

/** Observe the native provider without changing its configuration, buffers or return values. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ContinuityOutputProvider(
    context: Context,
    private val startup: ContinuityStartupDiagnostics,
    private val native: AudioOutputProvider = AudioTrackAudioOutputProvider.Builder(context).build(),
) : AudioOutputProvider by native {
    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        val beginNs = SystemClock.elapsedRealtimeNanos()
        val config = native.getOutputConfig(formatConfig)
        startup.configuration(formatConfig, config, beginNs, SystemClock.elapsedRealtimeNanos())
        return config
    }
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val beginNs = SystemClock.elapsedRealtimeNanos()
        val output = native.getAudioOutput(config)
        val createdNs = SystemClock.elapsedRealtimeNanos()
        val diagnostic = ContinuityOutputDiagnostics(output, config, beginNs, createdNs).also(startup::output)
        return object : AudioOutput by output {
            private val identity = System.identityHashCode(output)
            private val frameSize = Util.getPcmFrameSize(config.encoding, Integer.bitCount(config.channelMask))
            private var writtenBytes = 0L
            private fun trace(detail: String) {
                Log.i("ContinuityOutput", "timeNs=${SystemClock.elapsedRealtimeNanos()} association=${startup.id} output=$identity $detail")
            }
            init {
                trace("created rate=${config.sampleRate} encoding=${config.encoding} " +
                    "channelMask=${config.channelMask} capacityBytes=${config.bufferSize}")
                output.addListener(object : AudioOutput.Listener {
                    override fun onUnderrun() {
                        // Called from the native output's counter poll, before the next write.
                        // Position is Media3's output position, not a raw hardware-head reading.
                        diagnostic.underrun(writtenBytes / frameSize)
                        trace("underrun writtenFrames=${writtenBytes / frameSize} " +
                            "outputPositionUs=${output.positionUs} recentWrites=deferred")
                    }
                    override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
                        trace("positionAdvancing startSystemMs=$playoutStartSystemTimeMs")
                    }
                    override fun onReleased() { trace("released") }
                    override fun onOffloadDataRequest() { trace("offloadDataRequest") }
                    override fun onOffloadPresentationEnded() { trace("offloadPresentationEnded") }
                })
            }
            override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
                val before = buffer.position()
                val sequence = diagnostic.beginWrite(buffer.remaining(), presentationTimeUs, encodedAccessUnitCount,
                    writtenBytes / frameSize)
                var handled = -1L
                try {
                    val result = output.write(buffer, encodedAccessUnitCount, presentationTimeUs)
                    handled = if (result) 1L else 0L
                    return result
                } finally {
                    val returnNs = SystemClock.elapsedRealtimeNanos()
                    val accepted = buffer.position() - before
                    writtenBytes += accepted
                    diagnostic.endWrite(sequence, returnNs, accepted, writtenBytes / frameSize, handled)
                }
            }
            override fun play() {
                trace("play writtenFrames=${writtenBytes / frameSize}")
                diagnostic.event("play.begin", writtenBytes / frameSize)
                output.play()
                diagnostic.event("play.return", writtenBytes / frameSize)
            }
            override fun stop() {
                trace("stop writtenFrames=${writtenBytes / frameSize}")
                diagnostic.event("stop.begin", writtenBytes / frameSize)
                output.stop()
                diagnostic.event("stop.return", writtenBytes / frameSize)
            }
            override fun flush() {
                trace("flush writtenFrames=${writtenBytes / frameSize}")
                diagnostic.event("flush.begin", writtenBytes / frameSize)
                output.flush()
                writtenBytes = 0
                diagnostic.event("flush.return", 0)
            }
        }
    }
}

/** Bounded observations only: no new wait, native command, buffer policy or PCM transformation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ContinuityStartupDiagnostics(fixture: String?, throughQueue: Boolean) {
    val id = SystemClock.elapsedRealtimeNanos()
    private val label = "association=$id fixture=$fixture queue=$throughQueue"
    // Request return means caller return, not queue-actor completion/acceptance.
    // Requests are Main-owned; configurations/outputs are playback-thread-owned. Dump only
    // after ExoPlayer.release returns, never format new per-write strings during feeding.
    // Publish completed slots last: a release timeout must not expose a half-filled record.
    private val requestNames = arrayOfNulls<String>(16)
    private val requestTimes = LongArray(16)
    private var requestCount = 0
    private val formats = arrayOfNulls<AudioOutputProvider.FormatConfig>(16)
    private val configs = arrayOfNulls<AudioOutputProvider.OutputConfig>(16)
    private val configTimes = LongArray(32)
    @Volatile private var configCount = 0
    private val outputs = arrayOfNulls<ContinuityOutputDiagnostics>(8)
    @Volatile private var outputCount = 0

    fun request(name: String) {
        val index = requestCount++
        if (index >= requestNames.size) return
        requestTimes[index] = SystemClock.elapsedRealtimeNanos()
        requestNames[index] = name
    }
    fun configuration(format: AudioOutputProvider.FormatConfig, config: AudioOutputProvider.OutputConfig,
        beginNs: Long, returnNs: Long) {
        val index = configCount
        if (index < configs.size) {
            formats[index] = format; configs[index] = config
            configTimes[index * 2] = beginNs; configTimes[index * 2 + 1] = returnNs
        }
        configCount = index + 1
    }
    fun output(diagnostic: ContinuityOutputDiagnostics) {
        val index = outputCount
        if (index < outputs.size) outputs[index] = diagnostic
        outputCount = index + 1
    }
    fun dump() {
        Log.i("ContinuityStartup", "$label requests=$requestCount configs=$configCount outputs=$outputCount " +
            "limits=request16/config16/output8/write32/event64/underrun4x16 " +
            "nativeReads=sequential-not-atomic observerCostIsNonzero counterPollIsNotStarvationOnset " +
            "headIsUnsigned32NotAcousticProof dumpAfterReleaseReturn=true")
        repeat(minOf(requestCount, requestNames.size)) { index ->
            Log.i("ContinuityStartup", "$label request=${requestNames[index]} timeNs=${requestTimes[index]}")
        }
        repeat(minOf(configCount, configs.size)) { index ->
            val request = formats[index] ?: return@repeat
            val format = request.format
            val config = configs[index] ?: return@repeat
            Log.i("ContinuityStartup", "$label config=$index beginNs=${configTimes[index * 2]} " +
                "returnNs=${configTimes[index * 2 + 1]} inputRate=${format.sampleRate} channels=${format.channelCount} " +
                "pcmEncoding=${format.pcmEncoding} delay=${format.encoderDelay} padding=${format.encoderPadding} " +
                "preferredBytes=${request.preferredBufferSize} rate=${config.sampleRate} " +
                "encoding=${config.encoding} channelMask=${config.channelMask} capacityBytes=${config.bufferSize}")
        }
        repeat(minOf(outputCount, outputs.size)) { outputs[it]?.dump(label) }
    }
}

/** Public Media3 1.10.1 AudioTrackAudioOutput.getAudioTrack(); no reflection or replacement output. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ContinuityOutputDiagnostics(output: AudioOutput, private val config: AudioOutputProvider.OutputConfig,
    private val createBeginNs: Long, private val createReturnNs: Long) {
    private val identity = System.identityHashCode(output)
    private val track = (output as? AudioTrackAudioOutput)?.audioTrack
    private val first = LongArray(32 * WIDTH)
    private val recent = LongArray(16 * WIDTH)
    @Volatile private var writeCount = 0
    @Volatile private var completedWrites = 0
    private val eventNames = arrayOfNulls<String>(64)
    private val events = LongArray(64 * 6)
    @Volatile private var eventCount = 0
    private val underrunHistory = LongArray(4 * 16 * WIDTH)
    private val underrunWriteCounts = IntArray(4)
    @Volatile private var underrunCount = 0

    // -1: not exposed by this output; -2: public getter failed; -3: outside startup sample.
    // Getter failure is diagnostic data, never a reason to change native control flow.
    private fun underruns(): Long = try { track?.underrunCount?.toLong() ?: -1L } catch (_: RuntimeException) { -2L }
    private fun head(): Long = try { track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: -1L } catch (_: RuntimeException) { -2L }
    init { event("created", 0) }

    fun event(name: String, writtenFrames: Long) {
        val index = eventCount
        if (index < eventNames.size) {
            eventNames[index] = name
            val offset = index * 6
            events[offset] = SystemClock.elapsedRealtimeNanos()
            events[offset + 1] = writtenFrames
            events[offset + 2] = writeCount.toLong()
            events[offset + 3] = underruns()
            events[offset + 4] = head()
            events[offset + 5] = SystemClock.elapsedRealtimeNanos()
        }
        eventCount = index + 1
    }
    fun underrun(writtenFrames: Long) {
        event("underrun", writtenFrames)
        val index = underrunCount
        if (index < underrunWriteCounts.size) {
            underrunWriteCounts[index] = writeCount
            val start = maxOf(0, writeCount - 16)
            for (sequence in start until writeCount) {
                val from = (sequence % 16) * WIDTH
                recent.copyInto(underrunHistory, (index * 16 + sequence - start) * WIDTH, from, from + WIDTH)
            }
        }
        underrunCount = index + 1
    }
    fun beginWrite(requested: Int, pts: Long, units: Int, writtenFrames: Long): Int {
        val sequence = writeCount++
        val offset = (sequence % 16) * WIDTH
        java.util.Arrays.fill(recent, offset, offset + WIDTH, -1L)
        recent[offset] = sequence.toLong()
        recent[offset + 1] = SystemClock.elapsedRealtimeNanos()
        recent[offset + 5] = pts
        recent[offset + 6] = requested.toLong()
        recent[offset + 10] = if (sequence < 32) underruns() else -3L
        recent[offset + 11] = if (sequence < 32) head() else -3L
        recent[offset + 14] = writtenFrames
        recent[offset + 15] = units.toLong()
        recent[offset + 2] = SystemClock.elapsedRealtimeNanos()
        return sequence
    }
    fun endWrite(sequence: Int, returnNs: Long, accepted: Int, writtenFrames: Long, handled: Long) {
        val offset = (sequence % 16) * WIDTH
        recent[offset + 3] = returnNs
        recent[offset + 7] = accepted.toLong()
        recent[offset + 8] = writtenFrames
        recent[offset + 9] = handled
        recent[offset + 12] = if (sequence < 32) underruns() else -3L
        recent[offset + 13] = if (sequence < 32) head() else -3L
        recent[offset + 4] = SystemClock.elapsedRealtimeNanos()
        if (sequence < 32) recent.copyInto(first, sequence * WIDTH, offset, offset + WIDTH)
        completedWrites = sequence + 1
    }
    fun dump(label: String) {
        val prefix = "$label output=$identity"
        Log.i("ContinuityStartup", "$prefix createBeginNs=$createBeginNs createReturnNs=$createReturnNs " +
            "rawAudioTrackAvailable=${track != null} rate=${config.sampleRate} capacityBytes=${config.bufferSize} " +
            "writes=$writeCount completedWrites=$completedWrites events=$eventCount underruns=$underrunCount")
        Log.i("ContinuityStartup", "$prefix writeColumns=sequence,sampleBeginNs,callNs,returnNs,sampleEndNs," +
            "ptsUs,requestedBytes,acceptedBytes,totalFrames,handled,underrunsBefore,headBefore," +
            "underrunsAfter,headAfter,framesBefore,accessUnits")
        repeat(minOf(completedWrites, 32)) { index ->
            Log.i("ContinuityStartup", "$prefix write=" + first.sliceArray(index * WIDTH until (index + 1) * WIDTH).joinToString(","))
        }
        // Snapshots include the in-flight write at the native counter poll; its return
        // columns are -1, not an invented zero-byte write or a known hardware-event time.
        repeat(minOf(underrunCount, underrunWriteCounts.size)) { index ->
            repeat(minOf(underrunWriteCounts[index], 16)) { row ->
                val offset = (index * 16 + row) * WIDTH
                Log.i("ContinuityStartup", "$prefix underrunSnapshot=$index write=" +
                    underrunHistory.sliceArray(offset until offset + WIDTH).joinToString(","))
            }
        }
        Log.i("ContinuityStartup", "$prefix eventColumns=sampleBeginNs,writtenFrames,writeCalls,underruns,head,sampleEndNs")
        repeat(minOf(eventCount, eventNames.size)) { index ->
            Log.i("ContinuityStartup", "$prefix event=${eventNames[index]} " + events.sliceArray(index * 6 until (index + 1) * 6).joinToString(","))
        }
    }
    private companion object { const val WIDTH = 16 }
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
private class ContinuityEvents(private val association: Long) : Player.Listener, AnalyticsListener {
    private val startedMs = SystemClock.elapsedRealtime()
    private var playbackState = Player.STATE_IDLE
    private fun trace(detail: String) {
        Log.i("QueueContinuityEvent", "association=$association timeNs=${SystemClock.elapsedRealtimeNanos()} " +
            "elapsedMs=${SystemClock.elapsedRealtime() - startedMs} $detail")
    }
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
        this.playbackState = playbackState
        trace("state=$playbackState")
        if (playbackState == Player.STATE_BUFFERING && ready) rebuffers++
        if (playbackState == Player.STATE_READY) ready = true
        if (playbackState == Player.STATE_ENDED) ended = true
    }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        trace("transition=$reason")
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
        val detail = "bufferBytes=$bufferSize bufferMs=$bufferSizeMs sinceFeedMs=$elapsedSinceLastFeedMs " +
            "eventRealtimeMs=${eventTime.realtimeMs} eventWindow=${eventTime.windowIndex} " +
            "eventPositionMs=${eventTime.eventPlaybackPositionMs} currentWindow=${eventTime.currentWindowIndex} " +
            "currentPositionMs=${eventTime.currentPlaybackPositionMs} bufferedMs=${eventTime.totalBufferedDurationMs} " +
            "state=$playbackState"
        underruns += detail
        trace("underrun $detail")
    }
    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
        trace("positionAdvancing eventRealtimeMs=${eventTime.realtimeMs} " +
            "window=${eventTime.windowIndex} positionMs=${eventTime.eventPlaybackPositionMs} " +
            "playoutStartSystemTimeMs=$playoutStartSystemTimeMs")
    }
}
