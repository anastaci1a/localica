package io.github.anastaci1a.localica

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.playback.NativeReaders
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Real controller/actor and Media3 timeline; only disk scheduling and the clock are controlled. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueFileEditTest {
    @Test fun acquisitionTimeoutBehindAnotherCommandCannotLaterClearPlayback() = fixture {
        val gate = dao.gateNextSave()
        queues.rename("active", "Slow rename")
        gate.entered.await()
        val scheduler = TestCoroutineScheduler()
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        var edited = false
        try {
            val request = caller.async { queues.withFileEdit("one") { edited = true } }
            scheduler.runCurrent()
            scheduler.advanceTimeBy(15000)
            scheduler.runCurrent()
            gate.release.complete(Unit)
            barrier()
            scheduler.runCurrent()
            assertTrue(request.isCancelled)
            assertFalse(edited)
            assertRestored(expectedClears = 0)
        } finally { caller.cancel(); gate.release.complete(Unit) }
    }

    @Test fun cancellationDuringAcquisitionCaptureDoesNotReleaseSources() = fixture {
        withContext(Dispatchers.Main) { player.position = 3210 }
        val gate = dao.gateNextSave()
        var edited = false
        val request = launch { queues.withFileEdit("one") { edited = true } }
        gate.entered.await()
        request.cancel()
        gate.release.complete(Unit)
        request.join()
        barrier()
        assertFalse(edited)
        assertRestored(expectedClears = 0, position = 3210)
    }

    @Test fun cancellationAfterActorAcquiresBeforeCallerDispatchRestoresPlayback() = fixture {
        val scheduler = TestCoroutineScheduler()
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        var edited = false
        try {
            val request = caller.launch { queues.withFileEdit("one") { edited = true } }
            scheduler.runCurrent()
            awaitMain { player.clears == 1 && player.mediaItemCount == 0 }
            assertTrue(runCatching { readers.opening("one") }.isFailure)
            request.cancel()
            scheduler.runCurrent()
            barrier()
            scheduler.runCurrent()
            assertTrue(request.isCompleted)
            assertFalse(edited)
            readers.opening("one").closed()
            assertRestored(expectedClears = 1)
        } finally { caller.cancel() }
    }

    @Test fun cancellationWaitsForWriterCleanupAndDoesNotReleaseANewerOwner() = fixture {
        val writing = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val request = launch {
            queues.withFileEdit("one") {
                withContext(NonCancellable) {
                    writing.complete(Unit)
                    finishWrite.await()
                }
            }
        }
        try {
            writing.await()
            request.cancel()
            // A failed competing scope must not release the first scope's sources.
            val overlap = runCatching { queues.withFileEdit("two") { fail("Overlapping edit ran") } }
            assertTrue(overlap.exceptionOrNull() is IllegalStateException)
            withContext(Dispatchers.Main) {
                assertEquals(0, player.mediaItemCount)
                assertFalse(player.playWhenReady)
            }
            finishWrite.complete(Unit)
            request.join()
            assertRestored(expectedClears = 1)
            queues.withFileEdit("two") { withContext(Dispatchers.Main) { assertEquals(0, player.mediaItemCount) } }
            assertRestored(expectedClears = 2)
        } finally { finishWrite.complete(Unit); request.cancel() }
    }

    @Test fun timedOutReleaseStillRunsBeforeAnotherScopeAcquires() = fixture {
        val scheduler = TestCoroutineScheduler()
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val finish = CompletableDeferred<Unit>()
        val newerAcquired = CompletableDeferred<Unit>()
        val finishNewer = CompletableDeferred<Unit>()
        var gate: Gate? = null
        try {
            val request = caller.async { queues.withFileEdit("one") { finish.await() } }
            scheduler.runCurrent()
            awaitMain { player.mediaItemCount == 0 }
            scheduler.runCurrent()
            gate = dao.gateNextSave()
            queues.rename("active", "Block cleanup")
            gate.entered.await()
            finish.complete(Unit)
            scheduler.runCurrent()
            scheduler.advanceTimeBy(15000)
            scheduler.runCurrent()
            assertTrue(request.isCancelled)
            val newer = launch {
                queues.withFileEdit("two") { newerAcquired.complete(Unit); finishNewer.await() }
            }
            gate.release.complete(Unit)
            newerAcquired.await()
            withContext(Dispatchers.Main) {
                assertEquals(2, player.clears)
                assertEquals(0, player.mediaItemCount)
                assertFalse(player.playWhenReady)
            }
            finishNewer.complete(Unit)
            newer.join()
            assertRestored(expectedClears = 2)
        } finally {
            finish.complete(Unit); finishNewer.complete(Unit); gate?.release?.complete(Unit); caller.cancel()
        }
    }

    @Test fun failedCaptureDoesNotLoseOwnershipAndActionFailureStillRestores() = fixture {
        withContext(Dispatchers.Main) { player.position = 3456 }
        dao.failNextSave = true
        val failure = runCatching { queues.withFileEdit("one") { fail("Failed acquisition ran an edit") } }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertRestored(expectedClears = 0, position = 3456)
        val actionFailure = IOException("Edit failed before touching bytes")
        val failedAction = runCatching { queues.withFileEdit("one") { throw actionFailure } }
        assertSame(actionFailure, failedAction.exceptionOrNull())
        assertRestored(expectedClears = 1, position = 3456)
    }

    @Test fun releaseFailureKeepsPlaybackPausedAndPreservesTheWriterFailure() = fixture {
        val writerFailure = IOException("Writer failed before touching bytes")
        val result = runCatching {
            queues.withFileEdit("one") {
                withContext(Dispatchers.Main) { player.failNextTimeline = true }
                throw writerFailure
            }
        }
        assertSame(writerFailure, result.exceptionOrNull())
        assertEquals("Timeline rebuild failed", writerFailure.suppressed.single().message)
        withContext(Dispatchers.Main) {
            assertEquals(0, player.mediaItemCount)
            assertFalse(player.playWhenReady)
        }
        // Ownership is released even though reconstruction failed, so a new selection can retry.
        queues.selectEntry(original.currentEntryId!!)
        assertRestored(expectedClears = 1, position = 0)
    }

    @Test fun actorShutdownWhileReleaseIsQueuedWakesTheCallerAndLeavesPlaybackPaused() = fixture {
        val scheduler = TestCoroutineScheduler()
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val finish = CompletableDeferred<Unit>()
        var gate: Gate? = null
        try {
            val request = caller.async { queues.withFileEdit("one") { finish.await() } }
            scheduler.runCurrent()
            awaitMain { player.mediaItemCount == 0 }
            scheduler.runCurrent()
            gate = dao.gateNextSave()
            queues.rename("active", "Block release")
            gate.entered.await()
            finish.complete(Unit)
            scheduler.runCurrent()
            actor.cancel()
            // Wait for actual actor termination before dispatching the caller's cancellation.
            actor.coroutineContext[Job]!!.join()
            scheduler.runCurrent()
            assertTrue(request.isCancelled)
            withContext(Dispatchers.Main) {
                assertEquals(0, player.mediaItemCount)
                assertFalse(player.playWhenReady)
            }
        } finally { finish.complete(Unit); gate?.release?.complete(Unit); caller.cancel() }
    }

    @Test fun inactiveEditAndPlaybackReplacementPreserveTheNewPlayer() = fixture {
        queues.withFileEdit("absent") { assertRestored(expectedClears = 0) }
        queues.withFileEdit("one") {
            withContext(Dispatchers.Main) {
                queues.detach(player)
                queues.attach(player)
            }
        }
        assertRestored(expectedClears = 1, playing = false)
    }

    @Test fun stoppedActorCompletesPendingAcquisitionWithoutWaitingForTimeout() = fixture {
        val gate = dao.gateNextSave()
        queues.rename("active", "Blocked")
        gate.entered.await()
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val request = caller.async { queues.withFileEdit("one") { fail("Stopped actor ran an edit") } }
            actor.cancel()
            withTimeout(5000) { request.join() }
            assertTrue(request.isCancelled)
        } finally { caller.cancel(); gate.release.complete(Unit) }
    }

    @Test fun failedQueueRestoreNeverStartsWriterAndAcquisitionRemainsBounded() = fixture(failedRestore = true) {
        val scheduler = TestCoroutineScheduler()
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        var edited = false
        try {
            val request = caller.async { queues.withFileEdit("one") { edited = true } }
            scheduler.runCurrent()
            // The existing unavailable-queue gate skips both acquisition and release commands.
            scheduler.advanceTimeBy(30000)
            scheduler.runCurrent()
            assertTrue(request.isCancelled)
            assertFalse(edited)
            assertNotNull(queues.state.value.problem)
        } finally { caller.cancel() }
    }

    @Test fun readerAdmittedDuringCaptureIsDrainedAndLaterAdmissionIsRejected() = fixture {
        withContext(Dispatchers.Main) { player.position = 3210 }
        val gate = dao.gateNextSave()
        val writing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val request = launch { queues.withFileEdit("one") { writing.complete(Unit); finish.await() } }
        gate.entered.await()
        val reader = readers.opening("one")
        try {
            gate.release.complete(Unit)
            awaitMain { player.mediaItemCount == 0 }
            assertFalse(writing.isCompleted)
            assertTrue(runCatching { readers.opening("one") }.exceptionOrNull() is IOException)
            reader.closed()
            writing.await()
            finish.complete(Unit)
            request.join()
            assertRestored(expectedClears = 1, position = 3210)
        } finally { reader.closed(); gate.release.complete(Unit); finish.complete(Unit); request.cancel() }
    }

    @Test fun nativeCloseTimeoutNeverStartsWriterAndReleasesOnlyItsAdmissionBlock() = fixture {
        val scheduler = TestCoroutineScheduler()
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val reader = readers.opening("one")
        var edited = false
        try {
            val request = caller.async { queues.withFileEdit("one") { edited = true } }
            scheduler.runCurrent()
            awaitMain { player.mediaItemCount == 0 }
            scheduler.runCurrent()
            scheduler.advanceTimeBy(15000)
            scheduler.runCurrent()
            barrier()
            scheduler.runCurrent()
            assertTrue(request.isCancelled)
            assertFalse(edited)
            readers.opening("one").closed()
            reader.closed()
            scheduler.runCurrent()
            assertFalse(edited)
            assertRestored(expectedClears = 1)
        } finally { reader.closed(); caller.cancel() }
    }

    @Test fun cancellationDuringNativeCloseWaitNeverStartsWriter() = fixture {
        val reader = readers.opening("one")
        var edited = false
        val request = launch { queues.withFileEdit("one") { edited = true } }
        try {
            awaitMain { player.mediaItemCount == 0 }
            request.cancelAndJoin()
            assertFalse(edited)
            reader.closed()
            readers.opening("one").closed()
            assertRestored(expectedClears = 1)
        } finally { reader.closed(); request.cancel() }
    }

    @Test fun failedNativeClosePreventsThisAndSubsequentWriters() = fixture {
        val reader = readers.opening("one")
        reader.failedClose(IOException("Provider failed to close"))
        repeat(2) {
            val result = runCatching { queues.withFileEdit("one") { fail("Unknown reader lifetime admitted a writer") } }
            assertTrue(result.exceptionOrNull() is IOException)
            assertRestored(expectedClears = it + 1)
        }
    }

    @Test fun inactiveTargetLaterAttachedIsHeldButNewerUnrelatedPauseIsPreserved() = fixture {
        val absent = original.entries.first().track.copy(id = "absent")
        val unrelated = original.entries.first().track.copy(id = "unrelated")
        queues.withFileEdit("absent") {
            assertRestored(expectedClears = 0)
            queues.act(listOf(absent), SongTap.NewQueue)
            awaitMain { queues.state.value.library.active?.entries?.singleOrNull()?.track?.id == "absent" && player.mediaItemCount == 0 }
            withContext(Dispatchers.Main) { queues.detach(player); queues.attach(player) }
            queues.act(listOf(unrelated), SongTap.NewQueue)
            awaitMain { player.currentMediaItem?.mediaMetadata?.extras?.getString("trackId") == "unrelated" && player.isPlaying }
            withContext(Dispatchers.Main) { player.pause() }
        }
        withContext(Dispatchers.Main) {
            assertEquals("unrelated", player.currentMediaItem?.mediaMetadata?.extras?.getString("trackId"))
            assertFalse(player.playWhenReady)
            assertFalse(player.isPlaying)
            assertEquals(1, player.clears)
        }
    }

    private fun fixture(failedRestore: Boolean = false, test: suspend Fixture.() -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = java.util.UUID.randomUUID().toString()
        val file = File(context.cacheDir, "edit-lease-$id.mp3")
        instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        val prefsName = "edit-lease-$id"
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefsName, mode)
        })
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val tracks = listOf("one", "two", "one").map { Track(Uri.fromFile(file).toString(), it,
            fileName = file.name, relativePath = file.name, id = it) }
        val queue = ListeningQueue(id = "active", name = "Original", entries = tracks.map { QueueEntry(track = it) }, positionMs = 2000)
        val dao = GatedDao(QueueLibrary(listOf(queue), queue.id)).apply { failRead = failedRestore }
        val player = withContext(Dispatchers.Main) { ClockedPlayer(ExoPlayer.Builder(context).build().apply { volume = 0f }) }
        val readers = NativeReaders()
        val queues = QueueController(dao, preferences, (context.applicationContext as LocalicaApplication).library, actor, readers)
        val fixture = Fixture(this, actor, queues, dao, player, queue, readers)
        try {
            if (failedRestore) fixture.awaitMain { !queues.state.value.loading } else {
                queues.attach(player)
                fixture.awaitMain { player.mediaItemCount == 3 }
                fixture.barrier()
                withContext(Dispatchers.Main) { player.playWhenReady = true }
            }
            withTimeout(10000) { fixture.test() }
        } finally {
            actor.cancel()
            withContext(Dispatchers.Main) { player.release() }
            context.deleteSharedPreferences(prefsName)
            file.delete()
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        val actor: CoroutineScope,
        val queues: QueueController,
        val dao: GatedDao,
        val player: ClockedPlayer,
        val original: ListeningQueue,
        val readers: NativeReaders,
    ) : CoroutineScope by scope {
        suspend fun barrier() { queues.withFileEdit("absent") {} }
        suspend fun awaitMain(condition: () -> Boolean) = withTimeout(5000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(10)
        }
        suspend fun assertRestored(expectedClears: Int, position: Long = 2000, playing: Boolean = true) {
            awaitMain { player.mediaItemCount == queues.state.value.library.active?.journey?.window()?.size &&
                player.isPlaying == playing }
            withContext(Dispatchers.Main) {
                assertEquals(expectedClears, player.clears)
                assertEquals(playing, player.playWhenReady)
                assertEquals(position, player.currentPosition)
                assertEquals(original.currentEntryId, player.currentMediaItem?.mediaId)
            }
            val current = queues.state.value.library.active!!
            assertEquals(original.entries, current.entries)
            assertEquals(original.currentEntryId, current.currentEntryId)
            assertEquals(position, current.positionMs)
            assertEquals(original.entries.map { it.id }.toSet(), current.journey!!.plans.values.flatten().toSet())
        }
    }

    /** Suppress autonomous callback traffic so a gate unambiguously belongs to the requested capture. */
    private class ClockedPlayer(player: Player) : ForwardingPlayer(player) {
        var position = 2000L
        var clears = 0
        var failNextTimeline = false
        override fun getCurrentPosition() = position
        override fun addListener(listener: Player.Listener) = Unit
        override fun removeListener(listener: Player.Listener) = Unit
        override fun clearMediaItems() { clears++; super.clearMediaItems() }
        override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            if (failNextTimeline) { failNextTimeline = false; throw IOException("Timeline rebuild failed") }
            position = startPositionMs
            super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            position = positionMs
            super.seekTo(mediaItemIndex, positionMs)
        }
    }

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }
    private class GatedDao(private var saved: QueueLibrary) : QueueDao() {
        private val nextGate = AtomicReference<Gate?>()
        @Volatile var failNextSave = false
        var failRead = false
        fun gateNextSave() = Gate().also { check(nextGate.compareAndSet(null, it)) }
        override suspend fun read(): QueueLibrary {
            if (failRead) throw IOException("Restore failed")
            return saved
        }
        override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
            nextGate.getAndSet(null)?.let { it.entered.complete(Unit); it.release.await() }
            if (failNextSave) { failNextSave = false; throw IOException("Save failed") }
            saved = after
        }
        override suspend fun readQueues(): List<StoredQueue> = error("read() is overridden")
        override suspend fun readActive(): ActiveQueueRecord? = error("read() is overridden")
        override suspend fun upsertQueue(queue: QueueRecord): Unit = error("save() is overridden")
        override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int = error("save() is overridden")
        override suspend fun upsertActive(active: ActiveQueueRecord): Unit = error("save() is overridden")
        override suspend fun upsertJourney(journey: JourneyRecord): Unit = error("save() is overridden")
        override suspend fun clearJourney(id: String): Unit = error("save() is overridden")
        override suspend fun upsertVisits(visits: List<VisitRecord>): Unit = error("save() is overridden")
        override suspend fun updateVisits(visits: List<VisitRecord>): Int = error("save() is overridden")
        override suspend fun upsertCycleMembers(members: List<CycleMemberRecord>): Unit = error("save() is overridden")
        override suspend fun updateCycleMembers(members: List<CycleMemberRecord>): Int = error("save() is overridden")
        override suspend fun deleteVisits(queueId: String, tokens: List<String>): Unit = error("save() is overridden")
        override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>): Unit = error("save() is overridden")
        override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("save() is overridden")
        override suspend fun clearEntries(id: String): Unit = error("save() is overridden")
        override suspend fun deleteQueue(id: String): Unit = error("save() is overridden")
    }
}
