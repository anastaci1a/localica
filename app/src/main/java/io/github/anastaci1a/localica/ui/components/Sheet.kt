package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.engine.*
import kotlinx.coroutines.flow.first

@Stable interface SheetState {
    val expanded: Boolean
    val expansion: ExpansionView
    val contact: SheetContact
    fun expand()
    fun collapse()
    fun collapsedOpacity(visibility: Float = 1f): Float = visibility * (1f - expansion.boundedProgress())
}
/** Optional lifecycle receipts for retained sheet content, independent of cleared semantics or drawing. */
internal val LocalSheetContentObserver = staticCompositionLocalOf<((String, Boolean) -> Unit)?> { null }
@Composable internal fun ObserveSheetContent(key: String) {
    val observer = LocalSheetContentObserver.current ?: return
    DisposableEffect(key, observer) {
        observer(key, true)
        onDispose { observer(key, false) }
    }
}
/** Owner-created contact intents: edge displacement since contact start and release velocity, px and px/s toward expansion. */
@Stable class SheetContact internal constructor(val onDragStart: () -> Boolean, val onDrag: (Float) -> Unit,
    val onRelease: (Float) -> ReleaseDecision, val onDragCancel: () -> Unit)
private class SheetHandle(override val expansion: ExpansionView, initial: Boolean) : SheetState {
    var requested by mutableStateOf(initial)
    override val expanded get() = requested
    override var contact = SheetContact({ false }, {}, { error("No contact") }, {})
    override fun expand() { requested = true }; override fun collapse() { requested = false }
}
/** One ExpandCollapse owner; its physical frame follows the live endpoint basis (Rebase-capable, A2.3c). */
@Composable fun rememberSheetState(key: Any, initiallyExpanded: Boolean = false, basis: ExpansionBasis): SheetState {
    val owner = rememberExpandCollapse(key, initiallyExpanded) { it.sheetExpansion }
    val range = remember(key) { maxOf(1f, 4f * basis.edgeTravelPx) }
    val frame = rememberMotionRect(key to "frame", if (initiallyExpanded) basis.endBounds else basis.startBounds, EdgeRanges(range, range, range, range))
    val handle = remember(owner) { SheetHandle(owner, initiallyExpanded) }
    val clock = LocalUiClock.current
    LaunchedEffect(handle) {
        val requests = snapshotFlow { handle.requested }
        var requested = initiallyExpanded
        while (true) { requested = requests.first { it != requested }; owner.target(requested) }
    }
    SideEffect {
        owner.bind(frame); owner.setBasis(basis, reanchorAtRest = true)
        handle.contact = SheetContact(onDragStart = { owner.canContact.also { if (it) owner.contactBegin(clock.stampNow) } },
            onDrag = { owner.contactMove(it, clock.stampNow) },
            onRelease = { owner.contactRelease(it, clock.stampNow).also { decision -> handle.requested = decision.expanded } },
            onDragCancel = { owner.contactCancel() })
    }
    return handle
}
/** Holds contacts no face claims while a returning child exposes the resting player; resting, it never catches over a face. */
@Composable fun Sheet(state: SheetState, onSettled: (Boolean) -> Unit, returningChild: SheetState, content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize().sheetDrag(SheetDragTarget(state, onSettled), canStart = { state.expanded && state.expansion.phase.settled
        && !returningChild.expanded && !returningChild.expansion.phase.settled })) { content() }
