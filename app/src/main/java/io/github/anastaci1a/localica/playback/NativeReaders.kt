package io.github.anastaci1a.localica.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.IOException

/** App-lifetime admission and actual-close acknowledgements, shared by every playback engine. */
class NativeReaders(initialRecovery: RecoveryAdmission = RecoveryAdmission.Clear) {
    private class Source {
        val readers = mutableSetOf<Reader>()
        val blocks = mutableSetOf<Block>()
        var failedClose: IOException? = null
    }
    private val lock = Any()
    private val sources = mutableMapOf<String, Source>()
    private var recovery = initialRecovery
    private val changed = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val recoveryChanges = changed.asSharedFlow()
    init { changed.tryEmit(Unit) }

    fun recovery(): RecoveryAdmission = synchronized(lock) { recovery }

    /** The writer serializes journal/outcome publication. Notifications carry no stale snapshot. */
    fun publishRecovery(value: RecoveryAdmission) {
        val notify = synchronized(lock) {
            if (recovery == value) false else { recovery = value; true }
        }
        // A collector may resume inline. Never run actor/native work while holding the monitor.
        if (notify) changed.tryEmit(Unit)
    }

    /** Register before calling the provider: opening and partially failed opens also own resources. */
    fun opening(identity: String): Reader = synchronized(lock) {
        if (recovery.blocks(identity)) throw IOException(recovery.explanation())
        val source = sources.getOrPut(identity) { Source() }
        if (source.blocks.isNotEmpty()) throw IOException("This song is being edited. Try playback again after the edit.")
        Reader(identity).also { source.readers.add(it) }
    }

    /** Atomic with opening; every earlier admission is included and every later one is rejected. */
    fun block(identity: String): Block {
        val (block, ready) = synchronized(lock) {
            val source = sources.getOrPut(identity) { Source() }
            val block = Block(identity).also { source.blocks.add(it) }
            block to when {
                source.failedClose != null -> Result.failure<Unit>(source.failedClose!!)
                source.readers.isEmpty() -> Result.success(Unit)
                else -> null
            }
        }
        ready?.fold(block.closed::complete, block.closed::completeExceptionally)
        return block
    }

    inner class Reader internal constructor(private val identity: String) {
        private var finished = false
        fun closed() {
            val waiting = synchronized(lock) {
                if (finished) return
                finished = true
                val source = sources.getValue(identity)
                source.readers.remove(this)
                val waiting = if (source.readers.isEmpty() && source.failedClose == null) source.blocks.toList() else emptyList()
                prune(identity, source)
                waiting
            }
            // Completion may resume an undispatched caller inline. Never run it under the monitor.
            waiting.forEach { it.closed.complete(Unit) }
        }
        fun failedClose(cause: Throwable) {
            val (waiting, failure) = synchronized(lock) {
                if (finished) return
                finished = true
                val source = sources.getValue(identity)
                val failure = source.failedClose ?: IOException(
                    "Playback could not confirm this song is closed. Editing is unavailable for this app session.", cause)
                source.failedClose = failure
                // Keep the unacknowledged reader across block release/reacquisition. A thrown close is
                // not proof that the provider released its descriptor, even if another close succeeds.
                source.blocks.toList() to failure
            }
            waiting.forEach { it.closed.completeExceptionally(failure) }
        }
    }

    inner class Block internal constructor(private val identity: String) {
        internal val closed = CompletableDeferred<Unit>()
        private var released = false
        suspend fun awaitClosed() { closed.await() }
        fun release() = synchronized(lock) {
            if (released) return@synchronized
            released = true
            val source = sources.getValue(identity)
            source.blocks.remove(this)
            prune(identity, source)
        }
    }

    private fun prune(identity: String, source: Source) {
        if (source.readers.isEmpty() && source.blocks.isEmpty() && source.failedClose == null) sources.remove(identity)
    }
}
