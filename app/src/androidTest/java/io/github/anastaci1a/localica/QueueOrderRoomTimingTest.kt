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
 * Each callback observes committed owned SQLite and the native timeline before accepting
 * completion; this is not independent pre-callback-entry proof or disk/crash-durability evidence.
 * Its separately reported validation cost is INCLUDED in CallbackCompleted/total, never subtracted.
 * Occurrences default to13000; queueOrderRoomEntries optionally selects4..13000 while the real
 * Room library remains13000 tracks. All per-case counts and invariants remain exact.
 * queueOrderRoomUniqueTracks and queueOrderRoomFileBacked accept only true/false (default false).
 * Unique metadata tracks still resolve to one owned audio asset; file-backed means owned local
 * database files, not independent crash-durability or phone-latency proof.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueOrderRoomTimingTest {
    @Test fun reverseRandomizeAndRevertObserveCommittedDuplicateOccurrencesAtCallback() {
        assumeTrue("Opt-in diagnostic requires queueOrderRoomTiming=true; no timing sample ran",
            InstrumentationRegistry.getArguments().getString("queueOrderRoomTiming") == "true")
        runBlocking { exercise() }
    }

    private suspend fun exercise() {
        val requestedEntries = InstrumentationRegistry.getArguments().getString("queueOrderRoomEntries")
        require(requestedEntries == null || Regex("[1-9][0-9]{0,4}").matches(requestedEntries)) {
            "queueOrderRoomEntries must be an unsigned decimal count"
        }
        val occurrenceCount = requestedEntries?.toInt() ?: 13_000
        require(occurrenceCount in 4..13_000) { "queueOrderRoomEntries must be in4..13000" }
        fun optionalBoolean(name: String): Boolean {
            val value = InstrumentationRegistry.getArguments().getString(name)
            require(value == null || value == "true" || value == "false") { "$name must be true or false" }
            return value == "true"
        }
        val uniqueTracks = optionalBoolean("queueOrderRoomUniqueTracks")
        val fileBacked = optionalBoolean("queueOrderRoomFileBacked")
        val storage = if (fileBacked) "fileBacked" else "inMemory"
        val expectedDistinctTracks = if (uniqueTracks) occurrenceCount else 1
        var observedDistinctTracks: Int? = null
        val selectedIndex = occurrenceCount / 2
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
        var unloggedReport: Pair<String, QueueOrderTiming.Report>? = null
        var normalReportsLogged = 0
        val saveWindows = linkedMapOf<String, RoomSaveWindow>() // Only the three named commands.
        val loggedSaveWindows = mutableSetOf<String>()
        var observedDao: TimingRoomQueueDao? = null
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
            val queueDb = (if (fileBacked) Room.databaseBuilder(context, QueueDatabase::class.java,
                File(directory, "queue.db").absolutePath) else Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java)).build()
                .also { queueDatabase = it }
            val libraryDb = (if (fileBacked) Room.databaseBuilder(context, LibraryDatabase::class.java,
                File(directory, "library.db").absolutePath) else Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)).build()
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
                val distinctStored = if (uniqueTracks) storedTracks.associateBy { it.id } else emptyMap()
                val entries = List(occurrenceCount) { index ->
                    QueueEntry("occurrence-$index", if (uniqueTracks) distinctStored.getValue("synthetic-$index") else duplicate,
                        "synthetic-origin")
                }
                val ids = entries.map { it.id }
                val current = ids[selectedIndex]
                val journey = QueueJourney.start(ids, ids.first(), JourneyModes.from(settings), Random(11))
                    .select(current, ids, Random(12)).select(ids[selectedIndex + 1], ids, Random(13)).copy(cursor = 1)
                val queue = ListeningQueue(name = "Synthetic duplicates", entries = entries,
                    currentEntryId = current, positionMs = 1234, journey = journey)
                queueDb.queues().save(QueueLibrary(), QueueLibrary(listOf(queue), queue.id))
                queue
            }
            val timedDao = TimingRoomQueueDao(queueDb.queues()).also { observedDao = it }
            val queues = withContext(Dispatchers.Main) {
                val native = ExoPlayer.Builder(context).build().apply { volume = 0f; playWhenReady = false }
                player = native
                QueueController(timedDao, preferences, library, actor).also { queues ->
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
            assertEquals(occurrenceCount, opening.entries.size)
            assertEquals(occurrenceCount, opening.entries.map { it.id }.toSet().size)
            if (!uniqueTracks) assertTrue("All occurrences retain the exact duplicate metadata",
                opening.entries.all { it.track == opening.entries.first().track })
            assertTrue("Exact stored tracks, occurrence order and origins survive fixture activation", opening.entries == initial.entries)
            val actualDistinctTracks = opening.entries.map { it.track }.toSet().size
            observedDistinctTracks = actualDistinctTracks
            assertEquals(expectedDistinctTracks, actualDistinctTracks)
            assertEquals(expectedDistinctTracks, opening.entries.map { it.track.id }.toSet().size)
            assertEquals("All synthetic tracks resolve only to the one owned fixture asset", 1,
                opening.entries.map { it.track.uri }.toSet().size)
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
                val saveWindow = RoomSaveWindow()
                check(saveWindows.put(operation, saveWindow) == null) { "Command window already exists" }
                withTimeout(remaining(deadline)) {
                    withContext(Dispatchers.Main) {
                        timedDao.openWindow(saveWindow)
                        submit { commit ->
                            val callbackEnteredAt = System.nanoTime()
                            try {
                                timedDao.closeWindow(RoomSaveWindow.End.CallbackEntry, saveWindow)
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
                val (reportedOperation, report) = withTimeout(remaining(deadline)) {
                    reports.receive().also { unloggedReport = it }
                }
                remaining(deadline)
                // Keep partial/failure reports too. This contains only aggregate scalar evidence.
                Log.i("QueueOrderRoomTiming", "operation=$reportedOperation actualRoomQueue=true actualRoomLibrary=true " +
                    "storage=$storage uniqueTracks=$uniqueTracks expectedDistinctTracks=$expectedDistinctTracks " +
                    "observedDistinctTrackValues=$observedDistinctTracks distinctMediaUris=1 " +
                    "evidence=callback-observed requestedOccurrences=$occurrenceCount " +
                    "nativeApiReturnIsAudioReadiness=false callbackValidationNanos=${callbackNanos.get()} " +
                    "validationIncludedInStages=true $report " +
                    "saveAssociation=invocation-entry-in-command-window commandCausality=unproven " +
                    "priorQueuedWorkMayBeIncluded=true roomSave=${saveWindows[reportedOperation]?.snapshot() ?: "unavailable"}")
                loggedSaveWindows += reportedOperation
                unloggedReport = null
                normalReportsLogged++
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
                assertEquals(occurrenceCount, report.counts.queueEntries)
                assertEquals(13_000, report.counts.libraryTracks)
                assertEquals(occurrenceCount, requireNotNull(report.counts.templatesReused) + requireNotNull(report.counts.templatesBuilt))
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
            assertTrue("No duplicate timing reports remain",
                reports.tryReceive().also { unloggedReport = it.getOrNull() }.isFailure)
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
                attempt { observedDao?.closeWindow(RoomSaveWindow.End.FailureCleanup) }
                val owner = actor.coroutineContext[Job]!!
                attempt { withTimeout(10_000) { owner.cancelAndJoin() } }
                // Cancellation/timeout alone is not termination. Parent completion includes the
                // observer children; the timeout cannot impose a hard deadline on blocked native work.
                val terminated = owner.isCompleted
                if (terminated) {
                    var released = player == null
                    var queueClosed = queueDatabase == null
                    var libraryClosed = libraryDatabase == null
                    attempt { withContext(Dispatchers.Main) { player?.release(); released = true } }
                    attempt { withContext(Dispatchers.IO) { queueDatabase?.close(); queueClosed = true } }
                    attempt { withContext(Dispatchers.IO) { libraryDatabase?.close(); libraryClosed = true } }
                    attempt { reports.close() }
                    attempt { context.deleteSharedPreferences(name) }
                    if (released && (!fileBacked || queueClosed && libraryClosed)) attempt {
                        check(!directory.exists() || directory.deleteRecursively()) { "Owned fixture cleanup failed" }
                    }
                    else attempt {
                        Log.w("QueueOrderRoomTiming", "cleanup=unverified-native-or-database-release ownedFixtureRetained=true " +
                            "actorObserverTerminationVerified=true nativeReleased=$released queueDatabaseClosed=$queueClosed " +
                            "libraryDatabaseClosed=$libraryClosed storage=$storage")
                    }
                } else {
                    attempt { error("Actor/observer termination unverified; owned resources retained") }
                    attempt {
                        Log.w("QueueOrderRoomTiming", "cleanup=unverified destructiveCleanupSkipped=true " +
                            "playerRetained=${player != null} queueDatabaseRetained=${queueDatabase != null} " +
                            "libraryDatabaseRetained=${libraryDatabase != null} reportChannelRetained=true " +
                            "ownedPreferencesAndFixtureRetained=true")
                    }
                }
                if (primaryFailure != null || cleanupFailure != null) attempt {
                    var availableReportsLogged = 0
                    fun logAvailable(available: Pair<String, QueueOrderTiming.Report>) {
                        Log.w("QueueOrderRoomTiming", "operation=${available.first} evidence=failure-cleanup-terminal " +
                            "storage=$storage uniqueTracks=$uniqueTracks expectedDistinctTracks=$expectedDistinctTracks " +
                            "observedDistinctTrackValues=${observedDistinctTracks ?: "unavailable"} nativeApiReturnIsAudioReadiness=false " +
                            "callbackValidationNanos=unavailable validationIncludedInStages=true ${available.second} " +
                            "saveAssociation=invocation-entry-in-command-window commandCausality=unproven " +
                            "priorQueuedWorkMayBeIncluded=true roomSave=${saveWindows[available.first]?.snapshot() ?: "unavailable"}")
                        loggedSaveWindows += available.first
                        availableReportsLogged++
                    }
                    // Preserve a report received just before a deadline/assertion failed to log it.
                    // Never replay normal records, and never fabricate a partial report for timeout.
                    unloggedReport?.let { logAvailable(it); unloggedReport = null }
                    // Bound this observation by the calls already seen. If termination is unverified,
                    // later publications remain possible and their channel stays owned and open.
                    repeat(reportCount.get()) {
                        reports.tryReceive().getOrNull()?.let(::logAvailable)
                    }
                    saveWindows.filterKeys { it !in loggedSaveWindows }.forEach { (operation, window) ->
                        Log.w("QueueOrderRoomTiming", "operation=$operation terminalEvidence=unavailable-at-observation " +
                            "saveAssociation=invocation-entry-in-command-window commandCausality=unproven " +
                            "priorQueuedWorkMayBeIncluded=true roomSave=${window.snapshot()}")
                    }
                    Log.w("QueueOrderRoomTiming", "failureCleanupEvidence normalReportsLogged=$normalReportsLogged " +
                        "availableUnconsumedReportsLogged=$availableReportsLogged observerCalls=${reportCount.get()} " +
                        "additionalTerminalEvidence=unavailable-at-observation " +
                        "actorObserverTerminationVerified=$terminated laterEvidencePossible=${!terminated}")
                }
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

/** Scalar aggregates only. A save belongs to the window active at invocation entry, even if it
 * finishes after closure. Prior queued actor work can enter this window; this is not causality.
 * Change-detection cost is measured separately, excluded from the delegate-call wall time but
 * included in the existing controller stages. Snapshot counts describe observations, not SQL rows.
 * Measured sums retain partial evidence (null means none). Complete totals additionally require a
 * closed window and every relevant endpoint; missing samples and pending saves remain explicit.
 */
private class RoomSaveWindow {
    enum class End { CallbackEntry, FailureCleanup }
    data class Summary(val invocations: Long, val succeeded: Long, val threw: Long, val pending: Long,
        val classifiedCalls: Long, val entriesChangedCalls: Long, val orderChangedCalls: Long,
        val journeyChangedCalls: Long, val timedCalls: Long, val untimedCompletedCalls: Long,
        val measuredSaveNanos: Long?, val completeSaveNanos: Long?, val unclassifiedCalls: Long,
        val measuredChangeDetectionNanos: Long?, val completeChangeDetectionNanos: Long?,
        val observationFailures: Long, val closedAt: End?,
        val observationsComplete: Boolean)

    private var invocations = 0L
    private var succeeded = 0L
    private var threw = 0L
    private var classified = 0L
    private var entriesChanged = 0L
    private var orderChanged = 0L
    private var journeyChanged = 0L
    private var timed = 0L
    private var saveNanos = 0L
    private var detectionNanos = 0L
    private var failures = 0L
    private var ended: End? = null

    @Synchronized fun entered() { invocations++ }
    @Synchronized fun closed(end: End) { ended = end }
    @Synchronized fun observationFailed() { failures++ }
    @Synchronized fun classified(entries: Boolean, order: Boolean, journey: Boolean, elapsed: Long) {
        val total = Math.addExact(detectionNanos, elapsed)
        classified++
        if (entries) entriesChanged++
        if (order) orderChanged++
        if (journey) journeyChanged++
        detectionNanos = total
    }
    @Synchronized fun completed(success: Boolean, elapsed: Long?) {
        if (success) succeeded++ else threw++
        if (elapsed != null) {
            val total = Math.addExact(saveNanos, elapsed)
            timed++
            saveNanos = total
        }
    }
    @Synchronized fun snapshot(): Summary {
        val completed = succeeded + threw
        return Summary(invocations, succeeded, threw, invocations - completed, classified,
            entriesChanged, orderChanged, journeyChanged, timed, completed - timed,
            saveNanos.takeIf { timed > 0 },
            saveNanos.takeIf { ended != null && timed > 0 && timed == invocations && completed == invocations },
            invocations - classified, detectionNanos.takeIf { classified > 0 },
            detectionNanos.takeIf { ended != null && classified > 0 && classified == invocations }, failures, ended,
            ended != null && completed == invocations && classified == invocations && timed == completed && failures == 0L)
    }
}

/** Same high-level delegation boundary as PreparedQueueDao; Room still owns every transaction.
 * No input graph or per-invocation record survives save(). read() is never a measured save.
 */
private class TimingRoomQueueDao(private val delegate: QueueDao) : QueueDao() {
    private var active: RoomSaveWindow? = null
    @Synchronized fun openWindow(window: RoomSaveWindow) {
        check(active == null) { "A save observation window is already open" }
        active = window
    }
    @Synchronized fun closeWindow(end: RoomSaveWindow.End, expected: RoomSaveWindow? = null) {
        if (expected == null || active === expected) {
            active?.closed(end)
            active = null
        }
    }
    @Synchronized private fun enterWindow() = active?.also { it.entered() }

    override suspend fun read() = delegate.read()
    override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        val window = enterWindow()
        if (window == null) { delegate.save(before, after); return }
        var diagnosticFailure: Throwable? = null
        fun observe(action: () -> Unit) {
            try { action() } catch (failure: Throwable) {
                window.observationFailed()
                val earlier = diagnosticFailure
                if (earlier == null) diagnosticFailure = failure
                else if (!suppressDistinct(earlier, failure)) window.observationFailed()
            }
        }
        observe {
            val start = System.nanoTime()
            var entries = false
            var order = false
            var journey = false
            for (next in after.queues) {
                val previous = before.queues.firstOrNull { it.id == next.id }
                entries = entries || previous?.entries != next.entries
                order = order || previous?.order != next.order
                journey = journey || previous == null || previous.journey != next.journey
            }
            if (before.queues.any { previous -> after.queues.none { it.id == previous.id } }) {
                entries = true; order = true; journey = true
            }
            val elapsed = System.nanoTime() - start
            check(elapsed >= 0) { "Change-detection clock is invalid" }
            window.classified(entries, order, journey, elapsed)
        }
        var startedAt: Long? = null
        observe { startedAt = System.nanoTime() }
        var daoFailure: Throwable? = null
        try { delegate.save(before, after) } catch (failure: Throwable) { daoFailure = failure }
        var elapsed: Long? = null
        observe {
            val endedAt = System.nanoTime()
            startedAt?.let { start ->
                elapsed = (endedAt - start).also { check(it >= 0) { "Save boundary clock is invalid" } }
            }
        }
        observe { window.completed(daoFailure == null, elapsed) }
        // Observation problems never prevent the delegate call or replace its exact Throwable.
        // After delegate success they fail this diagnostic, qualified as observation failures.
        daoFailure?.let { primary ->
            diagnosticFailure?.let { if (!suppressDistinct(primary, it)) window.observationFailed() }
            throw primary
        }
        diagnosticFailure?.let { throw it }
    }

    private fun suppressDistinct(primary: Throwable, secondary: Throwable): Boolean = try {
        if (primary !== secondary && primary.suppressed.none { it === secondary }) primary.addSuppressed(secondary)
        true
    } catch (_: Throwable) { false }

    override suspend fun readQueues(): List<StoredQueue> = error("Use delegated read")
    override suspend fun readActive(): ActiveQueueRecord? = error("Use delegated read")
    override suspend fun upsertQueue(queue: QueueRecord): Unit = error("Use delegated save")
    override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int = error("Use delegated save")
    override suspend fun upsertActive(active: ActiveQueueRecord): Unit = error("Use delegated save")
    override suspend fun upsertJourney(journey: JourneyRecord): Unit = error("Use delegated save")
    override suspend fun clearJourney(id: String): Unit = error("Use delegated save")
    override suspend fun upsertVisits(visits: List<VisitRecord>): Unit = error("Use delegated save")
    override suspend fun updateVisits(visits: List<VisitRecord>): Int = error("Use delegated save")
    override suspend fun upsertCycleMembers(members: List<CycleMemberRecord>): Unit = error("Use delegated save")
    override suspend fun updateCycleMembers(members: List<CycleMemberRecord>): Int = error("Use delegated save")
    override suspend fun deleteVisits(queueId: String, tokens: List<String>): Unit = error("Use delegated save")
    override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>): Unit = error("Use delegated save")
    override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("Use delegated save")
    override suspend fun clearEntries(id: String): Unit = error("Use delegated save")
    override suspend fun deleteQueue(id: String): Unit = error("Use delegated save")
}
