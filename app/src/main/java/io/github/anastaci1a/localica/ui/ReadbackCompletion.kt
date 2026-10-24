package io.github.anastaci1a.localica.ui

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Cancellation suppresses publication, but cannot retire an unfinished native read. */
internal class ReadbackCompletion {
    private val pending = Mutex()

    suspend fun <L, T> capture(acquire: () -> L, read: suspend (L) -> T, release: (L) -> Unit): T =
        pending.withLock {
            currentCoroutineContext().ensureActive()
            val lease = acquire()
            try {
                val result = withContext(NonCancellable) { read(lease) }
                currentCoroutineContext().ensureActive()
                result
            } finally {
                release(lease)
            }
        }
}
