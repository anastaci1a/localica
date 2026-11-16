package io.github.anastaci1a.localica

import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
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
import io.github.anastaci1a.localica.ui.QueueScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
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
    @Test fun artworkWindowsDoNotNeedTheNativeResolvedFallback() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        barrier()
        withContext(Dispatchers.Main) {
            val queue = queues.state.value.library.active!!
            val resolve = QueueController::class.java.declaredMethods.single { it.name == "visitTrack" }.apply { isAccessible = true }
            fun legacy(source: ListeningQueue, entryId: String?) = resolve.invoke(queues, source, entryId) as Track?
            val cold = queue.copy(journey = queue.journey!!.copy())
            assertNull(cold.visitTrack(cold.currentEntryId))
            assertNotNull("Native receipts still resolve outside the prepared window", legacy(cold, cold.currentEntryId))
            assertNull(legacy(cold.copy(id = "different-queue"), cold.currentEntryId))
            assertEquals(listOf(null, null), cold.artworkNeighbors())
            assertTrue(cold.artworkPreloadUris().isEmpty())
            assertTrue(queue.journey!!.artworkWindow().isNotEmpty())
            for (visit in queue.journey!!.window()) assertNotNull(queue.journey!!.sourceOrdinal(queue.ids, visit.entryId))
            assertEquals(listOf(queue.journey!!.next, queue.journey!!.previous).map { legacy(queue, it?.entryId)?.uri }, queue.artworkNeighbors())
            assertEquals(queue.journey!!.artworkWindow().mapNotNull { legacy(queue, it.entryId)?.uri }.distinct(), queue.artworkPreloadUris())
        }
    }

    private suspend fun Fixture.selectWithFrozenNativePath() = withContext(Dispatchers.Main) {
        val mutable = QueueController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = mutable.get(queues) as kotlinx.coroutines.flow.MutableStateFlow<QueueUiState>
        val intents = QueueController::class.java.getDeclaredField("intents").apply { isAccessible = true }.get(queues) as PlaybackIntents
        val revision = QueueController::class.java.getDeclaredField("commandRevision").apply { isAccessible = true }
        val sync = QueueController::class.java.declaredMethods.single { it.name == "syncTimeline" }.apply { isAccessible = true }
        val apply = QueueController::class.java.declaredMethods.single { it.name == "applyNativeIntent" }.apply { isAccessible = true }
        val command = QueueController::class.java.declaredMethods.single { it.name == "editCommand" }.apply { isAccessible = true }
        val action: suspend () -> Unit = {
            val active = state.value.library.active!!
            val selected = withContext(Dispatchers.Default) { active.copy(currentEntryId = "duplicate-1", positionMs = 0,
                journey = active.journey!!.select("duplicate-1", active.ids)) }
            state.value = state.value.copy(library = state.value.library.update(selected))
            val accepted = revision.getLong(queues)
            intents.propose(accepted, true, documentIdentity(track.id))
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Boolean> {
                sync.invoke(queues, true, true, null, queues.state.value.settings, null, it)
            }
            apply.invoke(queues, player, accepted, true)
        }
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> {
            command.invoke(queues, false, {}, action, it)
        }
    }

    @Test fun sameQueueSelectionMatchesPriorNativePathWithoutReloading() {
        for (playing in listOf(false, true)) {
            var reference: List<String>? = null
            for (oldPath in listOf(true, false)) fixture(duplicateOccurrences = true) {
                awaitMain { player.engine.playbackState == Player.STATE_READY }
                queues.playbackRequested(playing)
                barrier()
                withContext(Dispatchers.Main) { player.selectionCalls.clear() }
                if (oldPath) selectWithFrozenNativePath() else queues.selectEntry("duplicate-1")
                barrier()
                withContext(Dispatchers.Main) {
                    assertEquals("duplicate-1", player.currentMediaItem!!.mediaId)
                    assertTrue(player.playWhenReady)
                    assertTrue(player.currentPosition < 1500)
                    val calls = player.selectionCalls.toList()
                    assertFalse("Selection must retain the prepared player", calls.any { it in listOf("load", "prepare", "stop", "clear") })
                    assertTrue(calls.contains("seek:duplicate-1:0"))
                    if (oldPath) reference = calls else assertEquals("Native calls must match the prior same-queue path", reference, calls)
                }
            }
        }
    }

    @Test fun selectingThenPausingDuringTimelineIoMatchesThePriorNativePath() {
        var reference: List<String>? = null
        for (oldPath in listOf(true, false)) fixture(duplicateOccurrences = true) {
            awaitMain { player.engine.playbackState == Player.STATE_READY }
            queues.playbackRequested(true); barrier()
            val gate = store.gateNextSnapshot()
            withContext(Dispatchers.Main) { player.selectionCalls.clear() }
            val old = if (oldPath) async { selectWithFrozenNativePath() } else null
            if (!oldPath) queues.selectEntry("duplicate-1")
            try {
                gate.entered.await()
                assertEquals("The selected occurrence is committed before IO completes", "duplicate-1",
                    queues.state.value.library.active!!.currentEntryId)
                withContext(Dispatchers.Main) { queues.playbackRequested(false) }
            } finally { gate.release.complete(Unit) }
            old?.await(); barrier()
            withContext(Dispatchers.Main) {
                val active = queues.state.value.library.active!!
                assertEquals("duplicate-1", active.currentEntryId)
                assertEquals(active.journey!!.current!!.token, player.currentMediaItem!!.queueVisit)
                assertEquals(1, active.journey.cursor)
                assertEquals(0L, player.currentPosition)
                assertFalse(player.playWhenReady)
                assertEquals(first.entries, active.entries)
                assertTrue(queues.state.value.library.queues.any { it.id == second.id })
                val calls = player.selectionCalls.toList()
                assertFalse(calls.any { it in listOf("load", "prepare", "stop", "clear") })
                assertEquals(1, calls.count { it == "seek:duplicate-1:0" })
                if (oldPath) reference = calls else assertEquals(reference, calls)
                assertNull(queues.state.value.problem)
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun tappingQueueRowDuringPlaybackRetainsThePreparedPlayer() = fixture(duplicateOccurrences = true) {
        queues.playbackRequested(true)
        barrier()
        awaitMain { player.isPlaying }
        val request = queues.state.value.playerRequest
        localicaRunComposeUiTest {
            setContent { FixtureTime.WithClock { EngineTheme {
                val state by queues.state.collectAsState()
                QueueScreen(state, queues, isPlaying = true)
            } } }
            waitUntil(timeoutMillis = 5000) { onAllNodesWithTag("media-duplicate-1").fetchSemanticsNodes().isNotEmpty() }
            runOnIdle { player.selectionCalls.clear() }
            onNodeWithTag("media-duplicate-1").performTouchInput { click() }
            waitUntil(timeoutMillis = 10000) { queues.state.value.playerRequest == request + 1 }
            runOnIdle {
                assertEquals("duplicate-1", player.currentMediaItem!!.mediaId)
                assertTrue(player.playWhenReady)
                assertTrue(player.currentPosition < 1500)
                assertFalse(player.selectionCalls.any { it in listOf("load", "prepare", "stop", "clear") })
                assertEquals(1, player.selectionCalls.count { it == "seek:duplicate-1:0" })
                assertNull(queues.state.value.problem)
            }
        }
    }

    @Test fun failedInitialRestoreRejectsQueuedAndLaterCompletionsWithoutEditingStorage() = fixture(attach = false, blockRestore = true) {
        dao.restore!!.entered.await()
        val receipts = CopyOnWriteArrayList<Pair<String, Boolean>>()
        fun issueCommands(prefix: String) {
            queues.act(listOf(track), SongTap.NewQueue) { receipts.add("$prefix-act" to it) }
            queues.appendTo(first.id, emptyList()) { receipts.add("$prefix-append" to it) }
            queues.moveQueueBefore(first.id, null) { receipts.add("$prefix-moveQueue" to it) }
            queues.removeEntries(emptySet()) { receipts.add("$prefix-remove" to it) }
            queues.moveEntry("missing", 1) { receipts.add("$prefix-edit" to it) }
            queues.reverseOrder(first.id) { receipts.add("$prefix-order" to (it != null)) }
            queues.swipeTo("missing") { receipts.add("$prefix-swipe" to it) }
            queues.refreshTrack(track) { receipts.add("$prefix-refresh" to it) }
            queues.requestSeek(100, null) { applied, sample ->
                assertNull(sample)
                receipts.add("$prefix-seek" to applied)
            }
        }
        withContext(Dispatchers.Main) { issueCommands("queued") }
        dao.restore.fail = true
        dao.restore.release.complete(Unit)
        awaitMain { !queues.state.value.loading }
        withContext(Dispatchers.Main) { issueCommands("later") }
        withTimeout(2000) { while (receipts.size < 18) delay(10) }
        assertEquals(18, receipts.size)
        assertEquals(18, receipts.map { it.first }.toSet().size)
        assertTrue(receipts.all { !it.second })
        var edited = false
        val failure = try {
            withTimeout(2000) { queues.withFileEdit(track.id) { edited = true } }
            null
        } catch (caught: IllegalStateException) { caught }
        assertNotNull("The suspended edit must receive restore failure, not wait for its watchdog", failure)
        assertTrue(failure!!.cause is IOException)
        assertFalse(edited)
        assertTrue(dao.saveBases.isEmpty())
        assertTrue(queues.state.value.library.queues.isEmpty())
        assertNotNull(queues.state.value.problem)
    }

    @Test fun detachReceiptWaitsForTheFinalCursorWriteWithoutBlockingMain() = fixture {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        queues.rename(first.id, first.name)
        barrier()
        val held = dao.gateNextSave()
        val receipt: Any? = withContext(Dispatchers.Main) {
            player.engine.seekTo(2345)
            queues.detach(player)
        }
        assertTrue("Detach must expose its final persistence completion", receipt is Deferred<*>)
        val completion = receipt as Deferred<*>
        withTimeout(5000) { held.entered.await() }
        assertFalse("The receipt must not acknowledge a queued but unwritten cursor", completion.isCompleted)
        withContext(Dispatchers.Main) { assertEquals(2345L, player.currentPosition) }
        held.release.complete(Unit)
        withTimeout(5000) { completion.await() }
        assertEquals(2345L, dao.read().active!!.positionMs)
        withContext(Dispatchers.Main) { assertFalse(player.playWhenReady) }
    }

    @Test fun orderedSeeksSurviveUnknownDurationAndNeverLeakToTheFollowingVisit() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        queues.rename(first.id, first.name)
        barrier()
        withContext(Dispatchers.Main) {
            player.durationOverride = androidx.media3.common.C.TIME_UNSET
            player.positionSeeks.clear()
            queues.next(); queues.seek(9000); queues.next()
        }
        barrier()
        withContext(Dispatchers.Main) {
            assertEquals(listOf("duplicate-1" to 9000L), player.positionSeeks.toList())
            assertEquals("duplicate-2", player.currentMediaItem!!.mediaId)
            assertEquals(0L, player.currentPosition)
            assertFalse(player.playWhenReady)
            player.positionSeeks.clear()
            queues.seek(3000); queues.seek(6000)
        }
        barrier()
        withContext(Dispatchers.Main) {
            assertEquals(listOf("duplicate-2" to 3000L, "duplicate-2" to 6000L), player.positionSeeks.toList())
            assertEquals(6000L, player.currentPosition)
            player.durationOverride = 5000L
            player.positionSeeks.clear()
            queues.seek(9000); queues.seek(-10)
        }
        barrier()
        withContext(Dispatchers.Main) {
            assertEquals(listOf("duplicate-2" to 5000L, "duplicate-2" to 0L), player.positionSeeks.toList())
            assertEquals(0L, player.currentPosition)
            player.durationOverride = 0L
            player.positionSeeks.clear()
            queues.seek(1000)
        }
        barrier()
        withContext(Dispatchers.Main) {
            assertTrue("Known empty media retains its no-seek contract", player.positionSeeks.isEmpty())
            player.durationOverride = null
        }
        queues.clearPlayback()
        barrier()
        withContext(Dispatchers.Main) {
            assertNull(player.currentMediaItem)
            player.positionSeeks.clear()
            queues.seek(500)
        }
        barrier()
        withContext(Dispatchers.Main) { assertTrue(player.positionSeeks.isEmpty()); assertFalse(player.playWhenReady) }
    }

    @Test fun queuedNextThenReceiptSeekAppliesBeforeTheNewVisitDurationIsKnown() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        queues.rename(first.id, first.name)
        barrier()
        val held = dao.gateNextSave()
        queues.rename(first.id, "Hold ordered receipt seek")
        held.entered.await()
        val receipt = CompletableDeferred<Pair<Boolean, QueuePlaybackSample?>>()
        val callbacks = AtomicInteger()
        val nextVisit = withContext(Dispatchers.Main) {
            val next = queues.state.value.library.active!!.journey!!.next!!
            player.durationOverride = androidx.media3.common.C.TIME_UNSET
            player.positionSeeks.clear()
            queues.next()
            queues.requestSeek(9000, next.token) { applied, sample ->
                callbacks.incrementAndGet()
                receipt.complete(applied to sample)
            }
            next
        }
        assertFalse("Enqueue alone must not acknowledge the seek", receipt.isCompleted)
        held.release.complete(Unit)
        val result = receipt.await()
        assertTrue("The receipt path must accept a seek while the new duration is unknown", result.first)
        assertEquals(nextVisit.token, result.second!!.playbackVisit)
        assertEquals(9000L, result.second!!.positionMs)
        barrier()
        withContext(Dispatchers.Main) {
            assertEquals(1, callbacks.get())
            assertEquals(listOf("duplicate-1" to 9000L), player.positionSeeks.toList())
            assertEquals("duplicate-1", player.currentMediaItem!!.mediaId)
            assertEquals(nextVisit.token, player.currentMediaItem!!.queueVisit)
            assertEquals(9000L, player.currentPosition)
            assertFalse(player.playWhenReady)
            player.durationOverride = null
        }
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        withContext(Dispatchers.Main) {
            assertEquals("The prepared new visit retains the requested position", 9000L, player.currentPosition)
            queues.next()
        }
        barrier()
        val stale = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            queues.requestSeek(6000, nextVisit.token) { applied, _ -> stale.complete(applied) }
        }
        assertFalse("The accepted old visit must not admit a later stale request", stale.await())
        barrier()
        withContext(Dispatchers.Main) {
            assertEquals("duplicate-2", player.currentMediaItem!!.mediaId)
            assertEquals(0L, player.currentPosition)
            assertEquals(listOf("duplicate-1" to 9000L), player.positionSeeks.toList())
        }
    }

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

    @Test fun clearingPlaybackRetiresTemplatesAndReactivationRebuildsRetainedOccurrences() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        queues.rename(first.id, first.name)
        barrier()
        val before = queues.state.value.library.active!!
        val builds = itemBuilds.get()
        val collections = store.collections.get()
        val oldNative = withContext(Dispatchers.Main) { requireNotNull(player.currentMediaItem) }
        val timeline = QueueTimeline()
        timeline.resolve(before.id, withContext(Dispatchers.Main) {
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        })
        assertEquals(before.id, timeline.queueId)
        timeline.clear()
        assertNull(timeline.queueId)
        queues.clearPlayback()
        barrier()
        val cleared = queues.state.value.library
        assertNull(cleared.activeId)
        assertEquals(before.entries, cleared.queues.first { it.id == before.id }.entries)
        withContext(Dispatchers.Main) {
            assertEquals(0, player.mediaItemCount)
            assertNull(player.currentMediaItem)
        }
        assertEquals(builds, itemBuilds.get())
        queues.settings { it.copy(switching = QueueSwitch.Paused) }
        barrier()
        queues.switchTo(before.id)
        barrier()
        assertEquals(before.id, queues.state.value.library.activeId)
        assertEquals(before.entries, queues.state.value.library.active!!.entries)
        assertEquals(before.currentEntryId, queues.state.value.library.active!!.currentEntryId)
        assertEquals(builds + before.entries.size, itemBuilds.get())
        assertTrue(store.collections.get() > collections)
        withContext(Dispatchers.Main) {
            assertNotSame(oldNative, player.currentMediaItem)
            assertEquals(before.currentEntryId, player.currentMediaItem?.mediaId)
            assertEquals(before.id, player.currentMediaItem?.listeningQueue)
            assertEquals("origin-0", player.currentMediaItem?.mediaMetadata?.extras?.getString("sourcePlaylistId"))
        }
    }

    @Test fun attachedOrderEditsReuseUnchangedWindowTemplatesAndCurrentVisit() = fixture(duplicateOccurrences = true) {
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
        assertTrue(reads > 0)
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
        assertTrue("The bounded window must read fresh metadata on every sync", indexed.reads.get() > reads)
        assertEquals("Unchanged per-occurrence templates must not be reconstructed", builds, itemBuilds.get())
        assertTrue("Every order still collects fresh source metadata", store.collections.get() >= collections + 3)
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
            assertTrue(indexed.reads.get() > 0)
            val probes = permissions.reads.get()
            val builds = itemBuilds.get()
            withContext(Dispatchers.Main) { assertEquals(track.uri, player.currentMediaItem?.localConfiguration?.uri.toString()) }
            permissions.first = false
            reverse() // Same snapshot identity: the grant authority changed independently.
            assertTrue(indexed.reads.get() > 0)
            assertTrue("Template reuse must never bypass fresh grant selection", permissions.reads.get() > probes)
            assertEquals("All three independently identified occurrences need fresh URI templates", builds + 3, itemBuilds.get())
            withContext(Dispatchers.Main) {
                assertEquals(alternate, player.currentMediaItem?.localConfiguration?.uri.toString())
                assertEquals(first.currentEntryId, player.currentMediaItem?.mediaId)
                assertEquals("origin-0", player.currentMediaItem?.mediaMetadata?.extras?.getString("sourcePlaylistId"))
            }
            val updated = CountingTracks(listOf(resolved.copy(title = "Fresh title", artist = "Fresh artist")))
            store.snapshot = LibrarySnapshot(tracks = updated)
            reverse()
            assertTrue("New metadata is read for the window", updated.reads.get() > 0)
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

    @Test fun playNextSurvivesDecoderArrivalWhileActivationResolvesUris() = fixture(rawPlayer = true, duplicateOccurrences = true) {
        queues.settings(QueueSettings(shuffle = true)); barrier()
        val before = queues.state.value.library.active!!
        val oldNext = before.journey!!.next!!
        val gate = store.gateNextSnapshot()
        val done = CompletableDeferred<Boolean>()
        queues.act(listOf(track, track), SongTap.PlayNext) { done.complete(it) }
        gate.entered.await()
        val inserted = queues.state.value.library.active!!.entries.filterNot { entry -> before.entries.any { it.id == entry.id } }.map { it.id }
        assertEquals(2, inserted.size)
        withContext(Dispatchers.Main) {
            val index = (0 until player.mediaItemCount).first { player.getMediaItemAt(it).queueVisit == oldNext.token }
            player.seekTo(index, 700)
        }
        gate.release.complete(Unit)
        assertTrue(withTimeout(5000) { done.await() }); barrier()
        val after = queues.state.value.library.active!!
        assertEquals(oldNext, after.journey!!.current)
        assertEquals(inserted, after.journey!!.history.drop(after.journey!!.cursor + 1).take(2).map { it.entryId })
        withContext(Dispatchers.Main) {
            assertEquals(inserted.first(), player.getMediaItemAt(player.nextMediaItemIndex).mediaId)
            assertFalse(player.playWhenReady)
            assertEquals(700L, player.currentPosition)
        }
    }

    @Test fun shuffleIntentShowsBeforeCommitWithoutChangingNativeWindowOrFlags() {
        val reads = AtomicReference<Gate?>()
        fixture(rawPlayer = true, duplicateOccurrences = true, playbackStore = { heldReads(reads, it) }) {
            awaitMain { player.engine.playbackState == Player.STATE_READY }
            barrier()
            val changes = AtomicInteger()
            val listener = object : Player.Listener {
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) changes.incrementAndGet()
                }
            }
            withContext(Dispatchers.Main) { player.engine.addListener(listener) }
            try {
                for (shuffle in listOf(true, false)) {
                    val held = Gate()
                    val before = withContext(Dispatchers.Main) {
                        Triple(changes.get(), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).queueVisit }, player.currentMediaItemIndex)
                    }
                    assertTrue(before.second.size > 1)
                    try {
                        withContext(Dispatchers.Main) {
                            reads.set(held)
                            queues.requestModes { it.copy(shuffle = shuffle) }
                        }
                        held.entered.await()
                        withContext(Dispatchers.Main) {
                            assertEquals(shuffle, queues.presentation.value.settings.shuffle)
                            assertEquals(!shuffle, queues.state.value.settings.shuffle)
                            assertEquals(!shuffle, player.engine.shuffleModeEnabled)
                            assertEquals(before.first, changes.get())
                            assertEquals(before.second, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).queueVisit })
                            assertEquals(before.third, player.currentMediaItemIndex)
                            val timeline = player.engine.currentTimeline
                            for (index in 0 until timeline.windowCount) {
                                assertEquals(if (index == timeline.windowCount - 1) androidx.media3.common.C.INDEX_UNSET else index + 1,
                                    timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true))
                            }
                        }
                    } finally { reads.compareAndSet(held, null); held.release.complete(Unit) }
                    barrier()
                    withContext(Dispatchers.Main) {
                        assertEquals(shuffle, queues.state.value.settings.shuffle)
                        assertEquals(shuffle, player.engine.shuffleModeEnabled)
                        assertEquals(queues.state.value.library.active!!.journey!!.window().map { it.token },
                            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).queueVisit })
                    }
                }
            } finally { withContext(Dispatchers.Main) { player.engine.removeListener(listener) } }
        }
    }

    @Test fun playNextAndPauseSurviveEveryReadHeldDuringActivation() {
        val held = Gate()
        val reads = AtomicReference<Gate?>(held)
        fixture(attach = false, duplicateOccurrences = true, playbackStore = { heldReads(reads, it) }) {
            val original = first.entries.map { it.id }
            val done = CompletableDeferred<Boolean>()
            queues.attach(player)
            controls.play()
            held.entered.await()
            withContext(Dispatchers.Main) {
                queues.act(listOf(track, track), SongTap.PlayNext) { done.complete(it) }
                controls.pause()
                held.release.complete(Unit)
            }
            assertTrue(withTimeout(5000) { done.await() })
            barrier()
            awaitMain { player.engine.playbackState == Player.STATE_READY }
            val active = queues.state.value.library.active!!
            val inserted = active.entries.map { it.id }.filterNot { it in original }
            assertEquals(2, inserted.size)
            assertEquals((original + inserted).toSet(), active.entries.map { it.id }.toSet())
            val journey = active.journey!!
            assertEquals(inserted, journey.window().dropWhile { it.token != journey.current!!.token }.drop(1).take(2).map { it.entryId })
            withContext(Dispatchers.Main) {
                assertEquals(journey.window().map { it.entryId }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
                assertEquals(original.first(), player.currentMediaItem!!.mediaId)
                assertEquals(journey.current!!.token, player.currentMediaItem!!.queueVisit)
                assertFalse(player.playWhenReady)
                assertEquals(0, player.trueRequests)
                assertFalse(player.preparedWhilePlaying)
            }
            assertNull(queues.state.value.problem)
        }
    }

    @Test fun newerPauseSurvivesEveryReadHeldThroughNativeQueueEnd() {
        val reads = AtomicReference<Gate?>()
        fixture(playbackStore = { heldReads(reads, it) }) {
            queues.settings { it.copy(advancedRepeat = AdvancedRepeat(queueEnd = QueueEnd.NextQueue)) }
            barrier()
            controls.play(); barrier(); assertPlayback(true)
            val held = Gate()
            withContext(Dispatchers.Main) {
                reads.set(held)
                player.engine.seekTo(player.duration - 80)
            }
            held.entered.await()
            awaitMain { player.engine.playbackState == Player.STATE_ENDED }
            withContext(Dispatchers.Main) {
                controls.pause()
                held.release.complete(Unit)
            }
            barrier()
            awaitMain { player.currentMediaItem?.listeningQueue == second.id }
            val active = queues.state.value.library.active!!
            assertEquals(second.id, active.id)
            assertEquals(second.entries.map { it.id }, active.entries.map { it.id })
            withContext(Dispatchers.Main) {
                assertEquals(active.journey!!.window().map { it.entryId }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
                assertEquals(active.journey!!.current!!.token, player.currentMediaItem!!.queueVisit)
                assertFalse(player.playWhenReady)
                assertEquals(1, player.trueRequests)
                assertFalse(player.preparedWhilePlaying)
            }
            assertNull(queues.state.value.problem)
        }
    }

    @Test fun playNextWithoutCurrentEntryStartsAtFirstAddedVisit() = fixture(attach = false, noCurrent = true, duplicateOccurrences = true) {
        queues.settings(QueueSettings(shuffle = true)); barrier()
        assertNull(queues.state.value.library.active!!.currentEntryId)
        val done = CompletableDeferred<Boolean>()
        queues.act(listOf(track, track), SongTap.PlayNext) { done.complete(it) }
        assertTrue(withTimeout(5000) { done.await() })
        val active = queues.state.value.library.active!!
        val added = active.entries.take(2).map { it.id }
        assertEquals(added.first(), active.currentEntryId)
        assertEquals(active.currentEntryId, active.journey!!.current!!.entryId)
        assertEquals(setOf(added.first()), active.journey!!.visited)
        assertEquals(added.last(), active.journey!!.next!!.entryId)
        queues.attach(player.engine); barrier()
        withContext(Dispatchers.Main) {
            assertEquals(active.currentEntryId, player.currentMediaItem!!.mediaId)
            assertEquals(added.last(), player.getMediaItemAt(player.nextMediaItemIndex).mediaId)
        }
    }

    @Test fun stopHereDoesNotTreatPendingPlayNextInActiveCycleAsQueueEnd() = stopAtPlayNextHistoryBoundary(false)
    @Test fun stopHereDoesNotTreatReturnFromScheduledVisitToOldHistoryAsQueueEnd() = stopAtPlayNextHistoryBoundary(true)

    private fun stopAtPlayNextHistoryBoundary(leavingScheduled: Boolean) = fixture(rawPlayer = true, duplicateOccurrences = true) {
        queues.settings(QueueSettings(shuffle = true,
            advancedRepeat = AdvancedRepeat(songEnd = SongEnd.Stop, queueEnd = QueueEnd.Repeat)))
        barrier()
        repeat(4) { queues.next(); barrier() }
        assertEquals(1L, queues.state.value.library.active!!.journey!!.cycle)
        repeat(4) { queues.previous(alwaysSong = true); barrier() }
        assertEquals(0L, queues.state.value.library.active!!.journey!!.current!!.cycle)
        val done = CompletableDeferred<Boolean>()
        queues.act(listOf(track, track), SongTap.PlayNext) { done.complete(it) }
        assertTrue(withTimeout(5000) { done.await() })
        if (leavingScheduled) repeat(2) { queues.next(); barrier() }
        val journey = queues.state.value.library.active!!.journey!!
        val current = journey.current!!
        assertEquals(if (leavingScheduled) 1L else 0L, current.cycle)
        assertEquals(if (leavingScheduled) 0L else 1L, journey.next!!.cycle)
        awaitMain { player.engine.playbackState == Player.STATE_READY && player.duration > 300 }
        withContext(Dispatchers.Main) {
            player.engine.seekTo(player.duration - 80)
            player.engine.play()
        }
        awaitMain { !player.engine.playWhenReady }
        barrier()
        assertEquals("Stop here must retain the ended visit when retracing the active traversal", current,
            queues.state.value.library.active!!.journey!!.current)
        withContext(Dispatchers.Main) {
            assertEquals(current.token, player.currentMediaItem!!.queueVisit)
            assertFalse(player.playWhenReady)
        }
    }

    @Test fun activationBuildsOnlyWindowTemplatesAtOperatorScale() {
        val arguments = InstrumentationRegistry.getArguments()
        val entries = arguments.getString("queueEntries", "13400")!!.toInt()
        val songs = arguments.getString("libraryTracks", "13400")!!.toInt()
        fixture(attach = false, duplicateOccurrences = true, occurrences = entries, librarySize = songs, initialIndex = arguments.getString("initialIndex")?.toInt() ?: (entries - 10).coerceAtLeast(0), timeoutMs = 300000) {
            val shuffle = arguments.getString("journeyShuffle", "false").toBoolean()
            val loop = arguments.getString("journeyLoop", "false").toBoolean()
            val reshuffle = arguments.getString("journeyReshuffle", "false").toBoolean()
            queues.settings { it.copy(shuffle = shuffle, advancedRepeat = AdvancedRepeat(
                queueEnd = if (loop) QueueEnd.Repeat else QueueEnd.Stop, reshuffle = reshuffle)) }
            barrier()
            val runtime = Runtime.getRuntime()
            repeat(3) { runtime.gc(); delay(100) }
            val before = runtime.totalMemory() - runtime.freeMemory()
            val peak = java.util.concurrent.atomic.AtomicLong(before)
            val beforeGc = android.os.Debug.getRuntimeStats()
            val sampler = launch(Dispatchers.Default) {
                var samples = 0
                while (isActive) {
                    peak.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }
                    if (++samples % 10 == 0) android.util.Log.i("Window957Sample", "entries=$entries tracks=$songs peakBytes=${peak.get()} maxBytes=${runtime.maxMemory()}")
                    delay(10)
                }
            }
            val started = android.os.SystemClock.elapsedRealtime()
            queues.attach(player)
            try {
                withTimeout(300000) { while (!withContext(Dispatchers.Main) { player.mediaItemCount > 0 }) delay(10) }
                barrier()
            } finally { sampler.cancelAndJoin() }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            val built = itemBuilds.get()
            val window = queues.state.value.library.active!!.journey!!.window()
            repeat(3) { runtime.gc(); delay(100) }
            val after = runtime.totalMemory() - runtime.freeMemory()
            val afterGc = android.os.Debug.getRuntimeStats()
            val line = "entries=$entries tracks=$songs shuffle=$shuffle loop=$loop reshuffle=$reshuffle beforeBytes=$before peakBytes=${peak.get()} settledBytes=$after maxBytes=${runtime.maxMemory()} built=$built visits=${window.size} activationMs=$elapsed gcBefore=$beforeGc gcAfter=$afterGc"
            android.util.Log.i("Window957", line)
            File(nativeEvidenceDirectory("window-957", InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "activation.txt").appendText(line + "\n")
            withContext(Dispatchers.Main) {
                assertEquals(window.map { it.entryId }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
                assertEquals("Only the native window may own occurrence templates", window.map { it.entryId }.distinct().size, built)
                assertTrue(player.mediaItemCount <= 129)
            }
            val edits = arguments.getString("permutationEdits", "0")!!.toInt()
            if (edits > 0) {
                require(shuffle && loop && !reshuffle)
                val editPeak = java.util.concurrent.atomic.AtomicLong(runtime.totalMemory() - runtime.freeMemory())
                val editGc = android.os.Debug.getRuntimeStats()
                val editStarted = android.os.SystemClock.elapsedRealtime()
                val editSampler = launch(Dispatchers.Default) {
                    while (isActive) {
                        editPeak.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }
                        delay(100)
                    }
                }
                var retained = 0
                var slots = 0L
                try { repeat(edits) {
                    val done = CompletableDeferred<Boolean>()
                    queues.act(List(4) { track }, SongTap.PlayNext) { done.complete(it) }
                    assertTrue(withTimeout(300000) { done.await() })
                    val counts = withContext(Dispatchers.Default) {
                        val queue = queues.state.value.library.active!!
                        val permutations = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<List<String>, Boolean>())
                        permutations.addAll(queue.journey!!.plans.values)
                        assertEquals(3, queue.journey.plans.size)
                        assertTrue(permutations.size <= 3)
                        val references = permutations.sumOf { it.size.toLong() }
                        assertTrue(references <= 3L * queue.entries.size)
                        permutations.size to references
                    }
                    retained = maxOf(retained, counts.first); slots = maxOf(slots, counts.second)
                } } finally { editSampler.cancelAndJoin() }
                val result = "initialEntries=$entries finalEntries=${queues.state.value.library.active!!.entries.size} tracks=$songs edits=$edits maxPermutations=$retained retainedReferenceSlots=$slots peakBytes=${editPeak.get()} settledBytes=${runtime.totalMemory() - runtime.freeMemory()} maxBytes=${runtime.maxMemory()} elapsedMs=${android.os.SystemClock.elapsedRealtime() - editStarted} gcBefore=$editGc gcAfter=${android.os.Debug.getRuntimeStats()}"
                android.util.Log.i("Permutation957", result)
                File(nativeEvidenceDirectory("window-957", InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "permutation-edits.txt").appendText(result + "\n")
            }
        }
    }

    @Test fun routineControllerCaptureReadsNoEntriesAtEitherEndOfALargeQueue() {
        for (initialIndex in listOf(0, 49_990)) fixture(duplicateOccurrences = true, occurrences = 50_000,
            initialIndex = initialIndex, timeoutMs = 300000) {
            awaitMain { player.engine.playbackState == Player.STATE_READY }
            barrier()
            withContext(Dispatchers.Main) {
                val mutable = QueueController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val state = mutable.get(queues) as kotlinx.coroutines.flow.MutableStateFlow<QueueUiState>
                val before = state.value
                val queue = before.library.active!!
                var reads = 0
                val counted = object : AbstractList<QueueEntry>() {
                    override val size get() = queue.entries.size
                    override fun get(index: Int): QueueEntry { reads++; return queue.entries[index] }
                }
                val capture = QueueController::class.java.getDeclaredMethod("captureNativeState", kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
                try {
                    player.engine.seekTo(2000)
                    state.value = before.copy(library = before.library.update(queue.copy(entries = counted, positionMs = 2001)))
                    assertSame(counted, state.value.library.active!!.entries)
                    reads = 0
                    repeat(20) { kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { capture.invoke(queues, it) } }
                    assertEquals("Routine capture must not inspect occurrence membership", 0, reads)
                    val captured = state.value.library.active!!
                    assertSame(queue.journey, captured.journey)
                    assertEquals(queue.currentEntryId, captured.currentEntryId)
                    assertEquals(2000L, captured.positionMs)
                } finally { state.value = before }
            }
        }
    }

    @Test fun controllerCaptureRejectsForeignRemovedAndBranchedTokensAndKeepsDuplicateIdentity() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        barrier()
        withContext(Dispatchers.Main) {
            val mutable = QueueController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val state = mutable.get(queues) as kotlinx.coroutines.flow.MutableStateFlow<QueueUiState>
            val before = state.value
            val queue = before.library.active!!
            val native = player.currentMediaItem!!
            val capture = QueueController::class.java.getDeclaredMethod("capturedLibrary", Player::class.java, kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
            suspend fun captureFrom(target: ListeningQueue, item: MediaItem, admitted: Boolean): QueueLibrary {
                state.value = before.copy(library = before.library.update(target))
                val source = object : ForwardingPlayer(player) {
                    override fun getCurrentMediaItem() = item
                    override fun getCurrentPosition(): Long {
                        assertTrue("Rejected visits must not read native position", admitted)
                        return 4321
                    }
                }
                return kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { capture.invoke(queues, source, it) }
            }
            try {
                val extras = android.os.Bundle(native.mediaMetadata.extras).apply { putString("queueId", second.id) }
                val foreign = native.buildUpon().setMediaMetadata(native.mediaMetadata.buildUpon().setExtras(extras).build()).build()
                assertSame(queue, captureFrom(queue, foreign, false).active)
                val removed = QueueEdits.remove(queue, queue.currentEntryId!!).let {
                    it.copy(journey = it.journey!!.reconcile(it.entries.map { entry -> entry.id }, it.currentEntryId,
                        JourneyModes.from(before.settings)))
                }
                assertSame(removed, captureFrom(removed, native, false).active)
                val branched = queue.copy(journey = queue.journey!!.select(queue.currentEntryId!!, queue.entries.map { it.id }))
                assertTrue(branched.journey!!.window().none { it.token == native.queueVisit })
                assertSame(branched, captureFrom(branched, native, false).active)
                val next = player.getMediaItemAt(1)
                assertEquals(native.mediaMetadata.extras!!.getString("trackId"), next.mediaMetadata.extras!!.getString("trackId"))
                assertNotEquals(native.mediaId, next.mediaId)
                val captured = captureFrom(queue, next, true).active!!
                assertEquals(next.mediaId, captured.currentEntryId)
                assertEquals(next.queueVisit, captured.journey!!.current!!.token)
                assertEquals(4321L, captured.positionMs)
            } finally { state.value = before }
        }
    }

    @Test fun restoredInvalidJourneyIsNormalizedBeforeNativeCaptureAdmission() = fixture(attach = false, blockRestore = true, duplicateOccurrences = true, occurrences = 160) {
        dao.restore!!.entered.await()
        val invalid = first.copy(journey = QueueJourney(history = listOf(QueueVisit("removed", 0)), cursor = 0))
        IntentDao::class.java.getDeclaredField("saved").apply { isAccessible = true }
            .set(dao, QueueLibrary(listOf(invalid, second), first.id))
        dao.restore.release.complete(Unit)
        awaitMain { !queues.state.value.loading }
        assertEquals(listOf(null, null), queues.state.value.library.active!!.artworkNeighbors())
        assertTrue(queues.state.value.library.active!!.artworkPreloadUris().isEmpty())
        queues.attach(player)
        barrier()
        withContext(Dispatchers.Main) {
            val active = queues.state.value.library.active!!
            assertEquals(first.currentEntryId, active.journey!!.current!!.entryId)
            assertTrue(active.journey.history.none { it.entryId == "removed" })
            assertEquals(active.journey.current!!.token, player.currentMediaItem!!.queueVisit)
            assertEquals(active.journey.window().map { it.token }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).queueVisit })
            active.journey.window().forEach { visit ->
                val ordinal = active.journey.sourceOrdinal(active.ids, visit.entryId)
                assertNotNull("Every exposed visit must have a prepared source ordinal", ordinal)
                assertEquals(visit.entryId, active.entries[ordinal!!].id)
            }
            assertNull(queues.state.value.problem)
            assertEquals(listOf(track.uri, null), queues.state.value.library.active!!.artworkNeighbors())
            assertEquals(listOf(track.uri), queues.state.value.library.active!!.artworkPreloadUris())
        }
        queues.seek(1234)
        barrier()
        assertEquals(1234L, dao.read().active!!.positionMs)
        assertTrue(dao.read().active!!.journey!!.history.none { it.entryId == "removed" })
    }

    @Test fun settingsAndPreparedJourneyPublishTogetherAfterWorkerPreparation() = fixture(attach = false, duplicateOccurrences = true, occurrences = 160) {
        val gate = Gate()
        val firstRead = java.util.concurrent.atomic.AtomicBoolean(true)
        val entries = object : AbstractList<QueueEntry>() {
            override val size = first.entries.size
            override fun get(index: Int): QueueEntry {
                if (firstRead.getAndSet(false)) {
                    assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                    runBlocking { gate.await() }
                }
                return first.entries[index]
            }
        }
        val warm = withContext(Dispatchers.Default) { QueueJourney.start(first.ids, first.currentEntryId, JourneyModes()) }
        val mutable = QueueController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = mutable.get(queues) as kotlinx.coroutines.flow.MutableStateFlow<QueueUiState>
        withContext(Dispatchers.Main) { state.value = state.value.copy(library = state.value.library.update(first.copy(entries = entries, journey = warm))) }
        val before = state.value
        val pairs = CopyOnWriteArrayList<Pair<JourneyModes, JourneyModes>>()
        val observer = launch(Dispatchers.Unconfined) { queues.state.collect { snapshot ->
            snapshot.library.active?.journey?.let { pairs += JourneyModes.from(snapshot.settings) to it.modes }
        } }
        try {
            try {
                queues.settings { it.copy(shuffle = true, repeat = QueueRepeat.All) }
                gate.entered.await()
                withContext(Dispatchers.Main) {
                    assertSame(before.settings, state.value.settings)
                    assertSame(before.library, state.value.library)
                }
            } finally { gate.release.complete(Unit) }
            barrier()
            withContext(Dispatchers.Main) {
                assertTrue(state.value.settings.shuffle)
                assertEquals(JourneyModes(shuffle = true, loop = true), state.value.library.active!!.journey!!.modes)
                assertNull(state.value.problem)
                assertTrue(pairs.isNotEmpty())
                assertTrue("No publication may pair new settings with an old journey", pairs.all { it.first == it.second })
            }
        } finally { gate.release.complete(Unit); observer.cancelAndJoin() }
    }

    @Test fun transportOnlyPlayKeepsSuspendedColdSourcePreparationWithoutAPlayer() = coldPlayDuringActivation(false)
    @Test fun sourceBoundPlaySupersedesSuspendedColdPreparationAndSynchronizesItsSource() = coldPlayDuringActivation(true)

    private fun coldPlayDuringActivation(differentSource: Boolean) = fixture(attach = false) {
        queues.settings { it.copy(switching = QueueSwitch.Resume) }; barrier()
        val secondReads = AtomicInteger()
        val secondMainReads = AtomicInteger()
        val coldSecond = if (differentSource) second.copy(entries = object : AbstractList<QueueEntry>() {
            override val size = second.entries.size
            override fun get(index: Int): QueueEntry {
                secondReads.incrementAndGet()
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) secondMainReads.incrementAndGet()
                return second.entries[index]
            }
        }) else second
        val mutable = QueueController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = mutable.get(queues) as kotlinx.coroutines.flow.MutableStateFlow<QueueUiState>
        val intents = QueueController::class.java.getDeclaredField("intents").apply { isAccessible = true }.get(queues) as PlaybackIntents
        val activate = QueueController::class.java.declaredMethods.single { it.name == "activate" }.apply { isAccessible = true }
        val command = QueueController::class.java.declaredMethods.single { it.name == "editCommand" }.apply { isAccessible = true }
        val gate = Gate()
        val firstRead = java.util.concurrent.atomic.AtomicBoolean(true)
        val entries = object : AbstractList<QueueEntry>() {
            override val size = first.entries.size
            override fun get(index: Int): QueueEntry {
                if (firstRead.getAndSet(false)) {
                    assertNotEquals("Cold preparation must run off Main", android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                    runBlocking { gate.await() }
                }
                return first.entries[index]
            }
        }
        val cold = withContext(Dispatchers.Default) { QueueJourney.start(first.ids, first.currentEntryId, JourneyModes()).copy() }
        withContext(Dispatchers.Main) { state.value = state.value.copy(library = state.value.library
            .update(first.copy(entries = entries, journey = cold)).update(coldSecond)) }
        val pending = async(Dispatchers.Main) {
            val action: suspend () -> Unit = {
                val before = state.value.library
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> {
                    activate.invoke(queues, true, false, true, true, null, it)
                }
                if (differentSource) assertSame("A different bound source rejects obsolete preparation", before, state.value.library)
                else {
                    val active = state.value.library.active!!
                    assertEquals(before.activeId, active.id)
                    assertSame(before.active!!.entries, active.entries)
                    assertEquals(before.active!!.currentEntryId, active.currentEntryId)
                    assertEquals(before.active!!.positionMs, active.positionMs)
                    assertEquals(active.currentEntryId, active.journey!!.current!!.entryId)
                    assertEquals(0, active.journey.cursor)
                }
                assertNull(player.currentMediaItem)
            }
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> {
                command.invoke(queues, false, {}, action, it)
            }
        }
        try {
            gate.entered.await()
            withContext(Dispatchers.Main) {
                if (differentSource) {
                    intents.accept(true, documentIdentity(second.entries.single().track.id))
                    queues.switchTo(second.id)
                } else queues.playbackRequested(true)
            }
        } finally { gate.release.complete(Unit) }
        pending.await()
        barrier()
        withContext(Dispatchers.Main) {
            val expected = if (differentSource) second else first
            assertEquals(expected.id, state.value.library.activeId)
            assertEquals(expected.currentEntryId, state.value.library.active!!.currentEntryId)
            assertEquals(expected.positionMs, state.value.library.active!!.positionMs)
            assertEquals(documentIdentity(expected.entries.single().track.id), intents.pending()!!.source)
            assertTrue(intents.pending()!!.play)
            if (differentSource) assertEquals(second.journey, state.value.library.active!!.journey)
            else assertNotNull(state.value.library.active!!.journey!!.current)
            if (differentSource) { assertTrue(secondReads.get() > 0); assertEquals(0, secondMainReads.get()) }
            assertTrue(state.value.library.queues.any { it.id == first.id })
            assertNull(player.currentMediaItem)
        }
    }

    @Test fun savePreparationRejectsOwnerLossBeforePublicationWithoutRestampingItsClosure() {
        for (ownerLoss in listOf("attach", "detach", "source")) fixture(duplicateOccurrences = true) {
            queues.settings { it.copy(switching = QueueSwitch.Resume) }; barrier()
            awaitMain { player.engine.playbackState == Player.STATE_READY }
            val mutable = QueueController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val state = mutable.get(queues) as kotlinx.coroutines.flow.MutableStateFlow<QueueUiState>
            val intents = QueueController::class.java.getDeclaredField("intents").apply { isAccessible = true }.get(queues) as PlaybackIntents
            val save = QueueController::class.java.declaredMethods.single { it.name == "saveStartingPlayback" }.apply { isAccessible = true }
            val gate = Gate()
            val firstRead = java.util.concurrent.atomic.AtomicBoolean(true)
            val entries = object : AbstractList<QueueEntry>() {
                override val size = first.entries.size
                override fun get(index: Int): QueueEntry {
                    if (firstRead.getAndSet(false)) runBlocking { gate.await() }
                    return first.entries[index]
                }
            }
            val starts = AtomicInteger()
            withContext(Dispatchers.Main) { player.selectionCalls.clear() }
            val finished = CompletableDeferred<Unit>()
            var rejected: Throwable? = null
            val submit = QueueController::class.java.declaredMethods.single { it.name == "submit" }.apply { isAccessible = true }
            val action: suspend () -> Unit = { try {
                val before = state.value.library
                val next = before.update(before.active!!.copy(entries = entries, currentEntryId = "duplicate-1", journey = before.active!!.journey!!.copy()))
                val start: suspend () -> Unit = { starts.incrementAndGet(); player.setPlayWhenReady(true) }
                assertFalse(kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Boolean> { save.invoke(queues, next, start, it) })
                assertNull(state.value.problem)
                assertSame(before, state.value.library)
                assertEquals(0, starts.get())
                assertTrue(player.selectionCalls.isEmpty())
            } catch (failure: Throwable) { rejected = failure; throw failure } finally { finished.complete(Unit) } }
            withContext(Dispatchers.Main) { submit.invoke(queues, intents.accept(), { _: Exception -> }, false, action) }
            var detached: Deferred<Unit>? = null
            try {
                gate.entered.await()
                withContext(Dispatchers.Main) {
                    if (ownerLoss == "source") {
                        intents.accept(true, documentIdentity(second.entries.single().track.id))
                        queues.switchTo(second.id)
                    } else {
                        if (ownerLoss == "detach") detached = queues.detach(player)
                        queues.attach(player)
                    }
                }
            } finally { gate.release.complete(Unit) }
            withTimeout(10000) { finished.await() }
            assertNull("Supersession is not a persistence failure", rejected)
            withContext(Dispatchers.Main) { assertNull("Supersession must not report a failed save", state.value.problem) }
            detached?.await(); barrier()
            withContext(Dispatchers.Main) {
                val active = state.value.library.active!!
                assertEquals(if (ownerLoss == "source") second.id else first.id, active.id)
                assertEquals(active.journey!!.current!!.token, player.currentMediaItem!!.queueVisit)
                assertEquals(0, starts.get())
                assertNull(state.value.problem)
                assertTrue(state.value.library.queues.any { it.id == first.id })
            }
        }
    }

    @Test fun generationLossDuringEditMembershipPreparationRejectsTheLibraryAndAnswer() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        withHeldEdit("absent") {
            val save = QueueController::class.java.declaredMethods.single { it.name == "saveStartingPlayback" }.apply { isAccessible = true }
            val held = QueueController::class.java.getDeclaredMethod("holdsForEdit").apply { isAccessible = true }
            val replacement = newPlayer()
            val gate = Gate()
            val firstRead = java.util.concurrent.atomic.AtomicBoolean(true)
            val starts = AtomicInteger()
            var detached: Deferred<Unit>? = null
            val saving = async { command {
                val before = queues.state.value.library
                val active = before.active!!
                assertNotNull(active.journey)
                val rows = active.entries + QueueEntry(id = "edited-source", track = track.copy(id = "absent"))
                val entries = object : AbstractList<QueueEntry>() {
                    override val size = rows.size
                    override fun get(index: Int): QueueEntry {
                        if (firstRead.getAndSet(false)) {
                            assertFalse(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                            assertTrue("Gate must suspend membership, not journey reconciliation", Thread.currentThread().stackTrace.any {
                                it.className.contains("QueueController") && it.className.contains("membership")
                            })
                            runBlocking { gate.await() }
                        }
                        return rows[index]
                    }
                }
                // A null journey bypasses reconciliation reads; membership owns the first entry read.
                val next = before.update(active.copy(name = "Preparing membership", entries = entries,
                    currentEntryId = null, journey = null, order = SavedOrder(rows.map { it.id })))
                val start: suspend () -> Unit = { starts.incrementAndGet(); replacement.setPlayWhenReady(true) }
                assertFalse(kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Boolean> { save.invoke(queues, next, start, it) })
                assertSame(before, queues.state.value.library)
                assertFalse(held.invoke(queues) as Boolean)
                assertEquals(0, starts.get())
                assertTrue(replacement.selectionCalls.isEmpty())
                assertNull(replacement.currentMediaItem)
            } }
            try {
                gate.entered.await()
                withContext(Dispatchers.Main) {
                    assertFalse(held.invoke(queues) as Boolean)
                    detached = queues.detach(player)
                    queues.attach(replacement)
                    assertNull(replacement.currentMediaItem)
                }
                gate.release.complete(Unit)
                saving.await()
                detached?.await(); barrier()
                withContext(Dispatchers.Main) {
                    assertSame(first.entries, queues.state.value.library.active!!.entries)
                    assertFalse(held.invoke(queues) as Boolean)
                    assertEquals(first.currentEntryId, replacement.currentMediaItem!!.mediaId)
                    assertFalse(replacement.playWhenReady)
                    assertEquals(0, starts.get())
                }
            } finally { gate.release.complete(Unit); saving.cancelAndJoin() }
        }
    }

    @Test fun rejectedCreationReportsFalseAndKeepsItsPendingRequest() {
        val gate = Gate()
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val workerEntries = AtomicInteger()
        val checkpoint = object : ThreadContextElement<Unit> {
            override val key = object : kotlin.coroutines.CoroutineContext.Key<ThreadContextElement<Unit>> {}
            override fun updateThreadContext(context: kotlin.coroutines.CoroutineContext) {
                if (armed.get() && context[kotlin.coroutines.ContinuationInterceptor] == Dispatchers.Default && workerEntries.incrementAndGet() == 2)
                    runBlocking { gate.await() }
            }
            override fun restoreThreadContext(context: kotlin.coroutines.CoroutineContext, oldState: Unit) = Unit
        }
        fixture(duplicateOccurrences = true, actorContext = checkpoint) {
            awaitMain { player.engine.playbackState == Player.STATE_READY }
            queues.settings { it.copy(maximum = 2) }; barrier()
            val completed = CompletableDeferred<Boolean>()
            queues.act(List(2) { track }, SongTap.NewQueue) { completed.complete(it) }
            awaitMain { queues.state.value.pending != null }
            val before = queues.state.value
            armed.set(true)
            queues.confirmCreation(setOf(second.id))
            try {
                gate.entered.await()
                withContext(Dispatchers.Main) {
                    assertSame(before.pending, queues.state.value.pending)
                    queues.attach(player)
                }
            } finally { armed.set(false); gate.release.complete(Unit) }
            assertFalse(withTimeout(10000) { completed.await() })
            command {}
            withContext(Dispatchers.Main) {
                val after = queues.state.value
                assertSame(before.pending, after.pending)
                assertEquals(before.library, after.library)
                assertEquals(before.browsedId, after.browsedId)
                assertEquals(before.playerRequest, after.playerRequest)
                assertEquals(first.id, player.currentMediaItem!!.listeningQueue)
                assertFalse(player.playWhenReady)
                assertNull(after.problem)
            }
        }
    }

    @Test fun pausedPlayNextPublishesArtworkBeforeSuspendedTimelineResolution() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        val song = second.entries.single().track
        val gate = store.gateNextSnapshot()
        val observed = CompletableDeferred<List<String?>>()
        val observer = launch(Dispatchers.Main.immediate) { queues.state.collect { snapshot ->
            val queue = snapshot.library.active!!
            val next = queue.journey?.next
            if (next != null && queue.entries.firstOrNull { it.id == next.entryId }?.track?.id == song.id)
                observed.complete(queues.state.value.library.active!!.artworkNeighbors())
        } }
        val completed = CompletableDeferred<Boolean>()
        try {
            queues.act(listOf(song), SongTap.PlayNext) { completed.complete(it) }
            gate.entered.await()
            assertEquals(song.uri, withTimeout(10000) { observed.await() }.first())
            withContext(Dispatchers.Main) {
                assertFalse(player.playWhenReady)
                assertTrue(queues.state.value.library.active!!.artworkPreloadUris().contains(song.uri))
                val next = queues.state.value.library.active!!.journey!!.next!!
                assertTrue((0 until player.mediaItemCount).none { player.getMediaItemAt(it).mediaId == next.entryId })
            }
        } finally { gate.release.complete(Unit); observer.cancelAndJoin() }
        assertTrue(withTimeout(10000) { completed.await() })
        barrier()
        assertEquals(song.uri, queues.state.value.library.active!!.artworkNeighbors().first())
    }

    @Test fun publishedSelectionIsSynchronizedByTheReattachedOwnerAfterOldIoIsDropped() {
        for (replacePlayer in listOf(false, true)) fixture(duplicateOccurrences = true) {
            queues.playbackRequested(true); barrier()
            val gate = store.gateNextSnapshot()
            withContext(Dispatchers.Main) { player.selectionCalls.clear() }
            queues.selectEntry("duplicate-1")
            var target: RecordingPlayer? = null
            var detached: Deferred<Unit>? = null
            lateinit var committed: QueueVisit
            try {
                gate.entered.await()
                committed = queues.state.value.library.active!!.journey!!.current!!
                assertEquals("duplicate-1", committed.entryId)
                withContext(Dispatchers.Main) {
                    queues.playbackRequested(false)
                    target = if (replacePlayer) newPlayer().also {
                        detached = queues.detach(player); player.engine.pause()
                    } else player
                    queues.attach(target!!)
                }
            } finally { gate.release.complete(Unit) }
            detached?.await(); barrier()
            withContext(Dispatchers.Main) {
                val attached = target!!
                val active = queues.state.value.library.active!!
                assertEquals(committed, active.journey!!.current)
                assertEquals(committed.token, attached.currentMediaItem!!.queueVisit)
                assertEquals(0L, attached.currentPosition)
                assertFalse(attached.playWhenReady)
                assertEquals(first.entries, active.entries)
                assertEquals("load", attached.selectionCalls.first { !it.startsWith("play:") })
                if (replacePlayer) assertTrue("Old IO cannot seek or play the detached player", player.selectionCalls.isEmpty())
                assertNull(queues.state.value.problem)
            }
        }
    }

    @Test fun finishResetKeepsItsOriginalOwnerThroughSourceLookupAndRestartPlanning() {
        for (worker in listOf(1, 2)) for (sourceChange in listOf(false, true)) {
            val gate = Gate()
            val armed = java.util.concurrent.atomic.AtomicBoolean(false)
            val workers = AtomicInteger()
            val checkpoint = object : ThreadContextElement<Unit> {
                override val key = object : kotlin.coroutines.CoroutineContext.Key<ThreadContextElement<Unit>> {}
                override fun updateThreadContext(context: kotlin.coroutines.CoroutineContext) {
                    if (armed.get() && context[kotlin.coroutines.ContinuationInterceptor] == Dispatchers.Default && workers.incrementAndGet() == worker)
                        runBlocking { gate.await() }
                }
                override fun restoreThreadContext(context: kotlin.coroutines.CoroutineContext, oldState: Unit) = Unit
            }
            fixture(duplicateOccurrences = true, actorContext = checkpoint) {
                queues.settings { it.copy(switching = QueueSwitch.Resume) }; barrier()
                repeat(2) { queues.next(); barrier() }
                awaitMain { player.engine.playbackState == Player.STATE_READY }
                val mutable = QueueController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val state = mutable.get(queues) as kotlinx.coroutines.flow.MutableStateFlow<QueueUiState>
                val intents = QueueController::class.java.getDeclaredField("intents").apply { isAccessible = true }.get(queues) as PlaybackIntents
                val finish = QueueController::class.java.declaredMethods.single { it.name == "finishQueue" }.apply { isAccessible = true }
                withContext(Dispatchers.Main) { player.selectionCalls.clear() }
                val pending = async { command {
                    val before = state.value.library
                    armed.set(true)
                    val failure = runCatching { kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> {
                        finish.invoke(queues, AdvancedRepeat(queueEnd = QueueEnd.Stop, resetToFirst = true), false, it)
                    } }.also { armed.set(false) }.exceptionOrNull()
                    if (failure != null) assertTrue(failure is IllegalStateException)
                    assertSame(before, state.value.library)
                    assertEquals("duplicate-2", state.value.library.active!!.currentEntryId)
                    assertTrue(player.selectionCalls.isEmpty())
                } }
                try {
                    gate.entered.await()
                    assertEquals(worker, workers.get())
                    withContext(Dispatchers.Main) {
                        if (sourceChange) {
                            intents.accept(true, documentIdentity(second.entries.single().track.id))
                            queues.switchTo(second.id)
                        } else queues.attach(player)
                    }
                } finally { gate.release.complete(Unit) }
                pending.await(); barrier()
                withContext(Dispatchers.Main) {
                    val active = state.value.library.active!!
                    assertEquals(if (sourceChange) second.id else first.id, active.id)
                    assertEquals(active.journey!!.current!!.token, player.currentMediaItem!!.queueVisit)
                    if (!sourceChange) assertEquals("duplicate-2", active.currentEntryId)
                }
            }
        }
    }

    @Test fun timelineResolutionAfterGenerationChangeCannotPublishToTheSamePlayer() = fixture {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        barrier()
        val resolved = QueueController::class.java.getDeclaredField("resolved").apply { isAccessible = true }
        val timeline = QueueController::class.java.getDeclaredField("timeline").apply { isAccessible = true }.get(queues)
        val templates = QueueTimeline::class.java.getDeclaredField("templates").apply { isAccessible = true }
        val sync = QueueController::class.java.declaredMethods.single { it.name == "syncTimeline" }.apply { isAccessible = true }
        val command = QueueController::class.java.declaredMethods.single { it.name == "editCommand" }.apply { isAccessible = true }
        val gate = store.gateNextSnapshot()
        store.snapshot = LibrarySnapshot(tracks = listOf(track.copy(title = "Late generation")))
        val pending = async(Dispatchers.Main) {
            val action: suspend () -> Unit = {
                val oldResolved = resolved.get(queues)
                val oldTemplates = templates.get(timeline)
                val oldNative = player.currentMediaItem
                val published = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Boolean> {
                    sync.invoke(queues, false, true, null, queues.state.value.settings, null, it)
                }
                assertFalse(published)
                assertSame(oldResolved, resolved.get(queues))
                assertSame(oldTemplates, templates.get(timeline))
                assertSame(oldNative, player.currentMediaItem)
            }
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> {
                command.invoke(queues, false, {}, action, it)
            }
        }
        try {
            gate.entered.await()
            withContext(Dispatchers.Main) { queues.attach(player) }
        } finally { gate.release.complete(Unit) }
        pending.await()
        barrier()
    }

    @Test fun staleWindowResolutionCannotPublishForANewerActiveQueue() = fixture(duplicateOccurrences = true) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        barrier()
        val resolved = QueueController::class.java.getDeclaredField("resolved").apply { isAccessible = true }
        val timeline = QueueController::class.java.getDeclaredField("timeline").apply { isAccessible = true }.get(queues)
        val templates = QueueTimeline::class.java.getDeclaredField("templates").apply { isAccessible = true }
        val mutable = QueueController::class.java.getDeclaredField("mutable").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = mutable.get(queues) as kotlinx.coroutines.flow.MutableStateFlow<QueueUiState>
        val sync = QueueController::class.java.declaredMethods.single { it.name == "syncTimeline" }.apply { isAccessible = true }
        val command = QueueController::class.java.declaredMethods.single { it.name == "editCommand" }.apply { isAccessible = true }
        for (differentQueue in listOf(false, true)) {
            val gate = Gate()
            val pending = async(Dispatchers.Main) {
                val action: suspend () -> Unit = {
                    val before = state.value
                    val oldResolved = resolved.get(queues)
                    val oldTemplates = templates.get(timeline)
                    val oldNative = player.currentMediaItem
                    store.snapshot = LibrarySnapshot(tracks = listOf(track.copy(title = "Stale prepared title")))
                    store.gateNextSnapshot(gate)
                    val published = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Boolean> {
                        sync.invoke(queues, false, true, null, queues.state.value.settings, null, it)
                    }
                    assertFalse("A stale window must decline publication", published)
                    assertSame(oldResolved, resolved.get(queues))
                    assertSame(oldTemplates, templates.get(timeline))
                    assertSame(oldNative, player.currentMediaItem)
                    state.value = before
                }
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> {
                    command.invoke(queues, false, {}, action, it)
                }
            }
            gate.entered.await()
            withContext(Dispatchers.Main) {
                val before = state.value
                state.value = before.copy(library = if (differentQueue) before.library.copy(activeId = second.id)
                    else before.library.update(before.library.active!!.copy(name = "New owner")))
            }
            gate.release.complete(Unit)
            pending.await()
        }
    }

    @Test fun twoNativeArrivalsDuringResolutionReconcileToTheLatestQueueWithoutRewind() = fixture(duplicateOccurrences = true, occurrences = 6) {
        awaitMain { player.engine.playbackState == Player.STATE_READY }
        barrier()
        val visits = queues.state.value.library.active!!.journey!!.window()
        val firstRead = store.gateNextSnapshot()
        withContext(Dispatchers.Main) { player.seekVisits.clear() }
        val pending = CompletableDeferred<Boolean>()
        queues.reverse(first.id) { pending.complete(it) }
        firstRead.entered.await()
        withContext(Dispatchers.Main) { player.engine.seekTo(1, 321) }
        val secondRead = store.gateNextSnapshot()
        firstRead.release.complete(Unit)
        secondRead.entered.await()
        withContext(Dispatchers.Main) { player.engine.seekTo(2, 777) }
        secondRead.release.complete(Unit)
        assertTrue(pending.await())
        barrier()
        withContext(Dispatchers.Main) {
            val current = queues.state.value.library.active!!
            assertEquals(visits[2], current.journey!!.current)
            assertEquals(visits[2].token, player.currentMediaItem!!.queueVisit)
            assertEquals(777L, player.currentPosition)
            assertTrue("Reconciliation must never seek the decoder backward", player.seekVisits.isEmpty())
        }
    }

    @Test fun resolvedVisitsRefreshMetadataAndGrantsWithoutActivation() = refreshWindow(3)
    @Test fun enteringVisitsReadFreshMetadataAndGrantsOffMain() = refreshWindow(160)

    private fun refreshWindow(occurrences: Int) {
        val permissions = MutablePermissions()
        fixture(duplicateOccurrences = true, occurrences = occurrences, permissions = permissions) {
            val alternate = second.entries.single().track.uri
            store.snapshot = LibrarySnapshot(tracks = listOf(track.copy(title = "Fresh window title", artist = "Fresh window artist",
                locations = listOf(TrackLocation("root-one", track.uri, file.name), TrackLocation("root-two", alternate, "second.mp3")))))
            permissions.first = false
            val initialIds = withContext(Dispatchers.Main) { (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet() }
            assertTrue(initialIds.size <= 129)
            assertTrue(initialIds.size < occurrences || occurrences == 3)
            val mainReads = store.mainReads.get()
            val mainGrants = permissions.mainReads.get()
            queues.next(); barrier()
            withContext(Dispatchers.Main) {
                val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                assertTrue(items.any { it.mediaId in initialIds })
                if (occurrences > 129) assertTrue("A new occurrence enters the native window", items.any { it.mediaId !in initialIds })
                else assertEquals(initialIds, items.map { it.mediaId }.toSet())
                items.forEach { item ->
                    assertEquals("Fresh window title", item.mediaMetadata.title.toString())
                    assertEquals("Fresh window artist", item.mediaMetadata.artist.toString())
                    assertEquals(alternate, item.localConfiguration!!.uri.toString())
                    assertEquals("origin-" + item.mediaId.substringAfterLast('-'), item.mediaMetadata.extras!!.getString("sourcePlaylistId"))
                }
                assertEquals(first.id, queues.state.value.library.activeId)
                assertEquals("duplicate-1", player.currentMediaItem!!.mediaId)
            }
            assertEquals("Metadata reads stay off Main", mainReads, store.mainReads.get())
            assertEquals("Grant reads stay off Main", mainGrants, permissions.mainReads.get())
            assertTrue(permissions.reads.get() > 0)
            assertNull(queues.state.value.problem)
        }
    }

    @Test fun rapidTransportReportsCaptureAndBacklogLatencyAtOperatorScale() {
        val arguments = InstrumentationRegistry.getArguments()
        val entries = arguments.getString("queueEntries", "50000")!!.toInt()
        val songs = arguments.getString("libraryTracks", "50000")!!.toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var database: androidx.room.RoomDatabase? = null
        try {
            fixture(attach = false, duplicateOccurrences = true, occurrences = entries, librarySize = songs,
                initialIndex = arguments.getString("initialIndex")?.toInt() ?: entries - 10, timeoutMs = 300000, playbackStore = { tracks ->
                    val db = androidx.room.Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
                    database = db
                    RoomLibraryStore(db.libraryDao()).also { store -> runBlocking {
                        store.replaceRoot(ScanResult(FolderSelection("file:///window-957", "Latency fixture"), tracks, 0))
                    } }
                }) {
                val shuffle = arguments.getString("journeyShuffle", "false").toBoolean()
                val loop = arguments.getString("journeyLoop", "false").toBoolean()
                val reshuffle = arguments.getString("journeyReshuffle", "false").toBoolean()
                queues.settings { it.copy(shuffle = shuffle, advancedRepeat = AdvancedRepeat(
                    queueEnd = if (loop) QueueEnd.Repeat else QueueEnd.Stop, reshuffle = reshuffle)) }
                barrier()
                queues.attach(player)
                withTimeout(300000) {
                    while (!withContext(Dispatchers.Main) { player.engine.playbackState == Player.STATE_READY }) delay(10)
                }
                val captures = mutableListOf<Double>()
                val acknowledgements = mutableListOf<Double>()
                val runtime = Runtime.getRuntime()
                val peak = java.util.concurrent.atomic.AtomicLong(runtime.totalMemory() - runtime.freeMemory())
                val sampler = launch(Dispatchers.Default) {
                    while (isActive) {
                        peak.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }
                        delay(100)
                    }
                }
                val gcBefore = android.os.Debug.getRuntimeStats()
                val started = android.os.SystemClock.elapsedRealtimeNanos()
                val done = CompletableDeferred<Unit>()
                withContext(Dispatchers.Main) {
                    val seekVisit = queues.state.value.library.active!!.journey!!.next!!.token
                    repeat(20) { index ->
                        queues.next()
                        val submitted = android.os.SystemClock.elapsedRealtimeNanos()
                        queues.requestSeek(2000L + index, seekVisit) { applied, _ ->
                            assertTrue(applied)
                            val capture = android.os.SystemClock.elapsedRealtimeNanos()
                            acknowledgements += (capture - submitted) / 1_000_000.0
                            assertNull("Every earlier capture reached native sync", player.timelineRead)
                            player.timelineRead = {
                                captures += (android.os.SystemClock.elapsedRealtimeNanos() - capture) / 1_000_000.0
                            }
                        }
                        queues.previous(alwaysSong = true)
                    }
                    queues.act(emptyList(), SongTap.Append) { done.complete(Unit) }
                }
                try { withTimeout(300000) { done.await() } } finally { sampler.cancelAndJoin() }
                val elapsed = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                withContext(Dispatchers.Main) {
                    assertEquals(20, acknowledgements.size)
                    assertEquals(20, captures.size)
                    assertNull(queues.state.value.problem)
                    assertEquals(first.currentEntryId, player.currentMediaItem!!.mediaId)
                }
                fun p95(values: List<Double>) = values.sorted()[kotlin.math.ceil(values.size * .95).toInt() - 1]
                // The seek receipt immediately precedes capture(); its first native item-count read follows template publication.
                val line = "entries=$entries tracks=$songs shuffle=$shuffle loop=$loop reshuffle=$reshuffle peakBytes=${peak.get()} settledBytes=${runtime.totalMemory() - runtime.freeMemory()} maxBytes=${runtime.maxMemory()} samples=20 queuedCommands=60 maxOutstandingSeeks=20 captureToPublishUpperP95Ms=${p95(captures)} acknowledgementP95Ms=${p95(acknowledgements)} drainMs=$elapsed captureSamples=$captures acknowledgementSamples=$acknowledgements gcBefore=$gcBefore gcAfter=${android.os.Debug.getRuntimeStats()}"
                android.util.Log.i("Latency957", line)
                File(nativeEvidenceDirectory("window-957", context.getExternalFilesDir(null)), "latency.txt").appendText(line + "\n")
            }
        } finally { database?.close() }
    }

    private class CountingTracks(private val values: List<Track>) : AbstractList<Track>() {
        val reads = AtomicInteger()
        override val size get() = values.size
        override fun get(index: Int): Track { reads.incrementAndGet(); return values[index] }
    }
    private class MutablePermissions : FolderPermissions {
        @Volatile var first = true
        val reads = AtomicInteger()
        val mainReads = AtomicInteger()
        override fun hasReadAccess(uri: String): Boolean {
            reads.incrementAndGet()
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) mainReads.incrementAndGet()
            return uri != "root-one" || first
        }
        override fun acquire(uri: String) = Unit
        override fun release(uri: String) = Unit
    }

    @Test fun heldTimelineIoKeepsNaturalEndWithItsPublishedModeOwner() {
        val failures = mutableListOf<String>()
        for (shuffle in listOf(false, true)) fixture(rawPlayer = true, duplicateOccurrences = true, timeoutMs = 30000) {
            queues.settings(QueueSettings())
            barrier()
            repeat(first.entries.size - 1) { queues.next(); barrier() }
            awaitMain { player.engine.playbackState == Player.STATE_READY && player.duration > 300 }
            val exhausted = withContext(Dispatchers.Main) {
                assertEquals(androidx.media3.common.C.INDEX_UNSET, player.engine.nextMediaItemIndex)
                player.currentMediaItem!!.queueVisit!!
            }
            // Playback starts before the mode command; only native autoplay runs during held actor IO.
            withContext(Dispatchers.Main) { player.engine.seekTo(0); player.engine.play() }
            awaitMain { player.engine.isPlaying }
            val held = store.gateNextSnapshot()
            queues.settings { it.copy(shuffle = shuffle, repeat = if (shuffle) QueueRepeat.Off else QueueRepeat.All) }
            var line = ""
            try {
                withTimeout(5000) { held.entered.await() }
                val published = queues.state.value.library.active!!.journey!!
                val expected = published.next
                withContext(Dispatchers.Main) {
                    if (player.currentMediaItem?.queueVisit == exhausted && player.engine.playWhenReady && player.engine.playbackState != Player.STATE_ENDED)
                        player.engine.seekTo(player.duration - 80)
                }
                withTimeout(5000) {
                    while (!withContext(Dispatchers.Main) {
                        player.currentMediaItem?.queueVisit != exhausted || player.engine.playbackState == Player.STATE_ENDED || !player.engine.playWhenReady
                    }) delay(10)
                }
                withContext(Dispatchers.Main) {
                    assertNull("The natural-end witness needs a healthy decoder", player.engine.playerError)
                    assertFalse("Timeline IO must still be held", held.release.isCompleted)
                    val actual = player.currentMediaItem?.queueVisit
                    val ended = player.engine.playbackState == Player.STATE_ENDED || !player.engine.playWhenReady
                    val matches = if (expected == null) actual == exhausted && ended else actual == expected.token
                    val settings = queues.state.value.settings
                    line = "mode=${if (shuffle) "shuffle" else "repeat-all"} matchesPublishedOwner=$matches exhausted=$exhausted expected=${expected?.token} expectedCycle=${expected?.cycle} actual=$actual uiCycle=${published.cycle} uiCurrent=${published.current?.token} uiWindow=${published.window().size} nativeCount=${player.mediaItemCount} nativeRepeat=${player.engine.repeatMode} nativeShuffle=${player.engine.shuffleModeEnabled} uiRepeat=${settings.repeat} uiShuffle=${settings.shuffle} ended=$ended"
                    if (!matches) failures += line
                    player.engine.pause()
                }
            } finally { held.release.complete(Unit) }
            barrier()
            val final = queues.state.value.library.active!!.journey!!
            line += " finalCycle=${final.cycle} finalCurrent=${final.current?.token}"
            android.util.Log.i("Mode957Publication", line)
            File(nativeEvidenceDirectory("mode-957-publication", InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "natural-end.txt")
                .appendText(line + "\n")
        }
        assertTrue("Natural end consumed a window outside the published owner:\n" + failures.joinToString("\n"), failures.isEmpty())
    }



    @Test fun modeIntentSupersedesHeldPreparationWithoutPublishingIntermediateModes() = fixture(rawPlayer = true, duplicateOccurrences = true) {
        val shuffled = AtomicInteger()
        withContext(Dispatchers.Main) { player.engine.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { if (enabled) shuffled.incrementAndGet() }
        }) }
        val firstRead = store.gateNextSnapshot()
        queues.shuffle()
        firstRead.entered.await()
        awaitMain { queues.presentation.value.settings.shuffle }
        assertFalse(queues.state.value.settings.shuffle)
        val latestRead = store.gateNextSnapshot()
        withContext(Dispatchers.Main) { queues.shuffle(); repeat(3) { queues.repeat() } }
        awaitMain { !queues.presentation.value.settings.shuffle && queues.presentation.value.settings.repeat == QueueRepeat.Off }
        withContext(Dispatchers.Main) { queues.playbackRequested(false); queues.repeat() }
        awaitMain { !queues.presentation.value.settings.shuffle && queues.presentation.value.settings.repeat == QueueRepeat.All }
        firstRead.release.complete(Unit)
        latestRead.entered.await()
        withContext(Dispatchers.Main) {
            assertFalse(player.engine.shuffleModeEnabled)
            assertEquals(Player.REPEAT_MODE_OFF, player.engine.repeatMode)
            assertEquals(0, shuffled.get())
        }
        latestRead.release.complete(Unit)
        barrier()
        withContext(Dispatchers.Main) {
            assertEquals(QueueRepeat.All, queues.state.value.settings.repeat)
            assertEquals(Player.REPEAT_MODE_ALL, player.engine.repeatMode)
            assertFalse(player.engine.playWhenReady)
            assertEquals(0, shuffled.get())
            assertEquals(queues.state.value.library.active!!.journey!!.window().map { it.token },
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it).queueVisit })
        }
    }

    @Test fun failedModeReadRetiresOnlyItsOverlayAndKeepsNewerRequest() = fixture(rawPlayer = true, duplicateOccurrences = true) {
        val failed = store.gateNextSnapshot().apply { fail = true }
        queues.shuffle()
        failed.entered.await()
        queues.repeat()
        val newer = store.gateNextSnapshot()
        failed.release.complete(Unit)
        newer.entered.await()
        awaitMain { queues.presentation.value.settings.shuffle && queues.presentation.value.settings.repeat == QueueRepeat.All }
        assertFalse(queues.state.value.settings.shuffle)
        newer.release.complete(Unit)
        barrier()
        val latestFailure = store.gateNextSnapshot().apply { fail = true }
        queues.shuffle()
        latestFailure.entered.await()
        awaitMain { !queues.presentation.value.settings.shuffle }
        latestFailure.release.complete(Unit)
        awaitMain { queues.state.value.problem != null && queues.presentation.value.settings.shuffle }
        assertEquals("Couldn’t save this queue change. Try again.", queues.state.value.problem)
        withContext(Dispatchers.Main) { assertTrue(player.engine.shuffleModeEnabled) }
        queues.shuffle()
        barrier()
        assertFalse(queues.state.value.settings.shuffle)
        assertNull(queues.state.value.problem)
    }

    @Test fun nonModeSettingPreservesRequestedModesWithoutReplacingItsOwner() = fixture(rawPlayer = true, duplicateOccurrences = true) {
        val saving = dao.gateNextSave()
        queues.rename(first.id, "Held")
        saving.entered.await()
        val nonModeRead = store.gateNextSnapshot()
        queues.settings { it.copy(preloadNext = false) }
        queues.shuffle()
        awaitMain { queues.presentation.value.settings.shuffle }
        saving.release.complete(Unit)
        nonModeRead.entered.await()
        val modeRead = store.gateNextSnapshot()
        nonModeRead.release.complete(Unit)
        modeRead.entered.await()
        awaitMain { !queues.presentation.value.settings.preloadNext && queues.presentation.value.settings.shuffle }
        assertFalse(queues.state.value.settings.shuffle)
        assertFalse(queues.state.value.settings.preloadNext)
        modeRead.release.complete(Unit)
        barrier()
        assertTrue(queues.state.value.settings.shuffle)
        assertFalse(queues.state.value.settings.preloadNext)
    }

    @Test fun nonModeSettingPublishesBeforeHeldReadAndSurvivesItsFailure() {
        for (fail in listOf(false, true)) fixture(rawPlayer = true, duplicateOccurrences = true) {
            val preferences = QueueController::class.java.getDeclaredField("preferences").apply { isAccessible = true }.get(queues) as QueuePreferences
            val committed = queues.state.value.library
            val held = store.gateNextSnapshot().also { it.fail = fail }
            queues.settings { it.copy(preloadNext = false, songTap = SongTap.Append, repeat = QueueRepeat.All) }
            held.entered.await()
            awaitMain { !queues.presentation.value.settings.preloadNext && queues.presentation.value.settings.songTap == SongTap.Append }
            assertSame(committed, queues.state.value.library)
            assertFalse(queues.state.value.settings.preloadNext)
            assertEquals(SongTap.Append, preferences.read().songTap)
            assertFalse(preferences.read().preloadNext)
            assertEquals(QueueRepeat.Off, preferences.read().repeat)
            withContext(Dispatchers.Main) { assertEquals(Player.REPEAT_MODE_OFF, player.engine.repeatMode) }
            held.release.complete(Unit)
            if (fail) awaitMain { queues.state.value.problem != null } else barrier()
            assertFalse(queues.state.value.settings.preloadNext)
            assertFalse(preferences.read().preloadNext)
            assertEquals(SongTap.Append, queues.state.value.settings.songTap)
            assertEquals(SongTap.Append, preferences.read().songTap)
            assertEquals(if (fail) QueueRepeat.Off else QueueRepeat.All, preferences.read().repeat)
            withContext(Dispatchers.Main) { assertEquals(if (fail) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL, player.engine.repeatMode) }
        }
    }

    @Test fun deferredModeOverlayRetiresOnControllerCancellation() = fixture(rawPlayer = true, duplicateOccurrences = true) {
        val saving = dao.gateNextSave()
        queues.tap(listOf(track), track.id)
        saving.entered.await()
        queues.shuffle()
        awaitMain { queues.presentation.value.settings.shuffle }
        val deferred = QueueController::class.java.getDeclaredField("commandAfterActivation").apply { isAccessible = true }
        awaitMain { deferred.get(queues) != null }
        val job = QueueController::class.java.getDeclaredField("commandJob").apply { isAccessible = true }.get(queues) as Job
        job.cancelAndJoin()
        awaitMain { queues.presentation.value.settings == queues.state.value.settings }
        assertFalse(queues.state.value.settings.shuffle)
        withContext(Dispatchers.Main) { assertFalse(player.engine.shuffleModeEnabled) }
        saving.release.complete(Unit)
    }

    @Test fun coldPlayerUnderEditHoldKeepsSavedModesThroughCapture() = fixture(attach = false, rawPlayer = true, duplicateOccurrences = true) {
        queues.settings(QueueSettings(shuffle = true, repeat = QueueRepeat.All))
        barrier()
        withHeldEdit(track.id) {
            queues.attach(player.engine)
            barrier()
            assertTrue(queues.state.value.settings.shuffle)
            assertEquals(QueueRepeat.All, queues.state.value.settings.repeat)
            withContext(Dispatchers.Main) {
                assertEquals(0, player.mediaItemCount)
                assertFalse(player.engine.shuffleModeEnabled)
                assertEquals(Player.REPEAT_MODE_OFF, player.engine.repeatMode)
            }
        }
        barrier()
        withContext(Dispatchers.Main) {
            assertTrue(player.engine.shuffleModeEnabled)
            assertEquals(Player.REPEAT_MODE_ALL, player.engine.repeatMode)
            assertTrue(queues.state.value.settings.shuffle)
            assertEquals(QueueRepeat.All, queues.state.value.settings.repeat)
            assertNull(queues.state.value.problem)
        }
    }

    @Test fun arrivalDuringModePreparationKeepsBProgressingAfterRederivation() {
        for (shuffle in listOf(false, true)) fixture(rawPlayer = true, duplicateOccurrences = true, occurrences = 8, timeoutMs = 30000) {
            awaitMain { player.engine.playbackState == Player.STATE_READY && player.duration > 500 }
            val b = queues.state.value.library.active!!.journey!!.next!!
            val firstRead = store.gateNextSnapshot()
            queues.requestModes { it.copy(shuffle = shuffle, repeat = if (shuffle) QueueRepeat.Off else QueueRepeat.All) }
            firstRead.entered.await()
            withContext(Dispatchers.Main) { player.engine.seekTo(player.duration - 200); player.engine.play() }
            awaitMain { player.currentMediaItem?.queueVisit == b.token }
            val secondRead = store.gateNextSnapshot()
            val seeks = AtomicInteger()
            val positions = withContext(Dispatchers.Main) {
                player.engine.addListener(object : Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks.incrementAndGet()
                    }
                })
                player.engine.currentPosition
            }
            firstRead.release.complete(Unit)
            secondRead.entered.await()
            delay(120)
            val progressed = withContext(Dispatchers.Main) {
                assertEquals(b.token, player.currentMediaItem!!.queueVisit)
                assertTrue(player.engine.currentPosition > positions)
                player.engine.currentPosition
            }
            secondRead.release.complete(Unit)
            awaitMain { queues.state.value.settings.shuffle == shuffle && queues.state.value.settings.repeat == (if (shuffle) QueueRepeat.Off else QueueRepeat.All) }
            delay(100)
            withContext(Dispatchers.Main) {
                assertEquals(b, queues.state.value.library.active!!.journey!!.current)
                assertEquals(b.token, player.currentMediaItem!!.queueVisit)
                assertEquals(b.entryId, player.currentMediaItem!!.mediaId)
                assertTrue(player.engine.currentPosition >= progressed)
                assertEquals(0, seeks.get())
                assertNull(player.engine.playerError)
                player.engine.pause()
            }
            barrier()
        }
    }

    @Test fun heldModeIoAtOperatorScaleKeepsActualOccurrenceAndCommitsRequestedModes() {
        val args = InstrumentationRegistry.getArguments()
        val count = args.getString("queueEntries", "13400")!!.toInt()
        val tracks = args.getString("libraryTracks", "13400")!!.toInt()
        for (shuffle in args.getString("modeShuffle")?.let { listOf(it.toBooleanStrict()) } ?: listOf(false, true)) fixture(rawPlayer = true, duplicateOccurrences = true,
            occurrences = count, librarySize = tracks, initialIndex = count - 1, timeoutMs = 300000) {
            awaitMain { player.engine.playbackState == Player.STATE_READY && player.duration > 300 }
            val exhausted = queues.state.value.library.active!!.journey!!.current!!
            val requested = QueueSettings(shuffle = shuffle, repeat = if (shuffle) QueueRepeat.Off else QueueRepeat.All)
            val runtime = Runtime.getRuntime()
            val peak = java.util.concurrent.atomic.AtomicLong(runtime.totalMemory() - runtime.freeMemory())
            val beforeGc = android.os.Debug.getRuntimeStats()
            val sampler = launch(Dispatchers.Default) { while (isActive) {
                peak.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }; delay(10)
            } }
            try {
                val held = store.gateNextSnapshot()
                queues.requestModes { requested }
                held.entered.await()
                awaitMain { queues.presentation.value.settings.shuffle == shuffle && queues.presentation.value.settings.repeat == requested.repeat }
                withContext(Dispatchers.Main) { player.engine.seekTo(player.duration - 100); player.engine.play() }
                awaitMain { player.currentMediaItem?.queueVisit != exhausted.token || player.engine.playbackState == Player.STATE_ENDED || !player.engine.playWhenReady }
                withContext(Dispatchers.Main) {
                    val published = queues.state.value.library.active!!.journey!!
                    val expected = published.next
                    val actual = player.currentMediaItem!!
                    val ended = player.engine.playbackState == Player.STATE_ENDED || !player.engine.playWhenReady
                    val matches = if (expected == null) actual.queueVisit == exhausted.token && ended else actual.queueVisit == expected.token
                    val line = "entries=$count tracks=$tracks shuffle=$shuffle matchesPublishedOwner=$matches expected=$expected exhausted=$exhausted actualToken=" + actual.queueVisit + " actualEntry=" + actual.mediaId + " actualTrack=" + actual.mediaMetadata.extras?.getString("trackId") + " nativeCount=" + player.mediaItemCount
                    android.util.Log.i("Mode1001", line)
                    File(nativeEvidenceDirectory("mode-1001", InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "scale.txt").appendText(line + "\n")
                    assertNull(player.engine.playerError)
                    assertTrue(line, matches)
                    assertEquals(exhausted.entryId, actual.mediaId)
                    assertEquals(first.entries.last().track.id, actual.mediaMetadata.extras!!.getString("trackId"))
                }
                held.release.complete(Unit)
                barrier()
                withContext(Dispatchers.Main) {
                    val committed = queues.state.value.library.active!!.journey!!
                    assertEquals(requested.shuffle, queues.state.value.settings.shuffle)
                    assertEquals(requested.repeat, queues.state.value.settings.repeat)
                    assertEquals(requested.shuffle, player.engine.shuffleModeEnabled)
                    assertEquals(if (shuffle) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL, player.engine.repeatMode)
                    assertEquals(JourneyModes.from(requested), committed.modes)
                    assertNotNull(committed.next)
                    assertEquals(exhausted, committed.current)
                    assertEquals(committed.window().map { it.token }, (0 until player.mediaItemCount).map { player.getMediaItemAt(it).queueVisit })
                    assertEquals(first.entries.last().track.id, player.currentMediaItem!!.mediaMetadata.extras!!.getString("trackId"))
                }
                val next = queues.state.value.library.active!!.journey!!.next!!
                val nextTrack = first.entries[next.entryId.removePrefix("duplicate-").toInt()].track.id
                withContext(Dispatchers.Main) { player.engine.seekTo(player.duration - 150); player.engine.play() }
                awaitMain { player.currentMediaItem?.queueVisit == next.token }
                withContext(Dispatchers.Main) {
                    assertEquals(next.entryId, player.currentMediaItem!!.mediaId)
                    assertEquals(nextTrack, player.currentMediaItem!!.mediaMetadata.extras!!.getString("trackId"))
                    assertNull(player.engine.playerError)
                    player.engine.pause()
                }
                barrier()
                assertEquals(next, queues.state.value.library.active!!.journey!!.current)
            } finally {
                sampler.cancelAndJoin()
                repeat(3) { runtime.gc(); delay(100) }
                val line = "entries=$count tracks=$tracks shuffle=$shuffle peakBytes=" + peak.get() + " settledBytes=" + (runtime.totalMemory() - runtime.freeMemory()) + " maxBytes=" + runtime.maxMemory() + " gcBefore=$beforeGc gcAfter=" + android.os.Debug.getRuntimeStats()
                android.util.Log.i("Mode1001", line)
                File(nativeEvidenceDirectory("mode-1001", InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "heap.txt").appendText(line + "\n")
            }
        }
    }

    private fun fixture(attach: Boolean = true, blockRestore: Boolean = false, rawPlayer: Boolean = false,
        duplicateOccurrences: Boolean = false, occurrences: Int = 3, librarySize: Int = 1, initialIndex: Int = 0, timeoutMs: Long = 20000, playbackStore: ((List<Track>) -> LibraryStore)? = null, noCurrent: Boolean = false, permissions: FolderPermissions? = null, actorContext: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext, test: suspend Fixture.() -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = java.util.UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "intent-$id").apply { mkdirs() }
        val file = File(directory, "queue-tone.mp3")
        instrumentation.context.assets.open(file.name).use { input -> file.outputStream().use(input::copyTo) }
        val track = Track(Uri.fromFile(file).toString(), "First", fileName = file.name, relativePath = file.name)
        val tracks = if (librarySize == 1) listOf(track) else List(librarySize) { track.copy(id = "scale-track-$it", title = "Scale track $it") }
        val first = ListeningQueue(name = "First", entries = if (duplicateOccurrences)
            (0 until occurrences).map { QueueEntry("duplicate-$it", tracks[it % tracks.size], if (librarySize > 1) "scale-origin" else "origin-$it") } else listOf(QueueEntry(track = track))).let { if (noCurrent) it.copy(currentEntryId = null) else it.copy(currentEntryId = it.entries[initialIndex].id) }
        val secondFile = File(directory, "second.mp3").also { file.copyTo(it) }
        val secondUri = Uri.fromFile(secondFile).toString()
        val second = ListeningQueue(name = "Second", entries = listOf(QueueEntry(track = track.copy(uri = secondUri, id = secondUri))))
        val dao = IntentDao(QueueLibrary(listOf(first, second), first.id), if (blockRestore) Gate() else null)
        val store = IntentStore().apply { if (librarySize > 1) snapshot = LibrarySnapshot(tracks = tracks) }
        val library = LibraryRepository(playbackStore?.invoke(tracks) ?: store, TreeScanner(AndroidDocuments(context)), permissions ?: PersistedFolderPermissions(context.contentResolver))
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("intent-$id", mode)
        })
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + actorContext)
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
            withTimeout(timeoutMs) { fixture.test() }
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
        suspend fun command(action: suspend () -> Unit) = withContext(Dispatchers.Main) {
            val edit = QueueController::class.java.declaredMethods.single { it.name == "editCommand" }.apply { isAccessible = true }
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { edit.invoke(queues, false, {}, action, it) }
        }
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
            queues.settings { it }
            queues.act(emptyList(), SongTap.Append) { done.complete(Unit) }
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
        val selectionCalls = CopyOnWriteArrayList<String>()
        override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            selectionCalls.add("load")
            super.setMediaItems(items, startIndex, startPositionMs)
        }
        override fun stop() { selectionCalls.add("stop"); super.stop() }
        override fun clearMediaItems() { selectionCalls.add("clear"); super.clearMediaItems() }
        var timelineRead: (() -> Unit)? = null
        override fun getMediaItemCount(): Int {
            timelineRead?.also { timelineRead = null; it() }
            return super.getMediaItemCount()
        }
        val seekVisits = CopyOnWriteArrayList<String>()
        val positionSeeks = CopyOnWriteArrayList<Pair<String?, Long>>()
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            selectionCalls.add("seek:${getMediaItemAt(mediaItemIndex).mediaId}:$positionMs")
            seekVisits.add(getMediaItemAt(mediaItemIndex).mediaId)
            super.seekTo(mediaItemIndex, positionMs)
        }
        var stateOverride: Int? = null
        var durationOverride: Long? = null
        var rejectSeek = false
        override fun getDuration() = durationOverride ?: super.getDuration()
        override fun seekTo(positionMs: Long) {
            check(!rejectSeek) { "Controlled native seek failure" }
            positionSeeks.add(currentMediaItem?.mediaId to positionMs)
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
            selectionCalls.add("play:$playWhenReady")
            requested.add(playWhenReady)
            if (playWhenReady) trueRequestSources.add(engine.currentMediaItem?.mediaMetadata?.extras?.getString("trackId"))
            super.setPlayWhenReady(playWhenReady)
        }
        override fun prepare() { selectionCalls.add("prepare"); preparedWhilePlaying = preparedWhilePlaying || playWhenReady; super.prepare() }
    }

    private class Gate {
        @Volatile var fail = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun await() { entered.complete(Unit); release.await() }
    }
    private fun heldReads(held: AtomicReference<Gate?>, tracks: List<Track>): LibraryStore {
        val source = IntentStore().apply { snapshot = LibrarySnapshot(tracks = tracks) }
        return object : LibraryStore by source {
            override suspend fun playbackSnapshot(trackIds: Set<String>): LibraryPlaybackRead {
                held.get()?.await()
                return source.playbackSnapshot(trackIds)
            }
        }
    }
    private class IntentStore : LibraryStore {
        @Volatile var snapshot: LibrarySnapshot? = null
        val collections = AtomicInteger()
        val mainReads = AtomicInteger()
        private val next = AtomicReference<Gate?>()
        private val gates = mutableListOf<Gate>()
        fun gateNextSnapshot(gate: Gate = Gate()) = gate.also { check(next.compareAndSet(null, it)); gates.add(it) }
        fun release() { gates.forEach { it.release.complete(Unit) } }
        override val snapshots = flow {
            collections.incrementAndGet()
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) mainReads.incrementAndGet()
            next.getAndSet(null)?.let { it.await(); if (it.fail) throw IOException("Controlled playback read failure") }; emit(snapshot ?: LibrarySnapshot())
        }
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
        override suspend fun read(): QueueLibrary {
            restore?.await()
            if (restore?.fail == true) throw IOException("Controlled restore failure")
            return saved
        }
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
        override suspend fun upsertCycleMembers(members: Sequence<CycleMemberRecord>): Unit = error("save overridden")
        override suspend fun updateVisited(id: String, cycle: Long, entries: List<String>, visited: Boolean): Int = error("save overridden")
        override suspend fun clearCycle(id: String, cycle: Long): Unit = error("save overridden")
        override suspend fun deleteVisits(queueId: String, tokens: List<String>): Unit = error("save overridden")
        override suspend fun deleteSparseMembers(queueId: String, cycle: Long, entries: List<String>): Unit = error("save overridden")
        override suspend fun insertEntries(entries: List<EntryRecord>): Unit = error("save overridden")
        override suspend fun clearEntries(id: String): Unit = error("save overridden")
        override suspend fun deleteQueue(id: String): Unit = error("save overridden")
    }
}

