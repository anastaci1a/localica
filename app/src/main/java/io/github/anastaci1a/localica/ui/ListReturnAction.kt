package io.github.anastaci1a.localica.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class ListReturnAction { ScrollTop, ClearFilter, None }

internal fun listReturnAction(scrolled: Boolean, scrolling: Boolean, filtered: Boolean): ListReturnAction = when {
    scrolled || scrolling -> ListReturnAction.ScrollTop
    filtered -> ListReturnAction.ClearFilter
    else -> ListReturnAction.None
}

/** One list owns one command. Losing its route/query owner cancels both readiness and motion. */
internal class ListReturnExecutor(private val scope: CoroutineScope) {
    private var job: Job? = null
    fun cancel() { job?.cancel(); job = null }
    fun submit(valid: () -> Boolean, ready: suspend () -> Unit,
        action: () -> ListReturnAction, execute: suspend (ListReturnAction) -> Unit) {
        cancel()
        job = scope.launch {
            ready()
            if (valid()) execute(action())
        }
    }
}
