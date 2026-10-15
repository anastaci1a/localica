package io.github.anastaci1a.localica

import android.os.SystemClock
import android.util.Log
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
import java.util.concurrent.atomic.AtomicLong

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PreparedQueuePlaybackTest {
    @Test fun largePreparedMovesKeepNativeTokensAndRepairInactiveRestoredJourney() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = QueuePreferences(context)
        val original = preferences.read()
        preferences.write(QueueSettings())
        val file = File(context.cacheDir, "prepared-queue-tone.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val song = Track(file.toURI().toString(), "Duplicate", fileName = file.name, relativePath = file.name)
        val active = QueueEdits.create(List(13_000) { song }, 0, "Large queue").let {
            it.copy(positionMs = 1000, journey = QueueJourney.start(it.entries.map { entry -> entry.id }, it.currentEntryId, JourneyModes()))
        }
        val inactive = QueueEdits.create(List(3) { song }, 0, "Restored queue").copy(
            journey = QueueJourney(history = listOf(QueueVisit("removed", 0)), cursor = 0))
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        runBlocking { db.queues().save(QueueLibrary(), QueueLibrary(listOf(active, inactive), active.id)) }
        val observed = PreparedQueueDao(db.queues())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: ExoPlayer
        lateinit var queues: QueueController
        fun remaining(deadline: Long): Long = (deadline - SystemClock.elapsedRealtime()).also {
            assertTrue("The command exceeded its original 30-second completion budget", it > 0)
        }
        fun awaitUntil(deadline: Long, condition: () -> Boolean) {
            await(timeoutMs = remaining(deadline), condition = condition)
            assertTrue("The command observation exceeded its original 30-second completion budget",
                SystemClock.elapsedRealtime() <= deadline)
        }
        fun checkpoint(after: Long? = null, deadline: Long = SystemClock.elapsedRealtime() + 30_000): Long {
            lateinit var expected: QueueLibrary
            instrumentation.runOnMainSync { expected = queues.state.value.library }
            // Observe the exact durable state without adding a capture/edit command to the timing.
            // Startup can already be durable; later moves must match the latest completed save,
            // not an older matching snapshot in a history of previous writes.
            var matched: PreparedQueueDao.Completed? = null
            awaitUntil(deadline) {
                observed.committed?.takeIf { it.library == expected && (after == null || it.sequence > after) }
                    ?.also { matched = it } != null
            }
            assertNull(queues.state.value.problem)
            return matched!!.at
        }
        fun at(visit: QueueVisit, action: String, requestedAt: Long, after: Long? = null) {
            await {
                var result = false
                instrumentation.runOnMainSync {
                    result = player.currentMediaItem?.mediaMetadata?.extras?.getString("queueVisit") == visit.token &&
                        queues.state.value.library.active?.journey?.current == visit && player.playbackState == Player.STATE_READY
                }
                result
            }
            val readyAfter = SystemClock.elapsedRealtime() - requestedAt
            val committedAt = checkpoint(after)
            // Diagnostic wall times include polling/dispatch; they are not physical-device audio latency budgets.
            Log.i("QueueScale", "entries=13000 action=$action readyMs=$readyAfter checkpointMs=${committedAt - requestedAt} checkpointSource=${if (after == null) "restore-read" else "transaction"}")
            assertEquals(visit, runBlocking { db.queues().read().active!!.journey!!.current })
            instrumentation.runOnMainSync { assertFalse(player.playWhenReady); assertTrue(player.mediaItemCount <= 129) }
        }
        fun move(action: String, visit: QueueVisit, command: () -> Unit) {
            val before = observed.committed!!.sequence
            val requestedAt = SystemClock.elapsedRealtime()
            command()
            at(visit, action, requestedAt, before)
        }
        try {
            val restoreAt = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build().apply { volume = 0f }
                queues = QueueController(observed, preferences, (context.applicationContext as LocalicaApplication).library, scope)
                queues.attach(player)
            }
            at(active.journey!!.current!!, "restore", restoreAt)
            assertEquals(inactive.journey, queues.state.value.library.queues.last().journey)
            val firstNext = queues.state.value.library.active!!.journey!!.next!!
            move("next", firstNext) { queues.next() }
            val repaired = queues.state.value.library.queues.last().journey!!
            assertEquals(inactive.currentEntryId, repaired.current?.entryId)
            assertTrue(repaired.history.none { it.entryId == "removed" })
            assertEquals(repaired, runBlocking { db.queues().read().queues.last().journey })
            move("previous", active.journey.current!!) { queues.previous(alwaysSong = true) }
            move("forward-history", firstNext) { queues.next() }
            for (shuffle in listOf(false, true)) {
                val before = observed.committed!!.sequence
                val settingsAt = SystemClock.elapsedRealtime()
                val deadline = settingsAt + 30_000
                val settings = QueueSettings(shuffle = shuffle, repeat = QueueRepeat.All)
                queues.settings(settings)
                awaitUntil(deadline) {
                    var applied = false
                    instrumentation.runOnMainSync { applied = queues.state.value.settings == settings }
                    applied
                }
                val committedAt = checkpoint(before, deadline)
                Log.i("QueueScale", "entries=13000 action=repeat-settings-shuffle-$shuffle checkpointMs=${committedAt - settingsAt}")
                val next = queues.state.value.library.active!!.journey!!.next!!
                move("repeat-next-shuffle-$shuffle", next) { queues.next() }
                val previous = queues.state.value.library.active!!.journey!!.previous!!
                move("repeat-previous-shuffle-$shuffle", previous) { queues.previous(alwaysSong = true) }
                move("repeat-forward-shuffle-$shuffle", next) { queues.next() }
            }
        } finally {
            instrumentation.runOnMainSync { queues.detach(player); player.release(); scope.cancel() }
            db.close(); file.delete(); preferences.write(original)
        }
    }
}

private class PreparedQueueDao(private val delegate: QueueDao) : QueueDao() {
    data class Completed(val library: QueueLibrary, val at: Long, val sequence: Long)
    private val sequence = AtomicLong()
    @Volatile var committed: Completed? = null
        private set
    override suspend fun read() = delegate.read().also {
        committed = Completed(it, SystemClock.elapsedRealtime(), sequence.incrementAndGet())
    }
    override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        delegate.save(before, after)
        committed = Completed(after, SystemClock.elapsedRealtime(), sequence.incrementAndGet())
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
