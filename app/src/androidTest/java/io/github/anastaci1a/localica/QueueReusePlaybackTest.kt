package io.github.anastaci1a.localica

import android.content.ContextWrapper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.SavedOrder
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueReusePlaybackTest {
    @Test fun repeatedAlbumRequestsDuringInitialSaveReuseOneQueueAtTheRetentionLimit() =
        fixture(QueueSettings(maximum = 1)) { f ->
            val gate = CompletableDeferred<Unit>()
            f.dao.creationGate = gate
            f.queues.tap(f.tracks, 0, sourceName = "Album")
            withTimeout(10_000) { f.dao.creationEntered.await() }
            val created = f.queues.state.value.library.active!!
            f.awaitMain { f.player.isPlaying && f.player.currentMediaItem?.mediaId == created.entries[0].id }
            assertTrue(f.db.queues().read().queues.isEmpty())
            // The second command is accepted while the first queue has not finished saving.
            f.queues.tap(f.tracks, 2, sourceName = "Album")
            f.queues.tap(f.tracks, 1, sourceName = "Album")
            gate.complete(Unit)
            f.awaitMain { f.queues.state.value.playerRequest == 3L }
            f.awaitMain { f.player.isPlaying && f.player.currentMediaItem?.mediaId == created.entries[1].id }
            val result = f.db.queues().read()
            assertEquals(listOf(created.id), result.queues.map { it.id })
            assertEquals(created.entries, result.active!!.entries)
            assertEquals(created.entries[1].id, result.active!!.currentEntryId)
            assertEquals(listOf(0, 2, 1).map { created.entries[it].id }, result.active!!.journey!!.history.map { it.entryId })
            assertNull(f.queues.state.value.pending)
            assertNull(f.queues.state.value.problem)
        }

    @Test fun inactiveReusePreservesOccurrenceHistoryAndOrderThenRejectsOldVisitSeekAndRestoresPaused() {
        val settings = QueueSettings(maximum = 2, shuffle = true, repeat = QueueRepeat.All)
        val origins = listOf("first", "middle", "second", null)
        fixture(settings, initial = { tracks ->
            val album = QueueEdits.create(tracks, 0, "Album", origins)
            val ids = album.entries.map { it.id }
            val journey = QueueJourney.start(ids, ids[0], JourneyModes.from(settings)).select(ids[1], ids)
            val saved = album.copy(currentEntryId = ids[1], positionMs = 2345, createdAt = 12,
                lastActivatedAt = 15, journey = journey,
                order = SavedOrder(ids.reversed(), TrackSort.Title, false))
            val other = QueueEdits.create(tracks.take(1), 0, "Other").copy(positionMs = 1234)
            QueueLibrary(listOf(saved, other), other.id)
        }) { f ->
            val before = f.queues.state.value.library
            val saved = before.queues.first()
            val other = before.active!!
            val oldJourney = saved.journey!!
            val reused = f.tap(f.tracks, 2, "Album", origins)
            assertEquals(saved.id, reused.id)
            assertEquals(saved.entries, reused.entries)
            assertEquals(saved.order, reused.order)
            assertEquals(saved.createdAt, reused.createdAt)
            assertTrue(reused.lastActivatedAt > saved.lastActivatedAt)
            assertEquals(saved.entries[2].id, reused.currentEntryId)
            assertNotEquals(saved.entries[0].id, reused.currentEntryId)
            assertEquals(oldJourney.history, reused.journey!!.history.dropLast(1))
            assertTrue(reused.journey!!.visited.containsAll(oldJourney.visited))
            assertEquals(oldJourney.plans, reused.journey!!.plans)
            assertEquals(other, f.queues.state.value.library.queues.last())
            assertEquals(settings, f.queues.state.value.settings)
            assertEquals(settings, f.preferences.read())
            assertEquals(saved.id, f.queues.state.value.browsedId)
            assertNull(f.queues.state.value.pending)

            f.pause()
            val seeked = CompletableDeferred<Boolean>()
            val visit = withContext(Dispatchers.Main) { f.queues.playbackSample()!!.playbackVisit }
            withContext(Dispatchers.Main) { f.queues.requestSeek(2345, visit) { applied, _ -> seeked.complete(applied) } }
            assertTrue(withTimeout(10_000) { seeked.await() })
            val rejected = CompletableDeferred<Boolean>()
            val requests = f.queues.state.value.playerRequest
            withContext(Dispatchers.Main) {
                f.queues.tap(f.tracks, 2, sourceName = "Album", playlistOrigins = origins)
                f.queues.requestSeek(3456, visit) { applied, _ -> rejected.complete(!applied) }
            }
            assertTrue(withTimeout(10_000) { rejected.await() })
            f.awaitMain { f.queues.state.value.playerRequest == requests + 1 && f.player.isPlaying }
            withContext(Dispatchers.Main) {
                assertEquals(saved.entries[2].id, f.player.currentMediaItem!!.mediaId)
                assertNotEquals(visit, f.queues.playbackSample()!!.playbackVisit)
                assertTrue("Reuse restarts the requested occurrence rather than restoring its old cursor", f.player.currentPosition < 1500)
            }
            f.pause()
            val stored = f.db.queues().read()
            assertEquals(2, stored.queues.size)
            assertEquals(saved.entries, stored.active!!.entries)
            assertEquals(saved.order, stored.active!!.order)
            val history = stored.active!!.journey!!.history
            assertEquals(oldJourney.history, history.dropLast(2))
            assertEquals(listOf(saved.entries[2].id, saved.entries[2].id), history.takeLast(2).map { it.entryId })
            f.reopen()
            f.awaitMain { f.player.playbackState == Player.STATE_READY }
            withContext(Dispatchers.Main) {
                assertFalse(f.player.playWhenReady)
                assertEquals(stored.active!!.currentEntryId, f.player.currentMediaItem?.mediaId)
            }
            assertEquals(stored, f.queues.state.value.library)
        }
    }

    @Test fun namesOrdersCountsAndOriginsStayDistinctAndEachSameNameSnapshotCanBeReopened() = fixture { f ->
        val original = f.tap(f.tracks, 2, "Album")
        val reorderedTracks = listOf(f.tracks[0], f.tracks[2], f.tracks[1], f.tracks[3])
        val reordered = f.tap(reorderedTracks, 1, "Album")
        val renamed = f.tap(f.tracks, 0, "Album (2)")
        val fewer = f.tap(f.tracks.take(2), 1, "Album")
        val origins = listOf("playlist-a", null, "playlist-b", null)
        val sourced = f.tap(f.tracks, 2, "Album", origins)
        val ids = listOf(original, reordered, renamed, fewer, sourced).map { it.id }
        assertEquals(5, ids.toSet().size)
        assertEquals(listOf("Album", "Album", "Album (2)", "Album", "Album"),
            f.queues.state.value.library.queues.map { it.name })
        assertEquals(original.id, f.tap(f.tracks, 1, "Album").id)
        assertEquals(reordered.id, f.tap(reorderedTracks, 2, "Album").id)
        assertEquals(renamed.id, f.tap(f.tracks, 2, "Album (2)").id)
        assertEquals(fewer.id, f.tap(f.tracks.take(2), 0, "Album").id)
        assertEquals(sourced.id, f.tap(f.tracks, 2, "Album", origins).id)
        assertEquals(ids, f.db.queues().read().queues.map { it.id })
    }

    @Test fun confirmationRechecksEquivalenceAndDoesNotRemovePreviouslySelectedQueues() =
        fixture(QueueSettings(maximum = 2), initial = { tracks ->
            val possible = QueueEdits.create(tracks, 0, "Before rename")
            val active = QueueEdits.create(tracks.take(1), 0, "Playing")
            QueueLibrary(listOf(possible, active), active.id)
        }) { f ->
            val before = f.queues.state.value.library
            val candidate = before.queues.first()
            val completed = CompletableDeferred<Boolean>()
            f.queues.act(f.tracks, SongTap.NewQueue) { completed.complete(it) }
            f.awaitMain { f.queues.state.value.pending != null }
            assertFalse(completed.isCompleted)
            f.queues.rename(candidate.id, "Selected songs")
            f.awaitMain { f.queues.state.value.library.queues.first().name == "Selected songs" }
            // Originally this inactive queue was selected for removal to make room.
            f.queues.confirmCreation(setOf(candidate.id))
            assertTrue(withTimeout(10_000) { completed.await() })
            f.awaitMain { f.player.isPlaying && f.player.currentMediaItem?.mediaId == candidate.entries[0].id }
            val after = f.db.queues().read()
            assertEquals(before.queues.map { it.id }, after.queues.map { it.id })
            assertEquals(candidate.id, after.activeId)
            assertEquals(candidate.entries, after.active!!.entries)
            assertNull(f.queues.state.value.pending)
            assertNull(f.queues.state.value.problem)
        }

    private class Fixture(val db: QueueDatabase, val dao: ReuseQueueDao, val preferences: QueuePreferences,
        val tracks: List<Track>, val context: android.content.Context) {
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: ExoPlayer
        lateinit var queues: QueueController

        suspend fun open() = withContext(Dispatchers.Main) {
            player = ExoPlayer.Builder(context).build().apply { volume = 0f }
            queues = QueueController(dao, preferences, (context.applicationContext as LocalicaApplication).library, scope)
            queues.attach(player)
        }
        suspend fun reopen() {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            withContext(Dispatchers.Main) { player.release() }
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            open()
            awaitMain { !queues.state.value.loading }
        }
        suspend fun tap(tracks: List<Track>, index: Int, name: String, origins: List<String?> = emptyList()): ListeningQueue {
            val request = queues.state.value.playerRequest
            queues.tap(tracks, index, sourceName = name, playlistOrigins = origins)
            awaitMain { queues.state.value.playerRequest == request + 1 }
            val active = queues.state.value.library.active!!
            awaitMain { player.isPlaying && player.currentMediaItem?.mediaId == active.entries[index].id }
            assertNull(queues.state.value.problem)
            return queues.state.value.library.active!!
        }
        suspend fun pause() {
            queues.playbackRequested(false)
            awaitMain { !player.playWhenReady && !player.isPlaying && player.playbackState == Player.STATE_READY }
            withTimeout(10_000) {
                while (db.queues().read() != queues.state.value.library) delay(10)
            }
        }
        suspend fun awaitMain(condition: () -> Boolean) = withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(10)
        }
    }

    private fun fixture(settings: QueueSettings = QueueSettings(),
        initial: (List<Track>) -> QueueLibrary = { QueueLibrary() }, action: suspend (Fixture) -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "queue-reuse-${UUID.randomUUID()}"
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int) = context.getSharedPreferences(name, mode)
        }).apply { write(settings) }
        val file = File(context.cacheDir, "$name.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val tracks = listOf("a", "b", "a", "c").map { Track(file.toURI().toString(), it,
            id = it, fileName = file.name, relativePath = file.name) }
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        val dao = ReuseQueueDao(db.queues())
        val f = Fixture(db, dao, preferences, tracks, context)
        try {
            db.queues().save(QueueLibrary(), initial(tracks))
            f.open()
            f.awaitMain { !f.queues.state.value.loading && (f.queues.state.value.library.active == null || f.player.playbackState == Player.STATE_READY) }
            action(f)
        } finally {
            dao.creationGate?.complete(Unit)
            f.scope.coroutineContext[Job]!!.cancelAndJoin()
            withContext(Dispatchers.Main) { f.player.release() }
            db.close(); file.delete(); context.deleteSharedPreferences(name)
        }
    }
}

private class ReuseQueueDao(private val delegate: QueueDao) : QueueDao() {
    var creationGate: CompletableDeferred<Unit>? = null
    val creationEntered = CompletableDeferred<Unit>()
    override suspend fun read() = delegate.read()
    override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        if (after.queues.size > before.queues.size) { creationEntered.complete(Unit); creationGate?.await() }
        delegate.save(before, after)
    }
    override suspend fun readQueues() = delegate.readQueues()
    override suspend fun readActive() = delegate.readActive()
    override suspend fun upsertQueue(queue: QueueRecord): Unit = error("Use save")
    override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int = error("Use save")
    override suspend fun upsertActive(active: ActiveQueueRecord): Unit = error("Use save")
    override suspend fun upsertJourney(journey: JourneyRecord): Unit = error("Use save")
    override suspend fun clearJourney(id: String): Unit = error("Use save")
    override suspend fun upsertVisits(visits: List<VisitRecord>): Unit = error("Use save")
    override suspend fun updateVisits(visits: List<VisitRecord>): Int = error("Use save")
    override suspend fun upsertCycleMembers(members: List<CycleMemberRecord>): Unit = error("Use save")
    override suspend fun updateCycleMembers(members: List<CycleMemberRecord>): Int = error("Use save")
    override suspend fun deleteVisits(queueId: String, tokens: List<String>): Unit = error("Use save")
    override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>): Unit = error("Use save")
    override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("Use save")
    override suspend fun clearEntries(id: String): Unit = error("Use save")
    override suspend fun deleteQueue(id: String): Unit = error("Use save")
}
