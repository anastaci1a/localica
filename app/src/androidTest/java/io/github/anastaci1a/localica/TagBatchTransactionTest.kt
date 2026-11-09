package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.documentIdentity
import io.github.anastaci1a.localica.playback.NativeReaders
import io.github.anastaci1a.localica.playback.RecoveryAdmission
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Real media/AtomicFile and native reader admission; queue/playback-intent proofs remain separate. */
class TagBatchTransactionTest {
    @Test fun deletedEntryCannotAuthorizeCancellationOrCommitCleanup() = fixture { runBlocking {
        val batch = begin(); transaction.writeOne(batch.id, 0)
        val written = files[0].readBytes()
        assertTrue(File(batchDirectory, "${batch.id}/entries/0.json").delete())
        reopen(TagBatchStore(batchDirectory))
        assertTrue(runCatching { transaction.restore(batch.id) }.isFailure)
        assertArrayEquals(written, files[0].readBytes())
        assertTrue(store.original(batch.id, 0).isFile)
        assertNotNull(store.read())
    } }

    @Test fun unpublishedSnapshotCannotBeAdoptedOrSilentlyCleanedAfterRestart() = fixture { runBlocking {
        val batch = begin()
        writer.snapshot(tracks[0], batch.id, 0)
        reopen(TagBatchStore(batchDirectory))
        assertTrue(runCatching { transaction.writeOne(batch.id, 0) }.isFailure)
        assertTrue(runCatching { transaction.restore(batch.id) }.isFailure)
        assertOriginals()
        assertTrue(store.original(batch.id, 0).isFile)
    } }

    @Test fun missingOriginalRetainsWrittenBytesAndAllowsCheckedRecoveryRetry() = fixture { runBlocking {
        val batch = begin(); transaction.writeOne(batch.id, 0)
        val copy = store.original(batch.id, 0)
        assertTrue(copy.delete())
        val written = files[0].readBytes()
        assertTrue(runCatching { transaction.restore(batch.id) }.isFailure)
        assertArrayEquals(written, files[0].readBytes())
        assertNotNull(store.read())
        copy.writeBytes(originals[0])
        transaction.restore(batch.id); assertOriginals()
    } }

    @Test fun restoreMetadataFailureRetainsOriginalUntilRetryRegistersRefresh() = fixture { runBlocking {
        val batch = begin(); transaction.writeOne(batch.id, 0)
        val written = files[0].readBytes()
        val failed = TagBatchTransaction(writer, { _, action -> action() }, { _, action -> action() },
            { throw IOException("Refresh journal unavailable") })
        assertTrue(runCatching { failed.restore(batch.id) }.isFailure)
        assertArrayEquals(written, files[0].readBytes())
        assertTrue(store.original(batch.id, 0).isFile)
        reopen(TagBatchStore(batchDirectory))
        transaction.restore(batch.id); assertOriginals()
    } }

    @Test fun spaceCalculationIncludesUndoAndRejectsOverflow() {
        assertEquals(100L, TagSaveSpace.required(100, 60, 10, true) - TagSaveSpace.required(100, 60, 10, false))
        assertTrue(TagSaveSpace.required(100, 60, 10, false) > 64L * 1024 * 1024 + 120)
        assertTrue(runCatching { TagSaveSpace.required(Long.MAX_VALUE, Long.MAX_VALUE, 0, true) }.isFailure)
    }

    @Test fun cancelDuringSecondReplacementRestoresAllBytesAndArtwork() = fixture { runBlocking {
        val batch = begin()
        assertTrue(transaction.writeOne(batch.id, 0)!!.saved)
        assertFalse(originals[0].contentEquals(files[0].readBytes()))
        access.hold = tracks[1].uri
        val write = async(Dispatchers.Default) { transaction.writeOne(batch.id, 1) }
        try {
            assertTrue("Second replacement reached its provider barrier", access.entered.await(15, TimeUnit.SECONDS))
            transaction.requestCancel(batch.id)
            transaction.requestCancel(batch.id)
            assertEquals(TagBatchPhase.CancelRequested, store.read()!!.phase)
        } finally { access.release.countDown() }
        write.await()
        transaction.restore(batch.id)
        assertOriginals()
        assertNull(store.read())
        assertEquals("Untouched file was never replaced", 0, access.replacements[tracks[2].uri] ?: 0)
        assertTrue("Each file restore acquired its own source scope", recoverySources.containsAll(tracks.take(2).map { documentIdentity(it.id) }))
        assertTrue(refreshed.containsAll(tracks.take(2).map { it.id }))
    } }

    @Test fun outcomePublicationFailureRetainsSingleJournalAndReconcilesStaleSavedCount() = fixture { runBlocking {
        var fail = true
        store = TagBatchStore(batchDirectory) { atomic, bytes ->
            commitRefreshRecord(atomic, bytes)
            if (fail && JSONObject(bytes.toString(Charsets.UTF_8)).optString("phase") == "Saved") {
                fail = false; throw IOException("After committed outcome")
            }
        }
        reopen(store)
        val batch = begin()
        assertTrue(runCatching { transaction.writeOne(batch.id, 0) }.isFailure)
        assertNotNull(writer.pending())
        assertEquals(1, store.read()!!.saved)
        // Simulate a new process: no running writer's verified flag can clear this quarantine.
        reopen(TagBatchStore(batchDirectory))
        writer.initialize()
        assertTrue(readers.recovery().blocks(documentIdentity(tracks[0].id)))
        transaction.reconcile(batch.id)
        assertEquals(0, store.read()!!.saved)
        assertArrayEquals(originals[0], files[0].readBytes())
        store.changePhase(batch.id, TagBatchPhase.Saving)
        assertTrue(transaction.writeOne(batch.id, 0)!!.saved)
        transaction.requestCancel(batch.id); transaction.restore(batch.id)
        assertOriginals()
    } }

    @Test fun missingOrCorruptUndoCopyCannotClaimCancellationOrOverwriteAudio() = fixture { runBlocking {
        val batch = begin()
        transaction.writeOne(batch.id, 0)
        val copy = store.original(batch.id, 0)
        copy.writeBytes(ByteArray(originals[0].size) { 1 })
        val written = files[0].readBytes()
        assertTrue(runCatching { transaction.restore(batch.id) }.isFailure)
        assertArrayEquals(written, files[0].readBytes())
        assertNotNull(store.read())
        assertTrue(copy.isFile)
        copy.writeBytes(originals[0])
        transaction.restore(batch.id)
        assertOriginals()
    } }

    @Test fun changedExternalBytesFailClosedInsteadOfBeingSilentlyUndone() = fixture { runBlocking {
        val batch = begin(); transaction.writeOne(batch.id, 0)
        val written = files[0].readBytes()
        val foreign = byteArrayOf(3, 1, 4, 1, 5)
        files[0].writeBytes(foreign)
        assertTrue(runCatching { transaction.restore(batch.id) }.isFailure)
        assertArrayEquals(foreign, files[0].readBytes())
        assertNotNull(store.read())
        files[0].writeBytes(written)
        transaction.restore(batch.id); assertOriginals()
    } }

    @Test fun terminalHeaderSurvivesMissingPayloadDuringInterruptedCleanup() = fixture { runBlocking {
        val batch = begin(); transaction.writeOne(batch.id, 0)
        store.changePhase(batch.id, TagBatchPhase.AwaitingDecision)
        store.changePhase(batch.id, TagBatchPhase.Committed)
        // A completed decision owns this deletion; restarting must not demand deleted payloads.
        assertTrue(File(batchDirectory, "${batch.id}/batch.json").delete())
        val restarted = TagBatchStore(batchDirectory)
        assertTrue(restarted.read()!!.terminal)
        restarted.cleanup(batch.id)
        assertNull(restarted.read())
    } }

    @Test fun abandonedAtomicUpdateSelectsTheValidatedOldCommittedState() = fixture {
        val batch = begin()
        AtomicFile(File(batchDirectory, "active.json")).startWrite().use { it.write("unfinished".toByteArray()) }
        val restarted = TagBatchStore(batchDirectory)
        assertEquals(batch.id, restarted.read()!!.id)
        assertEquals(TagBatchPhase.Saving, restarted.read()!!.phase)
    }

    @Test fun uncertainCancelCommitCannotBecomeResumeOrDone() = fixture {
        val batch = begin()
        val failing = TagBatchStore(batchDirectory) { atomic, bytes ->
            commitRefreshRecord(atomic, bytes)
            if (JSONObject(bytes.toString(Charsets.UTF_8)).optString("phase") == "CancelRequested")
                throw IOException("After committed cancellation")
        }
        assertTrue(runCatching { failing.changePhase(batch.id, TagBatchPhase.CancelRequested) }.isFailure)
        val restarted = TagBatchStore(batchDirectory)
        assertEquals(TagBatchPhase.CancelRequested, restarted.read()!!.phase)
        assertTrue(runCatching { restarted.changePhase(batch.id, TagBatchPhase.Saving) }.isFailure)
        assertTrue(runCatching { restarted.changePhase(batch.id, TagBatchPhase.Committed) }.isFailure)
    }

    @Test fun failedRestoreRetainsQuarantineAndRestartsWithExactOriginalBytes() = fixture { runBlocking {
        val batch = begin(); transaction.writeOne(batch.id, 0)
        access.failRestore = true
        assertTrue(runCatching { transaction.restore(batch.id) }.isFailure)
        assertNotNull(writer.pending())
        assertTrue(readers.recovery().blocks(documentIdentity(tracks[0].id)))
        assertTrue(runCatching { readers.opening(documentIdentity(tracks[0].id)) }.isFailure)
        readers.opening("unrelated-source").closed()
        access.failRestore = false
        reopen(TagBatchStore(batchDirectory)); writer.initialize()
        transaction.restore(batch.id)
        assertOriginals(); assertNull(store.read()); assertNull(writer.pending())
    } }

    @Test fun unreadableBatchCannotBeHiddenByInitializeOrLaterPendingProbe() = fixture { runBlocking {
        begin()
        val header = File(batchDirectory, "active.json")
        val valid = header.readBytes()
        header.writeText("corrupt committed ownership")
        assertTrue(runCatching { writer.initialize() }.isFailure)
        assertTrue(readers.recovery() is RecoveryAdmission.Unknown)
        assertTrue(runCatching { writer.pending() }.isFailure)
        assertTrue(readers.recovery() is RecoveryAdmission.Unknown)
        header.writeBytes(valid)
        writer.initialize()
        assertEquals(RecoveryAdmission.Clear, readers.recovery())
    } }

    @Test fun explicitNoUndoKeepsHashesButCreatesNoRetainedOriginals() = fixture { runBlocking {
        val batch = store.begin(tracks, patch, null, undo = false)
        transaction.writeOne(batch.id, 0)
        assertFalse(File(batchDirectory, "${batch.id}/originals").exists())
        assertNotNull(store.read()!!.entries[0]!!.originalHash)
        assertTrue(runCatching { transaction.requestCancel(batch.id) }.isFailure)
        store.changePhase(batch.id, TagBatchPhase.AwaitingDecision)
        transaction.commit(batch.id)
        assertNull(store.read())
        assertFalse(originals[0].contentEquals(files[0].readBytes()))
    } }

    @Test fun aliasesUseOneFirstTouchOriginalAndOneProgressItem() = fixture {
        fun alias(tree: String) = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri("batch.test", tree), "same-song").toString()
        val one = tracks[0].copy(uri = alias("first"), id = alias("first"))
        val two = one.copy(uri = alias("second"), id = alias("second"))
        assertEquals(documentIdentity(one.id), documentIdentity(two.id))
        val batch = store.begin(listOf(one, two), patch, null, undo = true)
        assertEquals(1, batch.tracks.size)
    }

    private fun fixture(block: Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.access.release.countDown(); fixture.directory.deleteRecursively() }
    }

    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "batch-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val batchDirectory = File(directory, "tag-batches")
        val files = listOf("tagged.mp3", "tagged.flac", "tone.wav").map { name -> File(directory, name).also { file ->
            instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        } }
        val patch = TagPatch(mapOf(EditField.Title to "Batch saved"), ArtworkChange.Remove)
        val originals: List<ByteArray>
        val tracks = files.map { Track(Uri.fromFile(it).toString(), "Original", fileName = it.name, relativePath = it.name) }
        val access = BarrierDocuments()
        val recoverySources = mutableListOf<String>()
        val refreshed = mutableListOf<String>()
        var readers = NativeReaders()
        var store = TagBatchStore(batchDirectory)
        lateinit var writer: RecoverableTagWriter
        lateinit var transaction: TagBatchTransaction
        init {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            files.take(2).forEach { TagCodec.edit(it, TagPatch(artwork = ArtworkChange.Replace(CoverImage(bytes, "image/png", 8, 8)))) }
            originals = files.map(File::readBytes)
            reopen(store)
        }
        fun begin() = store.begin(tracks, patch, "{\"values\":\"retained draft\"}", undo = true)
        fun reopen(next: TagBatchStore) {
            store = next
            readers = NativeReaders(RecoveryAdmission.Checking)
            writer = RecoverableTagWriter(File(directory, "tag-recovery"), access, readers, store)
            val editing: TagFileScope = { track, action ->
                val lease = readers.block(documentIdentity(track.id))
                try { lease.awaitClosed(); action() } finally { lease.release() }
            }
            val recovering: TagFileScope = { track, action ->
                recoverySources += documentIdentity(track.id)
                editing(track, action)
            }
            transaction = TagBatchTransaction(writer, editing, recovering, { refreshed += it.id })
        }
        fun assertOriginals() { files.forEachIndexed { index, file -> assertArrayEquals(file.name, originals[index], file.readBytes()) } }
    }

    private class BarrierDocuments : EditableDocuments {
        var hold: String? = null
        var failRestore = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacements = mutableMapOf<String, Int>()
        override fun writable(track: Track) = track.uri
        override fun open(uri: String) = File(requireNotNull(Uri.parse(uri).path)).inputStream()
        override fun size(uri: String) = File(requireNotNull(Uri.parse(uri).path)).length()
        override fun replace(uri: String, source: File) {
            replacements[uri] = (replacements[uri] ?: 0) + 1
            val file = File(requireNotNull(Uri.parse(uri).path))
            if (failRestore && source.name.startsWith("original.")) {
                file.writeBytes(byteArrayOf(8, 9)); throw IOException("Controlled restore disconnect")
            }
            if (hold == uri) {
                hold = null
                file.writeBytes(byteArrayOf(0, 1))
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "Owned provider barrier timed out" }
            }
            source.copyTo(file, overwrite = true)
        }
    }
}
