package io.github.anastaci1a.localica

import android.content.ContextWrapper
import androidx.media3.common.Player
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
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class InitialSaveTransportTest {
    @Test fun transportPrefixRunsBeforeCommitButCreationAndStructuralReceiptsWait() = fixture { f ->
        val initial = f.start()
        val next = initial.active!!.journey!!.next!!
        f.main {
            f.queues.next(); f.queues.next(); f.queues.previous(alwaysSong = true)
            f.queues.seek(3456); f.queues.playbackRequested(false)
        }
        f.at(next, 3456)
        assertFalse(f.created.isCompleted)
        assertEquals(QueueLibrary(), f.db.queues().read())
        val reversed = CompletableDeferred<Boolean>()
        val laterSeek = CompletableDeferred<Boolean>()
        f.main {
            f.queues.reverse(initial.activeId!!) { reversed.complete(it) }
            f.queues.requestSeek(4567, next.token) { accepted, _ -> laterSeek.complete(accepted) }
        }
        // This bounded negative wait exposes a later transport illegally bypassing the structural head.
        assertNull(withTimeoutOrNull(250) { laterSeek.await() })
        assertFalse(reversed.isCompleted)
        f.main { assertEquals(3456L, f.player.currentPosition) }
        f.dao.release.complete(false)
        assertTrue(withTimeout(10_000) { f.created.await() })
        assertTrue(withTimeout(10_000) { reversed.await() })
        assertTrue(withTimeout(10_000) { laterSeek.await() })
        f.at(next, 4567)
        f.flush()
        val saved = f.reopen()
        assertEquals(initial.active!!.entries.map { it.id }.reversed(), saved.active!!.entries.map { it.id })
        assertEquals(next, saved.active!!.journey!!.current)
        assertEquals(4567L, saved.active!!.positionMs)
        assertEquals(initial, f.dao.completed.first())
        assertEquals(QueueLibrary(), f.dao.attempts.first().first)
    }

    @Test fun failedInitialSaveKeepsBaselineAndRetriesLatestTransportWithoutEarlySuccess() = fixture { f ->
        val initial = f.start()
        val next = initial.active!!.journey!!.next!!
        f.main { f.queues.next(); f.queues.seek(2345) }
        f.at(next, 2345)
        assertFalse(f.created.isCompleted)
        f.dao.release.complete(true)
        assertFalse(withTimeout(10_000) { f.created.await() })
        f.flush()
        val attempts = f.dao.attempts.toList()
        assertTrue(attempts.size >= 2)
        assertEquals(QueueLibrary(), attempts[0].first)
        assertEquals(QueueLibrary(), attempts[1].first)
        assertEquals(next, f.reopen().active!!.journey!!.current)
        assertEquals(2345L, f.db.queues().read().active!!.positionMs)
    }

    @Test fun detachDuringInitialSaveWaitsForFinalRestorableCursor() = fixture { f ->
        val initial = f.start()
        val next = initial.active!!.journey!!.next!!
        f.main { f.queues.next(); f.queues.seek(4321) }
        f.at(next, 4321)
        val detached = f.main { f.queues.detach(f.player) }
        assertFalse(detached.isCompleted)
        assertFalse(f.created.isCompleted)
        assertEquals(QueueLibrary(), f.db.queues().read())
        f.dao.release.complete(false)
        withTimeout(10_000) { detached.await() }
        val restored = f.reopen().active!!
        assertEquals(next, restored.journey!!.current)
        assertEquals(4321L, restored.positionMs)
        assertEquals(initial.active!!.entries, restored.entries)
    }

    @Test fun cancellationDuringInitialSaveCannotAcknowledgeOrPersistCreation() = fixture { f ->
        f.start()
        f.scope.coroutineContext[Job]!!.cancelAndJoin()
        assertFalse(withTimeout(10_000) { f.created.await() })
        assertTrue(f.dao.completed.isEmpty())
        assertEquals(QueueLibrary(), f.reopen())
    }

    private class Fixture(val db: QueueDatabase, val dao: InitialSaveGateDao, val scope: CoroutineScope,
        val queues: QueueController, val player: ExoPlayer, val tracks: List<Track>,
        val reopen: suspend () -> QueueLibrary) {
        val created = CompletableDeferred<Boolean>()
        suspend fun <T> main(action: () -> T): T = withContext(Dispatchers.Main) { action() }
        suspend fun check(predicate: () -> Boolean) = withTimeout(5000) {
            while (!main(predicate)) delay(10)
        }
        suspend fun start(): QueueLibrary {
            check { !queues.state.value.loading }
            main { queues.act(tracks, SongTap.NewQueue) { created.complete(it) } }
            val initial = withTimeout(10_000) { dao.entered.await() }
            main { player.pause() }
            check { player.playbackState == Player.STATE_READY }
            return initial
        }
        suspend fun at(visit: QueueVisit, position: Long) = check {
            player.currentMediaItem?.queueVisit == visit.token && player.currentPosition == position &&
                queues.state.value.library.active?.journey?.current == visit && !player.playWhenReady
        }
        suspend fun flush() {
            val saved = CompletableDeferred<Boolean>()
            main { queues.moveQueueBefore(queues.state.value.library.activeId!!, null) { saved.complete(it) } }
            assertTrue(withTimeout(10_000) { saved.await() })
            // A no-op reorder need not save; detach supplies an explicit durable final receipt.
            val receipt = main { queues.detach(player) }
            withTimeout(10_000) { receipt.await() }
        }
    }

    private fun fixture(action: suspend (Fixture) -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "initial-save-" + UUID.randomUUID()
        val preferences = QueuePreferences(preparedTestContext(object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int) = context.getSharedPreferences(name, mode)
        }))
        preferences.write(QueueSettings())
        val file = File(context.cacheDir, "$name.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val tracks = List(6) { index -> Track(file.toURI().toString(), "Track $index", id = "$name:$index",
            durationMs = 20_000, fileName = file.name, relativePath = file.name) }
        val db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        val dao = InitialSaveGateDao(db.queues())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val player = withContext(Dispatchers.Main) { ExoPlayer.Builder(context).build().apply { volume = 0f } }
        try {
            val queues = withContext(Dispatchers.Main) {
                QueueController(dao, preferences, (context.applicationContext as LocalicaApplication).library, scope)
                    .also { it.attach(player) }
            }
            action(Fixture(db, dao, scope, queues, player, tracks) {
                val fresh = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
                try { fresh.queues().read() } finally { fresh.close() }
            })
        } finally {
            dao.release.complete(false)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            withContext(Dispatchers.Main) { player.release() }
            db.close(); context.deleteDatabase(name); context.deleteSharedPreferences(name); file.delete()
        }
    }
}

private class InitialSaveGateDao(private val delegate: QueueDao) : QueueDao() {
    val entered = CompletableDeferred<QueueLibrary>()
    val release = CompletableDeferred<Boolean>()
    val attempts = CopyOnWriteArrayList<Pair<QueueLibrary, QueueLibrary>>()
    val completed = CopyOnWriteArrayList<QueueLibrary>()
    private val claimed = AtomicBoolean()
    override suspend fun read() = delegate.read()
    override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        attempts += before to after
        if (before.queues.isEmpty() && after.active != null && claimed.compareAndSet(false, true)) {
            entered.complete(after)
            if (release.await()) error("Injected initial-save failure")
        }
        delegate.save(before, after)
        completed += after
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
