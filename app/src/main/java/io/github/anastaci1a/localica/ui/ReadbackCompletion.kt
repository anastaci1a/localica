package io.github.anastaci1a.localica.ui

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// An instance-local owner could itself be part of an unreachable suspended-read cycle.
// This process root deliberately retains reads whose native callbacks never complete.
private val processReadbackOwner = SupervisorJob()

/** Cancellation suppresses publication, but cannot retire an unfinished native read. */
internal class ReadbackCompletion private constructor(private val readOwner: Job) {
    constructor() : this(processReadbackOwner)
    private val pending = Mutex()

    companion object {
        /** Weakly consistent scalar observation, not a completion fence. Excludes
         * completed-read CPU fallback work and framework/native dependencies.
         * Enumerates without collecting or exposing any child reference.
         */
        internal fun activeProcessReadCount(): Int = processReadbackOwner.children.count { it.isActive }

        /** Isolated ownership assertions without exposing/cancelling the production root. */
        internal fun withOwnerForTest(owner: Job) = ReadbackCompletion(owner)
    }

    suspend fun <L, T> capture(acquire: () -> L, read: suspend (L) -> T, release: (L) -> Unit): T =
        pending.withLock {
            val caller = currentCoroutineContext()
            caller.ensureActive()
            val lease = acquire()
            try {
                // Keep the read's own Job attached to the process root. Wrapping read
                // in NonCancellable would sever that attachment again at suspension.
                val owned = CoroutineScope(caller.minusKey(Job) + readOwner)
                    .async(start = CoroutineStart.UNDISPATCHED) { read(lease) }
                val result = withContext(NonCancellable) { owned.await() }
                caller.ensureActive()
                result
            } finally {
                release(lease)
            }
        }
}
