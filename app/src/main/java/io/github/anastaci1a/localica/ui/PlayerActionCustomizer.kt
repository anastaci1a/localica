package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.scrollBy
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
    val handoff = Animatable(0f)
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
    val pool = rememberLazyGridState()
    val slots = remember { mutableMapOf<ActionSlot, Rect>() }
    val actionBounds = remember { mutableMapOf<PlayerAction, Rect>() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var preview by remember { mutableStateOf(Rect.Zero) }
    var poolBounds by remember { mutableStateOf(Rect.Zero) }
    var drag by remember { mutableStateOf<ActionDrag?>(null) }
    var selected by remember { mutableStateOf<PlayerAction?>(null) }
    var choosing by remember { mutableStateOf<ActionSlot?>(null) }
    fun haptic(start: Boolean = false) { view.performHapticFeedback(if (start)
        android.view.HapticFeedbackConstants.LONG_PRESS else android.view.HapticFeedbackConstants.CLOCK_TICK) }
    fun hover(d: ActionDrag) {
        val remove = poolBounds != Rect.Zero && poolBounds.contains(d.point)
        val target = if (remove) null else current.visibleSlots.firstOrNull { slots[it]?.contains(d.point) == true }
        if (remove != d.remove || target != d.target) { d.remove = remove; d.target = target; haptic() }
    }
    fun finish(cancel: Boolean) {
        val d = drag?.takeUnless { it.landing } ?: return
        d.landing = true
        val target = d.target
        val successful = !cancel && (target != null || d.remove)
        if (successful) {
            change(if (d.remove) current.remove(d.action) else current.place(d.action, target!!))
            selected = null; haptic()
        }
        scope.launch {
            // Resolve the actual reflowing destination throughout the landing,
            // including pool items that were initially outside its viewport.
            withFrameNanos { }; withFrameNanos { }
            if (d.action !in current.slots.values) {
                val index = PlayerAction.entries.filterNot { it in current.slots.values }.indexOf(d.action)
                if (index >= 0 && pool.layoutInfo.visibleItemsInfo.none { it.index == index }) pool.easeToItem(index, reducedMotion)
            }
            fun destination() = if (successful && target != null) slots[target]?.center ?: d.point
                else actionBounds[d.action]?.center ?: d.origin
            if (!reducedMotion) {
                val release = d.point
                animate(0f, 1f, animationSpec = tween(180)) { fraction, _ ->
                    d.point = androidx.compose.ui.geometry.lerp(release, destination(), fraction)
                }
                d.handoff.animateTo(1f, tween(220)) { d.point = destination() }
            }
            if (drag === d) drag = null
        }
    }
    @Composable fun Modifier.actionPosition(action: PlayerAction): Modifier {
        var ownBounds by remember(action) { mutableStateOf(Rect.Zero) }
        DisposableEffect(action) { onDispose { if (actionBounds[action] == ownBounds) actionBounds.remove(action) } }
        return onGloballyPositioned { ownBounds = it.boundsInRoot(); actionBounds[action] = ownBounds }
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
    GlassDialog(onDismiss, GlassPlacement.Sheet) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.88f).testTag("player-action-editor").onGloballyPositioned { rootOrigin = it.positionInRoot() }
            .pointerInput(Unit) {
                // The editor owns the drag, so removing its source from the pool
                // never disposes the active pointer handler or cancels the gesture.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val hit = actionBounds.entries.firstOrNull { it.value.contains(rootOrigin + down.position) }
                    if (hit != null && drag == null) {
                        val held = awaitLongPressOrCancellation(down.id)
                        if (held != null) {
                            selected = null
                            drag = ActionDrag(hit.key, hit.value.center, rootOrigin + held.position)
                            haptic(true)
                            var completed = false
                            try {
                                // Own motion before either scrolling child can consume it.
                                // The pickup source may disappear as the pool reflows.
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val point = event.changes.firstOrNull { it.id == held.id } ?: break
                                    // Native modal takeover dispatches a consumed synthetic up.
                                    // It must not turn a hovered slot into a committed assignment.
                                    if (point.isConsumed) break
                                    point.consume()
                                    drag?.let { it.point = rootOrigin + point.position; hover(it) }
                                    if (!point.pressed) { completed = true; break }
                                }
                            } finally { finish(!completed) }
                        }
                    }
                }
            }) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Player actions", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ActionIcon(R.drawable.ic_close, "Close customization", onClick = onDismiss)
                }
                OrderedGlassBubbles(listOf("Centered", "2 rows", "Compact"), buildSet {
                    if (value.centered) add("Centered")
                    if (value.twoRows) add("2 rows")
                    if (value.small) add("Compact")
                }, { it }, reducedMotion, enabled = drag == null, horizontalInset = 0.dp, onSelect = { option ->
                    onChange(when (option) {
                        "Centered" -> value.copy(centered = !value.centered)
                        "2 rows" -> value.copy(twoRows = !value.twoRows)
                        else -> value.copy(small = !value.small)
                    })
                })
                BoxWithConstraints(Modifier.fillMaxWidth().onGloballyPositioned { preview = it.boundsInRoot() }) {
                    val width = if (value.centered) (maxWidth / value.columns.size).coerceAtLeast(48.dp) else if (value.small) 48.dp else 64.dp
                    Row(Modifier.fillMaxWidth().nestedScroll(HorizontalGestureOwner).controlHorizontalScroll(scroll).testTag("customization-slots")) {
                        value.columns.forEach { column -> Column(Modifier.width(width)) {
                            kotlin.repeat(value.rows) { row ->
                                val slot = ActionSlot(row, column)
                                val action = value.slots[slot]
                                val hovered = drag?.target == slot && drag?.landing == false
                                LiquidGlassSurface(Modifier.fillMaxWidth().height(56.dp).padding(3.dp)
                                    .onGloballyPositioned { slots[slot] = it.boundsInRoot() }
                                    .testTag("custom-slot-${slot.key}").clip(Corners.card)
                                    .then(if (action != null) Modifier.actionPosition(action) else Modifier)
                                    .combinedClickable(onClick = {
                                        selected?.let { onChange(value.place(it, slot)); selected = null; haptic() } ?: run { choosing = slot }
                                    }, onLongClick = null)
                                    .semantics { contentDescription = "${slot.label}: ${action?.label ?: "Empty"}" },
                                    cornerRadius = 16.dp, tint = if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent, pressFeedback = false) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        if (action != null) Box(Modifier.graphicsLayer { alpha = if (drag?.action == action) drag?.handoff?.value ?: 0f else 1f }) {
                                            PlayerActionGlyph(action, null)
                                        }
                                    }
                                }
                            }
                        } }
                    }
                }
                if (value.hiddenCount > 0) Text("${value.hiddenCount} assigned actions are hidden by this layout.", style = MaterialTheme.typography.bodySmall)
                Text(if (selected == null) "Drag an action into a slot or back into the pool. You can also tap an action, then a slot." else "Choose a slot for ${selected!!.label}.",
                    Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(color = LocalContentColor.current.copy(alpha = .12f))
                Text("Available actions", style = MaterialTheme.typography.titleSmall)
                LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.weight(1f).onGloballyPositioned { poolBounds = it.boundsInRoot() }.testTag("action-palette")
                    .controlScrollParent(pool, ControlScrollAxis.Vertical),
                    state = pool, contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(PlayerAction.entries.filter { it !in value.slots.values && (it != drag?.action || drag?.landing == true) }, key = { it.name }) { action ->
                        Column(Modifier.animateItem().padding(3.dp).clip(Corners.card).actionPosition(action)
                            .graphicsLayer { alpha = if (drag?.action == action) drag?.handoff?.value ?: 0f else 1f }
                            .combinedClickable(onClick = { selected = if (selected == action) null else action }, onLongClick = null)
                            .testTag("palette-${action.name}").semantics { this.selected = selected == action; stateDescription = "Available" }
                            .padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CompositionLocalProvider(LocalContentColor provides (if (selected == action) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) {
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { PlayerActionGlyph(action, null) }
                                Text(action.label, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                    }
                }
                ActionTextButton(onClick = { onChange(PlayerActionSettings()); selected = null }, enabled = drag == null) { Text("Reset player actions") }
            }
            drag?.let { d ->
                GlassDragBubble(Modifier.size(52.dp).graphicsLayer {
                    val position = d.point
                    translationX = position.x - rootOrigin.x - size.width / 2
                    translationY = position.y - rootOrigin.y - size.height / 2
                    scaleX = 1.08f; scaleY = 1.08f
                }.zIndex(10f).testTag("drag-player-action"), landing = { d.handoff.value }) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PlayerActionGlyph(d.action, d.action.label) }
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
