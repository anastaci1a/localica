package io.github.anastaci1a.localica.ui

import kotlinx.coroutines.*

/** Owned by one UI lifetime; submission, cancellation and publication stay on its scope thread. */
internal class LatestUiRequest {
    private var current: Any? = null
    private var job: Job? = null

    fun cancel() {
        current = null
        job?.cancel()
        job = null
    }

    fun <T : Any> submit(scope: CoroutineScope, dispatcher: CoroutineDispatcher,
        stillCurrent: () -> Boolean, query: suspend () -> T?, publish: (T) -> Unit): Job {
        cancel()
        val request = Any()
        current = request
        val submitted = scope.launch {
            val result = withContext(dispatcher) { query() }
            ensureActive()
            if (result != null && current === request && stillCurrent()) publish(result)
        }
        job = submitted
        return submitted
    }
}

