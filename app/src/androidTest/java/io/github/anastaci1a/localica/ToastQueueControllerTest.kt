package io.github.anastaci1a.localica

import android.content.ContextWrapper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ToastQueueControllerTest {
    @Test fun browseLeavesPlaybackUnchangedUnderEveryQueueSwitchPolicy() = runBlocking {
        for (policy in QueueSwitch.entries) fixture(policy) { f ->
            val before = f.main { f.snapshot() }
            val accepted = CompletableDeferred<Pair<Boolean, Snapshot>>()
            f.main { f.queues.browse("other") { accepted.complete(it to f.snapshot()) } }
            val (success, after) = withTimeout(10_000) { accepted.await() }
            assertTrue(policy.name, success)
            assertEquals(before.copy(state = before.state.copy(browsedId = "other")), after)
            assertEquals(policy, after.state.settings.switching)
        }
    }

    @Test fun browsePreservesAnAcceptedPlayPendingBehindItsSerializedCompletion() = runBlocking {
        fixture(QueueSwitch.Resume) { f ->
            val gate = f.dao.gateNextSave()
            f.main { f.queues.rename("other", "Renamed") }
            withTimeout(10_000) { gate.entered.await() }
            val receipt = CompletableDeferred<Pair<Boolean, Snapshot>>()
            val pending = f.main {
                f.queues.browse("other") { receipt.complete(it to f.snapshot()) }
                f.queues.playbackRequested(true)
                f.snapshot().also { assertEquals(true, it.pending?.play) }
            }
            gate.release.complete(Unit)
            val (success, after) = withTimeout(10_000) { receipt.await() }
            assertTrue(success)
            assertEquals(pending.pending, after.pending)
            assertEquals(pending.revision, after.revision)
            assertEquals(pending.state.library.active, after.state.library.active)
            assertEquals(pending.state.library.activeId, after.state.library.activeId)
            assertEquals(pending.nativeCalls, after.nativeCalls)
            assertEquals(pending.playWhenReady, after.playWhenReady)
            assertEquals(pending.mediaId, after.mediaId)
        }
    }

    @Test fun invalidAndDeletedDestinationsRejectNavigationWithoutChangingPlayback() = runBlocking {
        fixture(QueueSwitch.KeepPlaybackState) { f ->
            f.main { f.queues.removeQueues(setOf("other")) }
            val barrier = CompletableDeferred<Boolean>()
            f.main { f.queues.browse("active") { barrier.complete(it) } }
            assertTrue(withTimeout(10_000) { barrier.await() })
            for (id in listOf("other", "never-existed")) {
                val before = f.main { f.snapshot() }
                val rejected = CompletableDeferred<Pair<Boolean, Snapshot>>()
                f.main { f.queues.browse(id) { rejected.complete(it to f.snapshot()) } }
                val (success, after) = withTimeout(10_000) { rejected.await() }
                assertFalse(success)
                assertEquals(before, after)
            }
        }
    }

    @Test fun unavailableAndClosedControllersRejectOnce() = runBlocking {
        fixture(QueueSwitch.Browse, failRestore = true) { f ->
            val accepted = CompletableDeferred<Boolean>()
            val calls = AtomicInteger()
            f.main { f.queues.browse("other") { calls.incrementAndGet(); accepted.complete(it) } }
            assertFalse(withTimeout(10_000) { accepted.await() })
            assertEquals(1, calls.get())
            assertTrue(f.main { f.player.calls.isEmpty() })
            assertNull(f.queues.state.value.browsedId)
        }
        fixture(QueueSwitch.Browse) { f ->
            f.scope.coroutineContext[Job]!!.cancelAndJoin()
            val before = f.main { f.snapshot() }
            val accepted = CompletableDeferred<Boolean>()
            val calls = AtomicInteger()
            f.main { f.queues.browse("other") { calls.incrementAndGet(); accepted.complete(it) } }
            assertFalse(withTimeout(10_000) { accepted.await() })
            assertEquals(1, calls.get())
            assertEquals(before, f.main { f.snapshot() })
        }
    }

    private data class Snapshot(val state: QueueUiState, val pending: PlaybackIntents.Choice?, val revision: Long,
        val nativeCalls: List<String>, val mediaId: String?, val playWhenReady: Boolean)
    private class Fixture(val queues: QueueController, val scope: CoroutineScope, val dao: ToastProbeDao,
        val player: RecordingPlayer) {
        suspend fun <T> main(action: () -> T): T = withContext(Dispatchers.Main) { action() }
        fun snapshot(): Snapshot {
            val field = QueueController::class.java.getDeclaredField("intents").apply { isAccessible = true }
            val intents = field.get(queues) as PlaybackIntents
            return Snapshot(queues.state.value, intents.pending(), intents.currentRevision(), player.calls.toList(),
                player.currentMediaItem?.mediaId, player.playWhenReady)
        }
    }

    private suspend fun fixture(policy: QueueSwitch, failRestore: Boolean = false, action: suspend (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "toast-controller-${UUID.randomUUID()}"
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int) = context.getSharedPreferences(name, mode)
        })
        preferences.write(QueueSettings(switching = policy))
        val file = File(context.cacheDir, "$name.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val track = Track(file.toURI().toString(), "Song", fileName = file.name, relativePath = file.name)
        val active = ListeningQueue(id = "active", name = "Active", entries = listOf(QueueEntry("playing", track)),
            lastActivatedAt = 1234)
        val other = ListeningQueue(id = "other", name = "Other", entries = listOf(QueueEntry("other-song", track)))
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        db.queues().save(QueueLibrary(), QueueLibrary(listOf(active, other), active.id))
        val dao = ToastProbeDao(db.queues(), failRestore)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val player = withContext(Dispatchers.Main) { RecordingPlayer(ExoPlayer.Builder(context).build().apply { volume = 0f }) }
        try {
            val queues = withContext(Dispatchers.Main) {
                QueueController(dao, preferences, (context.applicationContext as LocalicaApplication).library, scope)
            }
            withTimeout(10_000) { while (queues.state.value.loading) delay(10) }
            val fixture = Fixture(queues, scope, dao, player)
            if (!failRestore) {
                val attached = CompletableDeferred<Boolean>()
                fixture.main { queues.attach(player); queues.browse("active") { attached.complete(it) } }
                assertTrue(withTimeout(10_000) { attached.await() })
            }
            action(fixture)
        } finally {
            dao.release()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            withContext(Dispatchers.Main) { player.release() }
            db.close(); context.deleteSharedPreferences(name); file.delete()
        }
    }

    private class RecordingPlayer(engine: ExoPlayer) : ForwardingPlayer(engine) {
        val calls = CopyOnWriteArrayList<String>()
        override fun setMediaItems(items: List<MediaItem>, index: Int, position: Long) { calls += "setMediaItems"; super.setMediaItems(items, index, position) }
        override fun prepare() { calls += "prepare"; super.prepare() }
        override fun setPlayWhenReady(play: Boolean) { calls += "playWhenReady:$play"; super.setPlayWhenReady(play) }
        override fun play() { calls += "play"; super.play() }
        override fun pause() { calls += "pause"; super.pause() }
        override fun stop() { calls += "stop"; super.stop() }
        override fun clearMediaItems() { calls += "clear"; super.clearMediaItems() }
        override fun addMediaItem(index: Int, item: MediaItem) { calls += "add"; super.addMediaItem(index, item) }
        override fun removeMediaItem(index: Int) { calls += "remove"; super.removeMediaItem(index) }
        override fun moveMediaItem(from: Int, to: Int) { calls += "move"; super.moveMediaItem(from, to) }
        override fun replaceMediaItem(index: Int, item: MediaItem) { calls += "replace"; super.replaceMediaItem(index, item) }
        override fun seekTo(index: Int, position: Long) { calls += "seek"; super.seekTo(index, position) }
    }
}

private class ToastProbeDao(private val delegate: QueueDao, private val failRestore: Boolean) : QueueDao() {
    class Gate { val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>() }
    private var gate: Gate? = null
    fun gateNextSave() = Gate().also { check(gate == null); gate = it }
    fun release() { gate?.release?.complete(Unit) }
    override suspend fun read(): QueueLibrary { check(!failRestore) { "Injected restore failure" }; return delegate.read() }
    override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        gate?.let { blocked -> blocked.entered.complete(Unit); blocked.release.await(); gate = null }
        delegate.save(before, after)
    }
    override suspend fun readQueues(): List<StoredQueue> = error("Use read")
    override suspend fun readActive(): ActiveQueueRecord? = error("Use read")
    override suspend fun upsertQueue(queue: QueueRecord): Unit = error("Use save")
    override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int = error("Use save")
    override suspend fun upsertActive(active: ActiveQueueRecord): Unit = error("Use save")
    override suspend fun upsertJourney(journey: JourneyRecord): Unit = error("Use save")
    override suspend fun clearJourney(id: String): Unit = error("Use save")
    override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("Use save")
    override suspend fun clearEntries(id: String): Unit = error("Use save")
    override suspend fun deleteQueue(id: String): Unit = error("Use save")
    override suspend fun deleteVisits(queueId: String, tokens: List<String>): Unit = error("Use save")
    override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>): Unit = error("Use save")
}
