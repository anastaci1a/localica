package io.github.anastaci1a.localica.toast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Commands contain durable identities, never Activity/composition callbacks. Owners revalidate them. */
sealed interface ToastCommand {
    data class OpenQueue(val queueId: String, val firstAddedEntryId: String?, val playNext: Boolean) : ToastCommand
    data class OpenList(val listId: String, val firstAddedEntryId: String) : ToastCommand
    data class Operation(val owner: String, val operationId: String, val generation: Long) : ToastCommand
}

enum class ToastKind(val dwellMillis: Long) { Info(3_000), Success(3_000), Error(6_000) }
data class ToastAction(val label: String, val command: ToastCommand)
/** Coalescing is explicit and includes action identity; translated text is never a key. */
data class ToastKey(val operation: String, val target: String, val kind: ToastKind,
    val tap: ToastCommand?, val action: ToastAction?)
data class ToastMessage(val message: String, val kind: ToastKind = ToastKind.Info,
    val action: ToastAction? = null, val tap: ToastCommand? = null,
    val durationMillis: Long = kind.dwellMillis, val artworkUris: List<String> = emptyList(),
    val coalescingKey: ToastKey? = null) {
    init {
        require(message.isNotBlank())
        require(durationMillis > 0)
        require(coalescingKey == null || coalescingKey.kind == kind && coalescingKey.tap == tap && coalescingKey.action == action)
    }
}

enum class ToastPhase { Entering, Visible, Exiting }
enum class ToastPause { Background, Modal, Accessibility, Contact, Settling }
data class ToastEntry(val id: Long, val generation: Long, val content: ToastMessage, val count: Int = 1)
data class ToastDeadline(val id: Long, val generation: Long, val epoch: Long, val atMillis: Long)
data class ToastQueueState(val current: ToastEntry? = null, val pending: List<ToastEntry> = emptyList(),
    val phase: ToastPhase = ToastPhase.Entering, val pauses: Set<ToastPause> = setOf(ToastPause.Background),
    val marqueeComplete: Boolean = false, val deadline: ToastDeadline? = null)
sealed interface ToastEnqueue {
    data class Accepted(val id: Long, val coalesced: Boolean, val evictedId: Long? = null) : ToastEnqueue
    /** The producer retains actionable/durable outcomes and may retry foreground presentation. */
    data object Full : ToastEnqueue
}

/** One serialized reducer, safe for background callers. Its presenter owns one cancellable deadline.
 * Capacity includes the current entry. Overflow can evict only pending, non-actionable status;
 * actionable entries and the visible surface are never silently removed. */
class ToastQueue(private val capacity: Int = 32,
    onUndeliveredCommittedToast: (CommittedToastReceipt) -> Unit = {},
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    init { require(capacity > 0) }
    private val mutable = MutableStateFlow(ToastQueueState())
    val state = mutable.asStateFlow()
    val committedOutcomes = ToastOutcomeReporter(::showToast, onUndeliveredCommittedToast)
    private val mutablePresenter = MutableStateFlow<Long?>(null)
    val activePresenter = mutablePresenter.asStateFlow()
    private var nextPresenter = 0L
    private var nextId = 0L
    private var epoch = 0L
    private var remaining = 0L
    private var dwell = 0L
    private var presentedGeneration: Pair<Long, Long>? = null

    /** A lease carries only an app-local number; native views and callbacks remain with the UI. */
    @Synchronized fun attachPresenter(): ToastPresenterLease {
        pause(ToastPause.Background, true)
        pause(ToastPause.Modal, false)
        pause(ToastPause.Accessibility, false)
        // A replacement text owner must prove its own measurement/scroll completion.
        publish(mutable.value.copy(marqueeComplete = false, deadline = null))
        val token = ++nextPresenter
        mutablePresenter.value = token
        return ToastPresenterLease(this, token)
    }

    @Synchronized internal fun <T> fromPresenter(token: Long, rejected: T, operation: () -> T): T =
        if (mutablePresenter.value == token) operation() else rejected

    @Synchronized internal fun releasePresenter(token: Long): Boolean {
        if (mutablePresenter.value != token) return false
        mutablePresenter.value = null
        pause(ToastPause.Background, true)
        return true
    }

    @Synchronized fun showToast(content: ToastMessage): ToastEnqueue {
        // Defend the app queue against subsequent mutation of a producer's artwork collection.
        val frozen = content.copy(artworkUris = content.artworkUris.take(3).toList())
        val value = mutable.value
        val key = frozen.coalescingKey
        val current = value.current
        if (key != null && current?.content?.coalescingKey == key && value.phase != ToastPhase.Exiting) {
            val updated = current.copy(generation = current.generation + 1, content = frozen, count = current.count + 1)
            presentedGeneration = null
            remaining = frozen.durationMillis; dwell = remaining
            publish(value.copy(current = updated, marqueeComplete = false, deadline = null))
            return ToastEnqueue.Accepted(updated.id, true)
        }
        val duplicate = if (key == null) -1 else value.pending.indexOfFirst { it.content.coalescingKey == key }
        if (duplicate >= 0) {
            val pending = value.pending.toMutableList()
            val entry = pending[duplicate]
            pending[duplicate] = entry.copy(generation = entry.generation + 1, content = frozen, count = entry.count + 1)
            publish(value.copy(pending = pending))
            return ToastEnqueue.Accepted(entry.id, true)
        }
        var pending = value.pending
        var evicted: Long? = null
        if (pending.size + (if (current == null) 0 else 1) >= capacity) {
            val disposable = pending.indexOfFirst { it.content.action == null && it.content.tap == null }
            if (disposable < 0) return ToastEnqueue.Full
            evicted = pending[disposable].id
            pending = pending.filterIndexed { index, _ -> index != disposable }
        }
        val entry = ToastEntry(++nextId, 0, frozen)
        if (current == null) {
            remaining = frozen.durationMillis; dwell = remaining
            publish(value.copy(current = entry, phase = ToastPhase.Entering, marqueeComplete = false, deadline = null))
        } else publish(value.copy(pending = pending + entry))
        return ToastEnqueue.Accepted(entry.id, false, evicted)
    }

    /** Entry completion supplies the accessibility-adjusted full dwell. Reattach is idempotent. */
    @Synchronized fun presented(id: Long, generation: Long, recommendedDwellMillis: Long): Boolean {
        val value = matching(id, generation) ?: return false
        if (value.phase == ToastPhase.Exiting || presentedGeneration == (id to generation)) return false
        presentedGeneration = id to generation
        dwell = maxOf(value.current!!.content.durationMillis, recommendedDwellMillis)
        remaining = dwell
        publish(value.copy(phase = ToastPhase.Visible))
        return true
    }

    @Synchronized fun marqueeCompleted(id: Long, generation: Long) {
        val value = matching(id, generation) ?: return
        if (!value.marqueeComplete) publish(value.copy(marqueeComplete = true))
    }

    /** A reflow can expose unread text after an earlier gate completed. Preserve the unspent dwell;
     * old timer tickets cannot race the replacement text owner's measurement. */
    @Synchronized fun marqueePending(id: Long, generation: Long): Boolean {
        val value = matching(id, generation) ?: return false
        if (value.phase == ToastPhase.Exiting || !value.marqueeComplete) return false
        value.deadline?.let { remaining = (it.atMillis - clockMillis()).coerceAtLeast(0) }
        publish(value.copy(marqueeComplete = false, deadline = null))
        return true
    }

    /** Modal/background/accessibility pauses preserve remaining time. Contact resets on return. */
    @Synchronized fun pause(reason: ToastPause, paused: Boolean) {
        val value = mutable.value
        if ((reason in value.pauses) == paused) return
        value.deadline?.let { remaining = (it.atMillis - clockMillis()).coerceAtLeast(0) }
        publish(value.copy(pauses = if (paused) value.pauses + reason else value.pauses - reason, deadline = null))
    }

    /** Called only after a cancelled gesture has settled; an exit cannot be revived. */
    @Synchronized fun retainedAfterContact(id: Long, generation: Long): Boolean {
        val value = matching(id, generation) ?: return false
        if (value.phase == ToastPhase.Exiting) return false
        remaining = dwell
        publish(value.copy(pauses = value.pauses - ToastPause.Contact - ToastPause.Settling, deadline = null))
        return true
    }

    @Synchronized fun timeout(ticket: ToastDeadline): Boolean {
        val value = mutable.value
        if (value.deadline != ticket || clockMillis() < ticket.atMillis) return false
        return dismiss(ticket.id, ticket.generation)
    }

    @Synchronized fun dismiss(id: Long, generation: Long): Boolean {
        val value = matching(id, generation) ?: return false
        if (value.phase == ToastPhase.Exiting) return false
        publish(value.copy(phase = ToastPhase.Exiting, deadline = null))
        return true
    }

    /** Transition to Exiting is the atomic claim, shared by tap and accessible/action activation. */
    @Synchronized fun claimAction(id: Long, generation: Long, body: Boolean = false): ToastCommand? {
        val value = matching(id, generation) ?: return null
        if (value.phase == ToastPhase.Exiting || value.pauses.any { it != ToastPause.Contact }) return null
        val command = if (body) value.current!!.content.tap else value.current!!.content.action?.command
        if (command != null) dismiss(id, generation)
        return command
    }

    /** Only real exit completion retires a surface; stale completion cannot remove its successor. */
    @Synchronized fun exited(id: Long): Boolean {
        val value = mutable.value
        if (value.current?.id != id || value.phase != ToastPhase.Exiting) return false
        val next = value.pending.firstOrNull()
        presentedGeneration = null
        remaining = next?.content?.durationMillis ?: 0; dwell = remaining
        publish(value.copy(current = next, pending = value.pending.drop(1), phase = ToastPhase.Entering,
            pauses = value.pauses - ToastPause.Contact - ToastPause.Settling,
            marqueeComplete = false, deadline = null))
        return true
    }

    private fun matching(id: Long, generation: Long): ToastQueueState? = mutable.value.takeIf {
        it.current?.id == id && it.current.generation == generation
    }
    private fun publish(value: ToastQueueState) {
        val eligible = value.current != null && value.phase == ToastPhase.Visible && value.pauses.isEmpty() && value.marqueeComplete
        mutable.value = if (eligible && value.deadline == null) value.copy(deadline = ToastDeadline(
            value.current!!.id, value.current.generation, ++epoch, clockMillis() + remaining))
        else if (!eligible) value.copy(deadline = null) else value
    }
}
