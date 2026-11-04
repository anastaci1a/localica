package io.github.anastaci1a.localica.statistics

import java.util.UUID

/** The queue calls these on Main around its native edit hold, including failed/cancelled edits. */
interface ListeningEditLifecycle {
    fun suspendForEdit()
    fun finishEdit(restoredVisit: String?)
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
    private val counter = ListeningCounter { record(it, requireNotNull(identity).event) }

    fun update(next: ListeningSource?, visit: String?, durationMs: Long?, playing: Boolean, restart: Boolean = false) {
        val at = now()
        nativePlaying = playing
        if (next == null && editing && identity?.visit == editVisit) {
            counter.update(at, source, duration, false)
            return
        }
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
    fun detach() { nativePlaying = false; counter.update(now(), source, duration, false) }

    override fun suspendForEdit() {
        if (editing) return
        detach()
        editing = true
        editVisit = identity?.visit
    }

    override fun finishEdit(restoredVisit: String?) {
        if (!editing) return
        editing = false
        editVisit = null
        if (identity?.visit != restoredVisit) update(null, null, null, false)
        else counter.update(now(), source, duration, nativePlaying)
    }
}
