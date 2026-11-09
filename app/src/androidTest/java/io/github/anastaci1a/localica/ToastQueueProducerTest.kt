package io.github.anastaci1a.localica

import android.content.ContextWrapper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import io.github.anastaci1a.localica.toast.*
import io.github.anastaci1a.localica.library.SongRow
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import java.io.IOException
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ToastQueueProducerTest {
    @Test fun actAppendCompletesBeforeHeldOptionalSyncAndSurvivesCancellation() = runBlocking {
        cancelHeldAct(SongTap.Append)
    }

    @Test fun actPlayNextCompletesBeforeHeldOptionalSyncAndSurvivesCancellation() = runBlocking {
        cancelHeldAct(SongTap.PlayNext)
    }

    private suspend fun cancelHeldAct(action: SongTap) {
        fixture(QueueSwitch.Browse) { f ->
            val gate = f.factory.holdNext()
            val completions = CopyOnWriteArrayList<Boolean>()
            f.main { f.queues.act(listOf(f.track, f.track), action) { completions += it } }
            withTimeout(10_000) { gate.entered.await() }
            assertEquals(3, f.dao.read().active!!.entries.size)
            assertEquals(1L, f.toasts.committedOutcomes.state.value.total)
            assertEquals(listOf(true), completions)
            f.scope.cancel()
            gate.release.countDown()
            f.scope.coroutineContext[Job]!!.join()
            assertEquals(listOf(true), completions)
            assertEquals(1L, f.toasts.committedOutcomes.state.value.total)
        }
    }

    @Test fun failedStructuralSaveHasNoSuccessOutcomeOrSuccessfulCompletion() = runBlocking {
        fixture(QueueSwitch.Browse) { f ->
            f.dao.failSave = { it.active?.entries?.size == 3 }
            assertFalse(append(f))
            f.barrier()
            assertEquals(1, f.dao.read().active!!.entries.size)
            assertEquals(0L, f.toasts.committedOutcomes.state.value.total)
            assertNull(f.toasts.state.value.current)
        }
    }

    @Test fun durableAppendSurvivesPostSaveActivationFailure() = runBlocking {
        fixture(QueueSwitch.Browse) { f ->
            f.player.failNextAdd = true
            assertTrue(append(f))
            f.barrier()
            assertEquals(1, f.player.injectedFailures.get())
            assertEquals(3, f.dao.read().active!!.entries.size)
            assertEquals(1L, f.toasts.committedOutcomes.state.value.total)
            assertTrue(f.queues.state.value.problem!!.contains("Songs were saved"))
        }
    }

    @Test fun duplicateTracksCarryTheFirstNewOccurrenceAndCompletionThrowsOnlyOnce() = runBlocking {
        fixture(QueueSwitch.Browse) { f ->
            val completions = CopyOnWriteArrayList<Boolean>()
            assertTrue(append(f) { completions += it; throw IllegalStateException("Completion probe") })
            f.barrier()
            assertEquals(listOf(true), completions)
            val saved = f.dao.read().active!!
            assertEquals(3, saved.entries.size)
            assertEquals(3, saved.entries.map { it.id }.distinct().size)
            assertEquals(1, saved.entries.map { it.track.id }.distinct().size)
            val command = f.toasts.state.value.current!!.content.tap as ToastCommand.OpenQueue
            assertEquals(saved.entries[1].id, command.firstAddedEntryId)
            assertEquals(1L, f.toasts.committedOutcomes.state.value.total)
            assertNull(f.queues.state.value.problem)
        }
    }

    @Test fun fullAdmissionAndThrowingDiagnosticNeverTurnCommittedAppendIntoFailure() = runBlocking {
        val queue = ToastQueue(capacity = 1, onUndeliveredCommittedToast = { throw IllegalStateException("Diagnostic probe") })
        queue.showToast(ToastMessage("Existing action", tap = ToastCommand.OpenQueue("reserved", "entry", false)))
        fixture(QueueSwitch.Browse, toasts = queue) { f ->
            assertTrue(append(f))
            f.barrier()
            assertEquals(3, f.dao.read().active!!.entries.size)
            val report = queue.committedOutcomes.state.value
            assertEquals(1L, report.total)
            assertEquals(1L, report.declined)
            assertEquals(1L, report.reportingFailures)
            assertEquals(ToastAdmission.Full, report.lastUndelivered!!.admission)
            assertEquals("active", (report.lastUndelivered!!.content.tap as ToastCommand.OpenQueue).queueId)
            assertEquals("Existing action", queue.state.value.current!!.content.message)
            assertNull(f.queues.state.value.problem)
        }
    }

    @Test fun immediateAndPendingCreationEmitOnlyAfterTheirActualDurableCommit() = runBlocking {
        for (seed in listOf(Seed.Empty, Seed.InactiveAtLimit)) fixture(QueueSwitch.Browse, seed = seed) { f ->
            val completed = CompletableDeferred<Boolean>()
            f.main { f.queues.act(listOf(f.track, f.track), SongTap.Append) { completed.complete(it) } }
            if (seed == Seed.InactiveAtLimit) {
                withTimeout(10_000) { while (f.queues.state.value.pending == null) delay(10) }
                assertFalse(completed.isCompleted)
                assertNull(f.toasts.state.value.current)
                assertEquals(SongTap.Append, f.queues.state.value.pending!!.toastAction)
                f.main { f.queues.confirmCreation(setOf("other")) }
            }
            assertTrue(withTimeout(10_000) { completed.await() })
            val saved = f.dao.read().active!!
            assertEquals(2, saved.entries.size)
            val command = f.toasts.state.value.current!!.content.tap as ToastCommand.OpenQueue
            assertEquals(saved.id, command.queueId)
            assertEquals(saved.entries.first().id, command.firstAddedEntryId)
            assertEquals(1L, f.toasts.committedOutcomes.state.value.total)
        }
    }

    @Test fun activationFailureBeforeCreationPersistStillReportsTheCommittedCreation() = runBlocking {
        fixture(QueueSwitch.Browse, seed = Seed.Empty) { f ->
            f.player.failNextSet = true
            val completed = CompletableDeferred<Boolean>()
            f.main { f.queues.act(listOf(f.track, f.track), SongTap.Append) { completed.complete(it) } }
            assertTrue(withTimeout(10_000) { completed.await() })
            assertEquals(1, f.player.injectedFailures.get())
            assertEquals(2, f.dao.read().active!!.entries.size)
            assertEquals(1L, f.toasts.committedOutcomes.state.value.total)
        }
    }

    @Test fun cancellationBeforeReceiptDoesNotInventAnOutcomeOrAFailedCompletion() = runBlocking {
        fixture(QueueSwitch.Browse) { f ->
            val gate = f.dao.gateNextSave { it.active?.entries?.size == 3 }
            val completions = CopyOnWriteArrayList<Boolean>()
            f.main { f.queues.appendTo("active", f.rows()) { completions += it } }
            withTimeout(10_000) { gate.entered.await() }
            f.scope.coroutineContext[Job]!!.cancelAndJoin()
            assertEquals(emptyList<Boolean>(), completions)
            assertNull(f.toasts.state.value.current)
            assertEquals(1, f.dao.read().active!!.entries.size)
        }
    }

    @Test fun cancellationFromSuccessfulCompletionKeepsOneDurableOutcomeAndNoFalseCallback() = runBlocking {
        fixture(QueueSwitch.Browse) { f ->
            val completions = CopyOnWriteArrayList<Boolean>()
            val done = CompletableDeferred<Unit>()
            f.main { f.queues.appendTo("active", f.rows()) {
                completions += it
                f.scope.cancel()
                done.complete(Unit)
            } }
            withTimeout(10_000) { done.await() }
            f.scope.coroutineContext[Job]!!.join()
            assertEquals(listOf(true), completions)
            assertEquals(3, f.dao.read().active!!.entries.size)
            assertEquals(1L, f.toasts.committedOutcomes.state.value.total)
        }
    }

    @Test fun checkpointReturnsItsOwnSnapshotAfterTheWriterAdvancesToALaterCursor() = runBlocking {
        fixture(QueueSwitch.Browse) { f ->
            val first = f.queues.state.value.library.let { it.update(it.active!!.copy(positionMs = 111)) }
            val later = first.update(first.active!!.copy(positionMs = 777))
            val gate = f.dao.gateNextSave { it == first }
            val dispatcher = HoldingMainDispatcher()
            val result = async(dispatcher) { checkpoint(f.queues, first) }
            withTimeout(10_000) { gate.entered.await() }
            dispatcher.hold()
            try {
                f.main {
                    val cursor = QueueController::class.java.getDeclaredMethod("checkpointCursor", QueueLibrary::class.java).apply { isAccessible = true }
                    assertEquals(true, cursor.invoke(f.queues, later))
                }
                gate.release.complete(Unit)
                withTimeout(10_000) { while (f.main {
                    val field = QueueController::class.java.getDeclaredField("persistedLibrary").apply { isAccessible = true }
                    field.get(f.queues) != later
                }) delay(10) }
                assertFalse(result.isCompleted)
    
            } finally { dispatcher.release() }
            assertEquals(first, withTimeout(10_000) { result.await() })
            assertEquals(later, f.dao.read())
        }
    }

    @Test fun throwingPostCommitHookCannotReplaceTheOriginalActivationFailure() = runBlocking {
        fixture(QueueSwitch.Browse) { f ->
            val target = f.queues.state.value.library.let { it.update(it.active!!.copy(name = "Committed hook")) }
            val activation = IllegalStateException("Original activation failure")
            val deliveries = AtomicInteger()
            try {
                withContext(Dispatchers.Main) {
                    prepared(f.queues, target, { deliveries.incrementAndGet(); throw IllegalArgumentException("Delivery probe") }, { throw activation })
                }
                fail("The original activation failure must propagate")
            } catch (failure: IllegalStateException) { assertSame(activation, failure) }
            assertEquals(1, deliveries.get())
            assertEquals("Committed hook", f.dao.read().active!!.name)
        }
    }

    private suspend fun append(f: Fixture, complete: (Boolean) -> Unit = {}): Boolean {
        val result = CompletableDeferred<Boolean>()
        f.main { f.queues.appendTo("active", f.rows()) { result.complete(it); complete(it) } }
        return withTimeout(10_000) { result.await() }
    }

    private suspend fun checkpoint(controller: QueueController, target: QueueLibrary): QueueLibrary =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = QueueController::class.java.getDeclaredMethod("persistCheckpoint", QueueLibrary::class.java,
                java.lang.Boolean.TYPE, Continuation::class.java).apply { isAccessible = true }
            try { method.invoke(controller, target, false, continuation) }
            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
        }

    private suspend fun prepared(controller: QueueController, target: QueueLibrary,
        delivered: (QueueLibrary) -> Unit, start: suspend () -> Unit): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = QueueController::class.java.getDeclaredMethod("saveStartingPlayback", QueueLibrary::class.java,
                Function1::class.java, Function1::class.java, Continuation::class.java).apply { isAccessible = true }
            try { method.invoke(controller, target, delivered, start, continuation) }
            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
        }

    private class HoldingMainDispatcher : CoroutineDispatcher() {
        private var holding = false
        private val pending = mutableListOf<Pair<CoroutineContext, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(pending) { if (holding) { pending += context to block; return } }
            Dispatchers.Main.dispatch(context, block)
        }
        fun hold() { synchronized(pending) { holding = true } }
        fun release() {
            val ready = synchronized(pending) { holding = false; pending.toList().also { pending.clear() } }
            ready.forEach { (context, block) -> Dispatchers.Main.dispatch(context, block) }
        }
    }

    private enum class Seed { Active, Empty, InactiveAtLimit }

    private data class Snapshot(val state: QueueUiState, val pending: PlaybackIntents.Choice?, val revision: Long,
        val nativeCalls: List<String>, val mediaId: String?, val playWhenReady: Boolean)
    private class Fixture(val queues: QueueController, val scope: CoroutineScope, val dao: ToastProducerDao,
        val player: RecordingPlayer, val toasts: ToastQueue, val track: Track, val factory: GatedMediaFactory) {
        fun rows() = listOf(SongRow("first", track), SongRow("second", track))
        suspend fun barrier() {
            val result = CompletableDeferred<Boolean>()
            main { queues.browse(queues.state.value.library.queues.firstOrNull()?.id.orEmpty()) { result.complete(it) } }
            withTimeout(10_000) { result.await() }
        }
        suspend fun <T> main(action: () -> T): T = withContext(Dispatchers.Main) { action() }
        fun snapshot(): Snapshot {
            val field = QueueController::class.java.getDeclaredField("intents").apply { isAccessible = true }
            val intents = field.get(queues) as PlaybackIntents
            return Snapshot(queues.state.value, intents.pending(), intents.currentRevision(), player.calls.toList(),
                player.currentMediaItem?.mediaId, player.playWhenReady)
        }
    }

    private suspend fun fixture(policy: QueueSwitch, failRestore: Boolean = false, seed: Seed = Seed.Active,
        toasts: ToastQueue = ToastQueue(), action: suspend (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "toast-controller-${UUID.randomUUID()}"
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int) = context.getSharedPreferences(name, mode)
        })
        preferences.write(QueueSettings(switching = policy, maximum = if (seed == Seed.InactiveAtLimit) 1 else null))
        val file = File(context.cacheDir, "$name.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val track = Track(file.toURI().toString(), "Song", fileName = file.name, relativePath = file.name)
        val active = ListeningQueue(id = "active", name = "Active", entries = listOf(QueueEntry("playing", track)),
            lastActivatedAt = 1234)
        val other = ListeningQueue(id = "other", name = "Other", entries = listOf(QueueEntry("other-song", track)))
        val db = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        db.queues().save(QueueLibrary(), when (seed) {
            Seed.Active -> QueueLibrary(listOf(active, other), active.id)
            Seed.Empty -> QueueLibrary()
            Seed.InactiveAtLimit -> QueueLibrary(listOf(other))
        })
        val dao = ToastProducerDao(db.queues(), failRestore)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val factory = GatedMediaFactory()
        val player = withContext(Dispatchers.Main) { RecordingPlayer(ExoPlayer.Builder(context).build().apply { volume = 0f }) }
        try {
            val queues = withContext(Dispatchers.Main) {
                QueueController(dao, preferences, (context.applicationContext as LocalicaApplication).library, scope, mediaItemFactory = factory::item, toasts = toasts)
            }
            withTimeout(10_000) { while (queues.state.value.loading) delay(10) }
            val fixture = Fixture(queues, scope, dao, player, toasts, track, factory)
            if (!failRestore) {
                fixture.main { queues.attach(player) }
                fixture.barrier()
                if (seed == Seed.Active) {
                    withTimeout(10_000) {
                        while (!fixture.main { player.playbackState == Player.STATE_READY && !player.isLoading }) delay(10)
                    }
                    fixture.barrier()
                }
            }
            action(fixture)
        } finally {
            factory.release()
            dao.release()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            withContext(Dispatchers.Main) { player.release() }
            db.close(); context.deleteSharedPreferences(name); file.delete()
        }
    }

    private class GatedMediaFactory {
        class Gate {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val claimed = AtomicBoolean()
        }
        @Volatile private var gate: Gate? = null
        fun holdNext() = Gate().also { check(gate == null); gate = it }
        fun release() { gate?.release?.countDown() }
        fun item(id: String, track: Track, origin: String?): MediaItem {
            gate?.let { blocked -> if (blocked.claimed.compareAndSet(false, true)) {
                blocked.entered.complete(Unit)
                check(blocked.release.await(10, TimeUnit.SECONDS)) { "Held optional synchronization was not released" }
            } }
            return queueMediaItem(id, track, origin)
        }
    }

    private class RecordingPlayer(engine: ExoPlayer) : ForwardingPlayer(engine) {
        val calls = CopyOnWriteArrayList<String>()
        var failNextAdd = false
        var failNextSet = false
        val injectedFailures = AtomicInteger()
        override fun setMediaItems(items: List<MediaItem>, index: Int, position: Long) { calls += "setMediaItems"; if (failNextSet) { failNextSet = false; injectedFailures.incrementAndGet(); throw IllegalStateException("Activation probe") }; super.setMediaItems(items, index, position) }
        override fun prepare() { calls += "prepare"; super.prepare() }
        override fun setPlayWhenReady(play: Boolean) { calls += "playWhenReady:$play"; super.setPlayWhenReady(play) }
        override fun play() { calls += "play"; super.play() }
        override fun pause() { calls += "pause"; super.pause() }
        override fun stop() { calls += "stop"; super.stop() }
        override fun clearMediaItems() { calls += "clear"; super.clearMediaItems() }
        override fun addMediaItem(index: Int, item: MediaItem) { calls += "add"; if (failNextAdd) { failNextAdd = false; injectedFailures.incrementAndGet(); throw IllegalStateException("Synchronization probe") }; super.addMediaItem(index, item) }
        override fun removeMediaItem(index: Int) { calls += "remove"; super.removeMediaItem(index) }
        override fun moveMediaItem(from: Int, to: Int) { calls += "move"; super.moveMediaItem(from, to) }
        override fun replaceMediaItem(index: Int, item: MediaItem) { calls += "replace"; super.replaceMediaItem(index, item) }
        override fun seekTo(index: Int, position: Long) { calls += "seek"; super.seekTo(index, position) }
    }
}

private class ToastProducerDao(private val delegate: QueueDao, private val failRestore: Boolean) : QueueDao() {
    class Gate(val matches: (QueueLibrary) -> Boolean) { val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>() }
    private var gate: Gate? = null
    var failSave: ((QueueLibrary) -> Boolean)? = null
    fun gateNextSave(matches: (QueueLibrary) -> Boolean = { true }) = Gate(matches).also { check(gate == null); gate = it }
    fun release() { gate?.release?.complete(Unit) }
    override suspend fun read(): QueueLibrary { check(!failRestore) { "Injected restore failure" }; return delegate.read() }
    override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
        if (failSave?.invoke(after) == true) { failSave = null; throw IOException("Persistence probe") }
        gate?.takeIf { it.matches(after) }?.let { blocked -> blocked.entered.complete(Unit); blocked.release.await(); gate = null }
        delegate.save(before, after)
    }
    override suspend fun readQueues(): List<StoredQueue> = error("Use read")
    override suspend fun readActive(): ActiveQueueRecord? = error("Use read")
    override suspend fun upsertQueue(queue: QueueRecord): Unit = error("Use save")
    override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int = error("Use save")
    override suspend fun upsertActive(active: ActiveQueueRecord): Unit = error("Use save")
    override suspend fun upsertJourney(journey: JourneyRecord): Unit = error("Use save")
    override suspend fun clearJourney(id: String): Unit = error("Use save")
    override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("Use save")
    override suspend fun clearEntries(id: String): Unit = error("Use save")
    override suspend fun deleteQueue(id: String): Unit = error("Use save")
    override suspend fun deleteVisits(queueId: String, tokens: List<String>): Unit = error("Use save")
    override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>): Unit = error("Use save")
}
