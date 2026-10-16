package io.github.anastaci1a.localica

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.statistics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class LiveOrderPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alpha = Track("missing:a", "Alpha", id = "a", fileName = "a.mp3", relativePath = "a.mp3")
    private val beta = Track("missing:b", "Beta", id = "b", fileName = "b.mp3", relativePath = "b.mp3")
    private val originalSnapshot get() = LibrarySnapshot(tracks = listOf(alpha, beta))
    private val changedSnapshot get() = LibrarySnapshot(tracks = listOf(
        alpha.copy(uri = "current:a", title = "Zulu"), beta.copy(uri = "current:b", title = "Alpha"),
    ))

    private suspend fun completed(command: ((Boolean) -> Unit) -> Unit): Boolean {
        val result = CompletableDeferred<Boolean>()
        command { result.complete(it) }
        return withTimeout(10_000) { result.await() }
    }

    private suspend fun record(statistics: StatisticsRepository, trackId: String, expected: Long) {
        statistics.record(ListeningSource("listening:$trackId:$expected", trackId, "source-playlist"))
        withTimeout(10_000) { statistics.counts.first { it.tracks[trackId] == expected } }
    }

    /** The first command pauses before reading either metadata or durable statistics. */
    private class SortSource(initial: LibrarySnapshot, private val statistics: StatisticsRepository) {
        val snapshot = AtomicReference(initial)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reads = AtomicInteger()

        suspend fun read(): LibrarySnapshot {
            if (reads.incrementAndGet() == 1) {
                entered.complete(Unit)
                release.await()
            }
            return statistics.counts.first().applyTo(snapshot.get())
        }
    }

    @Test fun playlistSortReadsFreshKeysRetriesFailedSaveAndPreservesStoredOccurrences() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        val statisticsDb = Room.inMemoryDatabaseBuilder(context, StatisticsDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val statisticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val original = ManualPlaylist(id = "playlist", name = "Mix", entries = listOf(
                SongRow("first", alpha), SongRow("second", beta), SongRow("duplicate", alpha),
            ))
            val opening = PlaylistEdits.sort(original, TrackSort.Title, true)
            db.playlists().save(emptyList(), listOf(opening))
            val statistics = StatisticsRepository(statisticsDb.statistics(), statisticsScope)
            val source = SortSource(originalSnapshot, statistics)
            val controller = PlaylistController(db.playlists(), scope, sortSnapshot = source::read)
            withTimeout(10_000) { controller.state.first { !it.loading } }

            val firstAttempt = CompletableDeferred<Boolean>()
            controller.applySort(opening.id, TrackSort.Title, true) { firstAttempt.complete(it) }
            withTimeout(10_000) { source.entered.await() }
            source.snapshot.set(changedSnapshot)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_live_sort BEFORE UPDATE ON playlists BEGIN SELECT RAISE(ABORT, 'Injected write failure'); END")
            source.release.complete(Unit)
            assertFalse(withTimeout(10_000) { firstAttempt.await() })
            assertEquals(opening, controller.state.value.playlists.single())
            assertEquals(opening, db.playlists().read().single())
            assertFalse(LiveOrder(changedSnapshot).isApplied(opening.order, opening.entries, TrackSort.Title, true))

            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_live_sort")
            assertTrue(completed { controller.applySort(opening.id, TrackSort.Title, true, it) })
            val titled = controller.state.value.playlists.single()
            assertEquals(listOf("second", "first", "duplicate"), titled.entries.map { it.id })
            assertEquals(opening.order, titled.order)
            assertEquals(titled, db.playlists().read().single())

            record(statistics, "a", 1)
            assertTrue(completed { controller.applySort(opening.id, TrackSort.MostPlayed, false, it) })
            val counted = controller.state.value.playlists.single()
            assertEquals(listOf("first", "duplicate", "second"), counted.entries.map { it.id })
            assertEquals(SavedOrder(original.entries.map { it.id }, TrackSort.MostPlayed, false), counted.order)
            assertEquals(counted, db.playlists().read().single())

            record(statistics, "b", 1)
            record(statistics, "b", 2)
            val latest = statistics.counts.first().applyTo(changedSnapshot)
            assertFalse(LiveOrder(latest).isApplied(counted.order, counted.entries, TrackSort.MostPlayed, false))
            assertTrue(completed { controller.applySort(opening.id, TrackSort.MostPlayed, false, it) })
            val recounted = controller.state.value.playlists.single()
            assertEquals(listOf("second", "first", "duplicate"), recounted.entries.map { it.id })
            assertEquals(counted.order, recounted.order)
            assertTrue(LiveOrder(latest).isApplied(recounted.order, recounted.entries, TrackSort.MostPlayed, false))
            assertEquals(recounted, db.playlists().read().single())
            assertEquals(original.entries.associateBy { it.id }, recounted.entries.associateBy { it.id })
            assertTrue(recounted.entries.all { it.track.playCount == 0L })

            assertTrue(completed { controller.applySort(opening.id, TrackSort.Manual, true, it) })
            val restored = controller.state.value.playlists.single()
            assertEquals(original.entries, restored.entries)
            assertEquals(original.order, restored.order)
            assertEquals(restored, db.playlists().read().single())
            assertEquals(4, source.reads.get())
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            statisticsScope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
            statisticsDb.close()
        }
    }

    @Test fun queueSortReadsFreshKeysRetriesFailedSaveAndPreservesCursorAndJourney() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val statisticsDb = Room.inMemoryDatabaseBuilder(context, StatisticsDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val statisticsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = QueuePreferences(context)
        val priorSettings = preferences.read()
        try {
            val settings = priorSettings.copy(shuffle = true, repeat = QueueRepeat.All, advancedRepeat = null)
            preferences.write(settings)
            val original = ListeningQueue(id = "queue", name = "Mix", entries = listOf(
                QueueEntry("first", alpha, "origin-a"), QueueEntry("second", beta, "origin-b"),
                QueueEntry("duplicate", alpha, "origin-c"),
            ), currentEntryId = "duplicate", positionMs = 12_345)
            val ids = original.entries.map { it.id }
            val modes = JourneyModes.from(settings)
            val journey = QueueJourney.start(ids, "first", modes, Random(19))
                .select("duplicate", ids, Random(20)).select("second", ids, Random(21)).copy(cursor = 1)
            val opening = QueueEdits.sort(original.copy(journey = journey), TrackSort.Title, true)
            db.queues().save(QueueLibrary(), QueueLibrary(listOf(opening), opening.id))
            val statistics = StatisticsRepository(statisticsDb.statistics(), statisticsScope)
            val source = SortSource(originalSnapshot, statistics)
            lateinit var controller: QueueController
            instrumentation.runOnMainSync {
                controller = QueueController(db.queues(), preferences,
                    (context.applicationContext as LocalicaApplication).library, scope, sortSnapshot = source::read)
            }
            withTimeout(10_000) { controller.state.first { !it.loading } }

            val firstAttempt = CompletableDeferred<Boolean>()
            controller.applySort(opening.id, TrackSort.Title, true) { firstAttempt.complete(it) }
            withTimeout(10_000) { source.entered.await() }
            source.snapshot.set(changedSnapshot)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_live_sort BEFORE UPDATE ON queues BEGIN SELECT RAISE(ABORT, 'Injected write failure'); END")
            source.release.complete(Unit)
            assertFalse(withTimeout(10_000) { firstAttempt.await() })
            assertEquals(opening, controller.state.value.library.active)
            assertEquals(opening, db.queues().read().active)
            assertFalse(LiveOrder(changedSnapshot).isApplied(opening.order,
                opening.entries.map { SongRow(it.id, it.track) }, TrackSort.Title, true))

            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_live_sort")
            assertTrue(completed { controller.applySort(opening.id, TrackSort.Title, true, it) })
            val titled = controller.state.value.library.active!!
            assertEquals(listOf("second", "first", "duplicate"), titled.entries.map { it.id })
            assertEquals(opening.order, titled.order)
            assertEquals(titled, db.queues().read().active)
            assertQueueHistory(opening, titled)

            record(statistics, "a", 1)
            assertTrue(completed { controller.applySort(opening.id, TrackSort.MostPlayed, false, it) })
            val counted = controller.state.value.library.active!!
            assertEquals(listOf("first", "duplicate", "second"), counted.entries.map { it.id })
            assertEquals(SavedOrder(ids, TrackSort.MostPlayed, false), counted.order)
            assertEquals(counted, db.queues().read().active)
            assertQueueHistory(opening, counted)

            record(statistics, "b", 1)
            record(statistics, "b", 2)
            val latest = statistics.counts.first().applyTo(changedSnapshot)
            assertFalse(LiveOrder(latest).isApplied(counted.order,
                counted.entries.map { SongRow(it.id, it.track) }, TrackSort.MostPlayed, false))
            assertTrue(completed { controller.applySort(opening.id, TrackSort.MostPlayed, false, it) })
            val recounted = controller.state.value.library.active!!
            assertEquals(listOf("second", "first", "duplicate"), recounted.entries.map { it.id })
            assertEquals(counted.order, recounted.order)
            assertEquals(recounted, db.queues().read().active)
            assertQueueHistory(opening, recounted)
            assertEquals(original.entries.associateBy { it.id }, recounted.entries.associateBy { it.id })
            assertTrue(recounted.entries.all { it.track.playCount == 0L })

            assertTrue(completed { controller.applySort(opening.id, TrackSort.Manual, true, it) })
            val restored = controller.state.value.library.active!!
            assertEquals(original.entries, restored.entries)
            assertEquals(original.order, restored.order)
            assertEquals(restored, db.queues().read().active)
            assertQueueHistory(opening, restored)
            assertEquals(4, source.reads.get())
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            statisticsScope.coroutineContext[Job]!!.cancelAndJoin()
            preferences.write(priorSettings)
            db.close()
            statisticsDb.close()
        }
    }

    private fun assertQueueHistory(before: ListeningQueue, after: ListeningQueue) {
        assertEquals(before.currentEntryId, after.currentEntryId)
        assertEquals(before.positionMs, after.positionMs)
        assertEquals(before.lastActivatedAt, after.lastActivatedAt)
        assertEquals(before.order.manual, after.order.manual)
        assertEquals(before.journey, after.journey)
    }
}
