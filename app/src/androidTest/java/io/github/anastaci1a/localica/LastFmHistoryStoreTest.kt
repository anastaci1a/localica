package io.github.anastaci1a.localica

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.lastfm.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LastFmHistoryStoreTest {
    @Test fun journalRecoversOnlyCommittedPagesAfterPartialCursorAndOrphanPageWrites() = runBlocking {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "history-${UUID.randomUUID()}")
        try {
            val store = FileLastFmHistoryStore(directory)
            val first = LastFmHistoryCheckpoint("attempt", "user", 0, 100, true, "source", mapOf("local" to 7))
            store.begin(first)
            val rows = listOf(LastFmRecentTrack("Artist", "Song", 90, "Album"), LastFmRecentTrack("Artist", "Song", 90, "Album"))
            val next = first.copy(nextPage = 2, totalPages = 2, tracks = listOf(LastFmTrack("Artist", "Song", 2, "Album")),
                lastFingerprint = historyFingerprint(rows), lastTimestamp = 90)
            store.append(first, next, LastFmRecentPage(1, 2, rows))
            File(directory, "page-2.json").writeText("orphaned incomplete page")
            val atomic = AtomicFile(File(directory, "checkpoint.json"))
            atomic.startWrite().use { it.write("unfinished cursor".toByteArray()) }
            val restored = FileLastFmHistoryStore(directory).read()!!
            assertEquals(next, restored); assertEquals(2L, restored.tracks.single().plays)
            val lastRows = listOf(LastFmRecentTrack("Artist", "Song", 80, "Live"))
            val last = restored.copy(nextPage = 3, tracks = restored.tracks + LastFmTrack("Artist", "Song", 1, "Live"),
                lastFingerprint = historyFingerprint(lastRows), lastTimestamp = 80)
            store.append(restored, last, LastFmRecentPage(2, 2, lastRows))
            assertEquals(last, FileLastFmHistoryStore(directory).read())
        } finally { directory.deleteRecursively() }
    }

    @Test fun deletionAndAccountReplacementRejectStalePageAppends() = runBlocking {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "history-${UUID.randomUUID()}")
        try {
            val store = FileLastFmHistoryStore(directory)
            val first = LastFmHistoryCheckpoint("attempt", "user", 0, 100, true, "source", emptyMap())
            store.begin(first); store.clear()
            val page = LastFmRecentPage(1, 1, listOf(LastFmRecentTrack("Artist", "Song", 90)))
            try { store.append(first, first.copy(nextPage = 2), page); fail("Deleted checkpoint resurrected") }
            catch (_: java.io.FileNotFoundException) { }
            assertFalse(directory.exists())
            val replacement = first.copy(id = "replacement", username = "other")
            store.begin(replacement)
            try { store.append(first, first.copy(nextPage = 2), page); fail("Old account replaced current progress") }
            catch (_: IllegalStateException) { }
            assertEquals(replacement, store.read())
        } finally { directory.deleteRecursively() }
    }
}
