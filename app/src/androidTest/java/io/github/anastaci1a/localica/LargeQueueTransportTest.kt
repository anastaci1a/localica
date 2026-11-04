package io.github.anastaci1a.localica

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Native synthetic-provider diagnostics; shared fixture bytes do not model real-provider or phone latency. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LargeQueueTransportTest {
    @Test fun decodedProbeAcceptsEmptyInputWithoutChangingPcm() {
        val firstDecoded = AtomicLong()
        val processor = FirstDecodedProcessor(firstDecoded)
        processor.configure(AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT)); processor.flush()
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertFalse(processor.output.hasRemaining())
        assertEquals(0L, firstDecoded.get())
        val bytes = byteArrayOf(1, 2, 3, 4)
        val input = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { flip() }
        processor.queueInput(input)
        assertFalse(input.hasRemaining())
        val actual = ByteArray(bytes.size)
        processor.output.get(actual)
        assertArrayEquals(bytes, actual)
        assertTrue(firstDecoded.get() > 0)
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertFalse(processor.output.hasRemaining())
        processor.reset()
    }
    @Test fun distinctDocumentQueueStartsDecodesAndCommitsSeekAndNextCursors() = exerciseTransport(pendingSave = false)
    @Test fun nextAndSeekSubmittedBeforeInitialSaveWaitForTheMatchingCommit() = exerciseTransport(pendingSave = true)

    @Test fun measureLargeQueueTransportLatency() {
        org.junit.Assume.assumeTrue("Opt-in emulator latency diagnostic",
            InstrumentationRegistry.getArguments().getString("largeQueueLatency") == "true")
        for (size in listOf(1000, 6500, 13000)) exerciseTransport(false, size, measure = true)
    }

    private fun exerciseTransport(pendingSave: Boolean, size: Int = 13_000, measure: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        assertEquals(QueueScaleGrantActivity.TARGET, context.packageName)
        fun fixtureGrant(granted: Boolean) {
            context.startActivity(Intent(if (granted) QueueScaleGrantActivity.GRANT else QueueScaleGrantActivity.REVOKE)
                .setClassName(instrumentation.context.packageName, QueueScaleGrantActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val probe = DocumentsContract.buildDocumentUriUsingTree(QueueScaleGrantActivity.TREE, "scale-0.mp3")
        val tracks = List(size) { index ->
            val name = "scale-$index.mp3"
            val uri = DocumentsContract.buildDocumentUriUsingTree(QueueScaleGrantActivity.TREE, name).toString()
            Track(uri, "Track $index", durationMs = 20_000, fileName = name, relativePath = name)
        }
        assertEquals(size, tracks.map { it.uri }.toSet().size)
        val databaseName = "queue-scale-transport.db"
        context.deleteDatabase(databaseName)
        val db = Room.databaseBuilder(context, QueueDatabase::class.java, databaseName).build()
        val dao = ObservedQueueDao(db.queues())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val firstDecoded = AtomicLong()
        val firstReady = AtomicLong()
        val firstPlaybackError = AtomicReference<PlaybackException?>()
        val pendingRequestedAt = AtomicLong()
        val pendingReturnedAt = AtomicLong()
        val pendingNext = AtomicReference<QueueVisit?>()
        val pendingNativeState = AtomicReference<String>()
        lateinit var player: ExoPlayer
        lateinit var queues: QueueController
        var createdPlayer: ExoPlayer? = null
        var createdQueues: QueueController? = null
        fun checkPlayback() {
            firstPlaybackError.get()?.let { throw AssertionError("Native fixture playback failed", it) }
        }
        fun ready(entryId: String, positionMs: Long? = null): Boolean {
            checkPlayback()
            var result = false
            instrumentation.runOnMainSync {
                result = player.currentMediaItem?.mediaId == entryId && player.playbackState == Player.STATE_READY &&
                    (positionMs == null || player.currentPosition == positionMs)
            }
            return result
        }
        fun checkpoint(entryId: String, positionMs: Long, after: Long): Long {
            await(timeoutMs = 30_000) {
                checkPlayback()
                dao.completed.any { it.at >= after && it.library.active?.let { q ->
                    q.currentEntryId == entryId && q.positionMs == positionMs } == true }
            }
            assertNull(queues.state.value.problem)
            return dao.completed.first { it.at >= after && it.library.active?.let { q ->
                q.currentEntryId == entryId && q.positionMs == positionMs } == true }.at
        }
        fun seek(action: String, entryId: String, positionMs: Long, command: () -> Unit) {
            val requestedAt = SystemClock.elapsedRealtime()
            command()
            await(timeoutMs = 30_000) { ready(entryId, positionMs) }
            val readyAfter = SystemClock.elapsedRealtime() - requestedAt
            val committedAt = checkpoint(entryId, positionMs, requestedAt)
            instrumentation.runOnMainSync {
                assertFalse(player.playWhenReady)
                assertEquals(queues.state.value.library.active!!.journey!!.current!!.token,
                    player.currentMediaItem!!.mediaMetadata.extras!!.getString("queueVisit"))
                assertTrue(player.mediaItemCount <= 129)
            }
            Log.i("QueueScale", "documents=$size sharedBytes=true action=$action readyMs=$readyAfter commitMs=${committedAt - requestedAt}")
        }
        try {
            preferences.write(QueueSettings())
            fixtureGrant(true)
            // Verify the target UID can open the granted tree before timing; this warms the shared fixture bytes.
            await {
                try { context.contentResolver.openAssetFileDescriptor(probe, "r")?.use { true } ?: false }
                catch (_: SecurityException) { false }
            }
            context.contentResolver.call(probe, "resetStats", null, null)
            instrumentation.runOnMainSync {
                val processor = FirstDecodedProcessor(firstDecoded)
                val renderers = object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean): AudioSink = DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(processor)).build()
                }
                player = ExoPlayer.Builder(context, renderers).build().also { createdPlayer = it }.apply {
                    volume = 0f
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { firstPlaybackError.compareAndSet(null, error) }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) firstReady.compareAndSet(0, SystemClock.elapsedRealtime())
                        }
                    })
                }
                queues = QueueController(dao, preferences, (context.applicationContext as LocalicaApplication).library, scope)
                    .also { createdQueues = it }
                queues.attach(player)
            }
            await { !queues.state.value.loading }
            if (pendingSave) dao.onSaveStarted = { library ->
                val queue = library.active
                if (queue?.entries?.size == size && pendingNext.compareAndSet(null, queue.journey!!.next!!)) {
                    val pauseAndObserve = {
                        player.pause()
                        pendingNativeState.set("state=${player.playbackState} firstReady=${firstReady.get() > 0} firstDecoded=${firstDecoded.get() > 0}")
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) pauseAndObserve()
                    else instrumentation.runOnMainSync { pauseAndObserve() }
                    // This one-shot hook queues commands, then immediately starts the real save; no delay/gate.
                    assertFalse(dao.completed.any { it.library.active?.entries?.size == size })
                    pendingRequestedAt.set(SystemClock.elapsedRealtime())
                    queues.next(); queues.seek(9000)
                    pendingReturnedAt.set(SystemClock.elapsedRealtime())
                }
            }
            val requestedAt = SystemClock.elapsedRealtime()
            queues.tap(tracks, size / 2)
            await(timeoutMs = 30_000) { checkPlayback(); queues.state.value.library.active?.entries?.size == size }
            val active = queues.state.value.library.active!!
            val selected = active.entries[size / 2].id
            await(timeoutMs = 30_000) {
                checkPlayback()
                firstDecoded.get() > 0 && firstReady.get() > 0 && if (pendingSave)
                    pendingNext.get()?.let { ready(it.entryId, 9000) } == true else ready(selected)
            }
            val targetReadyAt = SystemClock.elapsedRealtime()
            if (!pendingSave) instrumentation.runOnMainSync { player.pause() }
            await(timeoutMs = 30_000) { checkPlayback(); dao.completed.any { it.library.active?.entries?.size == size } }
            val firstCommit = dao.completed.first { it.library.active?.entries?.size == size }.at
            // PCM is processor admission, not speaker output. Pending commands leave its source unassigned.
            Log.i("QueueScale", "documents=$size sharedBytes=true providerPreflight=true action=start pendingCommands=$pendingSave pcmOrigin=${if (pendingSave) "unassigned" else "fresh-start"} readyMs=${firstReady.get() - requestedAt} firstDecodedMs=${firstDecoded.get() - requestedAt} firstDecodedBeforeInitialCommit=${firstDecoded.get() < firstCommit} commitMs=${firstCommit - requestedAt}")
            assertTrue(firstDecoded.get() >= requestedAt)
            assertEquals(tracks[size / 2].uri, active.entries[size / 2].track.uri)
            val next = if (pendingSave) {
                val expected = pendingNext.get()!!
                val submitted = pendingRequestedAt.get()
                val committedAt = checkpoint(expected.entryId, 9000, submitted)
                assertTrue(pendingReturnedAt.get() >= submitted)
                assertTrue(firstCommit >= pendingReturnedAt.get())
                assertTrue(committedAt >= firstCommit)
                val snapshots = dao.completed.toList()
                val initialIndex = snapshots.indexOfFirst { it.library.active?.currentEntryId == selected }
                val targetIndex = snapshots.indexOfFirst { it.library.active?.let { q -> q.currentEntryId == expected.entryId && q.positionMs == 9000L } == true }
                assertTrue(initialIndex >= 0 && targetIndex > initialIndex)
                instrumentation.runOnMainSync { assertFalse(player.playWhenReady); assertTrue(player.mediaItemCount <= 129) }
                Log.i("QueueScale", "documents=$size sharedBytes=true action=pending-save-next-seek boundary=before-initial-delegate-save rawPlayerPaused=true ${pendingNativeState.get()} submissionReturnMs=${pendingReturnedAt.get() - submitted} readyMs=${targetReadyAt - submitted} initialCommitMs=${firstCommit - submitted} commitMs=${committedAt - submitted} postSeekPcm=unmeasured")
                expected
            } else {
                seek("seek", selected, 6000) { queues.seek(6000) }
                seek("fast-forward", selected, 11000) { queues.seek(11000) }
                val expected = queues.state.value.library.active!!.journey!!.next!!
                seek("queued-next-then-seek", expected.entryId, 9000) { queues.next(); queues.seek(9000) }
                expected
            }
            assertEquals(next.token, queues.state.value.library.active!!.journey!!.current!!.token)
            instrumentation.runOnMainSync {
                assertEquals(next.token, player.currentMediaItem!!.mediaMetadata.extras!!.getString("queueVisit"))
            }
            val stored = runBlocking { db.queues().read().active!! }
            assertEquals(size, stored.entries.size)
            assertEquals(next, stored.journey!!.current)
            assertEquals(9000L, stored.positionMs)
            if (measure) {
                // Upper bound including dispatch/observation, without ordinary await polling quantization.
                // Each action follows a durable checkpoint; pending-save is measured separately.
                val samples = linkedMapOf<String, MutableList<Double>>()
                fun sample(action: String, visit: QueueVisit, position: Long, warmup: Boolean, command: () -> Unit) {
                    val nativeAt = AtomicLong()
                    val listener = object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            if (player.currentMediaItem?.mediaMetadata?.extras?.getString("queueVisit") == visit.token &&
                                player.currentPosition == position) nativeAt.compareAndSet(0, SystemClock.elapsedRealtimeNanos())
                        }
                    }
                    instrumentation.runOnMainSync { player.addListener(listener) }
                    val startedMs = SystemClock.elapsedRealtime()
                    val started = SystemClock.elapsedRealtimeNanos()
                    command()
                    while (true) {
                        checkPlayback()
                        var matched = false
                        instrumentation.runOnMainSync {
                            matched = player.playbackState == Player.STATE_READY &&
                                player.currentMediaItem?.mediaMetadata?.extras?.getString("queueVisit") == visit.token &&
                                player.currentPosition == position &&
                                queues.state.value.library.active?.journey?.current == visit
                        }
                        if (matched) break
                        assertTrue("$action timed out", SystemClock.elapsedRealtime() - startedMs < 30_000)
                        Thread.sleep(1)
                    }
                    val readyMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    val committed = checkpoint(visit.entryId, position, startedMs)
                    instrumentation.runOnMainSync { player.removeListener(listener) }
                    if (!warmup) samples.getOrPut(action) { mutableListOf() }.add(readyMs)
                    val nativeMs = nativeAt.get().takeIf { it >= started }?.let { (it - started) / 1_000_000.0 }
                    Log.i("QueueLatency", "entries=$size action=$action warmup=$warmup readyMs=$readyMs nativeEventMs=$nativeMs commitMs=${committed - startedMs}")
                }
                repeat(35) { iteration ->
                    val current = queues.state.value.library.active!!.journey!!.current!!
                    val forward = queues.state.value.library.active!!.journey!!.next!!
                    sample("next", forward, 0, iteration < 5) { queues.next() }
                    sample("previous", current, 0, iteration < 5) { queues.previous(alwaysSong = true) }
                    sample("seek", current, 6000, iteration < 5) { queues.seek(6000) }
                    sample("seek-return", current, 9000, iteration < 5) { queues.seek(9000) }
                }
                samples.forEach { (action, values) ->
                    val sorted = values.sorted()
                    val p95 = sorted[kotlin.math.ceil(sorted.size * .95).toInt() - 1]
                    Log.i("QueueLatency", "SUMMARY entries=$size action=$action n=${values.size} p50Ms=${sorted[kotlin.math.ceil(sorted.size * .5).toInt() - 1]} p95Ms=$p95 maxMs=${sorted.last()} barMs=100 passes=${p95 < 100}")
                }
            }
            val provider = context.contentResolver.call(probe, "stats", null, null)!!
            val opened = provider.getStringArrayList("documents")!!
            assertTrue(opened.containsAll(listOf("scale-${size / 2}.mp3", "scale-${size / 2 + 1}.mp3")))
            Log.i("QueueScale", "documents=$size sharedBytes=true providerOpens=${provider.getInt("opens")} distinctOpened=${opened.size} readBytes=unmeasured")
        } finally {
            try {
                instrumentation.runOnMainSync { createdPlayer?.let { createdQueues?.detach(it); it.release() } }
            } finally {
                scope.cancel()
                try { db.close(); context.deleteDatabase(databaseName) } finally {
                    try { preferences.write(original) } finally {
                        fixtureGrant(false)
                        await { context.checkUriPermission(probe, Process.myPid(), Process.myUid(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_DENIED }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class FirstDecodedProcessor(private val firstDecoded: AtomicLong) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat) = inputAudioFormat
    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        // The pipeline may pass the shared EMPTY_BUFFER before decoding; copying it to itself throws.
        if (size == 0) return
        firstDecoded.compareAndSet(0, SystemClock.elapsedRealtime())
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }
}

private class ObservedQueueDao(private val delegate: QueueDao) : QueueDao() {
    data class Completed(val library: QueueLibrary, val at: Long)
    val completed = CopyOnWriteArrayList<Completed>()
    @Volatile var onSaveStarted: ((QueueLibrary) -> Unit)? = null
    override suspend fun read() = delegate.read()
    override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        onSaveStarted?.invoke(after)
        delegate.save(before, after)
        completed += Completed(after, SystemClock.elapsedRealtime())
    }
    override suspend fun readQueues(): List<StoredQueue> = error("Use read")
    override suspend fun readActive(): ActiveQueueRecord? = error("Use read")
    override suspend fun upsertQueue(queue: QueueRecord): Unit = error("Use save")
    override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int = error("Use save")
    override suspend fun upsertActive(active: ActiveQueueRecord): Unit = error("Use save")
    override suspend fun upsertJourney(journey: JourneyRecord): Unit = error("Use save")
    override suspend fun clearJourney(id: String): Unit = error("Use save")
    override suspend fun upsertVisits(visits: List<VisitRecord>): Unit = error("Use save")
    override suspend fun updateVisits(visits: List<VisitRecord>): Int = error("Use save")
    override suspend fun upsertCycleMembers(members: List<CycleMemberRecord>): Unit = error("Use save")
    override suspend fun updateCycleMembers(members: List<CycleMemberRecord>): Int = error("Use save")
    override suspend fun deleteVisits(queueId: String, tokens: List<String>): Unit = error("Use save")
    override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>): Unit = error("Use save")
    override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("Use save")
    override suspend fun clearEntries(id: String): Unit = error("Use save")
    override suspend fun deleteQueue(id: String): Unit = error("Use save")
}
