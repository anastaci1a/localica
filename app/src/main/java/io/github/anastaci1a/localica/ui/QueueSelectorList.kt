package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.components.TriggerRole

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onPlaced
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.ui.components.PopupSharedContent

internal val LocalQueueSelectorSharedName = staticCompositionLocalOf<PopupSharedContent?> { null }

/** Same in-host witness as the playlist adapter; no screenshot or redraw request. */
internal data class QueueSelectionDrawSource(val scene: SelectionScene, val state: LazyGridState,
    val viewport: LayoutCoordinates, val content: LayoutCoordinates,
    val rows: Map<String, LayoutCoordinates>, val browsedId: String?, val view: android.view.View,
    val hostDraws: () -> Int)
internal val LocalQueueSelectionDrawObserver = staticCompositionLocalOf<((QueueSelectionDrawSource) -> Unit)?> { null }
private class QueueSelectionProbe {
    var scene: SelectionScene? = null
    var viewport: LayoutCoordinates? = null
    var content: LayoutCoordinates? = null
    val rows = mutableMapOf<String, LayoutCoordinates>()
    var hostDraws = 0
}

private data class QueueDragPresentation(val browsedId: String?, val playingId: String?, val isPlaying: Boolean)

/** Queue identity and row content adapt the same transaction/material used by song lists. */
@Composable
internal fun QueueSelectorList(queues: List<ListeningQueue>, browsedId: String?, playingId: String?,
    isPlaying: Boolean, reducedMotion: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    onSelect: (String) -> Unit, onRename: (String) -> Unit, onDelete: (String) -> Unit,
    onOffset: (String, Int) -> Unit, onMove: (String, String?, (Boolean) -> Unit) -> Unit) {
    val panelReady = LocalGlassPanelReady.current
    val playingIndex = queues.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
    val initialIndex = queues.indexOfFirst { it.id == browsedId }.takeIf { it >= 0 } ?: playingIndex
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = initialIndex)
    val probe = remember { QueueSelectionProbe() }
    val observer = LocalQueueSelectionDrawObserver.current
    val observe by rememberUpdatedState(observer)
    val selectionView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(enabled) { if (enabled) grid.requestScrollToItem(initialIndex) }
    val scope = rememberCoroutineScope()
    val presentation by rememberUpdatedState(QueueDragPresentation(browsedId, playingId, isPlaying))
    // Resting numbers; a live drag numbers rows by its insertion instead (#735).
    val numbers = remember(queues) { queues.withIndex().associate { it.value.id to it.index + 1 } }
    val scene = remember { SelectionScene() }
    val move by rememberUpdatedState(onMove)
    val reorder = remember {
        object : KeyedDragState<ListeningQueue, QueueDragPresentation>(grid, scope, { it.id }, optimisticCommit = false) {
            override fun capturePresentation() = presentation
        }
    }
    val actions = remember { mutableMapOf<String, LayoutCoordinates>() }
    val commit: (Set<String>, String?, (Boolean) -> Unit) -> Unit = { ids, before, done ->
        ids.singleOrNull()?.let { move(it, before, done) } ?: done(false)
    }
    SideEffect {
        reorder.reconcile(queues)
        // The browsed queue's chosen lift is a resting lift: its card keeps it through the handoff,
        // and every held row rises in place first, exactly like a held song (#735).
        reorder.hoverLift = { scene.hover[it] }
        reorder.restsLifted = { it == browsedId }
    }
    // A concurrent add/remove/reorder invalidates the pointer's original insertion snapshot.
    LaunchedEffect(queues.map { it.id }) {
        val drag = reorder.drag
        if (drag != null && !drag.dropping && drag.original.map { it.id } != queues.map { it.id })
            reorder.finish(true, reducedMotion, commit = commit)
    }
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    LaunchedEffect(reorder.drag) { if (reorder.drag != null) reorder.autoScroll(edge) }
    Box(modifier.fillMaxWidth().testTag("queue-selector-list")
        .onGloballyPositioned { reorder.viewportOrigin = it.positionInRoot(); reorder.viewportCoordinates = it }
        .onSizeChanged { reorder.viewportWidth = it.width; reorder.viewportHeight = it.height }
        .sharedKeyedDrag(reorder, enabled && panelReady, reducedMotion, pickupHaptic = true, itemAt = { point ->
            val viewport = reorder.viewportCoordinates?.takeIf { it.isAttached }
            fun LayoutCoordinates.containsPoint() = isAttached &&
                viewport?.localBoundingBoxOf(this, clipBounds = false)?.contains(point) == true
            val key = reorder.itemCoordinates.entries.firstOrNull { (_, row) -> row.containsPoint() }?.key
            queues.firstOrNull { it.id == key && actions[key]?.containsPoint() != true }
        }, commit = commit)) {
        SelectionViewport(Modifier.then(if (observer == null) Modifier else Modifier.onPlaced { probe.viewport = it }),
            observePlacement = { grid.layoutInfo; if (observer != null) probe.hostDraws++; Unit }, scene = scene,
            visibleItemKeys = { grid.layoutInfo.visibleItemsInfo.mapTo(mutableSetOf()) { it.key } },
            contentDrawing = if (observer == null) Modifier else Modifier.drawWithContent {
                drawContent()
                val scene = probe.scene
                val viewport = probe.viewport
                val content = probe.content
                if (scene != null && viewport?.isAttached == true && content?.isAttached == true)
                    androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                        observe?.invoke(QueueSelectionDrawSource(scene, grid, viewport, content, probe.rows, browsedId, selectionView) { probe.hostDraws })
                    }
            }) { overscroll ->
            val scene = LocalSelectionScene.current
            SideEffect { probe.scene = scene }
            LazyVerticalGrid(columns = GridCells.Fixed(1), state = grid,
                modifier = Modifier.testTag("queue-selector-grid").selectableGroup().padding(horizontal = 8.dp)
                    .controlScrollParent(grid, ControlScrollAxis.Vertical)
                    .then(if (observer == null) Modifier else Modifier.onPlaced { probe.content = it }), overscrollEffect = overscroll) {
                items(reorder.rendered(queues), key = { it?.id ?: DropGapKey }) { queue ->
                    if (queue == null) {
                        Spacer(Modifier.fillMaxWidth().height(with(density) { (reorder.drag?.cardSize?.height ?: 0f).toDp() }))
                    } else {
                        val displayed = queues.firstOrNull { it.id == queue.id } ?: queue
                        val drag = reorder.drag
                        val interactive = enabled && drag == null
                        Box(Modifier.animateItem(placementSpec = if (drag?.let { it.dropping && queue.id in it.keys } == true) null
                            else tween(if (reducedMotion) 0 else 350), fadeInSpec = null, fadeOutSpec = null)
                            .graphicsLayer { alpha = reorder.drag?.takeIf { it.dropping && queue.id in it.keys }?.handoff?.value ?: 1f }
                            .keyedDragItem(reorder, queue.id, scene)) {
                            DisposableEffect(queue.id) { onDispose {
                                reorder.itemCoordinates.remove(queue.id); actions.remove(queue.id); probe.rows.remove(queue.id)
                            } }
                            val live by remember(reorder, queue.id) { derivedStateOf { reorder.livePosition(queue.id) } }
                            QueueSelectorRow(displayed, live ?: numbers[queue.id] ?: 0, queue.id == browsedId || reorder.held == queue.id,
                                queue.id == playingId, isPlaying, reducedMotion, interactive, browsing = queue.id == browsedId,
                                liftAlpha = { reorder.restAlpha(queue.id) }, motionPaused = drag != null,
                                actionsModifier = Modifier.onGloballyPositioned { actions[queue.id] = it },
                                selectionPlacement = if (observer == null) Modifier else Modifier.onPlaced { probe.rows[queue.id] = it },
                                sharedNameLive = drag?.keys?.contains(queue.id) != true,
                                onSelect = { onSelect(queue.id) }, onRename = { onRename(queue.id) }, onDelete = { onDelete(queue.id) },
                                onOffset = { onOffset(queue.id, it) })
                        }
                    }
                }
            }
        }
        KeyedDragOverlay(reorder, reducedMotion, countDescription = { "Dragging $it queues" }) { queue, appearance ->
            val displayed = queues.firstOrNull { it.id == queue.id } ?: queue
            // The card is the lift: no second lens, and its title rests so it hands off pixel-for-pixel.
            QueueSelectorRow(displayed, reorder.livePosition(queue.id) ?: numbers[queue.id] ?: 0, false,
                queue.id == appearance.playingId, appearance.isPlaying, reducedMotion, false,
                browsing = queue.id == appearance.browsedId, motionPaused = true,
                onSelect = {}, onRename = {}, onDelete = {}, onOffset = {})
        }
    }
}

/** [chosen] is the lifted choice: the browsing selection, or a held drag source while held. [browsing]
 * alone marks the browsing selection's title. */
@Composable
private fun QueueSelectorRow(queue: ListeningQueue, number: Int, chosen: Boolean, playing: Boolean,
    isPlaying: Boolean, reducedMotion: Boolean, enabled: Boolean, browsing: Boolean, actionsModifier: Modifier = Modifier,
    selectionPlacement: Modifier = Modifier, liftAlpha: () -> Float = { 1f }, motionPaused: Boolean = false, sharedNameLive: Boolean = true,
    onSelect: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onOffset: (Int) -> Unit) {
    val sharedName = LocalQueueSelectorSharedName.current
    Surface(shape = Corners.card, color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.padding(vertical = 2.dp).selectionLens(chosen, false, itemKey = queue.id,
            alpha = liftAlpha,
            outline = rememberPaddedItemOutline(null, 0.dp, null, 0.dp, 0.dp))
            .then(selectionPlacement).testTag("queue-row-${queue.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReorderHandle("Reorder ${queue.name}", onOffset, Modifier.testTag("queue-handle-${queue.id}"), enabled)
            Row(Modifier.weight(1f).testTag("queue-select-${queue.id}").selectable(chosen, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    if (browsing && sharedName != null) {
                        if (sharedNameLive) sharedName.Expanded(Modifier.fillMaxWidth())
                        else sharedName.ExpandedCopy(Modifier.fillMaxWidth())
                    }
                    else if (browsing) MarqueeTitle("$number. ${queue.name}", reducedMotion, style = MaterialTheme.typography.bodyLarge, motionPaused = motionPaused)
                    else Text("$number. ${queue.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (playing) Text(if (isPlaying) "Playing" else "Playback queue", style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(actionsModifier) {
                ActionIcon(AppIcon.ActionEdit, "Rename ${queue.name}", enabled = enabled, glass = false, role = TriggerRole.PlainIcon, onClick = onRename)
                ActionIcon(AppIcon.ActionClose, "Delete ${queue.name}", enabled = enabled, glass = false, role = TriggerRole.PlainIcon, onClick = onDelete)
            }
        }
    }
}
