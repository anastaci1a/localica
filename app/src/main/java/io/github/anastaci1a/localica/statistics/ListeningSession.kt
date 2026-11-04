package io.github.anastaci1a.localica.statistics

import java.util.UUID

/** The queue calls these on Main around its native edit hold, including failed/cancelled edits. */
interface ListeningEditLifecycle {
    fun suspendForEdit()
    fun finishEdit(restoredVisit: String?)
    fun clearTraversal()
}

/** Persisted before its qualification event; no decoder or elapsed-time state crosses process death. */
data class ListeningIdentity(val visit: String, val event: String)

/** App-owned traversal state. A service is only an observer, not the owner of a listening visit. */
class ListeningSession(
    private val now: () -> Long,
    restored: ListeningIdentity? = null,
    private val checkpoint: (ListeningIdentity?) -> Unit = {},
    private val record: (ListeningSource, String) -> Unit,
) : ListeningEditLifecycle {
    private var identity = restored
    private var source: ListeningSource? = null
    private var duration: Long? = null
    private var editVisit: String? = null
    private var editing = false
    private var nativePlaying = false
    private var nativeSourcePresent = false
    private val counter = ListeningCounter { record(it, requireNotNull(identity).event) }

    fun update(next: ListeningSource?, visit: String?, durationMs: Long?, playing: Boolean, restart: Boolean = false) {
        val at = now()
        nativePlaying = playing
        nativeSourcePresent = next != null
        // A failed/deferred native restore can outlive the writer lease. Only this held visit survives it.
        if (next == null && editVisit != null && identity?.visit == editVisit) {
            counter.update(at, source, duration, false)
            return
        }
        if (next != null && !editing) editVisit = null
        counter.sample(at)
        val key = if (next == null) null else visit ?: next.occurrenceId
        val changed = key != identity?.visit || (source != null && next != source) || restart
        if (changed) {
            identity = key?.let { ListeningIdentity(it, UUID.randomUUID().toString()) }
            checkpoint(identity)
        }
        source = next
        duration = durationMs
        counter.update(at, next, durationMs, playing && !(editing && key == editVisit), restart = changed)
    }

    /** Detaching a service excludes its absence without ending the app-owned traversal. */
    fun detach() {
        nativePlaying = false
        nativeSourcePresent = false
        counter.update(now(), source, duration, false)
    }

    override fun clearTraversal() {
        editing = false
        editVisit = null
        update(null, null, null, false)
    }

    override fun suspendForEdit() {
        if (editing) return
        detach()
        editing = true
        editVisit = identity?.visit
    }

    override fun finishEdit(restoredVisit: String?) {
        if (!editing) return
        editing = false
        if (identity?.visit != restoredVisit) {
            editVisit = null
            update(null, null, null, false)
        } else {
            if (nativeSourcePresent) editVisit = null
            counter.update(now(), source, duration, nativePlaying)
        }
    }
}
