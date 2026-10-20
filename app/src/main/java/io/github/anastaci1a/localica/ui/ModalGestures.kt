package io.github.anastaci1a.localica.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.Window
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A modal owns input when requested, not when its entrance/exit animation finishes. */
internal class ModalGestureCoordinator {
    private val windows = mutableSetOf<ModalGestureScope>()
    private val active = mutableListOf<ModalGestureScope>()

    fun attach(window: ModalGestureScope) {
        windows += window
        update()
    }
    fun detach(window: ModalGestureScope) {
        windows -= window
        window.block(false)
    }
    fun activate(window: ModalGestureScope) {
        if (window !in active) { active += window; update() }
    }
    fun deactivate(window: ModalGestureScope) {
        if (active.remove(window)) update()
    }
    private fun update() {
        val top = active.lastOrNull()
        windows.toList().forEach { it.block(if (top == null) !it.root else it !== top) }
    }
}

/** Registrations belong to their native window, so nested popup scrolling stays available. */
internal class ModalGestureScope(
    val coordinator: ModalGestureCoordinator,
    private val parent: ModalGestureScope?,
    private val coroutineScope: CoroutineScope,
) {
    var blocked = false
        private set
    var generation = 0L
        private set
    private val cancellations = mutableSetOf<() -> Unit>()
    private val scrolls = mutableMapOf<ScrollableState, Pair<Int, Job?>>()

    val root get() = parent == null
    fun activate() = coordinator.activate(this)
    fun deactivate() = coordinator.deactivate(this)
    fun block(value: Boolean) {
        if (blocked == value) return
        blocked = value
        if (value) {
            generation++
            cancellations.toList().forEach { it() }
        }
        scrolls.keys.toList().forEach { state ->
            val (count, job) = scrolls.getValue(state)
            job?.cancel()
            scrolls[state] = count to if (value) stopScroll(state) else null
        }
    }
    private fun stopScroll(state: ScrollableState) =
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Stop current motion without locking out source-layout restoration.
            // The nested-scroll barrier also rejects a later queued drag-stop fling.
            state.stopScroll(MutatePriority.PreventUserInput)
        }

    fun registerCancellation(cancel: () -> Unit): () -> Unit {
        cancellations += cancel
        if (blocked) cancel()
        return { cancellations -= cancel }
    }
    fun registerScroll(state: ScrollableState): () -> Unit {
        val old = scrolls[state]
        scrolls[state] = if (old == null) 1 to if (blocked) stopScroll(state) else null
            else old.first + 1 to old.second
        return {
            val current = scrolls.getValue(state)
            if (current.first == 1) { current.second?.cancel(); scrolls -= state }
            else scrolls[state] = current.first - 1 to current.second
        }
    }
}

internal val LocalModalGestures = staticCompositionLocalOf<ModalGestureScope?> { null }

@Composable
internal fun rememberModalGestureScope(): ModalGestureScope {
    val parent = LocalModalGestures.current
    val coroutineScope = rememberCoroutineScope()
    return remember(parent) {
        ModalGestureScope(parent?.coordinator ?: ModalGestureCoordinator(), parent, coroutineScope)
    }
}

@Composable
internal fun ModalGestureRoot(content: @Composable () -> Unit) {
    if (LocalModalGestures.current != null) content()
    else ModalGestureWindow(rememberModalGestureScope(), content)
}

@Composable
internal fun ModalGestureWindow(scope: ModalGestureScope, content: @Composable () -> Unit) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.gestureActivity()?.window
    DisposableEffect(scope, window) {
        val previous = window?.callback
        val callback = previous?.let { ModalGestureWindowCallback(it, scope) }
        if (callback != null) window.callback = callback
        val unregister = callback?.let { scope.registerCancellation(it::cancelContact) }
        scope.coordinator.attach(scope)
        onDispose {
            unregister?.invoke()
            callback?.dispose()
            if (window?.callback === callback) window?.callback = previous
            scope.coordinator.detach(scope)
        }
    }
    CompositionLocalProvider(LocalModalGestures provides scope, content = content)
}

/** Join the common modal barrier without changing Foundation's scrolling or semantics. */
internal fun Modifier.modalScroll(state: ScrollableState): Modifier = composed {
    val owner = LocalModalGestures.current
    val barrier = remember(owner) { ModalScrollBarrier(owner) }
    DisposableEffect(owner, state) {
        val unregister = owner?.registerScroll(state)
        onDispose { unregister?.invoke() }
    }
    Modifier.nestedScroll(barrier)
}

/** The cancellation epoch also rejects drag-stop work queued past a quick dismissal. */
internal class ModalScrollBarrier(private val owner: ModalGestureScope?) : NestedScrollConnection {
    private var contactGeneration = owner?.generation
    private val stale get() = owner?.blocked == true || contactGeneration != owner?.generation
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && owner?.blocked != true) contactGeneration = owner?.generation
        return if (stale) available else Offset.Zero
    }
    override suspend fun onPreFling(available: Velocity) = if (stale) available else Velocity.Zero
}

@Composable
internal fun OnModalGestureCancellation(onCancel: () -> Unit) {
    val owner = LocalModalGestures.current
    val cancel by rememberUpdatedState(onCancel)
    DisposableEffect(owner) {
        val unregister = owner?.registerCancellation { cancel() }
        onDispose { unregister?.invoke() }
    }
}

/** Once cancelled, a physical contact cannot become a fresh gesture after modal dismissal. */
internal class ModalPointerStream {
    private val pointers = mutableSetOf<Int>()
    var quarantined = false
        private set
    val active get() = pointers.isNotEmpty()
    fun down(id: Int, blocked: Boolean): Boolean {
        pointers.clear()
        pointers += id
        quarantined = blocked
        return !quarantined
    }
    fun pointerDown(id: Int) { pointers += id }
    fun pointerUp(id: Int) { pointers -= id }
    fun end() { pointers.clear() }
    fun cancel() { if (active) quarantined = true }
}

/** Observe the complete native stream, including pointers outside any Compose hit path. */
private class ModalGestureWindowCallback(
    private val original: Window.Callback,
    private val owner: ModalGestureScope,
) : Window.Callback by original {
    private val stream = ModalPointerStream()
    private var lastEvent: MotionEvent? = null
    private var dispatching = false
    private var cancelPending = false
    private var disposed = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (disposed) return original.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> stream.down(event.getPointerId(0), owner.blocked)
            MotionEvent.ACTION_POINTER_DOWN -> stream.pointerDown(event.getPointerId(event.actionIndex))
        }
        val swallow = owner.blocked || stream.quarantined
        if (!swallow) {
            lastEvent?.recycle()
            lastEvent = MotionEvent.obtain(event)
        }
        try {
            dispatching = true
            return swallow || original.dispatchTouchEvent(event)
        } finally {
            dispatching = false
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_UP -> stream.pointerUp(event.getPointerId(event.actionIndex))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stream.end()
                    lastEvent?.recycle()
                    lastEvent = null
                }
            }
            if (cancelPending) dispatchCancel()
        }
    }

    fun cancelContact() {
        if (!stream.active || stream.quarantined) return
        stream.cancel()
        cancelPending = true
        if (!dispatching) dispatchCancel()
    }
    private fun dispatchCancel() {
        cancelPending = false
        lastEvent?.let { source ->
            val cancel = MotionEvent.obtain(source)
            cancel.action = MotionEvent.ACTION_CANCEL
            try { original.dispatchTouchEvent(cancel) }
            finally { cancel.recycle() }
        }
        lastEvent?.recycle()
        lastEvent = null
    }
    fun dispose() {
        disposed = true
        lastEvent?.recycle()
        lastEvent = null
    }
}

private tailrec fun Context.gestureActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.gestureActivity()
    else -> null
}
