package io.github.anastaci1a.localica.ui

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
import io.github.anastaci1a.localica.R
import io.github.anastaci1a.localica.queue.ListeningQueue

private data class QueueDragPresentation(val browsedId: String?, val playingId: String?, val isPlaying: Boolean,
    val numbers: Map<String, Int>)

/** Queue identity and row content adapt the same transaction/material used by song lists. */
@Composable
internal fun QueueSelectorList(queues: List<ListeningQueue>, browsedId: String?, playingId: String?,
    isPlaying: Boolean, reducedMotion: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    onSelect: (String) -> Unit, onRename: (String) -> Unit, onDelete: (String) -> Unit,
    onOffset: (String, Int) -> Unit, onMove: (String, String?, (Boolean) -> Unit) -> Unit) {
    val panelReady = LocalGlassPanelReady.current
    val playingIndex = queues.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = playingIndex)
    LaunchedEffect(enabled) { if (enabled) grid.requestScrollToItem(playingIndex) }
    val scope = rememberCoroutineScope()
    val presentation by rememberUpdatedState(QueueDragPresentation(browsedId, playingId, isPlaying,
        queues.mapIndexed { index, queue -> queue.id to index + 1 }.toMap()))
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
    SideEffect { reorder.reconcile(queues) }
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
        .sharedKeyedDrag(reorder, enabled && panelReady, reducedMotion, itemAt = { point ->
            val viewport = reorder.viewportCoordinates?.takeIf { it.isAttached }
            fun LayoutCoordinates.containsPoint() = isAttached &&
                viewport?.localBoundingBoxOf(this, clipBounds = false)?.contains(point) == true
            val key = reorder.itemCoordinates.entries.firstOrNull { (_, row) -> row.containsPoint() }?.key
            queues.firstOrNull { it.id == key && actions[key]?.containsPoint() != true }
        }, commit = commit)) {
        SelectionViewport { overscroll ->
            LazyVerticalGrid(columns = GridCells.Fixed(1), state = grid,
                modifier = Modifier.testTag("queue-selector-grid").selectableGroup().padding(horizontal = 8.dp)
                    .controlScrollParent(grid, ControlScrollAxis.Vertical), overscrollEffect = overscroll) {
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
                            .onGloballyPositioned { reorder.itemCoordinates[queue.id] = it }) {
                            DisposableEffect(queue.id) { onDispose {
                                reorder.itemCoordinates.remove(queue.id); actions.remove(queue.id)
                            } }
                            QueueSelectorRow(displayed, presentation.numbers[queue.id] ?: 0, queue.id == browsedId,
                                queue.id == playingId, isPlaying, reducedMotion, interactive,
                                actionsModifier = Modifier.onGloballyPositioned { actions[queue.id] = it },
                                onSelect = { onSelect(queue.id) }, onRename = { onRename(queue.id) }, onDelete = { onDelete(queue.id) },
                                onOffset = { onOffset(queue.id, it) })
                        }
                    }
                }
            }
        }
        KeyedDragOverlay(reorder, reducedMotion, countDescription = { "Dragging $it queues" }) { queue, appearance ->
            val displayed = queues.firstOrNull { it.id == queue.id } ?: queue
            QueueSelectorRow(displayed, appearance.numbers[queue.id] ?: 0, queue.id == appearance.browsedId,
                queue.id == appearance.playingId, appearance.isPlaying, reducedMotion, false,
                onSelect = {}, onRename = {}, onDelete = {}, onOffset = {})
        }
    }
}

@Composable
private fun QueueSelectorRow(queue: ListeningQueue, number: Int, chosen: Boolean, playing: Boolean,
    isPlaying: Boolean, reducedMotion: Boolean, enabled: Boolean, actionsModifier: Modifier = Modifier,
    onSelect: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onOffset: (Int) -> Unit) {
    Surface(shape = Corners.card, color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.padding(vertical = 2.dp).selectionLens(chosen, false).testTag("queue-row-${queue.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReorderHandle("Reorder ${queue.name}", onOffset, Modifier.testTag("queue-handle-${queue.id}"), enabled)
            Row(Modifier.weight(1f).testTag("queue-select-${queue.id}").selectable(chosen, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    if (chosen) MarqueeTitle("$number. ${queue.name}", reducedMotion, style = MaterialTheme.typography.bodyLarge)
                    else Text("$number. ${queue.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (playing) Text(if (isPlaying) "Playing" else "Playback queue", style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(actionsModifier) {
                ActionIcon(R.drawable.ic_edit, "Rename ${queue.name}", enabled = enabled, glass = false, quiet = true, onClick = onRename)
                ActionIcon(R.drawable.ic_close, "Delete ${queue.name}", enabled = enabled, glass = false, quiet = true, onClick = onDelete)
            }
        }
    }
}
