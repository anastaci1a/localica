package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal val CollapsedQueueHeight = 64.dp

@Composable
internal fun BoxWithConstraintsScope.QueueSurface(progress: Animatable<Float, *>, settings: InterfaceSettings,
    onSettled: (Boolean) -> Unit, playerProgress: () -> Float = { 1f },
    onDragTrace: ((String) -> Unit)? = null, onOpeningContact: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val top = maxHeight - inset - CollapsedQueueHeight
    val density = LocalDensity.current
    val distance = with(density) { (top - status).toPx().coerceAtLeast(1f) }
    var start by remember { mutableFloatStateOf(1f) }
    var delta by remember { mutableFloatStateOf(0f) }
    fun settle(open: Boolean) {
        onSettled(open)
        scope.launch { progress.animateTo(if (open) 1f else 0f, tween(settings.motionDuration(400))) }
    }
    val currentSettle by rememberUpdatedState<(Boolean) -> Unit>(::settle)
    // Optional test diagnostics observe existing callbacks without adding a pointer node.
    // A stationary pointer observer would itself change Compose's hit-path dispatch.
    val currentTrace by rememberUpdatedState(onDragTrace)
    val currentOpeningContact by rememberUpdatedState(onOpeningContact)
    // Contact ownership is fixed by its first directed delta, not later reversal.
    // This input-only flag adds no geometry state or new pointer observer.
    val directDirectionAcquired = remember { booleanArrayOf(false) }
    val scrollOwnership = remember { ScrollGestureOwnership() }
    val currentDistance by rememberUpdatedState(distance)
    val currentDensity by rememberUpdatedState(density.density)
    // Geometry changes must not replace the connection midway through its contact.
    // Keep that contact's pixel-to-progress scale until release; the next pull
    // acquires the latest viewport and density.
    val listPull = remember(progress) {
        object : NestedScrollConnection {
            var pulling by mutableStateOf(false)
            var pulled = 0f
            var pullDistance = currentDistance
            var pullDensity = currentDensity
            fun cancelPull() {
                currentTrace?.invoke("nested-cancel pulling=$pulling pulled=$pulled progress=${progress.value}")
                if (!pulling) return
                pulling = false
                pulled = 0f
                currentSettle(true)
            }
            fun move(dy: Float): Offset {
                currentTrace?.invoke("nested-move dy=$dy pulled=$pulled progress=${progress.value}")
                // Keep raw travel and ownership through both clamped endpoints. A
                // reversal must retrace excess travel, never leak into the child list.
                pulled += dy
                val consumed = dy
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    progress.snapTo((1f - pulled / pullDistance).coerceIn(0f, 1f))
                    currentTrace?.invoke("nested-applied pulled=$pulled consumed=$consumed progress=${progress.value}")
                }
                return Offset(0f, consumed)
            }
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                currentTrace?.invoke("nested-pre source=$source available=$available pulling=$pulling pulled=$pulled progress=${progress.value}")
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                return if (pulling) move(available.y) else Offset(0f, scrollOwnership.consumeRetrace(available.y))
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                // Even a partial final list delta owns the rest of this contact. A fresh
                // downward pull already at the top still starts the documented drawer drag.
                scrollOwnership.recordScroll(consumed.y)
                currentTrace?.invoke("nested-post consumed=$consumed available=$available pulling=$pulling contentOwned=${scrollOwnership.contentOwned} mayHandoff=${scrollOwnership.mayHandoff} progress=${progress.value}")
                if (scrollOwnership.contentOwned) {
                    scrollOwnership.recordBoundary(available.y)
                    return Offset(0f, available.y)
                }
                if (!scrollOwnership.mayHandoff || available.y <= 0f) return Offset.Zero
                if (!pulling) {
                    pullDistance = currentDistance
                    pullDensity = currentDensity
                    pulled = (1f - progress.value) * pullDistance
                }
                scrollOwnership.claimDrawer()
                pulling = true
                return move(available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                currentTrace?.invoke("nested-fling available=$available pulling=$pulling mayCommit=${scrollOwnership.mayCommitPull} progress=${progress.value}")
                if (!pulling) return Velocity.Zero
                // Scrollable also dispatches a zero-velocity pre-fling on cancellation,
                // potentially before our observer receives its synthetic cancel event.
                if (!scrollOwnership.mayCommitPull) {
                    cancelPull()
                    return Velocity(0f, available.y)
                }
                pulling = false
                currentSettle(settleOpen(-pulled / pullDensity, -available.y / pullDensity,
                    progress.value, opening = false))
                pulled = 0f
                return Velocity(0f, available.y)
            }
        }
    }
    val shown by remember(progress) { derivedStateOf { progress.value > 0f } }
    val currentPlayerProgress by rememberUpdatedState(playerProgress)
    val interactive by remember { derivedStateOf { currentPlayerProgress() >= .99f } }
    if (shown) Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value * 0.2f }.clickable { settle(false) }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim) {}
    }
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
        .draggable(rememberDraggableState { dy ->
            currentTrace?.invoke("direct-delta dy=$dy start=$start delta=$delta progress=${progress.value}")
            delta += dy
            if (!directDirectionAcquired[0] && dy != 0f) {
                directDirectionAcquired[0] = true
                if (dy < 0f && (start - delta / distance).coerceIn(0f, 1f) > progress.value)
                    currentOpeningContact?.invoke()
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                progress.snapTo((start - delta / distance).coerceIn(0f, 1f))
                currentTrace?.invoke("direct-applied delta=$delta progress=${progress.value}")
            }
        }, Orientation.Vertical, onDragStarted = { position ->
            currentTrace?.invoke("direct-start position=$position progress=${progress.value}")
            progress.stop(); start = progress.value; delta = 0f
            directDirectionAcquired[0] = false
        }, onDragStopped = { velocity ->
            currentTrace?.invoke("direct-stop velocity=$velocity delta=$delta progress=${progress.value}")
            settle(settleOpen(-delta / density.density, -velocity / density.density, progress.value, start < .5f))
        })
        .then(if (onDragTrace == null) Modifier else Modifier.onGloballyPositioned {
            currentTrace?.invoke("layout top=${it.positionInRoot().y} progress=${progress.value} distance=$distance")
        })
        .observeScrollOwnership(scrollOwnership, listPull::cancelPull)
        .nestedScroll(listPull).testTag("queue-surface"), shape = Corners.panel) {
        Column(Modifier.fillMaxSize().clipToBounds().padding(bottom = inset)) {
            Box(Modifier.fillMaxWidth().height(CollapsedQueueHeight).clickable(onClickLabel = if (!shown) "Open queue" else "Close queue") {
                val open = progress.value < .5f
                if (open) currentOpeningContact?.invoke()
                settle(open)
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
    }) { measurables, constraints ->
        val drawer = measurables.single().measure(Constraints.fixed(constraints.maxWidth, viewportHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Preserve the original final pixel rounding, including player translation.
            val drawerY = with(density) {
                kotlin.math.round((top + (status - top) * progress.value +
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
