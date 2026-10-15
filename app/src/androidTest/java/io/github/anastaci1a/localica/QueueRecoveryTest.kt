package io.github.anastaci1a.localica

import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real durable journals, destructive/rollback faults and native source opens, using private fixtures. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueRecoveryTest {
    @Test fun failedOverwriteAndRollbackCannotReopenCurrentOrDuplicateSources() = fixture {
        play()
        val before = queues.state.value.library.active!!
        failEdit(tracks[0])
        awaitMain { player.mediaItemCount == 0 && !player.isPlaying }
        val openedBeforeRetry = opened.size
        queues.retryPlayback()
        queues.selectEntry(before.entries[2].id)
        barrier()
        assertEquals(openedBeforeRetry, opened.size)
        assertEquals(before.entries, queues.state.value.library.active!!.entries)
        assertNotNull(queues.state.value.problem)
        val notice = queues.state.value.problem
        queues.settings { it.copy(previousThresholdSeconds = 6) }
        barrier()
        assertEquals(notice, queues.state.value.problem)
        assertEquals(2L, files[0].length())
        assertNotNull(writer.pending())
        queues.selectEntry(before.entries[1].id)
        awaitMain { player.isPlaying && player.currentMediaItem?.mediaId == before.entries[1].id }
        assertTrue(opened.drop(openedBeforeRetry).contains(tracks[1].uri))
        assertFalse(opened.drop(openedBeforeRetry).contains(tracks[0].uri))
        barrier()
        assertNull("Unrelated playback must not carry the previous occurrence's notice", queues.state.value.problem)
        assertTrue(readers.recovery().blocks(tracks[0].id))
    }

    @Test fun failedRecoveryRemainsBlockedAndVerifiedLaterRecoveryLoadsPaused() = fixture(pending = 0) {
        awaitMain { !queues.state.value.loading }
        barrier()
        assertEquals(0, opened.size)
        val original = queues.state.value.library.active!!
        assertTrue(runCatching { queues.withFileRecovery(tracks[0].id) { writer.recover() } }.isFailure)
        barrier()
        assertEquals(0, opened.size)
        documents.failWrites = false
        queues.withFileRecovery(tracks[0].id) { writer.recover() }
        awaitMain { player.mediaItemCount > 0 && player.playbackState == Player.STATE_READY }
        withContext(Dispatchers.Main) { assertFalse(player.playWhenReady); assertFalse(player.isPlaying) }
        assertNull(writer.pending())
        barrier()
        assertNull("Verified recovery clears the owned notice", queues.state.value.problem)
        assertArrayEquals(originalBytes[0], files[0].readBytes())
        assertEquals(original.entries, queues.state.value.library.active!!.entries)
        assertEquals(original.currentEntryId, queues.state.value.library.active!!.currentEntryId)
    }

    @Test fun explicitPlayDuringVerifiedRecoveryWinsWithoutStaleAutoplay() = fixture(pending = 0) {
        barrier()
        documents.failWrites = false
        val restored = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val request = launch {
            queues.withFileRecovery(tracks[0].id) { writer.recover(); restored.complete(Unit); finish.await() }
        }
        try {
            restored.await()
            withContext(Dispatchers.Main) { io.github.anastaci1a.localica.playback.QueueTransportPlayer(player, queues).play() }
            // Queue a visible state mutation behind the explicit play request.
            queues.rename(original.id, "Requested play")
            awaitMain { queues.state.value.library.active?.name == "Requested play" }
            withContext(Dispatchers.Main) { assertFalse(player.isPlaying); assertEquals(0, player.mediaItemCount) }
            finish.complete(Unit); request.join()
            awaitMain { player.isPlaying }
        } finally { finish.complete(Unit); request.cancelAndJoin() }
    }

    @Test fun futureDamagedSourceStopsAtBoundaryWithoutRepeatWrappingOrInventedArrival() = fixture {
        queues.settings(QueueSettings(repeat = QueueRepeat.All, preloadNext = true))
        barrier()
        play()
        failEdit(tracks[1])
        awaitMain { player.isPlaying }
        val before = queues.state.value.library.active!!
        val opensAfterFailure = opened.size
        withContext(Dispatchers.Main) {
            assertEquals(1, player.mediaItemCount)
            assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
            player.seekTo(player.duration - 150)
        }
        awaitMain { player.playbackState == Player.STATE_ENDED && !player.isPlaying }
        barrier()
        assertEquals(before.currentEntryId, queues.state.value.library.active!!.currentEntryId)
        assertEquals(before.journey?.visited, queues.state.value.library.active!!.journey?.visited)
        assertEquals(QueueRepeat.All, queues.state.value.settings.repeat)
        assertEquals(QueueRepeat.All, preferences.read().repeat)
        assertFalse(opened.drop(opensAfterFailure).contains(tracks[1].uri))
        assertNotNull(queues.state.value.problem)
        queues.next(); barrier()
        assertEquals(before.entries[1].id, queues.state.value.library.active!!.currentEntryId)
        withContext(Dispatchers.Main) { assertEquals(0, player.mediaItemCount); assertFalse(player.playWhenReady) }
        queues.previous(alwaysSong = true); barrier()
        awaitMain { player.mediaItemCount > 0 }
        assertEquals(before.currentEntryId, queues.state.value.library.active!!.currentEntryId)
    }

    @Test fun futureBoundaryNoticeSurvivesMetadataAndSettingsUntilDismissed() = fixture(pending = 1) {
        play()
        awaitMain { player.playbackState == Player.STATE_READY }
        withContext(Dispatchers.Main) { player.seekTo(player.duration - 150) }
        awaitMain { player.playbackState == Player.STATE_ENDED && queues.state.value.problem != null }
        val notice = queues.state.value.problem
        assertFalse(readers.recovery().blocks(tracks[0].id))
        assertTrue(readers.recovery().blocks(tracks[1].id))
        barrier()
        assertEquals(notice, queues.state.value.problem)
        queues.settings { it.copy(previousThresholdSeconds = 6) }
        barrier()
        assertEquals(notice, queues.state.value.problem)
        val edited = CompletableDeferred<Boolean>()
        queues.moveEntry(original.entries.first().id, 0, complete = { edited.complete(it) })
        assertTrue(withTimeout(7000) { edited.await() })
        assertEquals("Passive activation of the same safe occurrence keeps its blocked-future notice",
            notice, queues.state.value.problem)
        queues.dismissProblem()
        barrier()
        assertNull(queues.state.value.problem)
        queues.settings { it.copy(previousThresholdSeconds = 7) }
        barrier()
        assertNull("Successful commands must not resurrect a dismissed notice", queues.state.value.problem)
        assertFalse(opened.contains(tracks[1].uri))
        assertEquals(original.currentEntryId, queues.state.value.library.active!!.currentEntryId)
        withContext(Dispatchers.Main) { assertFalse(player.isPlaying) }
    }

    @Test fun ordinaryTransientQueueErrorsStillClearAfterSuccessfulCommands() = fixture {
        play()
        assertEquals(RecoveryAdmission.Clear, readers.recovery())
        queues.settings { it.copy(previousThresholdSeconds = 0) }
        awaitMain { queues.state.value.problem?.contains("Couldn’t save") == true }
        barrier()
        assertNull("A successful rename still clears a transient command error", queues.state.value.problem)
        queues.settings { it.copy(previousThresholdSeconds = 0) }
        awaitMain { queues.state.value.problem?.contains("Couldn’t save") == true }
        queues.settings { it.copy(previousThresholdSeconds = 6) }
        awaitMain { queues.state.value.settings.previousThresholdSeconds == 6 }
        assertNull("A successful settings change still clears a transient command error", queues.state.value.problem)
        withContext(Dispatchers.Main) { assertTrue(player.isPlaying) }
    }

    @Test fun advancedEndPoliciesCannotAdvanceAutomaticallyIntoTheBlockedVisit() = fixture(pending = 1) {
        val policies = listOf(
            AdvancedRepeat(songEnd = SongEnd.Stop),
            AdvancedRepeat(songEnd = SongEnd.NextPaused),
            AdvancedRepeat(songEnd = SongEnd.Next),
            AdvancedRepeat(queueEnd = QueueEnd.Repeat),
        )
        for (policy in policies) {
            queues.settings(QueueSettings(advancedRepeat = policy))
            queues.selectEntry(original.entries.first().id)
            awaitMain { player.playbackState == Player.STATE_READY && player.isPlaying }
            val visit = queues.state.value.library.active!!.journey!!.current
            withContext(Dispatchers.Main) { player.seekTo(player.duration - 150) }
            awaitMain { !player.playWhenReady && queues.state.value.problem != null }
            barrier()
            assertEquals(visit, queues.state.value.library.active!!.journey!!.current)
            assertEquals(policy, queues.state.value.settings.advancedRepeat)
            assertFalse(opened.contains(tracks[1].uri))
        }
        queues.settings(QueueSettings(repeat = QueueRepeat.One))
        queues.selectEntry(original.entries.first().id)
        awaitMain { player.playbackState == Player.STATE_READY && player.isPlaying }
        withContext(Dispatchers.Main) { player.seekTo(player.duration - 150) }
        awaitMain { player.isPlaying && player.currentPosition < 1000 }
        assertFalse(opened.contains(tracks[1].uri))
    }

    @Test fun automaticQueueEndCannotResetOrSwitchToABlockedSource() = fixture(pending = 0, current = 3) {
        queues.tap(listOf(tracks[0]), 0, sourceName = "Blocked destination")
        awaitMain { queues.state.value.library.queues.size == 2 }
        for (policy in listOf(AdvancedRepeat(resetToFirst = true), AdvancedRepeat(queueEnd = QueueEnd.NextQueue))) {
            queues.settings(QueueSettings(advancedRepeat = policy))
            queues.selectEntry(original.entries.last().id)
            awaitMain { player.isPlaying && player.playbackState == Player.STATE_READY }
            val before = queues.state.value.library.active!!
            withContext(Dispatchers.Main) { player.seekTo(player.duration - 150) }
            awaitMain { !player.playWhenReady && queues.state.value.problem != null }
            barrier()
            assertEquals(original.id, queues.state.value.library.activeId)
            assertEquals(before.currentEntryId, queues.state.value.library.active!!.currentEntryId)
            assertEquals(before.journey!!.current, queues.state.value.library.active!!.journey!!.current)
            assertFalse(opened.contains(tracks[0].uri))
        }
    }

    @Test fun startupPendingRecoveryPrecedesNativePrepareAndStillAllowsUnrelatedPlayback() = fixture(pending = 0, current = 1) {
        barrier()
        awaitMain { player.playbackState == Player.STATE_READY }
        withContext(Dispatchers.Main) { assertEquals(1, player.mediaItemCount); assertFalse(player.playWhenReady) }
        assertFalse(opened.contains(tracks[0].uri))
        play()
        assertTrue(opened.contains(tracks[1].uri))
        assertFalse(opened.contains(tracks[0].uri))
    }

    @Test fun unreadableStartupJournalFailsClosedBeforeAnyNativeSourceOpen() = fixture(unreadable = true) {
        barrier()
        queues.retryPlayback()
        queues.selectEntry(original.entries[1].id)
        barrier()
        withContext(Dispatchers.Main) { assertEquals(0, player.mediaItemCount); assertFalse(player.playWhenReady) }
        assertTrue(opened.isEmpty())
        assertTrue(queues.state.value.problem.orEmpty().contains("could not be read"))
        queues.settings { it.copy(previousThresholdSeconds = 6) }
        barrier()
        assertTrue(queues.state.value.problem.orEmpty().contains("could not be read"))
        assertTrue(opened.isEmpty())
    }

    @Test fun inactiveSourceEditProtectsLaterSelectionAndKeepsNewUnrelatedPlayback() = fixture {
        val acquired = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val request = launch { queues.withFileEdit(tracks[3].id) { acquired.complete(Unit); finish.await() } }
        try {
            acquired.await()
            queues.tap(listOf(tracks[3]), 0)
            awaitMain { queues.state.value.library.active?.entries?.singleOrNull()?.track?.id == tracks[3].id }
            withContext(Dispatchers.Main) { assertEquals(0, player.mediaItemCount) }
            assertFalse(opened.contains(tracks[3].uri))
            queues.tap(listOf(tracks[1]), 0)
            awaitMain { player.isPlaying }
            val newer = queues.state.value.library.activeId
            val opens = opened.size
            finish.complete(Unit); request.join(); barrier()
            withContext(Dispatchers.Main) { assertTrue(player.isPlaying) }
            assertEquals(newer, queues.state.value.library.activeId)
            assertEquals(opens, opened.size)
        } finally { finish.complete(Unit); request.cancelAndJoin() }
    }

    @Test fun verifiedBytesRemainPlayableWhenMetadataPublicationFails() = fixture(pending = 0) {
        documents.failWrites = false
        val refresh = MetadataRefreshJournal(File(directory, "pending-metadata-refresh.json"))
        refresh.include(tracks[0])
        val result = runCatching {
            queues.withFileRecovery(tracks[0].id) {
                writer.recover()
                throw IOException("Metadata publication failed")
            }
        }
        assertEquals("Metadata publication failed", result.exceptionOrNull()?.message)
        assertNull(writer.pending())
        assertEquals(listOf(tracks[0]), refresh.read())
        play()
        assertArrayEquals(originalBytes[0], files[0].readBytes())
        assertEquals(original.entries, queues.state.value.library.active!!.entries)
    }

    @Test fun systemPauseDuringHeldEditOverridesEarlierPlayingIntent() = fixture {
        play()
        val acquired = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val request = launch { queues.withFileEdit(tracks[0].id) { acquired.complete(Unit); finish.await() } }
        try {
            acquired.await()
            withContext(Dispatchers.Main) { io.github.anastaci1a.localica.playback.QueueTransportPlayer(player, queues).pause() }
            queues.rename(original.id, "Paused by system")
            awaitMain { queues.state.value.library.active?.name == "Paused by system" }
            finish.complete(Unit); request.join()
            awaitMain { player.playbackState == Player.STATE_READY }
            withContext(Dispatchers.Main) { assertFalse(player.playWhenReady); assertFalse(player.isPlaying) }
        } finally { finish.complete(Unit); request.cancelAndJoin() }
    }

    @Test fun retainedTreeGrantsResolveToTheSameBlockedDocumentIdentity() {
        val first = "content://example.documents/tree/root/document/music%3Asong.mp3"
        val alternate = "content://example.documents/tree/another/document/music%3Asong.mp3"
        val identity = io.github.anastaci1a.localica.library.documentIdentity(first)
        assertEquals("content://example.documents/document/music%3Asong.mp3", identity)
        assertEquals(identity, io.github.anastaci1a.localica.library.documentIdentity(alternate))
        val tracks = listOf(first, alternate).map { Track(it, "Song", fileName = "song.mp3", relativePath = "song.mp3", id = io.github.anastaci1a.localica.library.documentIdentity(it)) }
        val entries = tracks.map { QueueEntry(track = it) }
        val queue = ListeningQueue(name = "Aliases", entries = entries,
            journey = QueueJourney.start(entries.map { it.id }, entries.first().id, JourneyModes()))
        val readers = NativeReaders(RecoveryAdmission.Pending(identity))
        assertTrue(recoveryWindow(queue) { readers.recovery().blocks(it) }.isEmpty())
        entries.forEach { assertTrue(readers.recovery().blocks(it.track.id)) }
        val delegateOpens = AtomicInteger()
        val factory = readers.track(DataSource.Factory { object : DataSource {
            override fun open(dataSpec: DataSpec): Long { delegateOpens.incrementAndGet(); return 0 }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
            override fun getUri(): Uri? = null
            override fun close() {}
            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
        } })
        for (uri in listOf(first, alternate)) {
            val source = factory.createDataSource()
            try { assertTrue(runCatching { source.open(DataSpec(Uri.parse(uri))) }.isFailure) }
            finally { source.close() }
        }
        assertEquals(0, delegateOpens.get())
    }

    @Test fun missingBackupRemainsSourceScopedAndUnrelatedPlaybackStillWorks() = fixture(pending = 0,
        mutateRecovery = { it.resolve("original.mp3").delete() }) {
        barrier()
        assertEquals(RecoveryAdmission.Pending(tracks[0].id), readers.recovery())
        val result = runCatching { queues.withFileRecovery(tracks[0].id) { writer.recover() } }
        assertTrue(result.isFailure)
        assertNotNull(writer.pending())
        queues.selectEntry(original.entries[1].id)
        awaitMain { player.isPlaying }
        assertFalse(opened.contains(tracks[0].uri))
    }

    @Test fun mismatchedAndUnfinishedJournalsFailClosedBeforeStartupOpening() {
        for (invalid in listOf("mismatch", "blank", "unfinished")) fixture(pending = 0, mutateRecovery = { recovery ->
            if (invalid == "unfinished") recovery.resolve("pending.json.new").writeText("unfinished")
            else {
                val record = org.json.JSONObject(recovery.resolve("pending.json").readText())
                record.put("uri", if (invalid == "blank") "" else "content://different/document/song")
                if (invalid == "blank") {
                    val track = org.json.JSONObject(record.getString("track")).put("id", "")
                    record.put("track", track.toString())
                }
                recovery.resolve("pending.json").writeText(record.toString())
            }
        }) {
            barrier(); queues.retryPlayback(); barrier()
            assertTrue(readers.recovery() is RecoveryAdmission.Unknown)
            assertTrue(opened.isEmpty())
            withContext(Dispatchers.Main) { assertEquals(0, player.mediaItemCount) }
        }
    }

    @Test fun retainedUnsafeJournalCannotBeReplacedByEditingAnotherSong() = fixture(pending = 0) {
        val otherBefore = files[1].readBytes()
        val result = runCatching { queues.withFileEdit(tracks[1].id) {
            writer.edit(tracks[1], TagPatch(mapOf(EditField.Title to "Another")))
        } }
        assertTrue(result.isFailure)
        assertEquals(tracks[0].id, writer.pending()!!.track.id)
        assertEquals(RecoveryAdmission.Pending(tracks[0].id), readers.recovery())
        assertArrayEquals(otherBefore, files[1].readBytes())
        queues.selectEntry(original.entries[1].id)
        awaitMain { player.isPlaying }
    }

    @Test fun cancellationDuringFailedWriteAndLateInitializationCannotDropQuarantine() = fixture {
        play()
        documents.failWrites = true
        val gate = WriteGate().also { documents.nextWrite = it }
        val request = async { runCatching { queues.withFileEdit(tracks[0].id) {
            writer.edit(tracks[0], TagPatch(mapOf(EditField.Title to "After")))
        } } }
        var initialization: Deferred<Unit>? = null
        try {
            withTimeout(10000) { gate.entered.await() }
            assertEquals(RecoveryAdmission.Pending(tracks[0].id), readers.recovery())
            val probe = async { writer.initialize() }
            initialization = probe
            request.cancel()
            delay(100)
            assertFalse("Cancellation must await the in-flight destructive transaction", request.isCompleted)
            assertFalse("Startup probing must wait for the writer mutex", probe.isCompleted)
            gate.release.countDown()
            withTimeout(10000) { request.join(); probe.await() }
            assertNotNull(writer.pending())
            assertArrayEquals(byteArrayOf(1, 2), files[0].readBytes())
            assertEquals(RecoveryAdmission.Pending(tracks[0].id), readers.recovery())
            val late = sources.createDataSource()
            try { assertTrue(runCatching { withContext(Dispatchers.IO) { late.open(DataSpec(Uri.parse(tracks[0].uri))) } }.isFailure) }
            finally { withContext(Dispatchers.IO) { late.close() } }
        } finally { withContext(NonCancellable) {
            gate.release.countDown()
            withTimeout(10000) { request.cancelAndJoin(); initialization?.cancelAndJoin() }
        } }
    }

    @Test fun cancellationAfterVerificationKeepsSafeBytesButRestartConservativelyUsesJournal() = fixture {
        play()
        val verified = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val request = async { queues.withFileEdit(tracks[0].id) {
            writer.edit(tracks[0], TagPatch(mapOf(EditField.Title to "Verified")))
            verified.complete(Unit); finish.await()
        } }
        try {
            withTimeout(10000) { verified.await() }
            assertEquals(RecoveryAdmission.Clear, readers.recovery())
            assertNotNull(writer.pending())
            // Even verified bytes remain temporarily excluded until the action's cleanup exits.
            assertTrue(runCatching { readers.opening(tracks[0].id) }.isFailure)
            request.cancelAndJoin()
            awaitMain { player.isPlaying }
            assertEquals(RecoveryAdmission.Clear, readers.recovery())
            val restarted = NativeReaders(RecoveryAdmission.Checking)
            RecoverableTagWriter(File(directory, "recovery"), documents, restarted).initialize()
            assertEquals(RecoveryAdmission.Pending(tracks[0].id), restarted.recovery())
            assertTrue(runCatching { restarted.opening(tracks[0].id) }.isFailure)
        } finally { withContext(NonCancellable) { finish.complete(Unit); request.cancelAndJoin() } }
    }

    private fun fixture(pending: Int? = null, current: Int = 0, unreadable: Boolean = false,
        mutateRecovery: (File) -> Unit = {}, test: suspend Fixture.() -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = java.util.UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "queue-recovery-$id").apply { mkdirs() }
        val files = List(4) { index -> File(directory, "song-$index.mp3").also { file ->
            instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
        } }
        val originals = files.map { it.readBytes() }
        val tracks = files.map { Track(Uri.fromFile(it).toString(), "Before", fileName = it.name, relativePath = it.name) }
        val activeSources = ConcurrentHashMap<String, AtomicInteger>()
        val documents = FaultingDocuments(files.associateBy { Uri.fromFile(it).toString() }, activeSources)
        val seedWriter = RecoverableTagWriter(File(directory, "recovery"), documents)
        if (pending != null) {
            documents.failWrites = true
            assertTrue(runCatching { seedWriter.edit(tracks[pending], TagPatch(mapOf(EditField.Title to "After"))) }.isFailure)
            assertNotNull(seedWriter.pending())
        }
        if (unreadable) File(directory, "recovery").apply { mkdirs() }.resolve("pending.json").writeText("not-json")
        mutateRecovery(File(directory, "recovery"))
        val readers = NativeReaders(RecoveryAdmission.Checking)
        // Recreate the writer from retained files; startup cannot trust a previous in-memory verdict.
        val writer = RecoverableTagWriter(File(directory, "recovery"), documents, readers)
        val entries = listOf(tracks[0], tracks[1], tracks[0], tracks[2]).map { QueueEntry(track = it) }
        val original = ListeningQueue(name = "Recovery", entries = entries, currentEntryId = entries[current].id, positionMs = 1000)
        val database = Room.inMemoryDatabaseBuilder(context, QueueDatabase::class.java).build()
        withContext(Dispatchers.IO) { database.queues().save(QueueLibrary(), QueueLibrary(listOf(original), original.id)) }
        val preferences = QueuePreferences(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("queue-recovery-$id", mode)
        })
        val actor = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val opened = CopyOnWriteArrayList<String>()
        val delegate = DefaultDataSource.Factory(context)
        val factory = DataSource.Factory {
            val source = delegate.createDataSource()
            object : DataSource by source {
                private var active: AtomicInteger? = null
                override fun open(dataSpec: DataSpec): Long {
                    check(active == null)
                    active = activeSources.getOrPut(dataSpec.uri.toString()) { AtomicInteger() }.also { it.incrementAndGet() }
                    val size = source.open(dataSpec)
                    opened.add(dataSpec.uri.toString()) // Actual successful native delegate open.
                    return size
                }
                override fun close() {
                    try { source.close() }
                    finally { active?.decrementAndGet(); active = null }
                }
            }
        }
        val player = withContext(Dispatchers.Main) {
            ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(readers.track(factory))).build().apply { volume = 0f }
        }
        val queues = QueueController(database.queues(), preferences, (context.applicationContext as LocalicaApplication).library,
            actor, readers)
        val fixture = Fixture(this, queues, player, writer, documents, preferences, opened, directory, files, originals, tracks, original, readers, readers.track(factory))
        var primaryFailure: Throwable? = null
        try {
            queues.attach(player)
            fixture.awaitMain { !queues.state.value.loading && queues.state.value.problem?.startsWith("Checking") == true }
            assertTrue("Startup reached the gate before any native open", opened.isEmpty())
            runCatching { writer.initialize() }
            withTimeout(60000) { fixture.test() }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally { withContext(NonCancellable) {
            val closing = tracks.map { readers.block(it.id) }
            var closed = false
            try {
                try { withTimeout(10000) { actor.coroutineContext[Job]!!.cancelAndJoin() } }
                // Coroutine timeouts bound suspension, not the synchronous native release call.
                finally { withContext(Dispatchers.Main) { player.release() } }
                withTimeout(10000) { closing.forEach { it.awaitClosed() } }
                assertTrue(activeSources.values.all { it.get() == 0 })
                closed = true
            } catch (cleanup: Throwable) {
                if (primaryFailure == null) throw cleanup else primaryFailure.addSuppressed(cleanup)
            } finally {
                closing.forEach { it.release() }
                database.close()
                context.deleteSharedPreferences("queue-recovery-$id")
                if (closed) directory.deleteRecursively()
            }
        } }
    }

    private class Fixture(scope: CoroutineScope, val queues: QueueController, val player: ExoPlayer,
        val writer: RecoverableTagWriter, val documents: FaultingDocuments, val preferences: QueuePreferences,
        val opened: List<String>, val directory: File, val files: List<File>, val originalBytes: List<ByteArray>,
        val tracks: List<Track>, val original: ListeningQueue, val readers: NativeReaders,
        val sources: DataSource.Factory) : CoroutineScope by scope {
        private var checkpoints = 0
        suspend fun barrier() {
            // A real metadata publication confirms prior actor work without a new edit/reader lease.
            // Keeping an applicable recovery notice across this successful rename is also a contract.
            val name = "Checkpoint ${++checkpoints}"
            queues.rename(original.id, name)
            awaitMain { queues.state.value.library.queues.first { it.id == original.id }.name == name }
        }
        suspend fun awaitMain(condition: () -> Boolean) = withTimeout(7000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(10)
        }
        suspend fun play() { queues.retryPlayback(); awaitMain { player.isPlaying } }
        suspend fun failEdit(track: Track) {
            documents.failWrites = true
            val result = runCatching { queues.withFileEdit(track.id) { writer.edit(track, TagPatch(mapOf(EditField.Title to "After"))) } }
            assertTrue(result.isFailure)
            assertNotNull(writer.pending())
        }
    }

    private class FaultingDocuments(private val files: Map<String, File>,
        private val activeSources: Map<String, AtomicInteger>) : EditableDocuments {
        @Volatile var failWrites = false
        @Volatile var nextWrite: WriteGate? = null
        override fun writable(track: Track) = track.uri
        override fun open(uri: String) = files.getValue(uri).inputStream()
        override fun replace(uri: String, source: File) {
            check((activeSources[uri]?.get() ?: 0) == 0) { "Writer cannot replace while a native descriptor is open" }
            nextWrite?.also { nextWrite = null; it.await() }
            val file = files.getValue(uri)
            if (failWrites) { file.writeBytes(byteArrayOf(1, 2)); throw IOException("Provider disconnected during overwrite") }
            source.copyTo(file, overwrite = true)
        }
    }
    private class WriteGate {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        fun await() {
            entered.complete(Unit)
            check(release.await(20, TimeUnit.SECONDS)) { "Controlled provider write gate timed out" }
        }
    }

}
