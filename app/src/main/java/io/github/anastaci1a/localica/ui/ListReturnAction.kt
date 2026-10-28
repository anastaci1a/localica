package io.github.anastaci1a.localica.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal enum class ListReturnAction { ScrollTop, ScrollSongs, ClearFilter, None }

internal fun listReturnAction(scrolled: Boolean, scrolling: Boolean, filtered: Boolean): ListReturnAction = when {
    scrolled || scrolling -> ListReturnAction.ScrollTop
    filtered -> ListReturnAction.ClearFilter
    else -> ListReturnAction.None
}

/** Preserve the callable action while exposing physical/modal takeover cancellation. */
internal class ListReturnCommand(private val submit: (ListReturnAction) -> Unit,
    private val onCancel: () -> Unit, private val active: () -> Boolean = { false }) : (ListReturnAction) -> Unit {
    val pending: Boolean get() = active()
    override fun invoke(action: ListReturnAction) = submit(action)
    fun cancel() = onCancel()
}

/** One list owns one command. Losing its route/query owner cancels both readiness and motion. */
internal class ListReturnExecutor(private val scope: CoroutineScope,
    private val onActivity: (Boolean) -> Unit = {}) {
    private var job: Job? = null
    fun cancel() {
        val old = job ?: return
        job = null
        old.cancel()
        onActivity(false)
    }
    fun submit(valid: () -> Boolean, ready: suspend () -> Unit,
        action: () -> ListReturnAction, execute: suspend (ListReturnAction) -> Unit) {
        cancel()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            ready()
            if (valid()) execute(action())
        }
        job = next
        onActivity(true)
        next.invokeOnCompletion {
            if (job === next) {
                job = null
                onActivity(false)
            }
        }
        next.start()
    }
}
