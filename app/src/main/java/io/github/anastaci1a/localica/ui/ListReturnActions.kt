package io.github.anastaci1a.localica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

/** Shared list step only. The tab caller, not Back, owns any preceding root navigation. */
@Composable
internal fun ListReturnActions(request: Int, owner: Any, active: Boolean, backEnabled: Boolean,
    action: ListReturnAction, onSubmit: () -> Unit = {}, ready: suspend () -> Unit, execute: suspend (ListReturnAction) -> Unit): ListReturnCommand {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(false) }
    val executor = remember(scope) { ListReturnExecutor(scope) { pending = it } }
    val currentOwner by rememberUpdatedState(owner)
    val currentActive by rememberUpdatedState(active)
    val currentReady by rememberUpdatedState(ready)
    val currentExecute by rememberUpdatedState(execute)
    val currentAction by rememberUpdatedState(action)
    val currentSubmit by rememberUpdatedState(onSubmit)
    var consumed by rememberSaveable { mutableIntStateOf(0) }
    DisposableEffect(owner, active) { onDispose { executor.cancel() } }
    OnModalGestureCancellation { executor.cancel() }
    fun submit(captured: ListReturnAction?) {
        val token = owner
        if (!currentActive || currentOwner !== token) return
        currentSubmit()
        executor.submit({ currentActive && currentOwner === token }, { currentReady() },
            { captured ?: currentAction }, { currentExecute(it) })
    }
    LaunchedEffect(request, active) {
        if (active && consumed != request) {
            // A request belongs to the current query inputs. Publication of its result
            // does not replace owner; a genuinely different route/data query cancels it.
            consumed = request
            submit(null)
        }
    }
    BackHandler(active && backEnabled && action != ListReturnAction.None) {
        // Capture this press's step; readiness must not turn a scroll request into filter clearing.
        submit(action)
    }
    // Visible controls use a captured step, just like Back. Readiness cannot turn
    // a return-to-top tap into clearing the list's filter.
    return ListReturnCommand({ submit(it) }, executor::cancel) { pending }
}
