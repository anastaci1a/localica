package io.github.anastaci1a.localica.ui.layout.menu

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One continuous presence of an owner key. A row removed and added again gets a new incarnation. */
@Immutable class OwnerToken internal constructor(val key: String, val incarnation: Long) {
    override fun equals(other: Any?) = other is OwnerToken && key == other.key && incarnation == other.incarnation
    override fun hashCode() = 31 * key.hashCode() + incarnation.hashCode()
}

enum class DispatchResult { Delivered, DroppedRevoked, DroppedRetired, DroppedDisabled, DroppedStalePayload, DroppedUnbound }

/**
 * The immutable result of preparing one admission result during composition. It holds the proposed incarnation of
 * every owner, so a newly composed row has its token before anything is published. Preparing changes nothing: an
 * abandoned composition never revokes or replaces the page that is live.
 */
@Immutable class PendingPublication internal constructor(internal val base: Long, val generation: Long, val admission: Admission,
    internal val incarnations: Map<String, Long>) {
    val accepted: AcceptedPage? get() = (admission as? Admission.Accepted)?.page
    fun token(key: QualifiedKey): OwnerToken? = incarnations[key.path]?.let { OwnerToken(key.path, it) }
}

/**
 * One per page host, created above the lazy list and stable for the page's lifetime. Rows fire
 * `(token, slot, payload)` and never hold a handler; the dispatcher resolves the handler in the declaration that
 * is live when the event arrives.
 */
@Stable class MenuDispatcher {
    private var generation = 0L
    private var bindings: BindingTable? = null
    private var index: ContentIndex? = null
    private var incarnations: Map<String, Long> = emptyMap()
    /** Debug hook for dropped events: result, owner key and slot. Never titles or values. */
    var onDrop: ((DispatchResult, String, ActionSlot) -> Unit)? = null

    /** Pure with respect to live dispatch: reads the live incarnations and proposes the next ones. */
    fun prepare(admission: Admission): PendingPublication {
        val page = (admission as? Admission.Accepted)?.page
        val next: Map<String, Long> = when {
            page == null -> emptyMap()
            page.index === index -> incarnations
            else -> HashMap<String, Long>().also { map -> for (key in page.index.keys) map[key] = incarnations[key] ?: (generation + 1) }
        }
        return PendingPublication(generation, generation + 1, admission, next)
    }

    /**
     * Called after a successful apply. Accepted: replaces the table and index, keeps the incarnation of every key
     * present before and after, retires the rest. Rejected: clears the table and retires every incarnation in this
     * same step. A pending result prepared against an older state is ignored, never applied over a newer one.
     */
    fun publish(pending: PendingPublication): Boolean {
        if (pending.base != generation) return false
        val page = pending.accepted
        generation = pending.generation
        bindings = page?.bindings
        index = page?.index
        incarnations = pending.incarnations
        return true
    }

    /** The token an owner has in the live declaration; for owners that outlive rows (the popup registry). */
    internal fun liveToken(key: String): OwnerToken? = incarnations[key]?.let { OwnerToken(key, it) }
    fun isLive(token: OwnerToken): Boolean = incarnations[token.key] == token.incarnation
    fun isEnabled(token: OwnerToken): Boolean = isLive(token) && index?.get(token.key)?.enabled == true

    fun click(token: OwnerToken, slot: ActionSlot = ActionSlot.Click) = deliver(token, slot, null) { (it as? Binding.Click)?.run?.invoke() }
    fun toggle(token: OwnerToken, value: Boolean) = deliver(token, ActionSlot.Change, null) { (it as? Binding.Toggle)?.run?.invoke(value) }
    fun number(token: OwnerToken, value: Float) = deliver(token, ActionSlot.Change, null) { (it as? Binding.Number)?.run?.invoke(value) }
    fun text(token: OwnerToken, value: String, slot: ActionSlot = ActionSlot.Change) = deliver(token, slot, null) { (it as? Binding.Text)?.run?.invoke(value) }
    /** An option, tab, record or entry by id; the id must exist in the live content. */
    fun select(token: OwnerToken, id: String, slot: ActionSlot = ActionSlot.Select) = deliver(token, slot, id) { (it as? Binding.Id)?.run?.invoke(id) }
    fun move(token: OwnerToken, id: String, to: Int) = deliver(token, ActionSlot.Move, id) { (it as? Binding.Move)?.run?.invoke(id, to) }

    private inline fun deliver(token: OwnerToken, slot: ActionSlot, id: String?, invoke: (Binding) -> Unit?): DispatchResult {
        val table = bindings
        val state = index?.get(token.key)
        val binding = table?.get(token.key, slot)
        val result = when {
            table == null -> DispatchResult.DroppedRevoked
            incarnations[token.key] != token.incarnation || state == null -> DispatchResult.DroppedRetired
            !state.enabled -> DispatchResult.DroppedDisabled
            id != null && !state.accepts(id) -> DispatchResult.DroppedStalePayload
            binding == null -> DispatchResult.DroppedUnbound
            else -> if (invoke(binding) != null) DispatchResult.Delivered else DispatchResult.DroppedUnbound
        }
        if (result != DispatchResult.Delivered) onDrop?.invoke(result, token.key, slot)
        return result
    }
}

/** One open popup. It outlives the row that opened it, declaration rebuilds and item recycling. */
@Stable class MenuPopupEntry internal constructor(val key: String, content: PopupContent, internal var incarnation: Long) {
    /** The content declared now; while the page is rejected, the last admitted content. */
    var content: PopupContent by mutableStateOf(content)
        internal set
    /** The editor draft. It survives rejection and rebinding. */
    var draft: String by mutableStateOf((content as? TextEntryContent)?.initial.orEmpty())
    /** The staged selection of a choice popup. */
    var staged: String by mutableStateOf((content as? SingleChoiceContent)?.selected.orEmpty())
    /** The page is rejected: declaration-bound actions are inert; the window, the draft and native retirement are untouched. */
    var quarantined: Boolean by mutableStateOf(false)
        internal set
    /** The owner's key left the declaration: the popup owner must retire the window through its departure path. */
    var departing: Boolean by mutableStateOf(false)
        internal set
}

/**
 * One registry above the tab switch and the lazy rows. It holds popup state only; the window, visibility and the
 * morph stay with the existing popup owner. Withdrawing a row never closes an entry.
 */
@Stable class MenuPopupRegistry internal constructor(private val dispatcher: MenuDispatcher) {
    private val entries = mutableStateMapOf<String, MenuPopupEntry>()
    val open: Collection<MenuPopupEntry> get() = entries.values
    operator fun get(key: QualifiedKey): MenuPopupEntry? = entries[key.path]

    /** A home-capable row was activated. Opens only for a live, enabled owner of the live declaration. */
    fun open(owner: OwnerToken, popup: PopupContent): Boolean {
        if (!dispatcher.isEnabled(owner)) return false
        val token = dispatcher.liveToken(popup.key.path) ?: return false
        if (popup.key.path !in entries) entries[popup.key.path] = MenuPopupEntry(popup.key.path, popup, token.incarnation)
        return true
    }

    fun click(entry: MenuPopupEntry, slot: ActionSlot) = fire(entry) { dispatcher.click(it, slot) }
    fun text(entry: MenuPopupEntry, slot: ActionSlot = ActionSlot.Confirm) = fire(entry) { dispatcher.text(it, entry.draft, slot) }
    fun select(entry: MenuPopupEntry, id: String, slot: ActionSlot) = fire(entry) { dispatcher.select(it, id, slot) }
    private inline fun fire(entry: MenuPopupEntry, send: (OwnerToken) -> DispatchResult): DispatchResult =
        if (entry.quarantined || entry.departing) DispatchResult.DroppedRevoked else send(OwnerToken(entry.key, entry.incarnation))

    /** The popup owner finished retiring the window (dismissal, confirmation or departure). */
    fun retired(entry: MenuPopupEntry) { if (entries[entry.key] === entry) entries.remove(entry.key) }

    /** Runs in the same publish step as the dispatcher. */
    internal fun reconcile(pending: PendingPublication) {
        val page = pending.accepted
        for (entry in entries.values) {
            if (page == null) { entry.quarantined = true; continue }
            val content = page.index.popup(entry.key)
            val incarnation = pending.incarnations[entry.key]
            if (content == null || incarnation == null) { entry.departing = true; continue }
            entry.content = content
            entry.incarnation = incarnation
            entry.quarantined = false
        }
    }
}

/**
 * The page host's non-UI core: admission cache, dispatcher and popup registry behind one prepare/publish pair.
 * The composable host calls [prepare] during composition and [publish] in a `SideEffect`; tests drive the same two
 * calls, so they exercise the real publication path.
 */
@Stable class MenuHostCore {
    val cache = AdmissionCache()
    val dispatcher = MenuDispatcher()
    val popups = MenuPopupRegistry(dispatcher)

    /** `declare` output in, pending publication out. Admission is reused for equal content; bindings never are. */
    fun prepare(draft: PageDraft): PendingPublication = dispatcher.prepare(cache.admit(draft))
    fun publish(pending: PendingPublication) { if (dispatcher.publish(pending)) popups.reconcile(pending) }
}
