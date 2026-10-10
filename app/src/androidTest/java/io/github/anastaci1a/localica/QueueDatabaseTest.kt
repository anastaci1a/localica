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
class QueueDatabaseTest {
    @Test fun snapshotsDuplicateEntriesAndCursorsSurviveReopenAndFailedEdits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "queue-test.db"
        context.deleteDatabase(name)
        var database = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        val track = Track("content://missing/song", "Title", fileName = "song.mp3", relativePath = "song.mp3", id = "song",
            comment = "Keep this", locations = listOf(TrackLocation("root", "content://missing/song", "song.mp3")))
        val queue = QueueEdits.create(listOf(track, track), track.id, "Session").let {
            it.copy(currentEntryId = it.entries.last().id, positionMs = 1234, shuffle = true, repeat = QueueRepeat.All)
        }
        val saved = QueueLibrary(listOf(queue), queue.id)
        try {
            database.queues().save(QueueLibrary(), saved)
            database.close()
            database = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
            assertEquals(saved, database.queues().read())
            val invalid = saved.update(queue.copy(entries = listOf(queue.entries[0], queue.entries[0])))
            try { database.queues().save(saved, invalid); fail("Duplicate entry IDs must fail") }
            catch (_: android.database.SQLException) { }
            assertEquals(saved, database.queues().read())
            val advanced = saved.update(queue.copy(positionMs = 2345))
            database.queues().save(saved, advanced)
            assertEquals(advanced, database.queues().read())
            database.queues().save(advanced, QueueLibrary())
            assertEquals(QueueLibrary(), database.queues().read())
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun queueOrderChangesPersistWithoutReplacingEntriesOrCursor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        try {
            val track = Track("song", "Song", fileName = "song.mp3", relativePath = "song.mp3", composer = "Composer", lyricist = "Lyricist", modifiedAt = 1234L)
            val first = QueueEdits.create(listOf(track, track), 1, "First").copy(positionMs = 2400)
            val second = QueueEdits.create(listOf(track), 0, "Second")
            val original = QueueLibrary(listOf(first, second), first.id)
            db.queues().save(QueueLibrary(), original)
            val reordered = original.copy(queues = original.queues.reversed())
            db.queues().save(original, reordered)
            assertEquals(reordered, db.queues().read())
        } finally { db.close() }
    }
}
