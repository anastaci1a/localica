@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.anastaci1a.localica.ui.engine

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import io.github.anastaci1a.localica.ui.spec.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs

/** One public mutation lease, driven only by native input and acknowledged placements. */
internal class NativeViewportRelocation(private val state: ScrollState, private val scope: CoroutineScope,
    val window: NativeRelocationWindow, val inherited: BringIntoViewSpec, private val admitted: () -> Boolean) : AutoCloseable {
    private enum class Mode { Ordinary, Ime, Superseded }
    private var mode = Mode.Ordinary
    private var disposed = false
    private var generation = 0L
    private var focus = 0L
    private var focused = false
    private var frame: NativeRelocationFrame? = null
    private var placement: NativeRelocationPlacement? = null
    private var writer: Job? = null
    private var ordinaryRequests = 0
    private var ordinaryFocusObserved = false
    private var requestSequence = 0L
    private var pending: CompletableDeferred<Unit>? = null
    private val drags = mutableSetOf<Any>()
    private val flings = mutableMapOf<Job, DisposableHandle?>()
    private val signals = Channel<Long>(Channel.CONFLATED)
    var result = NativeRelocationResult.Ordinary
        private set

    val bodyPolicy = object : BringIntoViewSpec {
        override val scrollAnimationSpec get() = inherited.scrollAnimationSpec.also {
            if (mode == Mode.Ordinary) ordinaryFocusObserved = true
        }
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            if (mode == Mode.Ordinary) inherited.calculateScrollDistance(offset, size, containerSize) else 0f
    }

    private fun allowed() = !disposed && admitted()
    private fun userOwned() = drags.isNotEmpty() || flings.isNotEmpty()
    private fun current(value: NativeRelocationFrame) = value.window === window && value.focus == focus && value.focused == focused

    fun focusChanged(identity: Long, present: Boolean) {
        if (disposed || identity < focus) return
        if (identity != focus || present != focused) {
            focus = identity; focused = present
            frame = frame?.copy(focus = identity, focused = present)
            pending?.cancel(); pending = null
            if (!present) supersede("focus-lost")
        }
    }

    fun native(value: NativeRelocationFrame) {
        if (disposed || !current(value) || value.measure <= (frame?.measure ?: 0)) return
        val old = frame
        frame = value
        val changed = old != null && value.nativeStep != old.nativeStep
        if (changed && mode == Mode.Ordinary && focused) {
            generation++
            mode = Mode.Ime
            result = NativeRelocationResult.Adjusting
            if (userOwned() || !allowed()) supersede("native-entry-user-or-window")
            else if (state.isScrollInProgress && ordinaryRequests == 0 && !ordinaryFocusObserved)
                supersede("native-entry-unknown-mutator")
            else startWriter()
        }
        restoreIfIdle()
    }

    fun placed(value: NativeRelocationPlacement) {
        if (disposed || value.frame != frame || !current(value.frame)) return
        placement = value
        if (mode == Mode.Ime) signals.trySend(generation) else restoreIfIdle()
    }

    private fun startWriter() {
        if (writer?.isActive == true || mode != Mode.Ime) return
        val ticket = generation
        writer = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            var reconciled = false
            var unreachable = false
            try {
                // Public arbitration cancels or awaits the prior focus owner's actual cleanup.
                state.scroll(MutatePriority.Default) {
                    while (isActive && ticket == generation && mode == Mode.Ime) {
                        if (signals.receive() != ticket) continue
                        if (!allowed() || userOwned()) throw CancellationException("Input/window superseded")
                        val receipt = placement ?: continue
                        if (receipt.frame != frame || !current(receipt.frame) || !focused) continue
                        if (receipt.viewport != state.viewportSize || receipt.viewport == 0) continue
                        // A prior write must have reached placement before another write is admitted.
                        if (receipt.appliedScroll != state.value) continue
                        val target = receipt.target ?: continue
                        val extent = receipt.viewport.toFloat()
                        val delta = when {
                            target.top >= 0f && target.bottom <= extent -> 0f
                            target.top < 0f && target.bottom > extent -> 0f
                            abs(target.top) < abs(target.bottom - extent) -> target.top
                            else -> target.bottom - extent
                        }
                        if (!allowed() || userOwned() || receipt.frame != frame || !current(receipt.frame))
                            throw CancellationException("Stale native/focus/window receipt")
                        if (abs(delta) > .5f) {
                            val consumed = scrollBy(delta)
                            check(consumed.isFinite() && abs(consumed) <= abs(delta) + .5f && consumed * delta >= 0f)
                            if (abs(consumed) > .5f) result = NativeRelocationResult.AwaitingPlacement
                            else if (receipt.frame.settled) { unreachable = true; reconciled = true; break }
                        } else if (receipt.frame.settled) {
                            pending?.complete(Unit)
                            unreachable = target.top < -.5f || target.bottom > extent + .5f
                            reconciled = true
                            break
                        } else pending?.complete(Unit)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!disposed && ticket == generation && mode == Mode.Ime) supersede("public-mutation-cancelled")
                throw cancelled
            } finally {
                if (!disposed && ticket == generation && reconciled && mode == Mode.Ime) {
                    pending = null
                    mode = Mode.Ordinary
                    ordinaryFocusObserved = false
                    result = if (unreachable) NativeRelocationResult.Unreachable else NativeRelocationResult.Ordinary
                }
            }
        }
    }

    /** Forwarding owns no scroll capability; only a current request can leave this scope. */
    suspend fun request(sequence: Long, focusIdentity: Long, forward: suspend () -> Unit) {
        if (!allowed() || focusIdentity != focus || sequence <= requestSequence) return
        requestSequence = sequence
        pending?.cancel()
        val completion = CompletableDeferred<Unit>()
        pending = completion
        if (mode == Mode.Ordinary) {
            ordinaryRequests++
            try { forward() } finally { ordinaryRequests-- }
        } else if (mode == Mode.Ime) {
            signals.trySend(generation)
            completion.await()
            if (currentRequest(sequence, focusIdentity)) forward()
        }
    }
    fun currentRequest(sequence: Long, focusIdentity: Long) = allowed() && (focused || mode == Mode.Ordinary) &&
        sequence == requestSequence && focusIdentity == focus && mode != Mode.Superseded

    fun drag(identity: Any, began: Boolean) {
        if (disposed) return
        if (began) { drags += identity; supersede("drag") }
        else { drags -= identity; restoreIfIdle() }
    }
    fun preFling(job: Job) {
        if (disposed) return
        if (!flings.containsKey(job)) {
            flings[job] = null
            val observer = job.invokeOnCompletion { scope.launch(Dispatchers.Main.immediate) { postFling(job) } }
            if (flings.containsKey(job)) flings[job] = observer else observer.dispose()
        }
        supersede("fling")
    }
    fun postFling(job: Job) {
        if (disposed || !flings.containsKey(job)) return
        flings.remove(job)?.dispose()
        restoreIfIdle()
    }
    fun progress(running: Boolean) {
        if (!running && ordinaryRequests == 0) ordinaryFocusObserved = false
        restoreIfIdle()
    }
    fun admissionChanged() { if (!allowed()) supersede("window-or-modal") else restoreIfIdle() }
    fun cancel(reason: String) { supersede(reason) }
    private fun supersede(reason: String) {
        if (disposed || mode == Mode.Ordinary) return
        mode = Mode.Superseded
        result = NativeRelocationResult.Superseded
        generation++
        writer?.cancel(CancellationException(reason))
        pending?.cancel(); pending = null
        restoreIfIdle()
    }
    private fun restoreIfIdle() {
        if (mode == Mode.Superseded && frame?.settled == true && !userOwned() && !state.isScrollInProgress && allowed()) {
            mode = Mode.Ordinary
            result = NativeRelocationResult.Ordinary
            ordinaryFocusObserved = false
            pending = null
        }
    }
    override fun close() {
        if (disposed) return
        disposed = true
        result = NativeRelocationResult.Disposed
        generation++
        pending?.cancel(); pending = null
        flings.values.forEach { it?.dispose() }; flings.clear()
        writer?.cancel()
        signals.close()
    }
}
