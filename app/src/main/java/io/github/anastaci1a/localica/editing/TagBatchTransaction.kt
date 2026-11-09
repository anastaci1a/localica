package io.github.anastaci1a.localica.editing

import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.documentIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal typealias TagFileScope = suspend (Track, suspend () -> Unit) -> Unit

/** Provider IO belongs to the supplied existing file scopes, never to the store's monitor. */
internal class TagBatchTransaction(private val writer: RecoverableTagWriter,
    private val editing: TagFileScope, private val recovering: TagFileScope,
    private val refresh: suspend (Track) -> Unit,
    private val changed: suspend (TagBatch) -> Unit = {}) {
    private val store = writer.batches
    private val worker = Mutex()

    suspend fun writeOne(id: String, index: Int): EditResult? = worker.withLock {
        val initial = current(id)
        if (initial.phase != TagBatchPhase.Saving) return@withLock null
        val track = initial.tracks[index]
        if (initial.entries[index]?.phase == TagBatchEntryPhase.Saved)
            return@withLock EditResult(track, true, "Saved and verified")
        var result: EditResult? = null
        editing(track) {
            var batch = current(id)
            if (batch.phase != TagBatchPhase.Saving) return@editing
            try {
                var entry = batch.entries[index]
                if (entry == null || (!entry.attempted && entry.original == null && batch.undo)) {
                    val source = writer.source(track)
                    entry = if (batch.undo) {
                        val copy = writer.snapshot(track, id, index)
                        TagBatchEntry(index, copy.uri, copy.original.name, copy.hash, copy.original.length())
                    } else TagBatchEntry(index, source.uri, null, null, source.size)
                    batch = update(id, entry)
                }
                if (batch.phase != TagBatchPhase.Saving) return@editing
                refresh(track.copy(uri = entry.uri))
                entry = entry.copy(phase = TagBatchEntryPhase.Writing, attempted = true, message = null)
                batch = update(id, entry)
                if (batch.phase != TagBatchPhase.Saving) return@editing
                val receipt = writer.editChecked(track, requireNotNull(batch.patch), entry.originalHash, id)
                // Do not discard the single-file recovery until this exact outcome commits.
                update(id, entry.copy(phase = TagBatchEntryPhase.Saved, originalHash = entry.originalHash ?: receipt.originalHash, writtenHash = receipt.writtenHash, message = null))
                writer.complete()
                result = EditResult(receipt.track, true, "Saved and verified")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // Every decision starts from reopened, validated committed records. A failed
                // publication may already have reached disk; never overwrite it from a cache.
                batch = current(id)
                val pending = writer.pending()
                if (pending != null || batch.entries[index]?.phase == TagBatchEntryPhase.Saved) {
                    if (batch.phase == TagBatchPhase.Saving) transition(id, TagBatchPhase.Interrupted)
                    throw failure
                }
                val entry = batch.entries[index]
                if (entry != null) update(id, entry.copy(phase = TagBatchEntryPhase.Failed, message = failure.message))
                else {
                    // A failed snapshot can leave uncommitted bytes. Do not adopt those bytes as
                    // another first original: retain the batch for explicit restore/cleanup.
                    if (batch.phase == TagBatchPhase.Saving) transition(id, TagBatchPhase.Interrupted)
                    throw failure
                }
                result = EditResult(track, false, failure.message ?: "Couldn’t save this file.")
            }
        }
        result
    }

    /** Must run before resuming a reconstructed batch or restoring all its files. */
    suspend fun reconcile(id: String) = worker.withLock { reconcileLocked(id) }

    private suspend fun reconcileLocked(id: String) {
        var batch = current(id)
        val pending = writer.pending()
        if (pending != null) {
            val index = batch.tracks.indexOfFirst { documentIdentity(it.id) == documentIdentity(pending.track.id) }
            val entry = batch.entries[index]
            check(entry != null && entry.attempted && documentIdentity(entry.uri) == documentIdentity(pending.uri)) {
                "A different interrupted file must be recovered before this batch."
            }
            check((!batch.undo && entry.originalHash == null) || pending.hash == entry.originalHash) { "The interrupted file does not match its first undo copy." }
            // This callback acquires a NEW source-bound request; never reuse one for the next file.
            recovering(pending.track) {
                refresh(pending.track.copy(uri = pending.uri))
                writer.recover()
            }
            val cancelling = batch.phase in listOf(TagBatchPhase.CancelRequested, TagBatchPhase.Restoring)
            update(id, entry.copy(originalHash = entry.originalHash ?: pending.hash, phase = if (cancelling) TagBatchEntryPhase.Restored else TagBatchEntryPhase.Prepared,
                writtenHash = null, message = null))
            batch = current(id)
        }
        // Recovery may have verified bytes and cleared its journal immediately before process
        // loss. Inspect real source hashes before trusting any old Saved/Writing outcome.
        for (entry in batch.entries.values.filter { it.attempted && it.phase != TagBatchEntryPhase.Restored }) {
            val track = batch.tracks[entry.index]
            recovering(track) {
                val actual = writer.currentHash(track, entry.uri)
                when {
                    actual == entry.originalHash -> {
                        val cancelling = batch.phase in listOf(TagBatchPhase.CancelRequested, TagBatchPhase.Restoring)
                        update(id, entry.copy(phase = if (cancelling) TagBatchEntryPhase.Restored else TagBatchEntryPhase.Prepared,
                            writtenHash = null, message = null))
                    }
                    actual == entry.writtenHash && entry.writtenHash != null -> Unit
                    !batch.undo && entry.phase == TagBatchEntryPhase.Prepared -> Unit
                    else -> error("The song changed outside this batch or its interrupted outcome is unknown. Undo files are retained.")
                }
            }
        }
    }

    suspend fun restore(id: String) = worker.withLock {
        var batch = current(id)
        check(batch.undo && !batch.terminal)
        if (batch.phase !in listOf(TagBatchPhase.CancelRequested, TagBatchPhase.Restoring))
            batch = transition(id, TagBatchPhase.CancelRequested)
        reconcileLocked(id)
        batch = transition(id, TagBatchPhase.Restoring)
        for (index in batch.entries.keys.sorted()) {
            val latest = current(id)
            val entry = requireNotNull(latest.entries[index])
            if (!entry.attempted || entry.phase == TagBatchEntryPhase.Restored) continue
            val track = latest.tracks[index]
            recovering(track) {
                val original = io { store.checkedOriginal(latest, entry) }
                refresh(track.copy(uri = entry.uri))
                val restoring = entry.copy(phase = TagBatchEntryPhase.Restoring)
                update(id, restoring)
                writer.restoreSnapshot(original, setOfNotNull(entry.writtenHash, entry.originalHash), id)
                update(id, restoring.copy(phase = TagBatchEntryPhase.Restored, writtenHash = null, message = null))
            }
        }
        val terminal = transition(id, TagBatchPhase.Restored)
        io { store.cleanup(id) }
        changed(terminal)
    }

    suspend fun commit(id: String): TagBatch = worker.withLock {
        val batch = current(id)
        check(batch.phase == TagBatchPhase.AwaitingDecision) { "Finish the active work before committing." }
        check(writer.pending() == null) { "Recover the interrupted file before finishing this batch." }
        check(batch.entries.values.none { it.phase == TagBatchEntryPhase.Writing || it.phase == TagBatchEntryPhase.Restoring })
        val terminal = transition(id, TagBatchPhase.Committed)
        io { store.cleanup(id) }
        terminal
    }

    suspend fun requestCancel(id: String): TagBatch = transition(id, TagBatchPhase.CancelRequested)
    private suspend fun current(id: String): TagBatch = io { requireNotNull(store.read()).also { check(it.id == id) } }
    private suspend fun transition(id: String, phase: TagBatchPhase): TagBatch = io { store.changePhase(id, phase) }.also { changed(it) }
    private suspend fun update(id: String, entry: TagBatchEntry): TagBatch = io { store.put(id, entry) }.also { changed(it) }
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
