package io.github.anastaci1a.localica

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueuePersistenceTest {
    @Test fun thirteenThousandOccurrencesKeepSavedOrderAcrossCursorAndJourneyCheckpoints() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "queue-large-checkpoint.db"
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        val track = Track("content://song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        val seed = QueueEdits.create(List(13_000) { track }, 0, "Large queue")
        val ids = seed.entries.map { it.id }
        val journey = QueueJourney.start(ids, ids.first(), JourneyModes())
        val queue = seed.copy(journey = journey)
        var saved = QueueLibrary(listOf(queue), queue.id)
        try {
            db.queues().save(QueueLibrary(), saved)
            // Fail any UPDATE that even mentions orderState, regardless of whether its value changes.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER no_order_rewrite BEFORE UPDATE OF orderState ON queues BEGIN SELECT RAISE(ABORT, 'Unchanged order rewritten'); END")
            val cursor = saved.update(queue.copy(positionMs = 1234))
            db.queues().save(saved, cursor); saved = cursor
            val nextJourney = journey.arrive(journey.next!!.token, ids)
            val next = saved.update(saved.active!!.copy(currentEntryId = nextJourney.current!!.entryId,
                positionMs = 0, journey = nextJourney))
            db.queues().save(saved, next); saved = next
            assertEquals(saved, db.queues().read())
            db.close(); db = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
            assertEquals(saved, db.queues().read())
            assertEquals(13_000, saved.active!!.entries.map { it.id }.toSet().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun metadataEntryAndOrderChangesRemainAtomicAndRetryable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val track = Track("content://song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        val first = QueueEdits.create(listOf(track, track), 0, "First")
        val second = QueueEdits.create(listOf(track), 0, "Second")
        var saved = QueueLibrary(listOf(first, second), first.id)
        try {
            db.queues().save(QueueLibrary(), saved)
            val renamed = first.copy(name = "Renamed", createdAt = 123, lastActivatedAt = 456,
                positionMs = 789, currentEntryId = first.entries.last().id, shuffle = true, repeat = QueueRepeat.All)
            val reordered = saved.copy(queues = listOf(second, renamed))
            db.queues().save(saved, reordered); saved = reordered
            assertEquals(saved, db.queues().read())
            val edited = renamed.copy(entries = renamed.entries.map { it.copy(track = it.track.copy(title = "Edited")) })
            val updatedEntries = saved.update(edited)
            db.queues().save(saved, updatedEntries); saved = updatedEntries
            assertEquals(saved, db.queues().read())
            val changedOrder = edited.copy(order = edited.order.copy(manual = edited.order.manual.reversed(), ascending = false))
            val updatedOrder = saved.update(changedOrder)
            db.queues().save(saved, updatedOrder); saved = updatedOrder
            assertEquals(saved, db.queues().read())
            val invalid = saved.update(changedOrder.copy(positionMs = 9999,
                entries = listOf(changedOrder.entries.first(), changedOrder.entries.first())))
            try { db.queues().save(saved, invalid); fail("Duplicate occurrence must roll back metadata too") }
            catch (_: android.database.SQLException) { }
            assertEquals(saved, db.queues().read())
            val retried = saved.update(changedOrder.copy(positionMs = 9999))
            db.queues().save(saved, retried)
            assertEquals(retried, db.queues().read())
        } finally { db.close() }
    }

    @Test fun unknownRepeatDefaultsToOffWithoutChangingSavedQueue() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val track = Track("content://song", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        val seed = QueueEdits.create(listOf(track, track), 1, "Repeated",
            playlistOrigins = listOf("source-a", "source-b"))
        val ids = seed.entries.map { it.id }
        val queue = seed.copy(positionMs = 1234, shuffle = true, repeat = QueueRepeat.All,
            journey = QueueJourney.start(ids, ids.last(), JourneyModes()),
            order = io.github.anastaci1a.localica.library.SavedOrder(ids.reversed(),
                io.github.anastaci1a.localica.library.TrackSort.Title, false))
        val saved = QueueLibrary(listOf(queue), queue.id)
        try {
            db.queues().save(QueueLibrary(), saved)
            assertEquals(saved, db.queues().read())
            val cases = QueueRepeat.entries.map { it.name to it } +
                listOf("FutureRepeat" to QueueRepeat.Off, "" to QueueRepeat.Off,
                    "all" to QueueRepeat.Off, "All " to QueueRepeat.Off)
            for ((name, expected) in cases) {
                db.openHelper.writableDatabase.execSQL("UPDATE queues SET repeat = ? WHERE id = ?", arrayOf(name, queue.id))
                assertEquals(saved.update(queue.copy(repeat = expected)), db.queues().read())
                db.openHelper.readableDatabase.query("SELECT repeat FROM queues WHERE id = ?", arrayOf(queue.id)).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("Read must not rewrite stored names", name, it.getString(0))
                }
            }
        } finally { db.close() }
    }
}
