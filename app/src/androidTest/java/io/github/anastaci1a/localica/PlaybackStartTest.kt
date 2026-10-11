package io.github.anastaci1a.localica

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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackStartTest {
    @Test fun largeQueueStartsBeforeStorageCompletesAndLoadedSelectionDoesNotWaitForSave() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val savedSettings = preferences.read()
        preferences.write(QueueSettings())
        val file = File(context.cacheDir, "start-tone.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val tracks = (0 until 1000).map { Track(file.toURI().toString(), "Track $it", fileName = file.name, relativePath = file.name, id = "start:$it") }
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val dao = GatedQueueDao(db.queues())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: ExoPlayer
        lateinit var queues: QueueController
        fun playing(index: Int) = await { var result = false; instrumentation.runOnMainSync {
            result = player.currentMediaItem?.mediaMetadata?.extras?.getString("trackId") == "start:$index" && player.isPlaying
        }; result }
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                queues = QueueController(dao, preferences, (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(player)
            }
            await { !queues.state.value.loading }
            val gate = CompletableDeferred<Unit>()
            dao.gate = gate
            queues.tap(tracks, 500)
            // A disk stall must not prevent the selected local track from reaching the audio renderer.
            playing(500)
            assertFalse(gate.isCompleted)
            assertTrue(runBlocking { db.queues().read().queues.isEmpty() })
            // Native/system arrivals must all be journaled even while the command channel waits on storage.
            instrumentation.runOnMainSync { player.seekToNextMediaItem(); player.seekToNextMediaItem() }
            playing(502)
            gate.complete(Unit)
            await { runBlocking { db.queues().read().active?.journey?.history?.size == 3 } }
            assertEquals(listOf(500, 501, 502).map { queues.state.value.library.active!!.entries[it].id },
                queues.state.value.library.active!!.journey!!.history.map { it.entryId })
            instrumentation.runOnMainSync { player.pause() }
            await { queues.state.value.library.active?.currentEntryId == playerId(instrumentation, player) }
            val next = queues.state.value.library.active!!.entries[700].id
            dao.gate = CompletableDeferred()
            queues.selectEntry(next)
            playing(700)
            assertFalse(dao.gate!!.isCompleted)
            dao.gate!!.complete(Unit)
            await { runBlocking { db.queues().read().active?.currentEntryId == next } }
            // A service shutdown during an unrelated pending write must retain both the edit and final visits.
            dao.gateName = "Renamed while playing"
            dao.gate = CompletableDeferred()
            dao.entered = CompletableDeferred()
            val activeId = queues.state.value.library.activeId!!
            queues.rename(activeId, "Renamed while playing")
            runBlocking { withTimeout(10000) { dao.entered!!.await() } }
            instrumentation.runOnMainSync { player.seekToNextMediaItem(); player.seekToNextMediaItem() }
            val finalEntry = queues.state.value.library.active!!.entries[702].id
            await { playerId(instrumentation, player) == finalEntry }
            instrumentation.runOnMainSync {
                queues.detach(player); player.release()
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                queues.attach(player)
            }
            dao.gate!!.complete(Unit)
            dao.gateName = null
            await { runBlocking { db.queues().read().active?.currentEntryId == finalEntry } }
            await { playerId(instrumentation, player) == finalEntry }
            assertEquals("Renamed while playing", queues.state.value.library.active!!.name)
            assertEquals(listOf(700, 701, 702).map { queues.state.value.library.active!!.entries[it].id },
                queues.state.value.library.active!!.journey!!.history.takeLast(3).map { it.entryId })
            instrumentation.runOnMainSync { assertFalse(player.playWhenReady) }
            // A failed playback save remains retryable rather than treating the in-memory state as durable.
            dao.failNext = true
            queues.tap(tracks.take(2), 1)
            playing(1)
            await { queues.state.value.problem != null || runBlocking { db.queues().read().queues.size == 2 } }
            queues.seek(0)
            await { runBlocking { db.queues().read().queues.size == 2 } }
        } finally {
            dao.gate?.complete(Unit)
            instrumentation.runOnMainSync { queues.detach(player); player.release(); scope.cancel() }
            db.close(); file.delete(); preferences.write(savedSettings)
        }
    }
    private fun playerId(instrumentation: android.app.Instrumentation, player: Player): String? {
        var id: String? = null
        instrumentation.runOnMainSync { id = player.currentMediaItem?.mediaId }
        return id
    }
}

private class GatedQueueDao(private val delegate: QueueDao) : QueueDao() {
    @Volatile var gate: CompletableDeferred<Unit>? = null
    @Volatile var entered: CompletableDeferred<Unit>? = null
    @Volatile var gateName: String? = null
    @Volatile var failNext = false
    override suspend fun read() = delegate.read()
    override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        if ((gateName != null && after.active?.name == gateName && before.active?.name != gateName) || before.activeId != after.activeId || before.active?.currentEntryId != after.active?.currentEntryId) {
            entered?.complete(Unit)
            gate?.await()
            if (failNext) { failNext = false; error("Injected storage failure") }
        }
        delegate.save(before, after)
    }
    override suspend fun readQueues() = delegate.readQueues()
    override suspend fun readActive() = delegate.readActive()
    override suspend fun upsertQueue(queue: QueueRecord): Unit = error("Use save")
    override suspend fun upsertActive(active: ActiveQueueRecord): Unit = error("Use save")
    override suspend fun upsertJourney(journey: JourneyRecord): Unit = error("Use save")
    override suspend fun clearJourney(id: String): Unit = error("Use save")
    override suspend fun upsertVisits(visits: List<VisitRecord>): Unit = error("Use save")
    override suspend fun upsertCycleMembers(members: List<CycleMemberRecord>): Unit = error("Use save")
    override suspend fun deleteVisits(queueId: String, tokens: List<String>): Unit = error("Use save")
    override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>): Unit = error("Use save")
    override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("Use save")
    override suspend fun clearEntries(id: String): Unit = error("Use save")
    override suspend fun deleteQueue(id: String): Unit = error("Use save")
}
