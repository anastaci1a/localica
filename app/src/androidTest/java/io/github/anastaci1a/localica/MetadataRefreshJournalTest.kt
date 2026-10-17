package io.github.anastaci1a.localica

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import io.github.anastaci1a.localica.editing.MetadataRefreshJournal
import io.github.anastaci1a.localica.editing.commitRefreshRecord
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSnapshot
import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class MetadataRefreshJournalTest {
    private val first = Track("content://songs/one", "One", id = "one", fileName = "one.mp3", relativePath = "one.mp3")
    private val second = first.copy(id = "two", uri = "content://songs/two", title = "Two")

    @Test fun twicePerTrackIncludesWriteOnlyOneRecordWithLinearTotalBytes() = fixture { file ->
        val totals = listOf(100, 200, 400).map { count ->
            var writes = 0
            var bytes = 0L
            val journalFile = File(file.parentFile, "scale-$count.json")
            val journal = MetadataRefreshJournal(journalFile) { atomic, data ->
                writes++; bytes += data.size
                commitRefreshRecord(atomic, data)
            }
            repeat(count) { index ->
                val track = first.copy(id = "track-$index", comment = "metadata ".repeat(50))
                journal.include(track)
                journal.include(track.copy(uri = "content://written/$index"))
            }
            assertEquals(2 * count, writes)
            val reopened = MetadataRefreshJournal(journalFile).read()
            assertEquals(count, reopened.size)
            assertTrue(reopened.all { it.uri.startsWith("content://written/") })
            bytes
        }
        assertTrue("Doubling must not quadruple journal bytes: $totals", totals[1] < totals[0] * 2.1)
        assertTrue("Doubling must not quadruple journal bytes: $totals", totals[2] < totals[1] * 2.1)
    }

    @Test fun partialMigrationRetainsLegacyAndRetriesWithoutRewritingCommittedEntries() = fixture { file ->
        file.writeText(JSONArray(listOf(first, second).map(TrackSnapshot::encode)).toString())
        var writes = 0
        val interrupted = MetadataRefreshJournal(file) { atomic, data ->
            if (++writes == 2) throw IOException("controlled migration interruption")
            commitRefreshRecord(atomic, data)
        }
        assertThrows(IOException::class.java) { interrupted.read() }
        assertTrue("Old array must survive incomplete migration", file.exists())
        var retried = 0
        val recovered = MetadataRefreshJournal(file) { atomic, data ->
            retried++; commitRefreshRecord(atomic, data)
        }
        assertEquals(listOf(first, second), recovered.read())
        assertEquals("Only the missing entry needs copying", 1, retried)
        assertFalse(file.exists())
        val moved = first.copy(uri = "content://written/one")
        recovered.include(moved)
        assertEquals(listOf(second, moved), MetadataRefreshJournal(file).read())
    }

    @Test fun interruptedReplacementRecoversPreviousSnapshotOnBothAtomicFileImplementations() = fixture { file ->
        MetadataRefreshJournal(file).include(first)
        val entry = File(file.path + ".entries").listFiles()!!.single()
        val atomic = AtomicFile(entry)
        // Close the incomplete stream without finishWrite/failWrite to model an abandoned process.
        atomic.startWrite().use { it.write("incomplete".toByteArray()) }
        assertEquals(listOf(first), MetadataRefreshJournal(file).read())
    }

    @Test fun failedIncludeReloadsDiskBeforeRetryEvenIfCommitAlreadyReachedDisk() = fixture { file ->
        var fail = true
        val journal = MetadataRefreshJournal(file) { atomic, data ->
            commitRefreshRecord(atomic, data)
            if (fail) { fail = false; throw IOException("after commit") }
        }
        assertThrows(IOException::class.java) { journal.include(first) }
        journal.include(second)
        assertEquals(listOf(first, second), MetadataRefreshJournal(file).read())
    }

    @Test fun corruptedRecordFailsClosedAndDoesNotDiscardLegacyOrOtherRecords() = fixture { file ->
        MetadataRefreshJournal(file).include(first)
        val entry = File(file.path + ".entries").listFiles()!!.single()
        entry.writeText("corrupt")
        file.writeText(JSONArray(listOf(TrackSnapshot.encode(second))).toString())
        assertThrows(Exception::class.java) { MetadataRefreshJournal(file).include(second) }
        assertEquals("corrupt", entry.readText())
        assertTrue(file.exists())
    }

    @Test fun failedRetentionWriteCannotRemoveOtherPendingTracks() = fixture { file ->
        MetadataRefreshJournal(file).write(listOf(first, second))
        val interrupted = MetadataRefreshJournal(file) { _, _ -> throw IOException("retention failure") }
        assertThrows(IOException::class.java) { interrupted.write(listOf(first)) }
        assertEquals(listOf(first, second), MetadataRefreshJournal(file).read())
        MetadataRefreshJournal(file).write(listOf(first))
        assertEquals(listOf(first), MetadataRefreshJournal(file).read())
        MetadataRefreshJournal(file).write(emptyList())
        assertTrue(MetadataRefreshJournal(file).read().isEmpty())
    }

    private fun fixture(test: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "refresh-${UUID.randomUUID()}").apply { mkdirs() }
        try { test(File(directory, "pending.json")) } finally { directory.deleteRecursively() }
    }
}
