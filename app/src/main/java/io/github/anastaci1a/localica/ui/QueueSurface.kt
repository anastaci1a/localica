package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.ui.components.SheetState
import io.github.anastaci1a.localica.ui.components.verticalSurfaceDrag
import io.github.anastaci1a.localica.ui.components.DrawerScrim
import io.github.anastaci1a.localica.ui.components.rememberExpansionClosed

internal val CollapsedQueueHeight = 64.dp

@Composable
internal fun BoxWithConstraintsScope.QueueSurface(sheet: SheetState,
    onSettled: (Boolean) -> Unit, playerProgress: () -> Float = { 1f },
    onDragTrace: ((String) -> Unit)? = null, onOpeningContact: (() -> Unit)? = null,
    providedPopupHome: GlassPopupHome? = null,
    content: @Composable ColumnScope.() -> Unit) {
    // U10.02: the drawer's phase, contacts and settles belong to its root ExpandCollapse owner.
    val phase = sheet.expansion.phase
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val top = maxHeight - inset - CollapsedQueueHeight
    val density = LocalDensity.current
    var delta by remember { mutableFloatStateOf(0f) }
    val currentPlayerProgress by rememberUpdatedState(playerProgress)
    val interactive by remember { derivedStateOf { currentPlayerProgress() >= .99f } }
    val departing by remember { derivedStateOf { !sheet.expanded || !interactive } }
    val popupHome = providedPopupHome ?: rememberGlassPopupHome(departing)
    // Optional test diagnostics observe existing callbacks without adding a pointer node.
    // A stationary pointer observer would itself change Compose's hit-path dispatch.
    val currentTrace by rememberUpdatedState(onDragTrace)
    val currentOpeningContact by rememberUpdatedState(onOpeningContact)
    val currentSettled by rememberUpdatedState(onSettled)
    // Contact ownership is fixed by its first directed delta, not later reversal; [1] holds the owner's admission.
    val directContact = remember { booleanArrayOf(false, false) }
    val scrollOwnership = remember { ScrollGestureOwnership() }
    val listPull = remember(sheet) {
        object : NestedScrollConnection {
            var pulling by mutableStateOf(false)
            var pulled = 0f
            fun cancelPull() {
                currentTrace?.invoke("nested-cancel pulling=$pulling pulled=$pulled progress=${phase.value()}")
                if (!pulling) return
                pulling = false
                pulled = 0f
                sheet.contact.onDragCancel()
            }
            fun move(dy: Float): Offset {
                // Keep raw travel and ownership through both endpoints. A reversal
                // must retrace excess travel, never leak into the child list.
                pulled += dy
                sheet.contact.onDrag(-pulled)
                currentTrace?.invoke("nested-applied pulled=$pulled consumed=$dy progress=${phase.value()}")
                return Offset(0f, dy)
            }
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                currentTrace?.invoke("nested-pre source=$source available=$available pulling=$pulling pulled=$pulled progress=${phase.value()}")
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                return if (pulling) move(available.y) else Offset(0f, scrollOwnership.consumeRetrace(available.y))
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                // Even a partial final list delta owns the rest of this contact. A fresh
                // downward pull already at the top still starts the documented drawer drag.
                scrollOwnership.recordScroll(consumed.y)
                currentTrace?.invoke("nested-post consumed=$consumed available=$available pulling=$pulling contentOwned=${scrollOwnership.contentOwned} mayHandoff=${scrollOwnership.mayHandoff} progress=${phase.value()}")
                if (scrollOwnership.contentOwned) {
                    scrollOwnership.recordBoundary(available.y)
                    return Offset(0f, available.y)
                }
                if (!scrollOwnership.mayHandoff || available.y <= 0f) return Offset.Zero
                if (!pulling && !sheet.contact.onDragStart()) return Offset.Zero
                scrollOwnership.claimDrawer()
                pulling = true
                return move(available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                currentTrace?.invoke("nested-fling available=$available pulling=$pulling mayCommit=${scrollOwnership.mayCommitPull} progress=${phase.value()}")
                if (!pulling) return Velocity.Zero
                // Scrollable also dispatches a zero-velocity pre-fling on cancellation,
                // potentially before our observer receives its synthetic cancel event.
                if (!scrollOwnership.mayCommitPull) {
                    cancelPull()
                    return Velocity(0f, available.y)
                }
                pulling = false
                pulled = 0f
                currentSettled(sheet.contact.onRelease(-available.y).expanded)
                return Velocity(0f, available.y)
            }
        }
    }
    val closed by rememberExpansionClosed(sheet.expansion)
    val shown = !closed
    DrawerScrim(sheet.expansion, shown, MaterialTheme.colorScheme.scrim) { onSettled(false) }
    // Keep the list viewport fixed while the drawer moves. Resizing every frame
    // repeatedly measures/virtualizes rows and their glass; the window clips the reveal.
    val viewportHeight = with(density) { (maxHeight - status).roundToPx() }
    fun shelfY() = with(density) {
        kotlin.math.round((top + 80.dp * (1f - playerProgress())).toPx()).toInt()
    }
    Layout(modifier = Modifier.offset { IntOffset(0, shelfY()) }
        .width(maxWidth).height(CollapsedQueueHeight).then(QueuePointerFrame), content = {
    // The drawer is an app panel, not a replica of the display. Its one intrinsic
    // contour clips content/material and draws the rim; the window clips hardware edges.
    FrostedSurface(Modifier.width(maxWidth).height(maxHeight - status)
        .graphicsLayer { alpha = ((playerProgress() - .4f) / .6f).coerceIn(0f, 1f) }
        .then(if (!interactive) Modifier.clearAndSetSemantics {} else Modifier)
        // Touch-down on a moving drawer catches it (#608 contact); a stationary drawer keeps its taps.
        .verticalSurfaceDrag(onCatch = { !phase.settled && sheet.contact.onDragStart().also { directContact[1] = it
                directContact[0] = true; delta = 0f } },
            onDelta = { dy ->
            currentTrace?.invoke("direct-delta dy=$dy delta=$delta progress=${phase.value()}")
            delta += dy
            if (!directContact[0] && dy != 0f) {
                directContact[0] = true
                if (dy < 0f && !sheet.expanded) currentOpeningContact?.invoke()
            }
            if (directContact[1]) sheet.contact.onDrag(-delta)
            currentTrace?.invoke("direct-applied delta=$delta progress=${phase.value()}")
        }, onStart = {
            currentTrace?.invoke("direct-start progress=${phase.value()}")
            delta = 0f
            directContact[0] = false
            directContact[1] = sheet.contact.onDragStart()
        }, onStop = { velocity ->
            currentTrace?.invoke("direct-stop velocity=$velocity delta=$delta progress=${phase.value()}")
            if (directContact[1]) { directContact[1] = false; currentSettled(sheet.contact.onRelease(-velocity).expanded) }
        }, onCancel = { if (directContact[1]) { directContact[1] = false; sheet.contact.onDragCancel() } })
        .then(if (onDragTrace == null) Modifier else Modifier.onGloballyPositioned {
            currentTrace?.invoke("layout top=${it.positionInRoot().y} progress=${phase.value()}")
        })
        // Header and list controls yield the swipes this drawer can still travel.
        .controlScrollParent { when { !phase.settled || sheet.expansion.inContact -> ControlScrollDirection.Down or ControlScrollDirection.Up
            sheet.expansion.expanded -> ControlScrollDirection.Down; else -> ControlScrollDirection.Up } }
        .observeScrollOwnership(scrollOwnership, listPull::cancelPull)
        .nestedScroll(listPull).testTag("queue-surface"), shape = Corners.panel) {

        GlassPopupHome(popupHome) {
        Column(Modifier.fillMaxSize().clipToBounds().padding(bottom = inset)) {
            Box(Modifier.fillMaxWidth().height(CollapsedQueueHeight).clickable(onClickLabel = if (!shown) "Open queue" else "Close queue") {
                val open = !sheet.expanded
                if (open) currentOpeningContact?.invoke()
                onSettled(open); if (open) sheet.expand()
            }
                .semantics { contentDescription = if (!shown) "Open queue" else "Close queue" }) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(36.dp, 4.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .38f), RoundedCornerShape(2.dp)))
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(CompactControlSize))
                    Text("Queue", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(CompactControlSize))
                }
            }
            // Keep the child recognizer alive at the closed endpoint until release.
            if (shown || listPull.pulling) {
                content()
            }
        }
        }

    }
    }) { measurables, constraints ->
        val drawer = measurables.single().measure(Constraints.fixed(constraints.maxWidth, viewportHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Preserve the original final pixel rounding, including player translation.
            val drawerY = with(density) {
                kotlin.math.round((top + (status - top) * phase.value() +
                    80.dp * (1f - playerProgress())).toPx()).toInt()
            }
            drawer.place(0, drawerY - shelfY())
        }
    }
}

// Compose 1.8 compares successive event positions in each pointer node's local
// frame. A drawer moving exactly with the finger can otherwise suppress the next
// move. Keep one stable ancestor in the hit path without moving the recognizers.
// Its bounds cover only the collapsed shelf, so hidden drawer space cannot steal
// background hits. Expanded descendants remain hittable outside this unclipped
// ancestor; the node neither consumes events nor installs gesture ownership.
private object QueuePointerFrame : ModifierNodeElement<QueuePointerFrameNode>() {
    override fun create() = QueuePointerFrameNode()
    override fun update(node: QueuePointerFrameNode) {}
    override fun equals(other: Any?) = this === other
    override fun hashCode() = javaClass.hashCode()
    override fun InspectorInfo.inspectableProperties() { name = "queuePointerFrame" }
}

private class QueuePointerFrameNode : Modifier.Node(), PointerInputModifierNode {
    override fun interceptOutOfBoundsChildEvents() = true
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {}
    override fun onCancelPointerInput() {}
}
