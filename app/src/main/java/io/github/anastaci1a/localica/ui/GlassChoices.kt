package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One moving lens, with the same targets for tapping, dragging and accessibility. */
@Composable
internal fun GlassChoices(labels: List<String>, selected: Int, modifier: Modifier = Modifier, horizontal: Boolean = false, icons: List<Int>? = null, role: Role = Role.RadioButton, onSelect: (Int) -> Unit) {
    if (labels.isEmpty()) return
    val position = remember { Animatable(selected.toFloat()) }
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    var dragging by remember { mutableStateOf(false) }
    var raw by remember { mutableFloatStateOf(selected.toFloat()) }
    var pressure by remember { mutableFloatStateOf(0f) }
    val change by rememberUpdatedState(onSelect)
    LaunchedEffect(selected, dragging) {
        if (!dragging) {
            if (reduced) position.snapTo(selected.toFloat())
            else position.animateTo(selected.toFloat(), spring(1f, 650f))
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 4.dp)
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f), RoundedCornerShape(24.dp))
        .padding(4.dp).selectableGroup()) {
        val width = if (horizontal) maxWidth / labels.size else maxWidth
        val height = (52 * (LocalDensity.current.fontScale.coerceAtLeast(1f))).dp
        val stride = with(LocalDensity.current) { (if (horizontal) width else height).toPx() }
        SelectionLens(Modifier.testTag("choice-lens").offset { androidx.compose.ui.unit.IntOffset(
            if (horizontal) (stride * position.value).roundToInt() else 0,
            if (horizontal) 0 else (stride * position.value).roundToInt()) }.width(width).height(height),
            position, vertical = !horizontal, pressure = { pressure }, wall = {
                when { position.value <= .001f -> -1f; position.value >= labels.lastIndex - .001f -> 1f; else -> 0f }
            })
        @Composable fun Item(index: Int, modifier: Modifier) {
            Box(modifier.height(height).selectable(index == selected, role = role,
                interactionSource = null, indication = null, onClick = { change(index) })
                .then(if (index == selected) Modifier.draggable(rememberDraggableState { delta ->
                    raw += delta / stride
                    val next = raw.coerceIn(0f, labels.lastIndex.toFloat())
                    pressure = glassEdgePressure(raw - next)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { position.snapTo(next) }
                }, if (horizontal) Orientation.Horizontal else Orientation.Vertical,
                    onDragStarted = { position.stop(); raw = position.value; dragging = true }, onDragStopped = {
                        val next = position.value.roundToInt().coerceIn(labels.indices)
                        pressure = 0f; dragging = false; change(next)
                    }) else Modifier), contentAlignment = if (horizontal) Alignment.Center else Alignment.CenterStart) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    icons?.getOrNull(index)?.let { Icon(painterResource(it), null, Modifier.size(24.dp)) }
                    Text(labels[index], style = if (role == Role.Tab) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        if (horizontal) Row { labels.indices.forEach { Item(it, Modifier.width(width)) } }
        else Column { labels.indices.forEach { Item(it, Modifier.fillMaxWidth()) } }
    }
}

@Composable
internal fun SelectionPane(modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalGlassAppearance provides LocalGlassAppearance.current.copy(blur = 0f, whiteTint = 0f, contrast = 0f)) {
        LiquidGlassSurface(modifier, cornerRadius = 20.dp, pressFeedback = false)
    }
}

/** Full-row choice treatment shared by sorting and settings dialogs. */
@Composable
internal fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (selected) SelectionPane(Modifier.matchParentSize())
        Text(label, Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, interactionSource = null,
            indication = null, onClick = onClick).heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun SortDirection(ascending: Boolean, onSelect: (Boolean) -> Unit) {
    GlassChoices(listOf("Ascending", "Descending"), if (ascending) 0 else 1, horizontal = true) { onSelect(it == 0) }
}
