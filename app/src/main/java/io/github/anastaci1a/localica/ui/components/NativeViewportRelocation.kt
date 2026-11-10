@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.*
import io.github.anastaci1a.localica.ui.engine.NativeViewportRelocation
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.Job

/** A scoped facade; it exports policy and evidence inputs, never a mutation capability. */
internal class NativeViewportRelocationHandle internal constructor(private val owner: NativeViewportRelocation) {
    val window get() = owner.window
    val bodyPolicy get() = owner.bodyPolicy
    val result get() = owner.result
    fun focus(identity: Long, present: Boolean) = owner.focusChanged(identity, present)
    fun native(frame: NativeRelocationFrame) = owner.native(frame)
    fun placed(placement: NativeRelocationPlacement) = owner.placed(placement)
    fun drag(identity: Any, began: Boolean) = owner.drag(identity, began)
    fun preFling(job: Job) = owner.preFling(job)
    fun postFling(job: Job) = owner.postFling(job)
    fun progress(running: Boolean) = owner.progress(running)
    fun admissionChanged() = owner.admissionChanged()
    fun cancel(reason: String) = owner.cancel(reason)
    suspend fun request(sequence: Long, focus: Long, forward: suspend () -> Unit) = owner.request(sequence, focus, forward)
    fun currentRequest(sequence: Long, focus: Long) = owner.currentRequest(sequence, focus)
    @Composable fun Body(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides owner.bodyPolicy, content = content)
    }
    @Composable fun Children(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides owner.inherited, content = content)
    }
}

@Composable
internal fun rememberNativeViewportRelocation(state: ScrollState, windowKey: Any,
    admitted: () -> Boolean): NativeViewportRelocationHandle {
    val inherited = LocalBringIntoViewSpec.current
    val scope = rememberCoroutineScope()
    val latestAdmission by rememberUpdatedState(admitted)
    val owner = remember(state, windowKey, inherited) {
        NativeViewportRelocation(state, scope, NativeRelocationWindow(), inherited) { latestAdmission() }
    }
    val handle = remember(owner) { NativeViewportRelocationHandle(owner) }
    DisposableEffect(owner) { onDispose { owner.close() } }
    LaunchedEffect(owner, state) {
        state.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> owner.drag(interaction, true)
                is DragInteraction.Stop -> owner.drag(interaction.start, false)
                is DragInteraction.Cancel -> owner.drag(interaction.start, false)
                else -> Unit
            }
        }
    }
    LaunchedEffect(owner, state) { snapshotFlow { state.isScrollInProgress }.collect { owner.progress(it) } }
    return handle
}
