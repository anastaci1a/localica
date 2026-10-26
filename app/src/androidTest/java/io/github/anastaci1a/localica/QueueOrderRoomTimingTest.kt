package io.github.anastaci1a.localica

import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in Room/native-handoff diagnostic, not an app-only latency benchmark or audio-readiness test.
 * Each callback observes committed in-memory SQLite and the native timeline before accepting
 * completion; this is not independent pre-callback-entry proof or disk/crash-durability evidence.
 * Its separately reported validation cost is INCLUDED in CallbackCompleted/total, never subtracted.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueOrderRoomTimingTest {
    @Test fun reverseRandomizeAndRevertObserveCommittedDuplicateOccurrencesAtCallback() {
        assumeTrue("Opt-in diagnostic requires queueOrderRoomTiming=true; no timing sample ran",
            InstrumentationRegistry.getArguments().getString("queueOrderRoomTiming") == "true")
        runBlocking { exercise() }
    }

    private suspend fun exercise() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "queue-order-room-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, name)
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val reports = Channel<Pair<String, QueueOrderTiming.Report>>(Channel.UNLIMITED)
        val reportCount = AtomicInteger()
        val grantReads = AtomicInteger()
        var queueDatabase: QueueDatabase? = null
        var libraryDatabase: LibraryDatabase? = null
        var player: ExoPlayer? = null
        var primaryFailure: Throwable? = null
        try {
            check(directory.mkdirs()) { "Could not create the owned fixture directory" }
            val audio = File(directory, "queue-tone.mp3")
            instrumentation.context.assets.open(audio.name).use { input ->
                audio.outputStream().use(input::copyTo)
            }
            val preferences = QueuePreferences(object : ContextWrapper(context) {
                override fun getSharedPreferences(ignored: String, mode: Int) =
                    context.getSharedPreferences(name, mode)
            })
            val settings = QueueSettings(switching = QueueSwitch.Paused)
            preferences.write(settings)
            val queueDb = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
                .also { queueDatabase = it }
            val libraryDb = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
                .also { libraryDatabase = it }
            val store = RoomLibraryStore(libraryDb.libraryDao())
            val root = Uri.fromFile(directory).toString()
            val permissions = object : FolderPermissions {
                override fun hasReadAccess(uri: String): Boolean {
                    grantReads.incrementAndGet()
                    return uri == root
                }
                override fun acquire(uri: String): Unit = error("Fixture never acquires external grants")
                override fun release(uri: String): Unit = error("Fixture never releases external grants")
            }
            val library = LibraryRepository(store, TreeScanner(AndroidDocuments(context)), permissions)
            // Distinct synthetic metadata rows exercise real Room rematerialization/search indexing.
            // Every row resolves only to this owned asset copy, never a user library or private file.
            val tracks = List(13_000) { index ->
                Track(Uri.fromFile(audio).toString(), "Synthetic $index", id = "synthetic-$index",
                    fileName = audio.name, relativePath = audio.name, durationMs = 20_000)
            }
            val initial = withTimeout(30_000) {
                store.replaceRoot(ScanResult(FolderSelection(root, "Synthetic fixture"), tracks, 0))
                val storedTracks = store.current().tracks
                assertEquals(13_000, storedTracks.size)
                val duplicate = storedTracks.single { it.id == "synthetic-0" }
                val entries = List(13_000) { index ->
                    QueueEntry("occurrence-$index", duplicate, "synthetic-origin")
                }
                val ids = entries.map { it.id }
                val current = ids[6500]
                val journey = QueueJourney.start(ids, ids.first(), JourneyModes.from(settings), Random(11))
                    .select(current, ids, Random(12)).select(ids[6501], ids, Random(13)).copy(cursor = 1)
                val queue = ListeningQueue(name = "Synthetic duplicates", entries = entries,
                    currentEntryId = current, positionMs = 1234, journey = journey)
                queueDb.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id))
                queue
            }
            val queues = withContext(Dispatchers.Main) {
                val native = ExoPlayer.Builder(context).build().apply { volume = 0f; playWhenReady = false }
                player = native
                QueueController(queueDb.queues(), preferences, library, actor).also { queues ->
                    queues.orderTimingObserver = { operation, report ->
                        reportCount.incrementAndGet()
                        check(reports.trySend(operation to report).isSuccess) { "Timing report was not retained" }
                    }
                    queues.attach(native)
                }
            }
            val native = requireNotNull(player)
            withTimeout(30_000) {
                while (!withContext(Dispatchers.Main) {
                    assertTrue("Native fixture preparation failed", native.playerError == null)
                    !queues.state.value.loading && native.playbackState == Player.STATE_READY &&
                        native.currentMediaItem?.queueVisit == initial.journey!!.current!!.token
                }) delay(10)
            }
            val opening = withContext(Dispatchers.Main) {
                assertTrue("Fixture must remain paused", !native.playWhenReady && !native.isPlaying)
                assertEquals(1234L, native.currentPosition)
                requireNotNull(queues.state.value.library.active)
            }
            assertEquals(13_000, opening.entries.size)
            assertEquals(13_000, opening.entries.map { it.id }.toSet().size)
            assertTrue("All occurrences retain the exact duplicate metadata",
                opening.entries.all { it.track == opening.entries.first().track })
            val openingJourney = requireNotNull(opening.journey)
            assertEquals(1, openingJourney.cursor)
            assertEquals(3, openingJourney.history.size)
            val checkpoint = OrderCheckpoint.capture(opening.entries.map { it.id }, opening.order)

            suspend fun command(operation: String, expectedEntries: List<QueueEntry>, expectedOrder: SavedOrder,
                submit: ((OrderCommit?) -> Unit) -> Unit) {
                val expectedReports = reportCount.get() + 1
                val readsBefore = grantReads.get()
                val deadline = SystemClock.elapsedRealtime() + 30_000
                val completed = CompletableDeferred<Unit>()
                val callbackCalls = AtomicInteger()
                val callbackNanos = AtomicLong()
                val callbackFailure = AtomicReference<Throwable?>()
                withTimeout(remaining(deadline)) {
                    withContext(Dispatchers.Main) {
                        submit { commit ->
                            val callbackEnteredAt = System.nanoTime()
                            try {
                                assertEquals("One completion callback per command", 1, callbackCalls.incrementAndGet())
                                assertTrue("Order command must succeed", commit != null)
                                val actual = requireNotNull(queues.state.value.library.active)
                                assertTrue("No controller failure", queues.state.value.problem == null)
                                assertTrue("Exact ordered occurrences survive", actual.entries == expectedEntries)
                                assertTrue("Saved manual order is exact", actual.order == expectedOrder)
                                assertTrue("Completion revision is the published entries", commit!!.revision === actual.entries)
                                assertTrue("Completion carries the saved order", commit.order == actual.order)
                                assertTrue("Completion retains the selected duplicate", commit.currentEntryId == opening.currentEntryId)
                                assertTrue("The operation changes order", commit.changed)
                                assertPreserved(opening, actual)
                                assertNativeHandoff(native, actual)

                                // The actor has already returned from its save transaction. Room reads
                                // run on IO/query executors, with no dispatch back to blocked Main.
                                // This intentionally blocks only this diagnostic callback; report its
                                // complete cost below rather than hiding it in app-only latency claims.
                                val durable = runBlocking(Dispatchers.IO) {
                                    withTimeout(remaining(deadline)) { queueDb.queues().read() }
                                }
                                assertTrue("SQLite active identity is committed", durable.activeId == actual.id)
                                assertTrue("SQLite occurrences, order, journey and cursor are committed", durable.active == actual)
                                assertNativeHandoff(native, actual)
                                assertTrue("Preferences remain unchanged", preferences.read() == settings)
                                remaining(deadline)
                            } catch (failure: Throwable) {
                                callbackFailure.compareAndSet(null, failure)
                            } finally {
                                callbackNanos.set(System.nanoTime() - callbackEnteredAt)
                                completed.complete(Unit)
                            }
                        }
                    }
                    completed.await()
                }
                val (reportedOperation, report) = withTimeout(remaining(deadline)) { reports.receive() }
                remaining(deadline)
                // Keep partial/failure reports too. This contains only aggregate scalar evidence.
                Log.i("QueueOrderRoomTiming", "operation=$reportedOperation actualRoomQueue=true actualRoomLibrary=true " +
                    "storage=inMemory evidence=callback-observed " +
                    "nativeApiReturnIsAudioReadiness=false callbackValidationNanos=${callbackNanos.get()} " +
                    "validationIncludedInStages=true $report")
                callbackFailure.get()?.let { throw it }
                assertTrue("Timing report belongs to this command", reportedOperation == operation)
                assertEquals("Exactly one terminal report per command", expectedReports, reportCount.get())
                assertEquals(1, callbackCalls.get())
                assertEquals(QueueOrderTiming.Outcome.Success, report.outcome)
                assertTrue(report.clockValid)
                val elapsed = QueueOrderTiming.Stage.entries.map { requireNotNull(report.elapsedNanos(it)) }
                assertEquals(8, elapsed.size)
                assertEquals(0L, elapsed.first())
                assertTrue("Stage observations are monotonic", elapsed.zipWithNext().all { (a, b) -> b >= a })
                assertTrue(requireNotNull(report.totalNanos) >= elapsed.last())
                assertEquals(13_000, report.counts.queueEntries)
                assertEquals(13_000, report.counts.libraryTracks)
                assertEquals(13_000, requireNotNull(report.counts.templatesReused) + requireNotNull(report.counts.templatesBuilt))
                assertTrue("Every activation checks current fixture grants", grantReads.get() > readsBefore)
                assertTrue("Callback stage includes validation overhead",
                    requireNotNull(report.durationNanos(QueueOrderTiming.Stage.MainNativeApiReturned,
                        QueueOrderTiming.Stage.CallbackCompleted)) >= callbackNanos.get())
            }

            val reversed = opening.entries.reversed()
            command("reverse", reversed, SavedOrder(reversed.map { it.id })) { queues.reverseOrder(opening.id, it) }
            val selected = reversed.single { it.id == opening.currentEntryId }
            val randomized = listOf(selected) + reversed.filterNot { it.id == selected.id }.shuffled(Random(7))
            command("randomize", randomized, SavedOrder(randomized.map { it.id })) {
                queues.randomizeOrder(opening.id, Random(7), it)
            }
            command("revert", opening.entries, checkpoint.saved) { queues.revertOrderWithCommit(opening.id, checkpoint, it) }
            // Drain all owned observer jobs before checking for an extra terminal publication.
            withTimeout(10_000) { actor.coroutineContext[Job]!!.cancelAndJoin() }
            assertEquals(3, reportCount.get())
            assertTrue("No duplicate timing reports remain", reports.tryReceive().isFailure)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                suspend fun attempt(action: suspend () -> Unit) {
                    try { action() } catch (failure: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
                    }
                }
                attempt { withTimeout(10_000) { actor.coroutineContext[Job]!!.cancelAndJoin() } }
                var released = player == null
                attempt { withContext(Dispatchers.Main) { player?.release(); released = true } }
                attempt { withContext(Dispatchers.IO) { queueDatabase?.close() } }
                attempt { withContext(Dispatchers.IO) { libraryDatabase?.close() } }
                reports.close()
                attempt { context.deleteSharedPreferences(name) }
                if (released) attempt { check(!directory.exists() || directory.deleteRecursively()) { "Owned fixture cleanup failed" } }
                cleanupFailure?.let { if (primaryFailure == null) throw it else primaryFailure!!.addSuppressed(it) }
            }
        }
    }

    private fun remaining(deadline: Long): Long = (deadline - SystemClock.elapsedRealtime()).also {
        assertTrue("Command and observations exceeded the original 30-second correctness budget", it > 0)
    }

    private fun assertPreserved(before: ListeningQueue, after: ListeningQueue) {
        val oldJourney = requireNotNull(before.journey)
        val newJourney = requireNotNull(after.journey)
        assertTrue("Current duplicate occurrence is retained", before.currentEntryId == after.currentEntryId)
        assertEquals(before.positionMs, after.positionMs)
        assertTrue("Current visit is retained", oldJourney.current == newJourney.current)
        assertTrue("History is retained", oldJourney.history == newJourney.history)
        assertEquals(oldJourney.cursor, newJourney.cursor)
        assertEquals(oldJourney.cycle, newJourney.cycle)
        assertTrue("Visited membership is retained", oldJourney.visited == newJourney.visited)
        assertTrue("Traversal modes are retained", oldJourney.modes == newJourney.modes)
        assertTrue("Only order and its future projection change", before.copy(entries = after.entries,
            order = after.order, journey = after.journey) == after)
    }

    private fun assertNativeHandoff(player: ExoPlayer, queue: ListeningQueue) {
        val journey = requireNotNull(queue.journey)
        val window = journey.window()
        assertTrue("Native API has the selected duplicate", player.currentMediaItem?.mediaId == queue.currentEntryId)
        assertTrue("Native API has the current visit", player.currentMediaItem?.queueVisit == journey.current?.token)
        assertTrue("Native API retains the occurrence origin",
            player.currentMediaItem?.mediaMetadata?.extras?.getString("sourcePlaylistId") == "synthetic-origin")
        assertEquals(queue.positionMs, player.currentPosition)
        assertTrue("Native timeline is bounded", player.mediaItemCount in 1..129)
        assertTrue("Native handoff contains the exact projected visits", (0 until player.mediaItemCount).map {
            player.getMediaItemAt(it).queueVisit
        } == window.map { it.token })
        assertTrue("Native handoff maps visits to the exact occurrences", (0 until player.mediaItemCount).map {
            player.getMediaItemAt(it).mediaId
        } == window.map { it.entryId })
        assertTrue("No native error", player.playerError == null)
        assertTrue("Native player remains paused", !player.playWhenReady && !player.isPlaying)
        assertEquals(0f, player.volume, 0f)
        // No STATE_READY assertion here: synchronous native API return is not decoder/audio readiness.
    }
}
