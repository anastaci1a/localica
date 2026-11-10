package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.SavedOrderCodec
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackLocation
import io.github.anastaci1a.localica.library.TrackSnapshot
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.library.enumNameOrNull
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paged, sharing restore of #774 returns exactly what the relation restore it replaced returned. The reference
 * below is that earlier restore, kept verbatim over the same database rows: tied and out-of-order ordinals across a
 * page boundary, an exact page multiple, repeated songs and origins, saved order and journey, two snapshots of one
 * song id, and a DAO without a database.
 */
class QueueRestoreEquivalenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "restore-774-equivalence.db"
    private val page = 2000

    @After fun cleanUp() { context.deleteDatabase(name) }

    private fun track(n: Int, edition: String = "") = "content://music/tree/root/document/song%20$n.flac".let { uri ->
        Track(uri, "Song $n$edition", artist = "Artist ${n % 7}", album = "Album ${n % 11}", durationMs = 1000L + n,
            fileName = "song $n.flac", relativePath = "Artist ${n % 7}/Album ${n % 11}/song $n.flac", albumArtist = "Artist ${n % 7}",
            trackNumber = n % 13, year = 1990 + n % 30, genre = "Genre ${n % 3}", comment = if (n % 5 == 0) "note $n" else null,
            locations = listOf(TrackLocation("content://music/tree/root", uri, "Artist ${n % 7}/Album ${n % 11}/song $n.flac")) +
                if (n % 17 == 0) listOf(TrackLocation("content://music/tree/other", "$uri?copy", "copies/song $n.flac")) else emptyList(),
            composer = "Composer ${n % 4}", modifiedAt = 5L + n, addedAt = 9L + n, compilation = n % 2 == 0)
    }

    /** Songs repeat inside a queue, origins repeat, and every 97th occurrence carries a changed snapshot of its song id. */
    private fun queue(label: String, size: Int, journey: Boolean): ListeningQueue {
        val entries = List(size) { index ->
            val song = index % 613
            QueueEntry(track = if (index % 97 == 0) track(song, " (remaster)") else track(song),
                sourcePlaylistId = when (index % 4) { 0 -> null; 1 -> "playlist-a"; else -> "playlist-b" })
        }
        val ids = entries.map { it.id }
        val order = SavedOrder(ids.reversed(), TrackSort.Title, ascending = false)
        val visits = ids.take(5).map { QueueVisit(it, 2) }
        return ListeningQueue(name = label, entries = entries, currentEntryId = ids[size / 2], positionMs = 4321, order = order,
            journey = if (!journey) null else QueueJourney(visits.take(3), 2, visits.drop(3), 2, ids.take(3).toSet(),
                mapOf(1L to ids.shuffled(java.util.Random(7)), 2L to ids.shuffled(java.util.Random(8))), JourneyModes(true, false, true), "traversal", visits[1].token))
    }

    /** The restore before #774, unchanged: one relation read, a stable sort by ordinal, one decode per row. */
    private fun reference(stored: List<StoredQueue>, active: ActiveQueueRecord?): QueueLibrary {
        val queues = stored.map { item -> item.queue.let { q ->
            ListeningQueue(q.id, q.name, q.createdAt, item.entries.sortedBy { it.ordinal }.map {
                QueueEntry(it.id, TrackSnapshot.decode(it.track), it.sourcePlaylistId) }, q.currentEntryId, q.positionMs, q.shuffle,
                enumNameOrNull<QueueRepeat>(q.repeat) ?: QueueRepeat.Off, q.lastActivatedAt,
                item.journeys.singleOrNull()?.restore(item.visits, item.cycleMembers),
                SavedOrderCodec.decode(q.orderState, item.entries.sortedBy { it.ordinal }.map { it.id }))
        } }
        return QueueLibrary(queues, active?.queueId?.takeIf { id -> queues.any { it.id == id } })
    }

    @Test fun pagedSharingRestoreEqualsTheRelationRestoreIncludingTiesAndTheDatabaseFreePath() {
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, QueueDatabase::class.java, name).build()
        try {
            val saved = listOf(queue("exact pages", page * 2, journey = true), queue("across a page", page + 137, journey = true),
                queue("one short page", 41, journey = false), queue("one row over", page + 1, journey = false))
            runBlocking {
                var library = QueueLibrary()
                for (queue in saved) { val next = library.copy(queues = library.queues + queue, activeId = queue.id); database.queues().save(library, next); library = next }
            }
            // Rows are inserted in ordinal order. Reverse two queues so row order opposes ordinal order, then halve
            // ordinals so neighbours tie, including the pair that straddles the page boundary.
            database.openHelper.writableDatabase.apply {
                execSQL("UPDATE queue_entries SET ordinal = 100000 - ordinal WHERE queueId IN (?, ?)", arrayOf(saved[1].id, saved[3].id))
                execSQL("UPDATE queue_entries SET ordinal = ordinal / 2 WHERE queueId IN (?, ?)", arrayOf(saved[0].id, saved[1].id))
                execSQL("UPDATE queue_entries SET ordinal = 7 WHERE queueId = ?", arrayOf(saved[2].id))
            }
            val dao = database.queues()
            val expected = runBlocking { reference(dao.readQueues(), dao.readActive()) }
            val restored = runBlocking { dao.read() }
            assertEquals("fixture really ties and reverses", listOf(page * 2, page + 137, 41, page + 1), expected.queues.map { it.entries.size })
            assertTrue(expected.queues[3].entries.first().id == saved[3].entries.last().id)
            assertEquals(expected, restored)
            for ((old, new) in expected.queues.zip(restored.queues)) {
                assertEquals(old.entries.map { it.id }, new.entries.map { it.id })
                assertEquals(old.order, new.order)
                assertEquals(old.journey, new.journey)
            }
            // Sharing never merges different snapshots of one song id, and never merges occurrences.
            val first = restored.queues[0].entries
            val plain = first.first { it.track.title == "Song 5" }; val changed = first.first { it.track.title.endsWith("(remaster)") }
            assertSame(plain.track, restored.queues[1].entries.first { it.track.title == "Song 5" }.track)
            assertEquals(track(0, " (remaster)"), changed.track)
            assertNotSame(first[0], first[613])
            assertEquals(restored.queues.sumOf { it.entries.size }, restored.queues.flatMap { queue -> queue.entries.map { it.id } }.toSet().size)

            val withoutDatabase = runBlocking { DatabaseFreeDao(dao).read() }
            assertEquals("a DAO without a database restores the same library", expected, withoutDatabase)
        } finally { database.close() }
    }
}

/** A pure DAO as tests build them: no owning database, only the relation read. */
private class DatabaseFreeDao(private val delegate: QueueDao) : QueueDao() {
    override suspend fun readQueues() = delegate.readQueues()
    override suspend fun readActive() = delegate.readActive()
    override suspend fun upsertQueue(queue: QueueRecord): Unit = error("read only")
    override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int = error("read only")
    override suspend fun upsertActive(active: ActiveQueueRecord): Unit = error("read only")
    override suspend fun upsertJourney(journey: JourneyRecord): Unit = error("read only")
    override suspend fun clearJourney(id: String): Unit = error("read only")
    override suspend fun upsertVisits(visits: List<VisitRecord>): Unit = error("read only")
    override suspend fun updateVisits(visits: List<VisitRecord>): Int = error("read only")
    override suspend fun upsertCycleMembers(members: List<CycleMemberRecord>): Unit = error("read only")
    override suspend fun updateCycleMembers(members: List<CycleMemberRecord>): Int = error("read only")
    override suspend fun deleteVisits(queueId: String, tokens: List<String>): Unit = error("read only")
    override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>): Unit = error("read only")
    override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("read only")
    override suspend fun clearEntries(id: String): Unit = error("read only")
    override suspend fun deleteQueue(id: String): Unit = error("read only")
}
