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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(input!!)
        val files = (0..2).map { File(root, "track$it.m4a") }
        files.forEach { assertTrue("Private input missing", it.isFile) }
        val label = (if (corrected) "corrected-" else "") + if (queue) "queue-$preload" else "static-$preload"
        val out = File(context.getExternalFilesDir(null), "private-boundary/$label").apply { mkdirs() }
        val events = File(out, "events.jsonl")
        events.writeText("")
        val traceOutput = args.getString("privateOutputTrace") == "true"
        // The optional per-write trace must not open a file for every output attempt.
        // Buffer only this diagnostic path; timestamps and all failure assertions remain unchanged.
        var eventWriter = if (traceOutput) events.bufferedWriter(bufferSize = 64 * 1024) else null
        val started = SystemClock.elapsedRealtime()
        fun event(kind: String, vararg values: Pair<String, Any?>) = synchronized(events) {
            val item = JSONObject().put("kind", kind).put("elapsedMs", SystemClock.elapsedRealtime() - started)
            values.forEach { (key, value) -> item.put(key, value ?: JSONObject.NULL) }
            val line = item.toString() + "\n"
            eventWriter?.write(line) ?: events.appendText(line)
        }
        val captureOutput = FileOutputStream(File(out, "rendered.pcm"))
        val acceptedOutput = if (traceOutput) FileOutputStream(File(out, "accepted-output.pcm")).buffered(256 * 1024) else null
        event("diagnostic-config", "outputTrace" to traceOutput, "physicalOutputProof" to false)
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
        val rawBindingSeen = java.util.concurrent.atomic.AtomicBoolean()
        val tracks = files.mapIndexed { i, f -> Track(f.toURI().toString(), "Private $i", durationMs = listOf(212171L,362636L,364401L)[i], fileName = f.name, relativePath = f.name) }
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
                                if (format.customData is MediaEditTrim) rawBindingSeen.set(true)
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
                    if (corrected && firstInputSeen && engine!!.playbackState == Player.STATE_READY) {
                        assertTrue("Expected corrected first input to carry its edit binding", firstInputBound)
                        assertTrue("Expected decoded PCM to retain its edit binding", rawBindingSeen.get())
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
            assertEquals(2, arrivals)
            assertEquals("Rebuffered after initial readiness", 0, rebuffers)
            assertEquals("Reported underrun", 0, underruns)
        } finally {
            instrumentation.runOnMainSync { engine?.let { controller?.detach(it); it.release() }; job.cancel() }
            runBlocking { job.join() }
            db.close()
            synchronized(captureOutput) { captureOutput.close() }
            acceptedOutput?.close()
            synchronized(events) {
                val writer = eventWriter
                eventWriter = null // A late native release callback may still append its evidence.
                writer?.close()
            }
            if (queue) preferences.write(original)
        }
    }
}
