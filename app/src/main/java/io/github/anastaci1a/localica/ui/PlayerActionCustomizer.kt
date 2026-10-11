package io.github.anastaci1a.localica.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.anastaci1a.localica.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Stable
private class ActionDrag(val action: PlayerAction, val origin: Offset, point: Offset) {
    var point by mutableStateOf(point)
    var target by mutableStateOf<ActionSlot?>(null)
    var remove by mutableStateOf(false)
    var landing by mutableStateOf(false)
    val position = Animatable(point, Offset.VectorConverter)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerActionCustomizer(value: PlayerActionSettings, onChange: (PlayerActionSettings) -> Unit,
    onDismiss: () -> Unit, reducedMotion: Boolean = false) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val slots = remember { mutableMapOf<ActionSlot, Rect>() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var preview by remember { mutableStateOf(Rect.Zero) }
    var removeBounds by remember { mutableStateOf(Rect.Zero) }
    var drag by remember { mutableStateOf<ActionDrag?>(null) }
    var selected by remember { mutableStateOf<PlayerAction?>(null) }
    var choosing by remember { mutableStateOf<ActionSlot?>(null) }
    fun haptic(start: Boolean = false) { view.performHapticFeedback(if (start)
        android.view.HapticFeedbackConstants.LONG_PRESS else android.view.HapticFeedbackConstants.CLOCK_TICK) }
    fun hover(d: ActionDrag) {
        val remove = removeBounds != Rect.Zero && removeBounds.contains(d.point)
        val target = if (remove) null else current.visibleSlots.firstOrNull { slots[it]?.contains(d.point) == true }
        if (remove != d.remove || target != d.target) { d.remove = remove; d.target = target; haptic() }
    }
    fun finish(cancel: Boolean) {
        val d = drag?.takeUnless { it.landing } ?: return
        d.landing = true
        val target = d.target
        val successful = !cancel && (target != null || d.remove)
        val destination = if (successful && target != null) slots[target]?.center ?: d.point else d.origin
        if (successful) {
            change(if (d.remove) current.remove(d.action) else current.place(d.action, target!!))
            selected = null; haptic()
        }
        scope.launch {
            d.position.snapTo(d.point)
            if (!reducedMotion) d.position.animateTo(destination, tween(180))
            if (drag === d) drag = null
        }
    }
    @Composable fun Modifier.dragAction(action: PlayerAction): Modifier {
        var bounds by remember(action) { mutableStateOf(Rect.Zero) }
        return onGloballyPositioned { bounds = Rect(it.positionInRoot(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }
            .pointerInput(action) {
                detectDragGesturesAfterLongPress(onDragStart = { local ->
                    selected = null
                    drag = ActionDrag(action, bounds.center, bounds.topLeft + local)
                    haptic(true)
                }, onDrag = { pointer, amount ->
                    pointer.consume()
                    drag?.takeUnless { it.landing }?.let { it.point += amount; hover(it) }
                }, onDragEnd = { finish(false) }, onDragCancel = { finish(true) })
            }
    }
    LaunchedEffect(value.centered, value.twoRows, value.small) { drag = null }
    val edge = with(density) { 40.dp.toPx() }
    LaunchedEffect(drag) {
        val d = drag ?: return@LaunchedEffect
        fun speed(): Float = if (!preview.contains(d.point)) 0f else when {
            d.point.x < preview.left + edge && scroll.canScrollBackward -> -((preview.left + edge - d.point.x) / edge).coerceIn(0f, 1f)
            d.point.x > preview.right - edge && scroll.canScrollForward -> ((d.point.x - preview.right + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        var previous = withFrameNanos { it }
        while (!d.landing) {
            if (speed() == 0f) {
                snapshotFlow { d.landing || speed() != 0f }.first { it }
                previous = withFrameNanos { it }
            }
            val frame = withFrameNanos { it }
            val seconds = ((frame - previous) / 1_000_000_000f).coerceIn(0f, .032f)
            previous = frame
            if (d.landing) break
            val velocity = speed()
            if (velocity != 0f) { scroll.scrollBy(velocity * edge * 8 * seconds); hover(d) }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = Corners.panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.88f).testTag("player-action-editor").onGloballyPositioned { rootOrigin = it.positionInRoot() }) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Player actions", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ActionIcon(R.drawable.ic_close, "Close customization", onClick = onDismiss)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(value.centered, { onChange(value.copy(centered = !value.centered)) }, { Text("Centered") }, enabled = drag == null)
                    FilterChip(value.twoRows, { onChange(value.copy(twoRows = !value.twoRows)) }, { Text("2 rows") }, enabled = drag == null)
                    FilterChip(value.small, { onChange(value.copy(small = !value.small)) }, { Text("Small") }, enabled = drag == null)
                }
                BoxWithConstraints(Modifier.fillMaxWidth().onGloballyPositioned { preview = it.boundsInRoot() }) {
                    val width = if (value.centered) (maxWidth / value.columns.size).coerceAtLeast(48.dp) else if (value.small) 48.dp else 64.dp
                    Row(Modifier.fillMaxWidth().nestedScroll(HorizontalGestureOwner).horizontalScroll(scroll).testTag("customization-slots")) {
                        value.columns.forEach { column -> Column(Modifier.width(width)) {
                            kotlin.repeat(value.rows) { row ->
                                val slot = ActionSlot(row, column)
                                val action = value.slots[slot]
                                val hovered = drag?.target == slot && drag?.landing == false
                                val color by animateColorAsState(if (hovered) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    tween(if (reducedMotion) 0 else 120), label = "Drop slot")
                                Surface(Modifier.fillMaxWidth().height(56.dp).padding(3.dp)
                                    .onGloballyPositioned { slots[slot] = it.boundsInRoot() }
                                    .testTag("custom-slot-${slot.key}").clip(Corners.card)
                                    .then(if (action != null) Modifier.dragAction(action) else Modifier)
                                    .combinedClickable(onClick = {
                                        selected?.let { onChange(value.place(it, slot)); selected = null; haptic() } ?: run { choosing = slot }
                                    }, onLongClick = null)
                                    .semantics { contentDescription = "${slot.label}: ${action?.label ?: "Empty"}" },
                                    shape = Corners.card, color = color,
                                    border = BorderStroke(if (hovered) 2.dp else 1.dp, if (hovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (action != null) Box(Modifier.graphicsLayer { alpha = if (drag?.action == action) .25f else 1f }) {
                                            PlayerActionGlyph(action, null, value.small)
                                        }
                                    }
                                }
                            }
                        } }
                    }
                }
                if (value.hiddenCount > 0) Text("${value.hiddenCount} assigned actions are hidden by this layout.", style = MaterialTheme.typography.bodySmall)
                Text(if (selected == null) "Hold and drag an action, or tap an action then a slot." else "Choose a slot for ${selected!!.label}.",
                    Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                if (drag != null) Surface(Modifier.fillMaxWidth().height(48.dp).onGloballyPositioned { removeBounds = it.boundsInRoot() }.testTag("remove-player-action"),
                    shape = Corners.card, color = if (drag?.remove == true) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Box(contentAlignment = Alignment.Center) { Text("Release here to remove") }
                } else SideEffect { removeBounds = Rect.Zero }
                LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.weight(1f).testTag("action-palette"),
                    contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(PlayerAction.entries, key = { it.name }) { action ->
                        val assigned = action in value.slots.values
                        Column(Modifier.padding(3.dp).clip(Corners.card).dragAction(action)
                            .combinedClickable(onClick = { selected = if (selected == action) null else action }, onLongClick = null)
                            .testTag("palette-${action.name}").semantics { this.selected = selected == action; stateDescription = if (assigned) "Assigned" else "Available" }
                            .padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CompositionLocalProvider(LocalContentColor provides (if (selected == action) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                .copy(alpha = if (assigned && selected != action) .45f else 1f)) {
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { PlayerActionGlyph(action, null) }
                                Text(action.label, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                    }
                }
                TextButton(onClick = { onChange(PlayerActionSettings()); selected = null }, enabled = drag == null) { Text("Reset player actions") }
            }
            drag?.let { d ->
                Surface(Modifier.size(52.dp).graphicsLayer {
                    val position = if (d.landing) d.position.value else d.point
                    translationX = position.x - rootOrigin.x - size.width / 2
                    translationY = position.y - rootOrigin.y - size.height / 2
                    scaleX = 1.08f; scaleY = 1.08f
                }.zIndex(10f).testTag("drag-player-action"), shape = Corners.card, shadowElevation = 12.dp,
                    color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) { PlayerActionGlyph(d.action, d.action.label) }
                }
            }
        }
    }
    choosing?.let { slot ->
        ChoiceDialog(slot.label, listOf(null) + PlayerAction.entries, value.slots[slot], { it?.label ?: "Empty slot" }, { choosing = null }) { action ->
            onChange(if (action == null) value.copy(slots = value.slots - slot) else value.place(action, slot))
        }
    }
}
