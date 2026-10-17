package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.statistics.QualifiedPlay
import io.github.anastaci1a.localica.statistics.StatisticsDatabase
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in, three-installed-APK protocol. Each invocation must run in a new instrumentation process.
 * Uses dedicated persistent fixtures, never the user's queues/statistics. See Build compatibility gate.
 */
class ShuffleApkCompatibilityTest {
    @Test fun installedApksPreserveQueuesStatisticsAndReopenControllers() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("shuffleCompatibilityPhase")
        assumeTrue("Requires candidate → bridge → candidate APK installations", phase != null)
        require(phase in listOf("seed", "bridge", "verify"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val queueName = "shuffle-apk-compat-queues.db"
        val statsName = "shuffle-apk-compat-statistics.db"
        val witness = File(context.filesDir, "shuffle-apk-compat-token")
        if (phase == "seed") {
            context.deleteDatabase(queueName); context.deleteDatabase(statsName); witness.delete()
        }
        val db = Room.databaseBuilder(context, QueueDatabase::class.java, queueName).build()
        val stats = Room.databaseBuilder(context, StatisticsDatabase::class.java, statsName).build()
        val preferences = QueuePreferences(context)
        val originalSettings = preferences.read()
        val ids = listOf("A", "B", "C", "D", "E", "F")
        val track = Track("content://compat/same", "Duplicate", fileName = "same.mp3", relativePath = "same.mp3")
        val entries = ids.map { QueueEntry(it, track, "source-playlist") }
        val plays = listOf(QualifiedPlay("compat-1", track.id, "source-playlist", 123),
            QualifiedPlay("compat-2", track.id, null, 456))
        var succeeded = false
        try {
            preferences.write(QueueSettings(repeat = QueueRepeat.All))
            if (phase == "seed") {
                var journey = QueueJourney.start(ids, "A", JourneyModes(shuffle = true, loop = true))
                for (id in listOf("D", "B", "F")) journey = journey.select(id, ids)
                journey = journey.arrive(journey.history[1].token, ids)
                    .reconcile(ids, "D", JourneyModes(loop = true))
                assertEquals("C", journey.previous?.entryId)
                assertEquals("E", journey.next?.entryId)
                val queue = ListeningQueue("compat", "Before APK replacement", 123, entries, "D", 2345,
                    repeat = QueueRepeat.All, journey = journey, order = SavedOrder(ids.reversed()))
                db.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id))
                plays.forEach { stats.statistics().record(it) }
                witness.writeText(journey.current!!.token)
            } else {
                assertTrue("Seed must survive APK replacement", witness.isFile)
                val saved = db.queues().read()
                val queue = saved.active!!
                assertEquals(entries, queue.entries)
                assertEquals(SavedOrder(ids.reversed()), queue.order)
                assertEquals(QueueRepeat.All, queue.repeat)
                if (phase == "bridge") {
                    assertEquals("D", queue.currentEntryId)
                    assertEquals(2345L, queue.positionMs)
                    assertEquals(witness.readText(), queue.journey!!.current!!.token)
                    val header = db.queues().readQueues().single().journeys.single()
                    assertNotNull(header.orderedTraversalId)
                    // A metadata-only write must not erase identities unknown to the bridge model.
                    val renamed = saved.update(queue.copy(name = "Through bridge", positionMs = 3456))
                    db.queues().save(saved, renamed)
                    assertEquals(header, db.queues().readQueues().single().journeys.single())
                    withController(db, preferences) { controller ->
                        assertEquals(renamed, controller.state.value.library)
                        instrumentation.runOnMainSync { controller.selectEntry("E") }
                        withTimeout(10_000) {
                            while (controller.state.value.library.active?.currentEntryId != "E" ||
                                db.queues().read().active?.currentEntryId != "E") delay(20)
                        }
                    }
                    assertNull(db.queues().readQueues().single().journeys.single().orderedTraversalId)
                    witness.writeText(db.queues().read().active!!.journey!!.current!!.token)
                } else {
                    assertEquals("Through bridge", queue.name)
                    assertEquals("E", queue.currentEntryId)
                    assertEquals(0L, queue.positionMs)
                    assertEquals(witness.readText(), queue.journey!!.current!!.token)
                    withController(db, preferences) { controller ->
                        instrumentation.runOnMainSync { controller.settings { it } }
                        withTimeout(10_000) {
                            while (db.queues().readQueues().single().journeys.single().orderedTraversalId == null) delay(20)
                        }
                        assertEquals("D", controller.state.value.library.active!!.journey!!.previous?.entryId)
                        assertEquals("F", controller.state.value.library.active!!.journey!!.next?.entryId)
                        assertEquals(witness.readText(), controller.state.value.library.active!!.journey!!.current!!.token)
                    }
                }
            }
            withController(db, preferences) { controller ->
                assertEquals(db.queues().read(), controller.state.value.library)
                assertEquals(entries, controller.state.value.library.active!!.entries)
            }
            stats.openHelper.readableDatabase.query("SELECT id, trackId, playlistId, playedAt FROM qualified_plays ORDER BY id").use { cursor ->
                val actual = buildList { while (cursor.moveToNext()) add(QualifiedPlay(cursor.getString(0),
                    cursor.getString(1), if (cursor.isNull(2)) null else cursor.getString(2), cursor.getLong(3))) }
                assertEquals(plays, actual)
            }
            succeeded = true
        } finally {
            preferences.write(originalSettings)
            db.close(); stats.close()
            if (succeeded && phase == "verify") { context.deleteDatabase(queueName); context.deleteDatabase(statsName); witness.delete() }
        }
    }

    private suspend fun withController(db: QueueDatabase, preferences: QueuePreferences,
        action: suspend (QueueController) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val controller = withContext(Dispatchers.Main) {
                QueueController(db.queues(), preferences, (context.applicationContext as LocalicaApplication).library, scope)
            }
            withTimeout(10_000) { while (controller.state.value.loading) delay(20) }
            assertNull(controller.state.value.problem)
            action(controller)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
