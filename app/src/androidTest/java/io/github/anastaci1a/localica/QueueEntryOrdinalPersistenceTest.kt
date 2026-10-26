package io.github.anastaci1a.localica

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueEntryOrdinalPersistenceTest {
    private fun queue(): ListeningQueue {
        val track = Track("content://fixture/song", "Song", id = "same-track", fileName = "song.mp3",
            relativePath = "song.mp3", comment = "Original snapshot",
            locations = listOf(TrackLocation("root", "content://fixture/song", "song.mp3")))
        val entries = listOf(QueueEntry("one", track, "first"), QueueEntry("two", track.copy(), null),
            QueueEntry("three", track.copy(title = "Same ID, different metadata"), "third"))
        val ids = entries.map { it.id }
        return ListeningQueue(id = "queue", name = "Ordinal fixture", entries = entries,
            currentEntryId = "two", positionMs = 2345,
            journey = QueueJourney.start(ids, "one", JourneyModes()).select("two", ids))
    }

    @Test fun exactReorderUpdatesOrdinalsWithoutDeletingOrReinsertingSnapshotPayloads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        try {
            val queue = queue()
            val before = QueueLibrary(listOf(queue), queue.id)
            db.queues().save(QueueLibrary(), before)
            val original = db.queues().readQueues().single().entries.associateBy { it.id }
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_entry_delete BEFORE DELETE ON queue_entries BEGIN SELECT RAISE(ABORT, 'Unexpected entry delete'); END")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_entry_insert BEFORE INSERT ON queue_entries BEGIN SELECT RAISE(ABORT, 'Unexpected entry insert'); END")
            val after = before.update(QueueEdits.reverse(queue))
            db.queues().save(before, after)
            assertEquals(after, db.queues().read())
            val rows = db.queues().readQueues().single().entries.sortedBy { it.ordinal }
            assertEquals(listOf("three", "two", "one"), rows.map { it.id })
            rows.forEach { row ->
                val old = original.getValue(row.id)
                assertEquals(old.track, row.track)
                assertEquals(old.sourcePlaylistId, row.sourcePlaylistId)
                assertEquals(old.queueId, row.queueId)
            }
            assertEquals(queue.journey, after.active!!.journey)
            assertEquals("two", after.active!!.currentEntryId)
            assertEquals(2345L, after.active!!.positionMs)
        } finally { db.close() }
    }

    @Test fun missingStationaryOccurrenceRepairsAndContentChangesReplaceExactPayloadAndOrigin() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        try {
            val queue = queue()
            val before = QueueLibrary(listOf(queue), queue.id)
            db.queues().save(QueueLibrary(), before)
            // This occurrence keeps ordinal1 when reversed: checking only moved rows would miss it.
            db.openHelper.writableDatabase.execSQL("DELETE FROM queue_entries WHERE id = 'two'")
            val reversed = before.update(QueueEdits.reverse(queue))
            db.queues().save(before, reversed)
            assertEquals(reversed, db.queues().read())

            val replacement = queue.entries[1].track.copy(uri = "content://fixture/updated", title = "Updated",
                durationMs = 31415, locations = listOf(TrackLocation("new-root", "content://fixture/updated", "updated.mp3")))
            val entries = reversed.active!!.entries.map { if (it.id == "two") it.copy(track = replacement,
                sourcePlaylistId = "replacement-origin") else it }
            val changed = reversed.update(reversed.active!!.copy(entries = entries))
            db.queues().save(reversed, changed)
            assertEquals(changed, db.queues().read())
            val row = db.queues().readQueues().single().entries.single { it.id == "two" }
            assertEquals(TrackSnapshot.encode(replacement), row.track)
            assertEquals("replacement-origin", row.sourcePlaylistId)
        } finally { db.close() }
    }

    @Test fun ordinalFailureRollsBackAndForeignQueueOccurrenceCannotBeClaimedByRepair() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        try {
            val queue = queue()
            val other = queue.copy(id = "other", name = "Other", entries = emptyList(), currentEntryId = null,
                journey = null, order = SavedOrder(emptyList()))
            val before = QueueLibrary(listOf(queue, other), queue.id)
            db.queues().save(QueueLibrary(), before)
            val after = before.update(QueueEdits.reverse(queue).copy(name = "Must roll back"))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_second_ordinal BEFORE UPDATE OF ordinal ON queue_entries WHEN OLD.id = 'two' BEGIN SELECT RAISE(ABORT, 'Synthetic ordinal failure'); END")
            try { db.queues().save(before, after); fail("Ordinal failure must remain fatal") }
            catch (_: android.database.SQLException) { }
            assertEquals(before, db.queues().read())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_second_ordinal")

            db.openHelper.writableDatabase.execSQL("UPDATE queue_entries SET queueId = 'other' WHERE id = 'two'")
            val rowsBefore = db.queues().readQueues().associate { it.queue.id to it.entries.sortedBy { row -> row.id } }
            try { db.queues().save(before, after); fail("Repair must not steal a foreign occurrence ID") }
            catch (_: android.database.SQLException) { }
            val restored = db.queues().readQueues()
            assertEquals(rowsBefore, restored.associate { it.queue.id to it.entries.sortedBy { row -> row.id } })
            assertEquals(queue.name, restored.single { it.queue.id == queue.id }.queue.name)
            assertEquals("other", restored.single { it.queue.id == "other" }.entries.single().queueId)
        } finally { db.close() }
    }
}
