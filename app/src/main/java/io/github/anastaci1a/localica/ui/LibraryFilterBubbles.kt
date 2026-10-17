package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.anastaci1a.localica.browse.LibraryFilter
import io.github.anastaci1a.localica.navigation.MediaKind
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun LibraryFilterBubbles(filters: List<LibraryFilter>, selected: MediaKind?,
    reducedMotion: Boolean, onSelect: (MediaKind?) -> Unit, onConfigure: () -> Unit) {
    var menu by remember { mutableStateOf<LibraryFilter?>(null) }
    OrderedGlassBubbles(filters, filters.filter { it.kind == selected }.toSet(), { it.label },
        reducedMotion, Modifier.testTag("library-filters"), role = Role.Tab,
        tag = { "filter-${it.name}" }, onSelect = { onSelect(it.kind.takeUnless { kind -> kind == selected }) },
        onLongClick = { menu = it }) { filter, anchor ->
        GlassMenu(menu == filter, anchor, { menu = null }) {
            ActionMenuItem("Edit library filters and order", onClick = { menu = null; onConfigure() })
        }
    }
}

/** Selected and unselected groups retain their canonical order, with yielding neighbors. */
internal fun <T> orderedBubbleGroups(items: List<T>, selected: Set<T>) =
    items.filter { it in selected } + items.filterNot { it in selected }

@Composable
internal fun <T : Any> OrderedGlassBubbles(items: List<T>, selected: Set<T>, label: (T) -> String,
    reducedMotion: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true,
    role: Role = Role.Checkbox, horizontalInset: androidx.compose.ui.unit.Dp = 12.dp, tag: (T) -> String = { it.toString() }, onSelect: (T) -> Unit,
    onLongClick: ((T) -> Unit)? = null, overlay: @Composable (T, GlassAnchor) -> Unit = { _, _ -> }) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val widths = items.associateWith { with(density) { measurer.measure(label(it), style).size.width.toDp() + 32.dp } }
    val gap = with(density) { 8.dp.toPx() }
    val widthPx = widths.mapValues { with(density) { it.value.toPx() } }
    val order = orderedBubbleGroups(items, selected)
    var x = 0f
    val targets = order.associateWith { val start = x; x += widthPx.getValue(it) + gap; start }
    val offsets = remember { mutableMapOf<T, Animatable<Float, AnimationVector1D>>() }
    items.forEach { offsets.getOrPut(it) { Animatable(targets.getValue(it)) } }
    var previous by remember { mutableStateOf(selected) }
    var moving by remember { mutableStateOf(emptySet<T>()) }
    val scroll = rememberScrollState()
    LaunchedEffect(selected, items, widthPx, reducedMotion) {
        val travelers = ((selected - previous) + (previous - selected)).intersect(items.toSet())
        val follow = items.firstOrNull { it in selected && it !in previous }
        previous = selected
        moving = travelers
        val starts = items.associateWith { offsets.getValue(it).value }
        val startScroll = scroll.value
        if (reducedMotion) {
            items.forEach { offsets.getValue(it).snapTo(targets.getValue(it)) }
            scroll.scrollTo(0)
        } else coroutineScope {
            val animations = items.map { item -> launch {
                val crossing = if (item in travelers) 0f else travelers.mapNotNull { traveler ->
                    val from = starts.getValue(traveler)
                    val to = targets.getValue(traveler)
                    if (abs(to - from) < 1f) null else {
                        val center = starts.getValue(item) + widthPx.getValue(item) / 2
                        ((center - (from + widthPx.getValue(traveler) / 2)) / (to - from)).takeIf { it in 0f..1f }
                    }
                }.minOrNull() ?: 0f
                delay((crossing * 120).toLong())
                offsets.getValue(item).animateTo(targets.getValue(item),
                    spring(dampingRatio = 1f, stiffness = if (item in travelers) 440f else 700f))
            } }
            val follower = follow?.let { item -> launch {
                snapshotFlow { offsets.getValue(item).value }.collect { position ->
                    scroll.scrollTo((startScroll + position - starts.getValue(item)).roundToInt().coerceAtLeast(0))
                }
            } }
            animations.forEach { it.join() }
            follower?.cancel()
        }
        moving = emptySet()
    }
    val total = widths.values.fold(0.dp) { sum, width -> sum + width } + 8.dp * (items.size - 1).coerceAtLeast(0)
    val shadowInset = glassShadowInset()
    Box(modifier.fillMaxWidth().padding(vertical = ListPillVerticalInset).nestedScroll(HorizontalGestureOwner)
        .shadowViewportOutset(shadowInset).horizontalScroll(scroll)
        .padding(vertical = shadowInset).padding(horizontal = horizontalInset).width(total).height(listPillTargetHeight())) {
        GlassShadowScope(Modifier.fillMaxSize()) {
        items.forEach { item -> key(item) {
            val selection by animateControlStateFloat(if (item in selected) .07f else 0f, item in selected, "Library filter selection", reducedMotion)
            val anchor = rememberGlassAnchor()
            val source = LocalFrostedBackdrop.current
            SideEffect { anchor.source = source; anchor.face = { Text(label(item), style = style, maxLines = 1) } }
            Box(Modifier.offset { IntOffset(offsets.getValue(item).value.roundToInt(), 0) }
                .zIndex(if (item in moving) 2f else if (item in selected) 1f else 0f)
                .width(widths.getValue(item)).fillMaxHeight().testTag(tag(item))
                .ownButtonDrag(enabled && role != Role.Checkbox).combinedClickable(enabled = enabled, role = role, onClick = controlActivation(enabled) { onSelect(item) },
                    onLongClick = onLongClick?.let { callback -> { callback(item) } })
                .semantics { this.selected = item in selected }, contentAlignment = Alignment.Center) {
                LiquidGlassSurface(Modifier.fillMaxWidth().height(listPillHeight()).glassAnchor(anchor),
                    tint = androidx.compose.ui.graphics.Color.Black.copy(alpha = selection)) {
                    anchor.captureInk()
                    Text(label(item), Modifier.align(Alignment.Center), style = style, maxLines = 1)
                }
                overlay(item, anchor)
            }
        } }
        }
    }
}
