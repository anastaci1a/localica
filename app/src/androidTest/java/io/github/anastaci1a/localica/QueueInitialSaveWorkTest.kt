package io.github.anastaci1a.localica

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.library.SavedOrderCodec
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSnapshot
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Broad save components, not additive phase timings or transport/real-provider performance. */
class QueueInitialSaveWorkTest {
    @Test fun actualDaoEntryOnlyAndFullSavesReportRemainingWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "queue-initial-save-components.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        val tracks = List(13_000) { index -> Track("content://fixture/$index", "Track $index",
            artist = "Fixture", album = "Synthetic", durationMs = 20_000,
            fileName = "$index.mp3", relativePath = "fixture/$index.mp3") }
        val seed = QueueEdits.create(tracks, 6500, "Initial save components")
        val ids = seed.entries.map { it.id }
        val queue = seed.copy(journey = QueueJourney.start(ids, seed.currentEntryId, JourneyModes()))
        val full = QueueLibrary(listOf(queue), queue.id)
        try {
            for (sample in 0..1) {
                val entriesAt = SystemClock.elapsedRealtime()
                val encoded = tracks.map(TrackSnapshot::encode)
                val entryMs = SystemClock.elapsedRealtime() - entriesAt
                val orderAt = SystemClock.elapsedRealtime()
                val order = SavedOrderCodec.encode(queue.order)
                val orderMs = SystemClock.elapsedRealtime() - orderAt
                assertEquals(tracks, encoded.map(TrackSnapshot::decode))
                assertEquals(queue.order, SavedOrderCodec.decode(order, ids))
                Log.i("QueueScale", "component=standalone-encoding entries=13000 sample=$sample trackMs=$entryMs orderMs=$orderMs trackBytes=${encoded.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }} orderBytes=${order.toByteArray(Charsets.UTF_8).size}")
            }
            db.queues().save(QueueLibrary(), full)
            var previous = full
            // Both variants use the actual DAO and identical entries/metadata. Reset and assertions are untimed.
            // Setup warms pages; these broad components are not additive and ABBA cannot remove all cache effects.
            for ((sample, includeJourney) in listOf(false, true, true, false).withIndex()) {
                db.queues().save(previous, QueueLibrary())
                val target = if (includeJourney) full else full.copy(queues = listOf(queue.copy(journey = null)))
                val started = SystemClock.elapsedRealtime()
                db.queues().save(QueueLibrary(), target)
                val elapsed = SystemClock.elapsedRealtime() - started
                assertEquals(target, db.queues().read())
                val stored = db.queues().readQueues().single()
                assertEquals(13_000, stored.entries.size)
                assertEquals(if (includeJourney) 6500 else 0, stored.visits.size)
                assertEquals(if (includeJourney) 13_000 else 0, stored.cycleMembers.size)
                Log.i("QueueScale", "component=initial-save actualDao=true entries=13000 includeJourney=$includeJourney sample=$sample transactionMs=$elapsed warmedPages=true")
                previous = target
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
