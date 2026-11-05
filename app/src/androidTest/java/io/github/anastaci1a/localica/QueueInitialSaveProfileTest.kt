package io.github.anastaci1a.localica

import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Profiling only: on-disk Room stages, never transport-latency acceptance. */
class QueueInitialSaveProfileTest {
    @Test fun initialThirteenThousandEntryTransactionStages() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tracks = List(13_000) { index ->
            val name = "scale-$index.mp3"
            Track("content://queue-profile/$name", "Track $index", durationMs = 20_000,
                fileName = name, relativePath = name)
        }
        repeat(3) { trial ->
            val name = "queue-initial-save-profile-$trial.db"
            context.deleteDatabase(name)
            val db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
            try {
                val dao = db.queues()
                assertEquals(QueueLibrary(), dao.read()) // Open/schema setup is outside the measured save.
                val seed = QueueEdits.create(tracks, 6500, "Profile")
                val queue = seed.copy(journey = QueueJourney.start(seed.entries.map { it.id },
                    seed.currentEntryId, JourneyModes()))
                val target = QueueLibrary(listOf(queue), queue.id)
                val stages = mutableListOf<QueueSaveStage>()
                dao.saveStageObserver = { stages += it }
                val start = System.nanoTime()
                dao.save(QueueLibrary(), target)
                val total = System.nanoTime() - start
                dao.saveStageObserver = null
                val body = stages.single { it.name == "transaction-body" }.nanos
                val measured = stages.filter { it.name != "transaction-body" }.sumOf { it.nanos }
                stages.forEach {
                    Log.i("QueueSaveProfile", "trial=$trial entries=13000 stage=${it.name} rows=${it.rows} nanos=${it.nanos}")
                }
                Log.i("QueueSaveProfile", "trial=$trial entries=13000 stage=dao-return nanos=$total")
                Log.i("QueueSaveProfile", "trial=$trial entries=13000 stage=admission-and-commit-remainder nanos=${total - body}")
                Log.i("QueueSaveProfile", "trial=$trial entries=13000 stage=body-other nanos=${body - measured}")
                assertEquals(13_000, stages.single { it.name == "entries-insert" }.rows)
                assertEquals(13_000, stages.single { it.name == "tracks-encode" }.rows)
                assertEquals(queue.journey!!.cycleRecords(queue.id).size,
                    stages.single { it.name == "cycle-write" }.rows)
                assertTrue(total >= body)
                assertTrue(body >= measured)
                assertEquals(target, dao.read())
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
    }
}
