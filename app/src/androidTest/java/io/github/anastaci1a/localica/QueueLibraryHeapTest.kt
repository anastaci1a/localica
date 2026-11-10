package io.github.anastaci1a.localica

import android.os.SystemClock
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackLocation
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.queue.QueueDatabase
import io.github.anastaci1a.localica.queue.QueueEntry
import io.github.anastaci1a.localica.queue.QueueLibrary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Java heap retained by restoring many saved queues of a whole library (#774: 19 queues of 13,374 songs filled a
 * 256 MB heap at launch). The cost is measured per queue entry through the real database, at three library sizes.
 * Results are logged under `Heap774` and written to the evidence directory.
 */
class QueueLibraryHeapTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "heap-774-queues.db"

    @After fun cleanUp() { context.deleteDatabase(name) }

    private fun tracks(count: Int): List<Track> = List(count) { index ->
        val folder = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val album = index / 12; val n = index % 12 + 1
        val artist = "Artist ${(album / 3).toString().padStart(4, '0')} of the Long Name"
        val title = "Album ${album.toString().padStart(4, '0')} with a Longer Title"
        val path = "$artist/$title/${n.toString().padStart(2, '0')} - Song number $n of the album.flac"
        val uri = "$folder/document/primary%3AMusic%2F" + path.replace(" ", "%20").replace("/", "%2F")
        Track(uri, "Song number $n of the album $album", artist = artist, album = title, durationMs = 180_000L + n * 1000,
            fileName = path.substringAfterLast('/'), relativePath = path, albumArtist = artist, trackNumber = n, discNumber = 1,
            year = 1960 + album % 60, genre = "Genre ${album % 24}", locations = listOf(TrackLocation(folder, uri, path)),
            composer = "Composer ${album % 300}", modifiedAt = 1_700_000_000_000L + album, addedAt = 1_700_000_000_000L + album)
    }

    private fun liveBytes(): Long {
        repeat(3) { Runtime.getRuntime().gc(); SystemClock.sleep(250) }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }
    private fun open() = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()

    /** Saves [queues] whole-library queues and returns their sizes. Its own frame: nothing saved stays reachable. */
    private fun populate(songs: Int, queues: Int): List<Int> {
        context.deleteDatabase(name)
        var library = QueueLibrary()
        val tracks = tracks(songs)
        val database = open()
        runBlocking {
            // One queue per transaction, as the app saves them.
            repeat(queues) { number ->
                val queue = ListeningQueue(name = "${number + 1}. All Songs", entries = tracks.map { QueueEntry(track = it) })
                val next = library.copy(queues = library.queues + queue, activeId = queue.id)
                database.queues().save(library, next)
                // Only the shape is needed for the next save's comparison.
                library = next
            }
        }
        database.close()
        return library.queues.map { it.entries.size }
    }

    /** Saves [queues] whole-library queues, reopens the database and returns retained bytes per restored entry. */
    private fun restore(songs: Int, queues: Int): Double {
        val expected = populate(songs, queues)
        val before = liveBytes()
        val database = open()
        val restored = runBlocking { database.queues().read() }
        database.close()
        val retained = liveBytes() - before
        assertEquals(expected, restored.queues.map { it.entries.size })
        assertEquals("the first song survives the round trip", tracks(1).single(), restored.queues.last().entries.first().track)
        if (queues > 1) assertSame("queues of the same songs share one decoded song",
            restored.queues.first().entries[5].track, restored.queues.last().entries[5].track)
        val entries = expected.sum()
        val perEntry = retained.toDouble() / entries
        val line = "songs=$songs queues=$queues entries=$entries retainedMb=${retained / (1024 * 1024)} bytesPerEntry=${"%.1f".format(perEntry)} maxMb=${Runtime.getRuntime().maxMemory() / (1024 * 1024)}"
        android.util.Log.i("Heap774", line)
        File(nativeEvidenceDirectory("heap-774", context.getExternalFilesDir(null)), "queue-restore.txt").appendText(line + "\n")
        assertTrue("restored.queues stays reachable while measured", restored.queues.size == queues)
        return perEntry
    }

    @Test fun nineteenQueuesOfTheReportLibraryRestoreWithinBudget() {
        val perEntry = restore(13_374, 19)
        assertTrue("retained $perEntry bytes per queue entry", perEntry < 200.0)
    }

    @Test fun costPerEntryStaysFlatFromThirteenToFiftyThousandSongs() {
        val small = restore(13_374, 6)
        val double = restore(26_748, 6)
        val large = restore(50_000, 6)
        assertTrue("13k: $small bytes per entry", small < 260.0)
        assertTrue("27k: $double vs 13k: $small bytes per entry", double < small * 1.25 + 16)
        assertTrue("50k: $large vs 13k: $small bytes per entry", large < small * 1.25 + 16)
    }
}
