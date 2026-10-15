package io.github.anastaci1a.localica.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

/** Shared list step only. The tab caller, not Back, owns any preceding root navigation. */
@Composable
internal fun ListReturnActions(request: Int, owner: Any, active: Boolean, backEnabled: Boolean,
    action: ListReturnAction, ready: suspend () -> Unit, execute: suspend (ListReturnAction) -> Unit) {
    val scope = rememberCoroutineScope()
    val executor = remember(scope) { ListReturnExecutor(scope) }
    val currentOwner by rememberUpdatedState(owner)
    val currentActive by rememberUpdatedState(active)
    val currentReady by rememberUpdatedState(ready)
    val currentExecute by rememberUpdatedState(execute)
    val currentAction by rememberUpdatedState(action)
    var consumed by rememberSaveable { mutableIntStateOf(0) }
    DisposableEffect(owner, active) { onDispose { executor.cancel() } }
    fun submit(captured: ListReturnAction?) {
        val token = owner
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
}
