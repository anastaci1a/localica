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
import java.util.concurrent.atomic.AtomicInteger

/** Real actor/native playback; only restore/URI scheduling and continuity predicates are controlled. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackIntentTest {
    @Test fun queuedSkipsPublishEveryVisitBeforeOneSlowDurableCheckpoint() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        queues.rename(first.id, first.name) // Await the initial cursor snapshot before installing a disk gate.
        barrier()
        lateinit var held: Gate
        withContext(Dispatchers.Main) {
            player.seekVisits.clear()
            held = dao.gateNextSave()
            queues.next()
            queues.next()
            queues.previous(alwaysSong = true)
        }
        held.entered.await()
        withContext(Dispatchers.Main) {
            assertEquals(listOf("duplicate-1", "duplicate-2", "duplicate-1"), player.seekVisits.toList())
            assertEquals("Metadata already shows the last ordered press", "duplicate-1",
                queues.state.value.library.active!!.currentEntryId)
            assertEquals("duplicate-1", player.currentMediaItem!!.mediaId)
            assertFalse("Paused skips must remain paused", player.playWhenReady)
        }
        assertEquals("Checkpoint is still blocked, not silently acknowledged", "duplicate-0", dao.read().active!!.currentEntryId)
        held.release.complete(Unit)
        queues.rename(first.id, "Durable")
        barrier()
        assertEquals("Final ordered visit becomes durable", "duplicate-1", dao.read().active!!.currentEntryId)
    }

    @Test fun structuralQueueCommandWaitsBehindTheCombinedSkipCheckpoint() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        queues.rename(first.id, first.name) // Await the initial cursor snapshot before installing a disk gate.
        barrier()
        lateinit var held: Gate
        withContext(Dispatchers.Main) {
            held = dao.gateNextSave()
            queues.next()
            queues.next()
            queues.rename(first.id, "After skips")
        }
        held.entered.await()
        assertEquals("duplicate-2", queues.state.value.library.active!!.currentEntryId)
        assertEquals("A durable barrier separates cursor and structural work", first.name,
            queues.state.value.library.active!!.name)
        held.release.complete(Unit)
        barrier()
        assertEquals("After skips", dao.read().active!!.name)
        assertEquals("duplicate-2", dao.read().active!!.currentEntryId)
    }

    @Test fun skipsArrivingDuringDiskWaitStillPublishImmediatelyInOrder() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        queues.rename(first.id, first.name) // Await the initial cursor snapshot before installing a disk gate.
        barrier()
        val savesBefore = dao.saveBases.size
        lateinit var firstSave: Gate
        withContext(Dispatchers.Main) {
            player.seekVisits.clear()
            firstSave = dao.gateNextSave()
            queues.next()
        }
        firstSave.entered.await()
        val secondSave = dao.gateNextSave()
        withContext(Dispatchers.Main) {
            repeat(35) { queues.next(); queues.previous(alwaysSong = true) }
            queues.next()
            assertEquals(listOf("duplicate-1"), player.seekVisits.toList())
        }
        awaitMain { player.seekVisits.size == 72 }
        withContext(Dispatchers.Main) {
            val expected = listOf("duplicate-1") + List(35) { listOf("duplicate-2", "duplicate-1") }.flatten() + "duplicate-2"
            assertEquals("Every press survives across multiple 32-command batches", expected, player.seekVisits.toList())
            assertEquals("duplicate-2", player.currentMediaItem!!.mediaId)
        }
        assertFalse("Input progressed while the first disk write is still held", firstSave.release.isCompleted)
        firstSave.release.complete(Unit)
        secondSave.entered.await()
        assertEquals("A held write retains only the latest pending cursor checkpoint", savesBefore + 2, dao.saveBases.size)
        secondSave.release.complete(Unit)
        // An awaited rename preserves structural durability and drains previous cursor snapshots.
        queues.rename(first.id, "Drained")
        barrier()
        assertEquals(queues.state.value.library.active!!.journey, dao.read().active!!.journey)
    }

    @Test fun failedCombinedCheckpointRetriesFromTheLastDurableLibrary() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        queues.rename(first.id, first.name) // Await the initial cursor snapshot before installing a disk gate.
        barrier()
        val durable = dao.read()
        lateinit var held: Gate
        withContext(Dispatchers.Main) {
            dao.failWrites = true
            held = dao.gateNextSave()
            queues.next(); queues.next()
        }
        held.entered.await()
        assertEquals("duplicate-2", queues.state.value.library.active!!.currentEntryId)
        held.release.complete(Unit)
        awaitMain { queues.state.value.problem != null }
        assertEquals(durable, dao.read())
        val successfulBeforeRetry = dao.successfulSaveBases.size
        dao.failWrites = false
        queues.previous(alwaysSong = true)
        queues.rename(first.id, "Retried")
        barrier()
        assertEquals("Retry computes the transaction from last durable state", durable,
            dao.successfulSaveBases[successfulBeforeRetry])
        assertEquals("duplicate-1", dao.read().active!!.currentEntryId)
        assertEquals(queues.state.value.library.active!!.journey, dao.read().active!!.journey)
    }

    @Test fun blockedCursorStoragePreservesRepeatAndShuffleTraversalInBothDirections() {
        for (shuffle in listOf(false, true)) fixture(duplicateOccurrences = true) {
            awaitMain { player.engine.playbackState == Player.STATE_READY }
            queues.settings { it.copy(shuffle = shuffle, repeat = QueueRepeat.All) }
            queues.rename(first.id, first.name)
            barrier()
            val before = queues.state.value.library.active!!.journey!!
            val order = before.plans.getValue(before.cycle)
            val start = order.indexOf(before.current!!.entryId)
            val expected = (1..40).map { order[(start + it) % order.size] } +
                (39 downTo 30).map { order[(start + it) % order.size] }
            lateinit var held: Gate
            withContext(Dispatchers.Main) {
                player.seekVisits.clear()
                held = dao.gateNextSave()
                queues.next()
            }
            held.entered.await()
            withContext(Dispatchers.Main) {
                repeat(39) { queues.next() }
                repeat(10) { queues.previous(alwaysSong = true) }
            }
            awaitMain { player.seekVisits.size == expected.size }
            withContext(Dispatchers.Main) {
                assertEquals("shuffle=$shuffle retains every real visit", expected, player.seekVisits.toList())
                assertEquals(expected.last(), player.currentMediaItem!!.mediaId)
                assertFalse(player.playWhenReady)
            }
            assertFalse("No traversal waits for held disk checkpoint", held.release.isCompleted)
            held.release.complete(Unit)
            queues.rename(first.id, "Traversal saved")
            barrier()
            assertEquals(queues.state.value.library.active!!.journey, dao.read().active!!.journey)
        }
    }

    @Test fun delayedCursorFailureCannotEraseANewerRecoveryNotice() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        queues.rename(first.id, first.name)
        barrier()
        lateinit var held: Gate
        withContext(Dispatchers.Main) {
            held = dao.gateNextSave().also { it.fail = true }
            queues.next()
        }
        held.entered.await()
        readers.publishRecovery(RecoveryAdmission.Pending(track.id))
        awaitMain { player.engine.mediaItemCount == 0 && queues.state.value.problem != null }
        val notice = queues.state.value.problem
        held.release.complete(Unit)
        // This awaited save is ordered after the failing cursor write. Its normal success
        // preserves a still-owned recovery notice, exposing any stale-writer ownership loss.
        queues.rename(first.id, "Recovery retained")
        barrier()
        assertEquals(notice, queues.state.value.problem)
        withContext(Dispatchers.Main) { assertEquals(0, player.engine.mediaItemCount) }
    }

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

    @Test fun seekReceiptFollowsNativeApplicationBeforeItsSlowPersistence() = fixture {
        awaitMain { player.duration > 0 }
        val visit = withContext(Dispatchers.Main) { player.currentMediaItem?.queueVisit }
        val held = dao.gateNextSave()
        queues.rename(first.id, "Hold pending seek")
        held.entered.await()
        val receipt = CompletableDeferred<Pair<Boolean, QueuePlaybackSample?>>()
        withContext(Dispatchers.Main) {
            queues.requestSeek(1_500, visit) { applied, sample -> receipt.complete(applied to sample) }
        }
        assertFalse("Enqueue is not acknowledgement", receipt.isCompleted)
        held.release.complete(Unit)
        val result = receipt.await()
        assertTrue(result.first)
        assertEquals(1_500L, result.second!!.positionMs)
        assertEquals(visit, result.second!!.playbackVisit)
        barrier()
        val persistence = dao.gateNextSave()
        val second = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            queues.requestSeek(500, visit) { applied, sample ->
                assertEquals(500L, sample!!.positionMs)
                second.complete(applied)
            }
        }
        assertTrue(second.await())
        persistence.entered.await()
        assertFalse("Receipt does not wait for queue persistence", persistence.release.isCompleted)
        persistence.release.complete(Unit)
    }

    @Test fun seekQueuedBeforeServiceReplacementCannotApplyToTheReplacement() = fixture {
        awaitMain { player.duration > 0 }
        val visit = withContext(Dispatchers.Main) { player.currentMediaItem?.queueVisit }
        val held = dao.gateNextSave()
        queues.rename(first.id, "Hold replacement")
        held.entered.await()
        val replacement = newPlayer()
        val receipt = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            queues.requestSeek(1_500, visit) { applied, _ -> receipt.complete(applied) }
            queues.detach(player)
            queues.attach(replacement)
        }
        held.release.complete(Unit)
        assertFalse("Captured native generation must still own the request", receipt.await())
        barrier()
    }

    @Test fun failedUnavailableAndClosedSeekCommandsCompleteAsRejected() = fixture {
        awaitMain { player.duration > 0 }
        val visit = withContext(Dispatchers.Main) { player.currentMediaItem?.queueVisit }
        suspend fun rejected() {
            val receipt = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                queues.requestSeek(1_500, visit) { applied, _ -> receipt.complete(applied) }
            }
            assertFalse(receipt.await())
        }
        withContext(Dispatchers.Main) { player.durationOverride = 0 }
        rejected()
        withContext(Dispatchers.Main) { player.durationOverride = null; player.rejectSeek = true }
        rejected()
        withContext(Dispatchers.Main) { player.rejectSeek = false }
        actor.coroutineContext[Job]!!.cancelAndJoin()
        rejected()
    }

    @Test fun attachedOrderEditsReuseOnlyTheUnchangedLibraryIndexAndCurrentVisit() = fixture(duplicateOccurrences = true) {
        val timingReports = kotlinx.coroutines.channels.Channel<Pair<String, QueueOrderTiming.Report>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        withContext(Dispatchers.Main) {
            queues.orderTimingObserver = { operation, report -> timingReports.trySend(operation to report); Unit }
        }
        val indexed = CountingTracks(listOf(track))
        store.snapshot = LibrarySnapshot(tracks = indexed)
        suspend fun reverse() {
            val done = CompletableDeferred<Boolean>()
            queues.reverse(first.id) { done.complete(it) }
            assertTrue(withTimeout(5000) { done.await() })
        }
        reverse() // Warm this exact immutable snapshot, not the fixture's empty library.
        val reads = indexed.reads.get()
        assertEquals(1, reads)
        val native = withContext(Dispatchers.Main) { requireNotNull(player.currentMediaItem) }
        val opening = queues.state.value.library.active!!
        val checkpoint = OrderCheckpoint.capture(opening.entries.map { it.id }, opening.order)
        val collections = store.collections.get()
        val builds = itemBuilds.get()
        reverse()
        assertEquals(opening.entries.reversed(), queues.state.value.library.active!!.entries)
        val randomized = CompletableDeferred<Boolean>()
        queues.randomize(first.id, kotlin.random.Random(7)) { randomized.complete(it) }
        assertTrue(withTimeout(5000) { randomized.await() })
        val randomOrder = queues.state.value.library.active!!
        assertEquals(opening.entries.map { it.id }.toSet(), randomOrder.entries.map { it.id }.toSet())
        assertEquals(opening.currentEntryId, randomOrder.entries.first().id)
        val reverted = CompletableDeferred<Boolean>()
        queues.revertOrder(first.id, checkpoint) { reverted.complete(it) }
        assertTrue(withTimeout(5000) { reverted.await() })
        val restored = queues.state.value.library.active!!
        assertEquals(opening.entries, restored.entries)
        assertEquals(opening.currentEntryId, restored.currentEntryId)
        assertEquals(opening.positionMs, restored.positionMs)
        assertEquals("An unchanged immutable library must not be reindexed per order command", reads, indexed.reads.get())
        assertEquals("Unchanged per-occurrence templates must not be reconstructed", builds, itemBuilds.get())
        assertTrue("Fresh source collection is retained even on index hits", store.collections.get() >= collections + 3)
        withContext(Dispatchers.Main) {
            assertEquals(native.queueVisit, player.currentMediaItem?.queueVisit)
            assertSame("The unchanged current visit keeps its exact decorated item; future visits may legitimately change",
                native, player.currentMediaItem)
            assertEquals(restored.currentEntryId, player.currentMediaItem?.mediaId)
            assertEquals("origin-0", player.currentMediaItem?.mediaMetadata?.extras?.getString("sourcePlaylistId"))
        }
        val reports = withTimeout(5000) { List(4) { timingReports.receive() } }
        // Diagnostic jobs are independent of actor admission; their delivery order
        // is deliberately not another command-completion contract.
        assertEquals(mapOf("reverse" to 2, "randomize" to 1, "revert" to 1), reports.groupingBy { it.first }.eachCount())
        reports.forEach { (_, report) ->
            assertEquals(QueueOrderTiming.Outcome.Success, report.outcome)
            assertTrue(report.clockValid)
            val stages = QueueOrderTiming.Stage.entries.map { requireNotNull(report.elapsedNanos(it)) }
            assertTrue("Every completed stage is monotonic", stages.zipWithNext().all { (a, b) -> b >= a })
            assertTrue(requireNotNull(report.totalNanos) >= stages.last())
            assertEquals(3, report.counts.queueEntries)
            assertEquals(1, report.counts.libraryTracks)
            assertEquals(3, requireNotNull(report.counts.templatesReused) + requireNotNull(report.counts.templatesBuilt))
        }
        timingReports.close()
    }

    @Test fun attachedOrderCacheRechecksGrantsAndInvalidatesChangedMetadata() {
        val permissions = MutablePermissions()
        fixture(duplicateOccurrences = true, permissions = permissions) {
            val alternate = second.entries.single().track.uri
            val resolved = track.copy(locations = listOf(TrackLocation("root-one", track.uri, "one.mp3"),
                TrackLocation("root-two", alternate, "two.mp3")))
            val indexed = CountingTracks(listOf(resolved))
            store.snapshot = LibrarySnapshot(tracks = indexed)
            suspend fun reverse() {
                val done = CompletableDeferred<Boolean>()
                queues.reverse(first.id) { done.complete(it) }
                assertTrue(withTimeout(5000) { done.await() })
            }
            reverse()
            assertEquals(1, indexed.reads.get())
            val probes = permissions.reads.get()
            val builds = itemBuilds.get()
            withContext(Dispatchers.Main) { assertEquals(track.uri, player.currentMediaItem?.localConfiguration?.uri.toString()) }
            permissions.first = false
            reverse() // Same snapshot identity: the grant authority changed independently.
            assertEquals(1, indexed.reads.get())
            assertTrue("Index reuse must never bypass fresh grant selection", permissions.reads.get() > probes)
            assertEquals("All three independently identified occurrences need fresh URI templates", builds + 3, itemBuilds.get())
            withContext(Dispatchers.Main) {
                assertEquals(alternate, player.currentMediaItem?.localConfiguration?.uri.toString())
                assertEquals(first.currentEntryId, player.currentMediaItem?.mediaId)
                assertEquals("origin-0", player.currentMediaItem?.mediaMetadata?.extras?.getString("sourcePlaylistId"))
            }
            val updated = CountingTracks(listOf(resolved.copy(title = "Fresh title", artist = "Fresh artist")))
            store.snapshot = LibrarySnapshot(tracks = updated)
            reverse()
            assertEquals("A new snapshot owns a new index", 1, updated.reads.get())
            assertEquals("Changed metadata invalidates each occurrence template", builds + 6, itemBuilds.get())
            withContext(Dispatchers.Main) {
                assertEquals("Fresh title", player.currentMediaItem?.mediaMetadata?.title.toString())
                assertEquals("Fresh artist", player.currentMediaItem?.mediaMetadata?.artist.toString())
                assertEquals(alternate, player.currentMediaItem?.localConfiguration?.uri.toString())
            }
        }
    }

    @Test fun cachedAttachedOrdersStillWaitForDurableAndUriCompletionAndRespectRecovery() = fixture(duplicateOccurrences = true) {
        store.snapshot = LibrarySnapshot(tracks = listOf(track))
        val warmed = CompletableDeferred<Boolean>()
        queues.reverse(first.id) { warmed.complete(it) }
        assertTrue(withTimeout(5000) { warmed.await() })
        val opening = queues.state.value.library.active!!
        val saved = dao.gateNextSave()
        val done = CompletableDeferred<Boolean>()
        queues.reverse(first.id) { done.complete(it) }
        withTimeout(5000) { saved.entered.await() }
        assertFalse("No acknowledgement while durable save is held", done.isCompleted)
        assertEquals(opening.entries, queues.state.value.library.active!!.entries)
        val uri = store.gateNextSnapshot()
        saved.release.complete(Unit)
        withTimeout(5000) { uri.entered.await() }
        assertFalse("A populated cache cannot acknowledge before fresh URI resolution/native sync", done.isCompleted)
        assertEquals(opening.entries.reversed(), queues.state.value.library.active!!.entries)
        uri.release.complete(Unit)
        assertTrue(withTimeout(5000) { done.await() })
        withContext(Dispatchers.Main) {
            assertEquals(first.currentEntryId, player.currentMediaItem?.mediaId)
            assertEquals(track.uri, player.currentMediaItem?.localConfiguration?.uri.toString())
        }
        readers.publishRecovery(RecoveryAdmission.Pending(track.id))
        awaitMain { player.engine.mediaItemCount == 0 && queues.state.value.problem != null }
        val requests = player.trueRequests
        val builds = itemBuilds.get()
        val blocked = CompletableDeferred<Boolean>()
        queues.reverse(first.id) { blocked.complete(it) }
        assertTrue(withTimeout(5000) { blocked.await() }) // Durable order may change; playback stays blocked.
        withContext(Dispatchers.Main) {
            assertEquals(0, player.engine.mediaItemCount)
            assertFalse(player.engine.playWhenReady)
            assertEquals(requests, player.trueRequests)
            assertEquals("Recovery admission precedes even cached template preparation", builds, itemBuilds.get())
        }
        queues.withFileRecovery(track.id) { readers.publishRecovery(RecoveryAdmission.Clear) }
        assertPlayback(false)
    }

    private class CountingTracks(private val values: List<Track>) : AbstractList<Track>() {
        val reads = AtomicInteger()
        override val size get() = values.size
        override fun get(index: Int): Track { reads.incrementAndGet(); return values[index] }
    }
    private class MutablePermissions : FolderPermissions {
        @Volatile var first = true
        val reads = AtomicInteger()
        override fun hasReadAccess(uri: String): Boolean { reads.incrementAndGet(); return uri != "root-one" || first }
        override fun acquire(uri: String) = Unit
        override fun release(uri: String) = Unit
    }

    private fun fixture(attach: Boolean = true, blockRestore: Boolean = false, rawPlayer: Boolean = false,
        duplicateOccurrences: Boolean = false, permissions: FolderPermissions? = null, test: suspend Fixture.() -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = java.util.UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "intent-$id").apply { mkdirs() }
        val file = File(directory, "queue-tone.mp3")
        instrumentation.context.assets.open(file.name).use { input -> file.outputStream().use(input::copyTo) }
        val track = Track(Uri.fromFile(file).toString(), "First", fileName = file.name, relativePath = file.name)
        val first = ListeningQueue(name = "First", entries = if (duplicateOccurrences)
            (0..2).map { QueueEntry("duplicate-$it", track, "origin-$it") } else listOf(QueueEntry(track = track)))
        val secondFile = File(directory, "second.mp3").also { file.copyTo(it) }
        val secondUri = Uri.fromFile(secondFile).toString()
        val second = ListeningQueue(name = "Second", entries = listOf(QueueEntry(track = track.copy(uri = secondUri, id = secondUri))))
        val dao = IntentDao(QueueLibrary(listOf(first, second), first.id), if (blockRestore) Gate() else null)
        val store = IntentStore()
        val library = LibraryRepository(store, TreeScanner(AndroidDocuments(context)), permissions ?: PersistedFolderPermissions(context.contentResolver))
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("intent-$id", mode)
        })
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val readers = NativeReaders()
        val itemBuilds = AtomicInteger()
        val queues = QueueController(dao, preferences, library, actor, readers, mediaItemFactory = { entryId, resolved, origin ->
            itemBuilds.incrementAndGet()
            queueMediaItem(entryId, resolved, origin)
        })
        val players = mutableListOf<RecordingPlayer>()
        suspend fun player() = withContext(Dispatchers.Main) {
            RecordingPlayer(ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(readers.track(DefaultDataSource.Factory(context))))
                .build().apply { volume = 0f }).also(players::add)
        }
        val native = player()
        val attached = if (rawPlayer) native.engine else native
        val fixture = Fixture(this, actor, queues, dao, store, native, QueueTransportPlayer(attached, queues), first, second, track, file, readers, library, itemBuilds, ::player)
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

    private class Fixture(scope: CoroutineScope, val actor: CoroutineScope, val queues: QueueController, val dao: IntentDao, val store: IntentStore,
        val player: RecordingPlayer, val controls: QueueTransportPlayer, val first: ListeningQueue, val second: ListeningQueue,
        val track: Track, val file: File, val readers: NativeReaders, val library: LibraryRepository,
        val itemBuilds: AtomicInteger, val newPlayer: suspend () -> RecordingPlayer) : CoroutineScope by scope {
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
        val seekVisits = CopyOnWriteArrayList<String>()
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            seekVisits.add(getMediaItemAt(mediaItemIndex).mediaId)
            super.seekTo(mediaItemIndex, positionMs)
        }
        var stateOverride: Int? = null
        var durationOverride: Long? = null
        var rejectSeek = false
        override fun getDuration() = durationOverride ?: super.getDuration()
        override fun seekTo(positionMs: Long) {
            check(!rejectSeek) { "Controlled native seek failure" }
            super.seekTo(positionMs)
        }
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
        @Volatile var fail = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun await() { entered.complete(Unit); release.await() }
    }
    private class IntentStore : LibraryStore {
        @Volatile var snapshot: LibrarySnapshot? = null
        val collections = AtomicInteger()
        private val next = AtomicReference<Gate?>()
        private val gates = mutableListOf<Gate>()
        fun gateNextSnapshot() = Gate().also { check(next.compareAndSet(null, it)); gates.add(it) }
        fun release() { gates.forEach { it.release.complete(Unit) } }
        override val snapshots = flow { collections.incrementAndGet(); next.getAndSet(null)?.await(); emit(snapshot ?: LibrarySnapshot()) }
        override suspend fun current() = LibrarySnapshot()
        override suspend fun replaceRoot(result: ScanResult) = Unit
        override suspend fun mergeRoot(result: ScanResult) = Unit
        override suspend fun removeRoot(uri: String) = Unit
        override suspend fun updateMetadata(track: Track) = Unit
    }
    private class IntentDao(@Volatile private var saved: QueueLibrary, val restore: Gate?) : QueueDao() {
        private val nextSave = AtomicReference<Gate?>()
        private val gates = mutableListOf<Gate>()
        fun gateNextSave() = Gate().also { check(nextSave.compareAndSet(null, it)); gates.add(it) }
        fun release() { restore?.release?.complete(Unit); gates.forEach { it.release.complete(Unit) } }
        override suspend fun read(): QueueLibrary { restore?.await(); return saved }
        @Volatile var failWrites = false
        val saveBases = CopyOnWriteArrayList<QueueLibrary>()
        val successfulSaveBases = CopyOnWriteArrayList<QueueLibrary>()
        override suspend fun save(before: QueueLibrary, after: QueueLibrary) {
            saveBases.add(before)
            val gate = nextSave.getAndSet(null)
            gate?.await()
            if (gate?.fail == true || failWrites) throw IOException("Controlled checkpoint failure")
            successfulSaveBases.add(before)
            saved = after
        }
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

