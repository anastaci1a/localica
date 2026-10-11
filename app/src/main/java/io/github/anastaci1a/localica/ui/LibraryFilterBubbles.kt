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

/** Keep configured order independent of the transient leading selection. */
@Composable
internal fun LibraryFilterBubbles(filters: List<LibraryFilter>, selected: MediaKind?,
    reducedMotion: Boolean, onSelect: (MediaKind?) -> Unit, onConfigure: () -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val widths = filters.associateWith { with(density) { measurer.measure(it.label, style).size.width.toDp() + 32.dp } }
    val gap = with(density) { 8.dp.toPx() }
    val widthPx = widths.mapValues { with(density) { it.value.toPx() } }
    fun positions(order: List<LibraryFilter>): Map<LibraryFilter, Float> {
        var x = 0f
        return order.associateWith { val start = x; x += widthPx.getValue(it) + gap; start }
    }
    val active = filters.firstOrNull { it.kind == selected }
    val order = listOfNotNull(active) + filters.filterNot { it == active }
    val targets = positions(order)
    val offsets = remember { mutableMapOf<LibraryFilter, Animatable<Float, AnimationVector1D>>() }
    filters.forEach { offsets.getOrPut(it) { Animatable(targets.getValue(it)) } }
    var previous by remember { mutableStateOf(active) }
    var moving by remember { mutableStateOf(emptySet<LibraryFilter>()) }
    var menu by remember { mutableStateOf<LibraryFilter?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(active, filters, widthPx, reducedMotion) {
        val travelers = setOfNotNull(active, previous).intersect(filters.toSet())
        previous = active
        moving = travelers
        val starts = filters.associateWith { offsets.getValue(it).value }
        val startScroll = scroll.value
        if (reducedMotion) {
            filters.forEach { offsets.getValue(it).snapTo(targets.getValue(it)) }
            scroll.scrollTo(0)
        } else coroutineScope {
            filters.forEach { filter -> launch {
                // Neighbors yield when a traveling bubble reaches them, like a dragged row.
                // Both the new selection and the former selection travel continuously.
                val crossing = if (filter in travelers) 0f else travelers.mapNotNull { traveler ->
                    val from = starts.getValue(traveler)
                    val to = targets.getValue(traveler)
                    if (abs(to - from) < 1f) null else {
                        val center = starts.getValue(filter) + widthPx.getValue(filter) / 2
                        ((center - (from + widthPx.getValue(traveler) / 2)) / (to - from))
                            .takeIf { it in 0f..1f }
                    }
                }.minOrNull() ?: 0f
                delay((crossing * 120).toLong())
                offsets.getValue(filter).animateTo(targets.getValue(filter),
                    if (filter in travelers) spring(dampingRatio = .78f, stiffness = 440f)
                    else spring(dampingRatio = .58f, stiffness = 700f))
            } }
            active?.let { filter -> launch {
                snapshotFlow { offsets.getValue(filter).value }.collect { x ->
                    // Follow from the current viewport without jumping to center on tap.
                    scroll.scrollTo((startScroll + x - starts.getValue(filter)).roundToInt().coerceAtLeast(0))
                }
            } }?.let { follower ->
                // Children have bounded animations; only the frame follower is unbounded.
                delay(900)
                follower.cancel()
            }
        }
        moving = emptySet()
    }
    val total = widths.values.fold(0.dp) { sum, width -> sum + width } + 8.dp * (filters.size - 1).coerceAtLeast(0)
    Box(Modifier.testTag("library-filters").padding(vertical = ListPillVerticalInset)
        .nestedScroll(HorizontalGestureOwner).horizontalScroll(scroll).padding(horizontal = 12.dp)
        .width(total).height(listPillTargetHeight())) {
        filters.forEach { filter -> key(filter) {
            val menuAnchor = rememberGlassAnchor()
            val source = LocalFrostedBackdrop.current
            SideEffect { menuAnchor.source = source; menuAnchor.face = { Text(filter.label, style = style, maxLines = 1) } }
            Box(Modifier.offset { IntOffset(offsets.getValue(filter).value.roundToInt(), 0) }
                .zIndex(if (filter == active) 2f else if (filter in moving) 1f else 0f)
                .width(widths.getValue(filter)).fillMaxHeight()
                .testTag("filter-${filter.name}")
                .combinedClickable(role = Role.Tab,
                    onClick = { onSelect(filter.kind.takeUnless { it == selected }) },
                    onLongClickLabel = "Configure library filters", onLongClick = { menu = filter })
                .semantics { this.selected = filter.kind == selected }, contentAlignment = Alignment.Center) {
                LiquidGlassSurface(Modifier.fillMaxWidth().height(listPillHeight()).glassAnchor(menuAnchor),
                    tint = if (filter.kind == selected) androidx.compose.ui.graphics.Color.Black.copy(alpha = .07f)
                        else androidx.compose.ui.graphics.Color.Transparent) {
                    Text(filter.label, Modifier.align(Alignment.Center), style = style, maxLines = 1)
                }
                GlassMenu(menu == filter, menuAnchor, { menu = null }) {
                    ActionMenuItem("Edit library filters and order", onClick = { menu = null; onConfigure() })
                }
            }
        } }
    }
}
