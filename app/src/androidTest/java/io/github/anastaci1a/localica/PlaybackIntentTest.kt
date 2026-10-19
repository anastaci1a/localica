package io.github.anastaci1a.localica

import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.playback.QueueTransportPlayer
import io.github.anastaci1a.localica.playback.NativeReaders
import io.github.anastaci1a.localica.playback.RecoveryAdmission
import io.github.anastaci1a.localica.playback.track
import io.github.anastaci1a.localica.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Real actor/native playback; only restore/URI scheduling and continuity predicates are controlled. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackIntentTest {
    @Test fun explicitPlayBeforeAttachmentStartsButPassiveRestoreDoesNot() {
        fixture(attach = false) {
            queues.attach(player)
            barrier()
            assertPlayback(false)
            assertEquals(0, player.trueRequests)
        }
        fixture(attach = false) {
            controls.play()
            queues.attach(player)
            barrier()
            assertPlayback(true)
            assertEquals(1, player.trueRequests)
        }
    }

    @Test fun playPauseAndPausePlayBeforeAttachKeepLatestIntent() {
        for (last in listOf(false, true)) fixture(attach = false) {
            controls.playWhenReady = !last
            controls.playWhenReady = last
            queues.attach(player)
            barrier()
            assertPlayback(last)
            assertEquals(if (last) 1 else 0, player.trueRequests)
        }
    }

    @Test fun newQueuePlayNowAndPinnedSongTapStartTheirRequestedOccurrenceAfterAttach() {
        for (action in 0..2) fixture(attach = false) {
            val selected = track.copy(id = "selected", title = "Selected")
            when (action) {
                0 -> queues.act(listOf(selected), SongTap.NewQueue)
                1 -> queues.act(listOf(selected), SongTap.PlayNow)
                else -> queues.tap(listOf(track, selected), 1, sourceName = "Pinned songs")
            }
            barrier()
            val active = queues.state.value.library.active!!
            assertEquals("selected", active.entries.first { it.id == active.currentEntryId }.track.id)
            queues.attach(player)
            barrier()
            assertPlayback(true)
            assertEquals(1, player.trueRequests)
            withContext(Dispatchers.Main) { assertEquals(active.currentEntryId, player.currentMediaItem?.mediaId) }
        }
    }

    @Test fun newerPauseDuringRestoreSuppressesAnOlderQueuedNewQueueRequest() = fixture(attach = false, blockRestore = true) {
        dao.restore!!.entered.await()
        val selected = track.copy(id = "new", title = "New")
        queues.act(listOf(selected), SongTap.NewQueue)
        controls.pause()
        queues.attach(player)
        dao.restore.release.complete(Unit)
        barrier()
        assertEquals("new", queues.state.value.library.active!!.entries.single().track.id)
        assertPlayback(false)
        assertEquals(0, player.trueRequests)
        assertFalse(player.preparedWhilePlaying)
    }

    @Test fun newerPauseDuringUriResolutionPreventsAnyStaleNativePlay() = fixture(attach = false) {
        val gate = store.gateNextSnapshot()
        controls.play()
        queues.attach(player)
        gate.entered.await()
        controls.pause()
        gate.release.complete(Unit)
        barrier()
        assertPlayback(false)
        assertEquals(0, player.trueRequests)
        assertFalse(player.preparedWhilePlaying)
    }

    @Test fun clearDuringResolutionCancelsPendingPlayAndAllowsLaterExplicitPlay() = fixture(attach = false) {
        val gate = store.gateNextSnapshot()
        controls.play()
        queues.attach(player)
        gate.entered.await()
        queues.clearPlayback()
        gate.release.complete(Unit)
        barrier()
        assertNull(queues.state.value.library.activeId)
        withContext(Dispatchers.Main) {
            assertEquals(0, player.engine.mediaItemCount)
            assertFalse(player.engine.isPlaying)
            assertEquals(0, player.trueRequests)
            assertFalse(player.preparedWhilePlaying)
        }
        queues.settings { it.copy(switching = QueueSwitch.Paused) }
        queues.switchTo(second.id)
        barrier()
        assertPlayback(false)
        controls.play(); barrier(); assertPlayback(true)
        assertEquals(1, player.trueRequests)
    }

    @Test fun replacementAttachmentDuringResolutionKeepsNewerPause() = fixture(attach = false) {
        val gate = store.gateNextSnapshot()
        controls.play()
        queues.attach(player)
        gate.entered.await()
        controls.pause()
        val replacement = newPlayer()
        withContext(Dispatchers.Main) { queues.detach(player); queues.attach(replacement) }
        gate.release.complete(Unit)
        barrier()
        withContext(Dispatchers.Main) {
            assertFalse(player.engine.playWhenReady)
            assertFalse(replacement.engine.playWhenReady)
            assertEquals(0, player.trueRequests)
            assertEquals(0, replacement.trueRequests)
            assertFalse(replacement.preparedWhilePlaying)
        }
    }

    @Test fun fulfilledPlayDoesNotAutoplayPassiveServiceReplacement() = fixture {
        controls.play(); barrier(); assertPlayback(true)
        val replacement = newPlayer()
        withContext(Dispatchers.Main) { queues.detach(player); queues.attach(replacement) }
        barrier()
        withContext(Dispatchers.Main) {
            assertFalse(replacement.engine.playWhenReady)
            assertEquals(0, replacement.trueRequests)
        }
    }

    @Test fun newerExplicitSelectionAfterPauseStillStartsSelectedOccurrence() = fixture {
        controls.pause()
        queues.selectEntry(second.entries.single().id)
        barrier()
        assertPlayback(true)
        withContext(Dispatchers.Main) { assertEquals(second.entries.single().id, player.currentMediaItem?.mediaId) }
    }

    @Test fun systemPauseSetPlayWhenReadyAndUiToggleAllWinDuringHeldEdit() {
        for (pause in 0..2) fixture {
            controls.play(); barrier(); assertPlayback(true)
            withHeldEdit(track.id) {
                when (pause) { 0 -> controls.pause(); 1 -> controls.playWhenReady = false; else -> queues.togglePlayback() }
            }
            assertPlayback(false)
            controls.play(); barrier(); assertPlayback(true)
        }
    }

    @Test fun newerAcceptedPauseWinsWhenEditResumeIsAlreadyQueuedAheadOfItsActorCommand() = fixture {
        controls.play(); barrier(); assertPlayback(true)
        val scheduler = TestCoroutineScheduler()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val edit = async(StandardTestDispatcher(scheduler)) {
            queues.withFileEdit(track.id) { entered.complete(Unit); finish.await() }
        }
        var gate: Gate? = null
        try {
            pumpUntil(scheduler) { entered.isCompleted }
            gate = dao.gateNextSave()
            queues.rename(first.id, "Suspended save")
            gate.entered.await()
            finish.complete(Unit)
            // Run the real scope's finally to its queued release acknowledgment, while the
            // Main actor remains gated. The later Pause command must therefore queue after it.
            scheduler.runCurrent()
            assertFalse(edit.isCompleted)
            controls.pause()
            gate.release.complete(Unit)
            pumpUntil(scheduler) { edit.isCompleted }
            edit.await()
            barrier()
            assertPlayback(false)
            assertEquals("Queued edit completion must never issue a stale true", 1, player.trueRequests)
            assertFalse(player.preparedWhilePlaying)
        } finally { withContext(NonCancellable) {
            finish.complete(Unit); gate?.release?.complete(Unit); edit.cancel()
            pumpUntil(scheduler) { edit.isCompleted }
        } }
    }

    @Test fun unrelatedQueueTransportRemainsLiveDuringInactiveAndPreviouslyAffectedEdits() {
        for (affected in listOf(false, true)) fixture {
            controls.play(); barrier(); assertPlayback(true)
            withHeldEdit(if (affected) track.id else "absent") {
                queues.selectEntry(second.entries.single().id)
                barrier(); assertPlayback(true)
                controls.pause(); barrier(); assertPlayback(false)
                queues.togglePlayback(); barrier(); assertPlayback(true)
                controls.pause(); barrier(); assertPlayback(false)
            }
            assertPlayback(false)
            assertEquals(second.id, queues.state.value.library.activeId)
            withContext(Dispatchers.Main) { assertEquals(second.entries.single().id, player.currentMediaItem?.mediaId) }
            controls.play(); barrier(); assertPlayback(true)
        }
    }

    @Test fun pendingPlayAndNewerPauseSurviveAttachmentWhileEditedSourceIsHeld() {
        for (play in listOf(false, true)) fixture(attach = false) {
            withHeldEdit(track.id) {
                controls.play()
                if (!play) controls.pause()
                queues.attach(player)
                barrier()
                withContext(Dispatchers.Main) { assertEquals(0, player.mediaItemCount) }
            }
            assertPlayback(play)
            assertEquals(if (play) 1 else 0, player.trueRequests)
        }
    }

    @Test fun queueSwitchKeepsRequestedStateThroughBufferingAndSuppression() = continuity(delete = false)
    @Test fun activeQueueDeletionKeepsRequestedStateThroughBufferingAndSuppression() = continuity(delete = true)

    private fun continuity(delete: Boolean) {
        for (state in listOf("ready", "buffering", "suppressed", "paused", "error", "ended")) fixture {
            controls.play(); barrier(); assertPlayback(true)
            queues.settings { it.copy(switching = QueueSwitch.KeepPlaybackState) }
            barrier()
            withContext(Dispatchers.Main) {
                when (state) {
                    "buffering" -> player.stateOverride = Player.STATE_BUFFERING
                    "suppressed" -> player.suppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                    "paused" -> player.engine.pause()
                    "error" -> player.error = PlaybackException("Hard failure", IOException(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                    "ended" -> player.stateOverride = Player.STATE_ENDED
                }
                if (state != "ready") assertFalse(player.isPlaying)
            }
            if (delete) queues.removeQueues(setOf(first.id)) else queues.switchTo(second.id)
            barrier()
            withContext(Dispatchers.Main) { player.stateOverride = null; player.suppression = null; player.error = null }
            assertPlayback(state in setOf("ready", "buffering", "suppressed"))
            assertEquals(second.id, queues.state.value.library.activeId)
        }
    }

    @Test fun explicitRetryRestartsActualNativeHardErrorAfterFileBecomesAvailable() = fixture {
        val missing = File(file.parentFile, "missing.mp3")
        try {
            val broken = track.copy(uri = Uri.fromFile(missing).toString(), id = "missing")
            queues.act(listOf(broken), SongTap.NewQueue)
            awaitMain { player.engine.playerError != null }
            file.copyTo(missing)
            controls.play()
            barrier()
            assertPlayback(true)
            withContext(Dispatchers.Main) { assertNull(player.engine.playerError) }
        } finally { missing.delete() }
    }

    @Test fun fulfilledPlayDoesNotOverrideAutomaticNextPaused() = fixture(rawPlayer = true) {
        queues.act(listOf(track.copy(id = "next")), SongTap.Append)
        queues.settings { it.copy(advancedRepeat = AdvancedRepeat(songEnd = SongEnd.NextPaused)) }
        barrier()
        controls.play(); barrier(); assertPlayback(true)
        val next = queues.state.value.library.active!!.entries.last().id
        withContext(Dispatchers.Main) { queues.seek(player.engine.duration - 100) }
        awaitMain { queues.state.value.library.active!!.currentEntryId == next }
        assertPlayback(false)
    }

    @Test fun newerPauseDuringAutomaticNextQueueResolutionSuppressesResume() = fixture() {
        queues.settings { it.copy(advancedRepeat = AdvancedRepeat(queueEnd = QueueEnd.NextQueue)) }
        barrier()
        controls.play(); barrier(); assertPlayback(true)
        val gate = store.gateNextSnapshot()
        withContext(Dispatchers.Main) { queues.seek(player.engine.duration - 100) }
        gate.entered.await()
        controls.pause()
        gate.release.complete(Unit)
        barrier()
        assertEquals(second.id, queues.state.value.library.activeId)
        assertPlayback(false)
        assertEquals("Only the original explicit Play may request true", 1, player.trueRequests)
        assertFalse(player.preparedWhilePlaying)
    }

    @Test fun blockedPlayDoesNotAutoplayAfterLaterManualRecovery() = fixture {
        readers.publishRecovery(RecoveryAdmission.Pending(track.id))
        awaitMain { player.engine.mediaItemCount == 0 && queues.state.value.problem != null }
        controls.play(); barrier()
        val before = player.trueRequests
        queues.withFileRecovery(track.id) { readers.publishRecovery(RecoveryAdmission.Clear) }
        assertPlayback(false)
        assertEquals(before, player.trueRequests)
    }

    @Test fun onlyNewRecoverySessionIntentMayResumeAndNewerPauseWins() {
        for (play in listOf(false, true)) fixture {
            readers.publishRecovery(RecoveryAdmission.Pending(track.id))
            awaitMain { player.engine.mediaItemCount == 0 && queues.state.value.problem != null }
            controls.play(); barrier() // An old failed Play is retired for this source.
            val before = player.trueRequests
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val recovery = async { queues.withFileRecovery(track.id) {
                readers.publishRecovery(RecoveryAdmission.Clear)
                entered.complete(Unit); finish.await()
            } }
            try {
                entered.await()
                controls.play()
                if (!play) controls.pause()
                barrier()
                assertEquals(before, player.trueRequests)
                finish.complete(Unit); recovery.await()
                assertPlayback(play)
                assertEquals(before + if (play) 1 else 0, player.trueRequests)
            } finally { withContext(NonCancellable) { finish.complete(Unit); recovery.cancelAndJoin() } }
        }
    }

    @Test fun recoveryAcceptedBeforeExclusiveFileWaitPreservesNewerPlayAndPause() {
        for (pause in listOf(false, true)) fixture {
            readers.publishRecovery(RecoveryAdmission.Pending(track.id))
            awaitMain { player.engine.mediaItemCount == 0 && queues.state.value.problem != null }
            controls.play(); barrier()
            val before = player.trueRequests
            val locked = CompletableDeferred<Unit>()
            val unlock = CompletableDeferred<Unit>()
            val holder = async { library.withExclusiveFiles { locked.complete(Unit); unlock.await() } }
            locked.await()
            // The production Recover entry point creates this marker before its same library wait.
            val accepted = queues.recoveryRequested()
            val waiting = CompletableDeferred<Unit>()
            val recovery = async {
                waiting.complete(Unit)
                try { library.withExclusiveFiles {
                    queues.withFileRecovery(track.id, accepted) { readers.publishRecovery(RecoveryAdmission.Clear) }
                } } finally { queues.finishRecovery(accepted) }
            }
            try {
                waiting.await()
                controls.play()
                if (pause) controls.pause()
                barrier()
                assertEquals(before, player.trueRequests)
                assertFalse(recovery.isCompleted)
                unlock.complete(Unit); holder.await(); recovery.await()
                assertPlayback(!pause)
                assertEquals(before + if (pause) 0 else 1, player.trueRequests)
            } finally { withContext(NonCancellable) {
                unlock.complete(Unit)
                withTimeout(10000) { holder.cancelAndJoin(); recovery.cancelAndJoin() }
            } }
        }
    }

    @Test fun failedOrCancelledPreAcquisitionRecoveryCannotCarryPlayIntoALaterAttempt() {
        for (cancel in listOf(false, true)) fixture {
            readers.publishRecovery(RecoveryAdmission.Pending(track.id))
            awaitMain { player.engine.mediaItemCount == 0 && queues.state.value.problem != null }
            val before = player.trueRequests
            val locked = CompletableDeferred<Unit>()
            val unlock = CompletableDeferred<Unit>()
            val holder = async { library.withExclusiveFiles { locked.complete(Unit); unlock.await() } }
            locked.await()
            val accepted = queues.recoveryRequested()
            val waiting = CompletableDeferred<Unit>()
            val recovery = async { runCatching {
                waiting.complete(Unit)
                try { library.withExclusiveFiles { throw IOException("Controlled pending-journal inspection failure") } }
                finally { queues.finishRecovery(accepted) }
            } }
            try {
                waiting.await()
                controls.play(); barrier()
                assertEquals(before, player.trueRequests)
                if (cancel) recovery.cancelAndJoin()
                unlock.complete(Unit); holder.await(); recovery.join()
                assertTrue(recovery.isCompleted)
                queues.withFileRecovery(track.id) { readers.publishRecovery(RecoveryAdmission.Clear) }
                assertPlayback(false)
                assertEquals(before, player.trueRequests)
            } finally { withContext(NonCancellable) {
                unlock.complete(Unit)
                withTimeout(10000) { holder.cancelAndJoin(); recovery.cancelAndJoin() }
            } }
        }
    }

    @Test fun duplicateRecoveryMarkerCannotFinishTheOriginalWaitingSession() = fixture {
        readers.publishRecovery(RecoveryAdmission.Pending(track.id))
        awaitMain { player.engine.mediaItemCount == 0 && queues.state.value.problem != null }
        val accepted = queues.recoveryRequested()
        controls.play(); barrier() // Newer Play is already bound, but persistent quarantine holds it.
        val before = player.trueRequests
        val gate = dao.gateNextSave()
        queues.rename(first.id, "Waiting recovery acquisition")
        gate.entered.await()
        val firstRecovery = async(start = CoroutineStart.UNDISPATCHED) {
            queues.withFileRecovery(track.id, accepted) { readers.publishRecovery(RecoveryAdmission.Clear) }
        }
        try {
            var duplicateEntered = false
            val duplicate = runCatching { queues.withFileRecovery(track.id, accepted) { duplicateEntered = true } }
            assertTrue(duplicate.isFailure)
            assertFalse(duplicateEntered)
            assertFalse(firstRecovery.isCompleted)
            gate.release.complete(Unit); firstRecovery.await()
            assertPlayback(true)
            assertEquals(before + 1, player.trueRequests)
        } finally { withContext(NonCancellable) {
            gate.release.complete(Unit)
            withTimeout(10000) { firstRecovery.cancelAndJoin() }
            queues.finishRecovery(accepted)
        } }
    }

    @Test fun pausedSwitchThenNewerPlayBindsToNewSourceAndInactiveRecoveryCannotConsumeIt() = fixture {
        queues.settings(QueueSettings(switching = QueueSwitch.Paused)); barrier()
        val gate = dao.gateNextSave()
        queues.rename(first.id, "Suspended")
        gate.entered.await()
        val before = player.trueRequestSources.size
        queues.switchTo(second.id)
        withContext(Dispatchers.Main) { readers.publishRecovery(RecoveryAdmission.Pending(track.id)); yield() }
        controls.play()
        gate.release.complete(Unit)
        barrier(); assertPlayback(true)
        val secondSource = second.entries.single().track.id
        assertEquals(second.id, queues.state.value.library.activeId)
        assertTrue(player.trueRequestSources.drop(before).all { it == secondSource })
        val requests = player.trueRequests
        queues.withFileRecovery(track.id) { readers.publishRecovery(RecoveryAdmission.Clear) }
        barrier(); assertPlayback(true)
        assertEquals(second.id, queues.state.value.library.activeId)
        assertEquals(requests, player.trueRequests)
    }

    @Test fun queuedSwitchToBlockedSourceRetiresItsPlayWithoutTransientResumeOrLaterAutoplay() = fixture {
        queues.selectEntry(second.entries.single().id); barrier(); assertPlayback(true)
        queues.settings(QueueSettings(switching = QueueSwitch.Paused)); barrier()
        val gate = dao.gateNextSave()
        queues.rename(second.id, "Suspended")
        gate.entered.await()
        val before = player.trueRequests
        queues.switchTo(first.id)
        withContext(Dispatchers.Main) { readers.publishRecovery(RecoveryAdmission.Pending(track.id)); yield() }
        controls.play()
        gate.release.complete(Unit)
        barrier()
        awaitMain { queues.state.value.library.activeId == first.id && player.engine.mediaItemCount == 0 }
        assertEquals(before, player.trueRequests)
        queues.withFileRecovery(track.id) { readers.publishRecovery(RecoveryAdmission.Clear) }
        assertPlayback(false)
        assertEquals(before, player.trueRequests)
    }

    @Test fun coalescedSafeOutcomeRestoresWindowRepeatAndLatestIntentWithoutPendingNotification() {
        for (pause in listOf(false, true)) fixture {
            queues.settings(QueueSettings(repeat = QueueRepeat.All)); barrier()
            controls.play(); barrier(); assertPlayback(true)
            val before = queues.state.value.library.active!!
            val nativeCount = withContext(Dispatchers.Main) { player.engine.mediaItemCount }
            val scheduler = TestCoroutineScheduler()
            val dispatcher = StandardTestDispatcher(scheduler)
            val acquired = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val request = async(dispatcher) { queues.withFileEdit(track.id) {
                acquired.complete(Unit); finish.await()
                // Both publications happen while the actor is suspended. Its notification commands
                // can only observe Clear, so the scoped completion must restore playback itself.
                readers.publishRecovery(RecoveryAdmission.Pending(track.id))
                readers.publishRecovery(RecoveryAdmission.Clear)
            } }
            var gate: Gate? = null
            try {
                pumpUntil(scheduler) { acquired.isCompleted }
                gate = dao.gateNextSave()
                queues.rename(first.id, "Coalesced outcome")
                gate.entered.await()
                val requests = player.trueRequests
                finish.complete(Unit); scheduler.runCurrent()
                assertFalse(request.isCompleted)
                if (pause) controls.pause()
                gate.release.complete(Unit)
                pumpUntil(scheduler) { request.isCompleted }; request.await()
                barrier(); assertPlayback(!pause)
                withContext(Dispatchers.Main) {
                    assertEquals(nativeCount, player.engine.mediaItemCount)
                    assertEquals(Player.REPEAT_MODE_ALL, player.engine.repeatMode)
                }
                assertEquals(before.journey!!.current, queues.state.value.library.active!!.journey!!.current)
                assertEquals(QueueRepeat.All, queues.state.value.settings.repeat)
                if (pause) assertEquals(requests, player.trueRequests)
            } finally { withContext(NonCancellable) {
                gate?.release?.complete(Unit); finish.complete(Unit); request.cancel()
                pumpUntil(scheduler) { request.isCompleted }
            } }
        }
    }

    @Test fun quarantineRejectsLateNativeOpenWhileScopedCleanupIsQueuedBehindSuspendedActor() = fixture {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val acquired = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val request = async(dispatcher) { runCatching {
            queues.withFileEdit(track.id) {
                acquired.complete(Unit); finish.await()
                readers.publishRecovery(RecoveryAdmission.Pending(track.id))
                throw IOException("Controlled unresolved writer outcome")
            }
        } }
        var gate: Gate? = null
        try {
            pumpUntil(scheduler) { acquired.isCompleted }
            gate = dao.gateNextSave()
            queues.rename(first.id, "Holding cleanup")
            gate.entered.await()
            finish.complete(Unit)
            // Run the caller through action failure and temporary block release to its queued ack.
            scheduler.runCurrent()
            assertFalse(request.isCompleted)
            val native = readers.track(DefaultDataSource.Factory(InstrumentationRegistry.getInstrumentation().targetContext)).createDataSource()
            try { assertTrue(runCatching { withContext(Dispatchers.IO) {
                native.open(androidx.media3.datasource.DataSpec(Uri.parse(track.uri)))
            } }.exceptionOrNull() is IOException) }
            finally { withContext(Dispatchers.IO) { native.close() } }
            gate.release.complete(Unit)
            pumpUntil(scheduler) { request.isCompleted }
            assertTrue(request.await().exceptionOrNull() is IOException)
            assertEquals(RecoveryAdmission.Pending(track.id), readers.recovery())
            awaitMain { player.engine.mediaItemCount == 0 && !player.engine.playWhenReady }
        } finally { withContext(NonCancellable) {
            gate?.release?.complete(Unit); finish.complete(Unit)
            request.cancel()
            pumpUntil(scheduler) { request.isCompleted }
        } }
    }

    private fun fixture(attach: Boolean = true, blockRestore: Boolean = false, rawPlayer: Boolean = false, test: suspend Fixture.() -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = java.util.UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "intent-$id").apply { mkdirs() }
        val file = File(directory, "queue-tone.mp3")
        instrumentation.context.assets.open(file.name).use { input -> file.outputStream().use(input::copyTo) }
        val track = Track(Uri.fromFile(file).toString(), "First", fileName = file.name, relativePath = file.name)
        val first = ListeningQueue(name = "First", entries = listOf(QueueEntry(track = track)))
        val secondFile = File(directory, "second.mp3").also { file.copyTo(it) }
        val secondUri = Uri.fromFile(secondFile).toString()
        val second = ListeningQueue(name = "Second", entries = listOf(QueueEntry(track = track.copy(uri = secondUri, id = secondUri))))
        val dao = IntentDao(QueueLibrary(listOf(first, second), first.id), if (blockRestore) Gate() else null)
        val store = IntentStore()
        val library = LibraryRepository(store, TreeScanner(AndroidDocuments(context)), PersistedFolderPermissions(context.contentResolver))
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("intent-$id", mode)
        })
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val readers = NativeReaders()
        val queues = QueueController(dao, preferences, library, actor, readers)
        val players = mutableListOf<RecordingPlayer>()
        suspend fun player() = withContext(Dispatchers.Main) {
            RecordingPlayer(ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(readers.track(DefaultDataSource.Factory(context))))
                .build().apply { volume = 0f }).also(players::add)
        }
        val native = player()
        val attached = if (rawPlayer) native.engine else native
        val fixture = Fixture(this, queues, dao, store, native, QueueTransportPlayer(attached, queues), first, second, track, file, readers, library, ::player)
        var primaryFailure: Throwable? = null
        try {
            if (!blockRestore) fixture.awaitMain { !queues.state.value.loading }
            if (attach) { queues.attach(attached); fixture.barrier() }
            withTimeout(20000) { fixture.test() }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally { withContext(NonCancellable) {
            dao.release(); store.release()
            val closing = listOf(readers.block(track.id), readers.block(secondUri))
            var closed = false
            try {
                var failure: Throwable? = null
                suspend fun attempt(action: suspend () -> Unit) {
                    try { action() }
                    catch (caught: Throwable) { if (failure == null) failure = caught else failure!!.addSuppressed(caught) }
                }
                attempt { withTimeout(10000) { actor.coroutineContext[Job]!!.cancelAndJoin() } }
                // Try every engine even after a release error. A synchronous Main release cannot
                // be interrupted by coroutine timeout; the instrumentation job is the outer bound.
                players.forEach { native -> attempt { withContext(Dispatchers.Main) { native.engine.release() } } }
                attempt { withTimeout(10000) { closing.forEach { it.awaitClosed() } } }
                failure?.let { throw it }
                closed = true
            } catch (cleanup: Throwable) {
                if (primaryFailure == null) throw cleanup else primaryFailure.addSuppressed(cleanup)
            } finally {
                closing.forEach { it.release() }
                context.deleteSharedPreferences("intent-$id")
                if (closed) directory.deleteRecursively()
            }
        } }
    }

    private class Fixture(scope: CoroutineScope, val queues: QueueController, val dao: IntentDao, val store: IntentStore,
        val player: RecordingPlayer, val controls: QueueTransportPlayer, val first: ListeningQueue, val second: ListeningQueue,
        val track: Track, val file: File, val readers: NativeReaders, val library: LibraryRepository, val newPlayer: suspend () -> RecordingPlayer) : CoroutineScope by scope {
        suspend fun withHeldEdit(trackId: String, action: suspend () -> Unit) {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val edit = async { queues.withFileEdit(trackId) { entered.complete(Unit); finish.await() } }
            try { entered.await(); action(); finish.complete(Unit); edit.await() }
            finally { withContext(NonCancellable) { finish.complete(Unit); edit.cancelAndJoin() } }
        }
        suspend fun pumpUntil(scheduler: TestCoroutineScheduler, condition: () -> Boolean) = withTimeout(10000) {
            while (!condition()) { scheduler.runCurrent(); delay(10) }
        }
        suspend fun barrier() {
            val done = CompletableDeferred<Unit>()
            queues.settings { done.complete(Unit); it }
            withTimeout(5000) { done.await() }
        }
        suspend fun awaitMain(condition: () -> Boolean) = withTimeout(10000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(10)
        }
        suspend fun assertPlayback(playing: Boolean) {
            awaitMain { player.engine.mediaItemCount > 0 && player.engine.isPlaying == playing }
            withContext(Dispatchers.Main) { assertEquals(playing, player.engine.playWhenReady) }
        }
    }

    private class RecordingPlayer(val engine: ExoPlayer) : ForwardingPlayer(engine) {
        var stateOverride: Int? = null
        var suppression: Int? = null
        var error: PlaybackException? = null
        private val requested = CopyOnWriteArrayList<Boolean>()
        val trueRequests get() = requested.count { it }
        val trueRequestSources = CopyOnWriteArrayList<String?>()
        var preparedWhilePlaying = false
        override fun getPlaybackState() = stateOverride ?: super.getPlaybackState()
        override fun getPlaybackSuppressionReason() = suppression ?: super.getPlaybackSuppressionReason()
        override fun getPlayerError() = error ?: super.getPlayerError()
        override fun isPlaying() = playWhenReady && playbackState == Player.STATE_READY && playerError == null &&
            playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && engine.isPlaying
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            requested.add(playWhenReady)
            if (playWhenReady) trueRequestSources.add(engine.currentMediaItem?.mediaMetadata?.extras?.getString("trackId"))
            super.setPlayWhenReady(playWhenReady)
        }
        override fun prepare() { preparedWhilePlaying = preparedWhilePlaying || playWhenReady; super.prepare() }
    }

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun await() { entered.complete(Unit); release.await() }
    }
    private class IntentStore : LibraryStore {
        private val next = AtomicReference<Gate?>()
        private val gates = mutableListOf<Gate>()
        fun gateNextSnapshot() = Gate().also { check(next.compareAndSet(null, it)); gates.add(it) }
        fun release() { gates.forEach { it.release.complete(Unit) } }
        override val snapshots = flow { next.getAndSet(null)?.await(); emit(LibrarySnapshot()) }
        override suspend fun current() = LibrarySnapshot()
        override suspend fun replaceRoot(result: ScanResult) = Unit
        override suspend fun mergeRoot(result: ScanResult) = Unit
        override suspend fun removeRoot(uri: String) = Unit
        override suspend fun updateMetadata(track: Track) = Unit
    }
    private class IntentDao(private var saved: QueueLibrary, val restore: Gate?) : QueueDao() {
        private val nextSave = AtomicReference<Gate?>()
        private val gates = mutableListOf<Gate>()
        fun gateNextSave() = Gate().also { check(nextSave.compareAndSet(null, it)); gates.add(it) }
        fun release() { restore?.release?.complete(Unit); gates.forEach { it.release.complete(Unit) } }
        override suspend fun read(): QueueLibrary { restore?.await(); return saved }
        override suspend fun save(before: QueueLibrary, after: QueueLibrary) { nextSave.getAndSet(null)?.await(); saved = after }
        override suspend fun readQueues(): List<StoredQueue> = error("read overridden")
        override suspend fun readActive(): ActiveQueueRecord? = error("read overridden")
        override suspend fun upsertQueue(queue: QueueRecord): Unit = error("save overridden")
        override suspend fun updateQueueMetadata(queue: QueueMetadataRecord): Int = error("save() is overridden")
        override suspend fun upsertActive(active: ActiveQueueRecord): Unit = error("save overridden")
        override suspend fun upsertJourney(journey: JourneyRecord): Unit = error("save overridden")
        override suspend fun clearJourney(id: String): Unit = error("save overridden")
        override suspend fun upsertVisits(visits: List<VisitRecord>): Unit = error("save overridden")
        override suspend fun updateVisits(visits: List<VisitRecord>): Int = error("save overridden")
        override suspend fun upsertCycleMembers(members: List<CycleMemberRecord>): Unit = error("save overridden")
        override suspend fun updateCycleMembers(members: List<CycleMemberRecord>): Int = error("save overridden")
        override suspend fun deleteVisits(queueId: String, tokens: List<String>): Unit = error("save overridden")
        override suspend fun deleteCycleMembers(queueId: String, cycle: Long, entries: List<String>): Unit = error("save overridden")
        override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("save overridden")
        override suspend fun clearEntries(id: String): Unit = error("save overridden")
        override suspend fun deleteQueue(id: String): Unit = error("save overridden")
    }
}
