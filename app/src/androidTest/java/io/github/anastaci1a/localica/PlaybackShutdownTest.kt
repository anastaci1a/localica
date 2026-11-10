package io.github.anastaci1a.localica

import android.content.ContextWrapper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.playback.finishPlaybackShutdown
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackShutdownTest {
    @Test fun controlledShutdownRetainsOwnershipUntilFinalCursorIsRestorableFromFreshDatabase() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "shutdown-" + UUID.randomUUID()
        val preferences = QueuePreferences(preparedTestContext(object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int) = context.getSharedPreferences(name, mode)
        }))
        val file = File(context.cacheDir, "$name.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        var db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        val dao = ShutdownDao(db.queues())
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val queue = QueueEdits.create(listOf(Track(file.toURI().toString(), "Shutdown", fileName = file.name, relativePath = file.name)), 0, "Shutdown")
        dao.save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id))
        val released = AtomicInteger()
        val player = withContext(Dispatchers.Main) { ExoPlayer.Builder(context).build().apply { volume = 0f } }
        var shutdown: Job? = null
        try {
            val queues = withContext(Dispatchers.Main) {
                QueueController(dao, preferences, (context.applicationContext as LocalicaApplication).library, actor).also { it.attach(player) }
            }
            withTimeout(10000) { while (!withContext(Dispatchers.Main) { player.playbackState == Player.STATE_READY }) delay(10) }
            val barrier = CompletableDeferred<Unit>()
            queues.settings { barrier.complete(Unit); it }
            withTimeout(5000) { barrier.await() }
            dao.gate = CompletableDeferred()
            val stopping = withContext(Dispatchers.Main) {
                player.seekTo(2345)
                finishPlaybackShutdown(queues.detach(player)) { released.incrementAndGet(); player.release() }
            }
            shutdown = stopping
            withTimeout(1000) { dao.entered.await() }
            assertFalse(stopping.isCompleted)
            assertEquals(0, released.get())
            withContext(Dispatchers.Main) { assertEquals(2345L, player.currentPosition) }
            dao.gate!!.complete(Unit)
            withTimeout(5000) { stopping.join() }
            assertEquals(1, released.get())
            actor.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
            db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
            val restored = db.queues().read().active!!
            assertEquals(queue.id, restored.id)
            assertEquals(queue.currentEntryId, restored.currentEntryId)
            assertEquals(2345L, restored.positionMs)
        } finally {
            withContext(NonCancellable) {
                dao.gate?.complete(Unit)
                shutdown?.join()
                actor.coroutineContext[Job]!!.cancelAndJoin()
                withContext(Dispatchers.Main) { if (released.getAndIncrement() == 0) player.release() }
                db.close()
                context.deleteDatabase(name)
                context.deleteSharedPreferences(name)
                file.delete()
            }
        }
    }

    @Test fun failedAndStalledCheckpointsReleaseOnceWithoutCancellingTheWriter() = runBlocking {
        for (fail in listOf(false, true)) {
            val checkpoint = CompletableDeferred<Unit>()
            val releases = AtomicInteger()
            val job = withContext(Dispatchers.Main) {
                finishPlaybackShutdown(checkpoint) { releases.incrementAndGet() }
            }
            withContext(Dispatchers.Main) { assertEquals(0, releases.get()) }
            if (fail) checkpoint.completeExceptionally(IOException("Controlled write failure"))
            withTimeout(5000) { job.join() }
            assertEquals(1, releases.get())
            if (!fail) {
                assertFalse("Timeout bounds ownership, not the queue writer lifetime", checkpoint.isCompleted)
                checkpoint.complete(Unit)
            }
            job.join()
            assertEquals(1, releases.get())
        }
    }
}

private class ShutdownDao(private val delegate: QueueDao) : QueueDao() {
    @Volatile var gate: CompletableDeferred<Unit>? = null
    val entered = CompletableDeferred<Unit>()
    override suspend fun read() = delegate.read()
    override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        if (after.active?.positionMs == 2345L && before.active?.positionMs != 2345L) {
            entered.complete(Unit)
            gate?.await()
        }
        delegate.save(before, after)
    }
    override suspend fun readQueues() = delegate.readQueues()
    override suspend fun readActive() = delegate.readActive()
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
