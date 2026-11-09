package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.anastaci1a.localica.toast.*
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private data class ToastActivation(val id: Long, val generation: Long, val body: Boolean)

internal data class ToastContent(val entry: ToastEntry, val enabled: Boolean, val readingPaused: Boolean,
    val bodyAction: () -> Unit, val action: () -> Unit, val pending: () -> Unit, val read: () -> Unit,
    val readingReady: (Boolean) -> Unit)

/** One Activity-composition lease. Only this component owns the dwell coroutine and contact/reset
 * protocol. Screens supply the stock material, artwork and typed command bridge, never motion. */
@Composable internal fun ToastPresenter(queue: ToastQueue, root: ToastRowRoot, modal: Boolean,
    command: (ToastCommand, () -> Boolean) -> Unit, shadowClearancePx: Float,
    surface: @Composable (Modifier, @Composable () -> Unit) -> Unit,
    content: @Composable (ToastContent) -> Unit) {
    var attached by remember(queue) { mutableStateOf<ToastPresenterLease?>(null) }
    DisposableEffect(queue) {
        val lease = queue.attachPresenter()
        lease.pause(ToastPause.Settling, true)
        attached = lease
        onDispose { lease.release() }
    }
    val token by queue.activePresenter.collectAsState()
    val state by queue.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val foreground = lifecycleState == Lifecycle.State.RESUMED
    val lease = attached ?: return
    if (token != lease.token) return
    SideEffect {
        lease.pause(ToastPause.Background, !foreground)
        lease.pause(ToastPause.Modal, modal)
    }
    val deadline = state.deadline
    LaunchedEffect(lease, deadline) {
        if (deadline != null) {
            val remaining = lease.delayUntil(deadline) ?: return@LaunchedEffect
            delay(remaining)
            lease.timeout(deadline)
        }
    }
    val entry = state.current ?: return
    key(lease, entry.id) {
        ToastEntryPresenter(lease, state, root, modal || !foreground, command, shadowClearancePx, surface, content)
    }
}

@Composable private fun ToastEntryPresenter(lease: ToastPresenterLease, state: ToastQueueState,
    root: ToastRowRoot, blocked: Boolean, command: (ToastCommand, () -> Boolean) -> Unit,
    shadowClearancePx: Float, surface: @Composable (Modifier, @Composable () -> Unit) -> Unit,
    content: @Composable (ToastContent) -> Unit) {
    val entry = requireNotNull(state.current)
    val currentEntry = rememberUpdatedState(entry)
    val manager = LocalAccessibilityManager.current
    val recommendation = manager?.calculateRecommendedTimeoutMillis(entry.content.durationMillis,
        containsIcons = entry.content.artworkUris.isNotEmpty(), containsText = true,
        containsControls = true) ?: entry.content.durationMillis
    val dwell = toastRecommendedDwell(entry.content.durationMillis, recommendation)
    val currentDwell = rememberUpdatedState(dwell)
    var atRest by remember { mutableStateOf(false) }
    var readingReady by remember(entry.generation) { mutableStateOf(false) }
    fun visible() {
        if (!lease.current) return
        val item = currentEntry.value
        lease.presented(item.id, item.generation, currentDwell.value)
        if (ToastPause.Contact in lease.state.value.pauses) lease.retainedAfterContact(item.id, item.generation)
        else lease.pause(ToastPause.Settling, false)
    }
    val tags = remember { setOf("toast713-body", "toast713-action", "toast713-message") }
    ObserveToastAccessibilityFocus(tags) { focused ->
        if (lease.state.value.current?.id == entry.id) lease.pause(ToastPause.Accessibility, focused)
    }
    val edge = LocalUiSpec.current.px(Space.Base)
    val enabled = !blocked && state.phase != ToastPhase.Exiting
    var requestedActivation by remember { mutableStateOf<ToastActivation?>(null) }
    val blockedNow = rememberUpdatedState(blocked)
    val commandNow = rememberUpdatedState(command)
    fun activate(body: Boolean) {
        if (!enabled || requestedActivation != null) return
        val item = currentEntry.value
        val target = if (body) item.content.tap else item.content.action?.command
        if (target == null || target is ToastCommand.Operation) return
        requestedActivation = ToastActivation(item.id, item.generation, body)
    }
    // A native child click follows pointer-up. Wait for that same owner's real return endpoint;
    // the atomic reducer keeps rejecting Settling. A new contact or stale identity cancels intent.
    LaunchedEffect(requestedActivation) {
        val request = requestedActivation ?: return@LaunchedEffect
        fun invalid(): Boolean {
            val live = lease.state.value
            return !lease.current || blockedNow.value || live.current?.id != request.id ||
                live.current.generation != request.generation || live.phase == ToastPhase.Exiting
        }
        snapshotFlow {
            invalid() || (atRest &&
                lease.state.value.pauses.none { it == ToastPause.Contact || it == ToastPause.Settling })
        }.first { it }
        if (!invalid()) lease.claimAction(request.id, request.generation, request.body)?.let {
            commandNow.value(it) { lease.current }
        }
        if (requestedActivation == request) requestedActivation = null
    }
    val readingPaused = blocked || state.phase != ToastPhase.Visible || state.pauses.isNotEmpty()
    ToastMotionHost(entry.id, root.viewport, root.seat, edge, shadowClearancePx,
        exiting = state.phase == ToastPhase.Exiting, enabled = enabled, readingReady = readingReady,
        bind = { owner, body ->
            root.presentation.bind(owner, body)
            val release: () -> Unit = { root.presentation.unbind(owner) }
            release
        }, exited = { lease.exited(entry.id) }, ready = { ready ->
            atRest = ready
            if (ready) visible() else lease.pause(ToastPause.Settling, true)
        }, contactStarted = {
            val live = lease.state.value
            if (!lease.current || live.current?.id != entry.id || live.phase == ToastPhase.Exiting) false
            else {
                requestedActivation = null
                lease.pause(ToastPause.Contact, true)
                true
            }
        }, contactRejected = { lease.pause(ToastPause.Contact, false) }, releasing = { dismiss ->
            lease.pause(ToastPause.Settling, true)
            val live = lease.state.value.current
            if (dismiss && live?.id == entry.id) lease.dismiss(live.id, live.generation)
        }) { gesture ->
        FloatingRowInput(enabled) {
            surface(gesture.testTag("toast713-body").semantics(mergeDescendants = true) {
                testTagsAsResourceId = true
                if (!blocked) liveRegion = LiveRegionMode.Polite
                if (enabled) dismiss {
                    val item = currentEntry.value
                    lease.dismiss(item.id, item.generation)
                }
            }) {
                content(ToastContent(entry, enabled && requestedActivation == null, readingPaused, { activate(true) }, { activate(false) },
                    { lease.marqueePending(entry.id, entry.generation) },
                    { lease.marqueeCompleted(entry.id, entry.generation) }, { readingReady = it }))
            }
        }
    }
}

