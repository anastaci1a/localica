package io.github.anastaci1a.localica

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.*
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** Opt-in local diagnosis. Private inputs and decoded audio never belong in test assets or Git. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PrivateQueueBoundaryTest {
    @Test fun queueNaturalPreloadOn() = play(queue = true, preload = true)
    @Test fun queueNaturalPreloadOff() = play(queue = true, preload = false)
    @Test fun staticNaturalControl() = play(queue = false, preload = true)
    @Test fun correctedQueueNaturalPreloadOn() = play(queue = true, preload = true, corrected = true)
    @Test fun correctedQueueNaturalPreloadOff() = play(queue = true, preload = false, corrected = true)
    @Test fun correctedStaticNaturalControl() = play(queue = false, preload = true, corrected = true)

    private fun play(queue: Boolean, preload: Boolean, corrected: Boolean = false) {
        val args = InstrumentationRegistry.getArguments()
        val input = args.getString("privateBoundaryInput")
        assumeTrue("Requires explicitly supplied private local files", input != null)
        // Validate diagnostic parameters before creating/truncating any evidence output.
        val durations = args.getString("privateTrackDurationsMs")?.let { value ->
            val parts = value.split(',')
            require(parts.size == 3) { "privateTrackDurationsMs requires exactly three comma-separated durations" }
            parts.map { part ->
                require(part.matches(Regex("[0-9]+"))) { "Track durations must be positive integer milliseconds" }
                requireNotNull(part.toLongOrNull()?.takeIf { it > 0 }) { "Track duration is invalid or overflows Long" }
            }
        } ?: listOf(212171L, 362636L, 364401L)
        fun booleanArgument(name: String, default: Boolean): Boolean = when (val value = args.getString(name)) {
            null -> default
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("$name must be the literal true or false, got $value")
        }
        val expectedFirstInputBinding = booleanArgument("privateExpectedFirstInputEditBinding", true)
        val expectedRawBinding = booleanArgument("privateExpectedRawEditBinding", true)
        val checkEditBindings = corrected || args.containsKey("privateExpectedFirstInputEditBinding") ||
            args.containsKey("privateExpectedRawEditBinding")
        val outputLabel = args.getString("privateOutputLabel")
        require(outputLabel == null || outputLabel.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) {
            "privateOutputLabel must contain 1..80 ASCII letters/digits/underscores/hyphens and start with a letter/digit"
        }
        val traceOutput = booleanArgument("privateOutputTrace", false)
        val asyncEvidence = booleanArgument("privateAsyncEvidence", false)
        require(!asyncEvidence || traceOutput) { "Asynchronous evidence requires the explicit output trace" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(input!!)
        val files = (0..2).map { File(root, "track$it.m4a") }
        files.forEach { assertTrue("Private input missing", it.isFile) }
        val label = outputLabel ?: ((if (corrected) "corrected-" else "") + if (queue) "queue-$preload" else "static-$preload")
        val out = File(context.getExternalFilesDir(null), "private-boundary/$label")
        if (outputLabel != null) {
            require(!out.exists()) { "Explicit privateOutputLabel must name fresh evidence; refusing overwrite" }
            val parent = requireNotNull(out.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create evidence parent" }
            check(out.mkdir()) { "Cannot exclusively create fresh evidence directory" }
        } else {
            out.mkdirs()
        }
        val events = File(out, "events.jsonl")
        events.writeText("")
        val lateEvents = File(out, "late-events.jsonl").apply { writeText("") }
        fun evidenceOutput(file: File): java.io.OutputStream {
            val stream = FileOutputStream(file)
            return if (asyncEvidence) PrivateDiagnosticWriter(stream) else stream
        }
        // The optional per-write trace must not open a file for every output attempt.
        // Buffer only this diagnostic path; timestamps and all failure assertions remain unchanged.
        var eventWriter = if (traceOutput) java.io.BufferedWriter(
            java.io.OutputStreamWriter(evidenceOutput(events), Charsets.UTF_8), 64 * 1024) else null
        val started = SystemClock.elapsedRealtime()
        val traceWriteDelays = mutableListOf<JSONObject>()
        var traceWriteSlowCount = 0
        var traceWriteMaxNs = 0L
        fun event(kind: String, vararg values: Pair<String, Any?>) {
            val entered = SystemClock.elapsedRealtimeNanos()
            synchronized(events) {
                val acquired = SystemClock.elapsedRealtimeNanos()
                val item = JSONObject().put("kind", kind).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                values.forEach { (key, value) -> item.put(key, value ?: JSONObject.NULL) }
                val line = item.toString() + "\n"
                val writing = SystemClock.elapsedRealtimeNanos()
                // After playback teardown, late release callbacks use a separate append-only file.
                // Never reopen the async worker's file while a failed close may still own it.
                eventWriter?.write(line) ?: (if (traceOutput) lateEvents else events).appendText(line)
                val completed = SystemClock.elapsedRealtimeNanos()
                if (traceOutput) {
                    traceWriteMaxNs = maxOf(traceWriteMaxNs, completed - entered)
                    if (completed - entered >= 50_000_000L) {
                        traceWriteSlowCount++
                        // Keep bounded evidence in memory, not a recursive log write on this thread.
                        if (traceWriteDelays.size < 128) traceWriteDelays += JSONObject()
                            .put("eventKind", kind).put("entryNs", entered).put("acquiredNs", acquired)
                            .put("writingNs", writing).put("completedNs", completed)
                    }
                }
            }
        }
        val captureOutput = evidenceOutput(File(out, "rendered.pcm"))
        val acceptedOutput = if (traceOutput) evidenceOutput(File(out, "accepted-output.pcm")).buffered(256 * 1024) else null
        event("diagnostic-config", "outputTrace" to traceOutput, "asyncEvidence" to asyncEvidence,
            "asyncByteBudgetPerFile" to if (asyncEvidence) PrivateDiagnosticWriter.DEFAULT_MAX_OUTSTANDING_BYTES else 0,
            "physicalOutputProof" to false, "trackDurationsMs" to org.json.JSONArray(durations),
            "checkEditBindings" to checkEditBindings,
            "expectedFirstInputEditBinding" to expectedFirstInputBinding, "expectedRawEditBinding" to expectedRawBinding)
        var byteCount = 0L
        val capture = object : BaseAudioProcessor() {
            override fun onConfigure(format: AudioFormat): AudioFormat {
                event("processor-format", "rate" to format.sampleRate, "channels" to format.channelCount, "encoding" to format.encoding)
                return format
            }
            override fun queueInput(inputBuffer: ByteBuffer) {
                if (!inputBuffer.hasRemaining()) return
                val data = ByteArray(inputBuffer.remaining())
                inputBuffer.get(data)
                val captureWriteStart = SystemClock.elapsedRealtimeNanos()
                synchronized(captureOutput) { captureOutput.write(data); byteCount += data.size }
                val captureWriteDuration = SystemClock.elapsedRealtimeNanos() - captureWriteStart
                if (traceOutput && captureWriteDuration >= 50_000_000L)
                    event("capture-write-delay", "entryNs" to captureWriteStart,
                        "durationNs" to captureWriteDuration, "bytes" to data.size, "capturedBytes" to byteCount)
                replaceOutputBuffer(data.size).put(data).flip()
            }
            override fun onFlush() { event("processor-flush", "bytes" to byteCount) }
        }
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val readers = NativeReaders()
        var controller: QueueController? = null
        var engine: ExoPlayer? = null
        var error: PlaybackException? = null
        var ready = false
        var rebuffers = 0
        var underruns = 0
        var arrivals = 0
        var done = false
        var firstInputSeen = false
        var firstInputBound = false
        val rawFormatSeen = java.util.concurrent.atomic.AtomicBoolean()
        val rawBindingSeen = java.util.concurrent.atomic.AtomicBoolean()
        val tracks = files.mapIndexed { i, f -> Track(f.toURI().toString(), "Private $i", durationMs = durations[i], fileName = f.name, relativePath = f.name) }
        fun assertEditBindings() {
            assertTrue("Expected first-track input format callback", firstInputSeen)
            assertEquals("First input edit binding", expectedFirstInputBinding, firstInputBound)
            assertTrue("Expected an actual decoded RAW format callback", rawFormatSeen.get())
            assertEquals("First decoded RAW edit binding", expectedRawBinding, rawBindingSeen.get())
        }
        var playbackFailure: Throwable? = null
        try {
            if (queue) preferences.write(QueueSettings(preloadNext = preload))
            instrumentation.runOnMainSync {
                val renderers = object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
                        val builder = DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(capture))
                        if (traceOutput) builder.setAudioOutputProvider(PrivateBoundaryOutputProvider(context,
                            { kind, fields -> event(kind, *fields) }, checkNotNull(acceptedOutput)))
                        val sink = builder.build()
                        val traced = object : ForwardingAudioSink(sink) {
                            override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
                                if (format.sampleMimeType == MimeTypes.AUDIO_RAW && !rawFormatSeen.get()) {
                                    rawBindingSeen.set(format.customData is MediaEditTrim)
                                    rawFormatSeen.set(true)
                                }
                                event("sink-configure", "rate" to format.sampleRate, "channels" to format.channelCount, "delay" to format.encoderDelay, "padding" to format.encoderPadding, "editBinding" to (format.customData is MediaEditTrim))
                                super.configure(format, specifiedBufferSize, outputChannels)
                            }
                            override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                                val before = buffer.remaining()
                                val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                                val consumed = before - buffer.remaining()
                                if (consumed > 0) event("sink-buffer", "ptsUs" to presentationTimeUs, "inputBytes" to consumed, "capturedBytes" to byteCount)
                                return handled
                            }
                            override fun setOutputStreamOffsetUs(offsetUs: Long) { event("stream-offset", "offsetUs" to offsetUs); super.setOutputStreamOffsetUs(offsetUs) }
                            override fun playToEndOfStream() { event("sink-end", "bytes" to byteCount); super.playToEndOfStream() }
                        }
                        return if (corrected) MediaEditAudioSink(traced) else traced
                    }
                }
                val p = ExoPlayer.Builder(context, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(readers.track(DefaultDataSource.Factory(context)), PlaybackExtractors(decodedEditBinding = corrected))).build()
                engine = p
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                p.volume = 0f
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(e: PlaybackException) { error = e; event("error", "message" to e.toString()) }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_BUFFERING && ready) rebuffers++
                        if (state == Player.STATE_READY) ready = true
                        event("state", "state" to state, "position" to p.currentPosition, "buffered" to p.totalBufferedDuration)
                    }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) arrivals++
                        event("transition", "reason" to reason, "index" to p.currentMediaItemIndex, "capturedBytes" to byteCount, "buffered" to p.totalBufferedDuration)
                    }
                    override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) { event("discontinuity", "reason" to reason, "oldIndex" to old.mediaItemIndex, "newIndex" to new.mediaItemIndex) }
                })
                p.addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioInputFormatChanged(time: AnalyticsListener.EventTime, format: Format, reuse: DecoderReuseEvaluation?) {
                        if (time.windowIndex == 0) {
                            firstInputSeen = true
                            firstInputBound = format.customData is MediaEditTrim
                        }
                        event("input-format", "window" to time.windowIndex, "mime" to format.sampleMimeType, "rate" to format.sampleRate, "channels" to format.channelCount, "delay" to format.encoderDelay, "padding" to format.encoderPadding, "reuse" to reuse?.result, "discardReasons" to reuse?.discardReasons, "maximumInputSize" to format.maxInputSize, "preroll" to format.hasPrerollSamples, "editBinding" to (format.customData is MediaEditTrim), "asc" to format.initializationData.joinToString { b -> b.joinToString("") { "%02x".format(it) } })
                    }
                    override fun onAudioDecoderInitialized(time: AnalyticsListener.EventTime, name: String, stamp: Long, duration: Long) { event("decoder-init", "decoder" to name, "durationMs" to duration) }
                    override fun onAudioDecoderReleased(time: AnalyticsListener.EventTime, name: String) { event("decoder-release", "decoder" to name) }
                    override fun onAudioUnderrun(time: AnalyticsListener.EventTime, size: Int, sizeMs: Long, sinceMs: Long) { underruns++; event("underrun", "bufferBytes" to size, "bufferMs" to sizeMs, "sinceFeedMs" to sinceMs) }
                    override fun onRendererReadyChanged(time: AnalyticsListener.EventTime, index: Int, trackType: Int, isReady: Boolean) { event("renderer-ready", "ready" to isReady, "type" to trackType) }
                })
                if (queue) {
                    controller = QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope, readers)
                    controller!!.attach(p)
                } else {
                    p.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(if (preload) 5_000_000L else 0L))
                    p.setMediaItems(tracks.mapIndexed { i, track -> queueMediaItem("private-$i", track) })
                    p.prepare(); p.play()
                }
            }
            fun poll(condition: () -> Boolean): Boolean {
                var result = false
                instrumentation.runOnMainSync {
                    error?.let { throw AssertionError("Playback error", it) }
                    if (checkEditBindings && firstInputSeen && engine!!.playbackState == Player.STATE_READY) {
                        assertEditBindings()
                    }
                    result = condition()
                }
                return result
            }
            if (queue) {
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (!poll { !controller!!.state.value.loading }) { assertTrue(SystemClock.elapsedRealtime() < deadline); Thread.sleep(50) }
                instrumentation.runOnMainSync { controller!!.tap(tracks, 0, sourceName = "Private continuity") }
            }
            // Natural playback, normal speed, from track one through both boundaries and 12 seconds of track three.
            val deadline = SystemClock.elapsedRealtime() + 700_000
            var lastHeartbeat = 0L
            while (!done) {
                done = poll { arrivals == 2 && engine!!.currentPosition >= 12_000 }
                if (SystemClock.elapsedRealtime() - lastHeartbeat >= 10_000) {
                    instrumentation.runOnMainSync { event("heartbeat", "index" to engine!!.currentMediaItemIndex, "positionMs" to engine!!.currentPosition, "bufferedMs" to engine!!.totalBufferedDuration, "state" to engine!!.playbackState, "problem" to controller?.state?.value?.problem) }
                    lastHeartbeat = SystemClock.elapsedRealtime()
                }
                assertTrue("Natural boundary timeout", SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(100)
            }
            event("summary", "arrivals" to arrivals, "rebuffers" to rebuffers, "underruns" to underruns, "bytes" to byteCount, "queue" to queue, "preload" to preload)
            if (checkEditBindings) assertEditBindings()
            assertEquals(2, arrivals)
            assertEquals("Rebuffered after initial readiness", 0, rebuffers)
            assertEquals("Reported underrun", 0, underruns)
        } catch (failure: Throwable) {
            playbackFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val first = playbackFailure ?: cleanupFailure
                    if (first != null) { if (first !== failure) first.addSuppressed(failure) }
                    else cleanupFailure = failure
                }
            }
            cleanup { instrumentation.runOnMainSync {
                try { engine?.let { controller?.detach(it); it.release() } }
                finally { job.cancel() }
            } }
            cleanup { runBlocking { job.join() } }
            cleanup { db.close() }
            cleanup { synchronized(captureOutput) { captureOutput.close() } }
            cleanup { acceptedOutput?.close() }
            cleanup { synchronized(events) {
                // Keep diagnostic timing even when a codec/playback error exits before assertions.
                if (traceOutput) event("trace-write-summary", "maximumDurationNs" to traceWriteMaxNs,
                    "slowCount" to traceWriteSlowCount, "first128" to org.json.JSONArray(traceWriteDelays))
            } }
            cleanup { synchronized(events) {
                val writer = eventWriter
                eventWriter = null // Only post-playback callbacks can reach the separate late file.
                writer?.close()
            } }
            cleanup { if (queue) preferences.write(original) }
            cleanupFailure?.let { throw it }
        }
    }
}
