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
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
    val speed = LocalMotionSpeed.current
    val rtl = horizontal && androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val direction = if (rtl) -1f else 1f
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var raw by remember { mutableFloatStateOf(selected.toFloat()) }
    var pressure by remember { mutableFloatStateOf(0f) }
    var settleRequest by remember { mutableIntStateOf(0) }
    val change by rememberUpdatedState(onSelect)
    LaunchedEffect(selected, dragging, reduced, speed, settleRequest) {
        if (!dragging) {
            if (reduced) position.snapTo(selected.toFloat())
            else position.animateTo(selected.toFloat(), spring(1f, 650f * speed * speed))
        }
    }
    val density = LocalDensity.current
    var logicalWall by remember { mutableFloatStateOf(1f) }
    val shell = rememberGlassBody({ 0f }, pressure = { pressure }, contact = { if (dragging) 1f else 0f })
    val height = glassChoiceCellHeight()
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = ChoiceOuterVerticalInset)) {
        val baseWidth = with(density) { maxWidth.toPx() }
        val rowHeight = with(density) { height.toPx() }
        val inset = with(density) { ChoiceInnerInset.toPx() }
        val baseHeight = (if (horizontal) rowHeight else rowHeight * labels.size) + 2f * inset
        val stride = if (horizontal) (baseWidth - 2f * inset) / labels.size else rowHeight
        val geometry = selectorTrackGeometry(if (horizontal) baseWidth else baseHeight,
            if (horizontal) baseHeight else baseWidth,
            (-shell.value.strain).coerceIn(0f, .16f) * .25f, logicalWall,
            !horizontal, rtl, reduced, overflow = false)
        val insets = SelectorTrackInsets(inset, 0f, inset)
        val trackBounds = if (horizontal) androidx.compose.ui.geometry.Rect(geometry.travelStart, geometry.crossStart,
            geometry.travelEnd, geometry.crossStart + geometry.crossExtent)
            else androidx.compose.ui.geometry.Rect(geometry.crossStart, geometry.travelStart,
                geometry.crossStart + geometry.crossExtent, geometry.travelEnd)
        val radius = with(density) { 24.dp.toPx() }.coerceAtMost(minOf(trackBounds.width, trackBounds.height) / 2f)
        Box(Modifier.fillMaxWidth().height(with(density) { baseHeight.toDp() }).selectableGroup()
            .testTag("choice-track-host").selectorTrackDrag(horizontal,
                eligible = { point -> geometry.slot(selected.coerceIn(labels.indices), labels.size, insets, !horizontal, rtl).cell.contains(point) },
                contact = { pressed = it },
                onStart = { dragging = true; raw = position.value; scope.launch(start = CoroutineStart.UNDISPATCHED) { position.stop() } },
                onDelta = { delta ->
                    raw += direction * delta / stride
                    val next = raw.coerceIn(0f, labels.lastIndex.toFloat())
                    val excess = raw - next
                    if (excess != 0f) logicalWall = if (excess < 0f) -1f else 1f
                    pressure = glassEdgePressure(excess)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { position.snapTo(magneticChoicePosition(next)) }
                }, onEnd = { cancelled ->
                    val next = position.value.roundToInt().coerceIn(labels.indices)
                    pressure = 0f; dragging = false; settleRequest++
                    if (!cancelled) change(next)
                }), contentAlignment = AbsoluteAlignment.TopLeft) {
            Box(Modifier.selectorTrackPlacement(trackBounds).testTag("choice-track-shell")
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .10f),
                    RoundedCornerShape(with(density) { radius.toDp() })))
            SelectionLens(Modifier.testTag("choice-lens"), position, vertical = !horizontal,
                pressure = { pressure }, contact = { if (pressed || dragging) 1f else 0f },
                placement = { shape -> geometry.lens(position.value, labels.size, insets, !horizontal, rtl, shape, radius) })
            labels.indices.forEach { index ->
                val cell = geometry.slot(index, labels.size, insets, !horizontal, rtl).cell
                val contentBounds = if (horizontal) androidx.compose.ui.geometry.Rect(cell.left, cell.top + inset, cell.right, cell.bottom - inset)
                    else androidx.compose.ui.geometry.Rect(cell.left + inset, cell.top, cell.right - inset, cell.bottom)
                val originalWidth = if (horizontal) stride else baseWidth - 2f * inset
                Box(Modifier.selectorTrackPlacement(contentBounds).testTag("choice-option-$index")
                    .selectable(index == selected, role = role, interactionSource = null, indication = null, onClick = { change(index) })
                    .then(if (index == selected) Modifier else Modifier.ownButtonDrag()),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.requiredSize(with(density) { originalWidth.toDp() }, height)
                        .graphicsLayer { scaleX = contentBounds.width / originalWidth; scaleY = contentBounds.height / rowHeight },
                        contentAlignment = if (horizontal) Alignment.Center else Alignment.CenterStart) {
                        Box(Modifier.testTag("choice-option-content-$index")) {
                            GlassChoiceFace(labels[index], icons?.getOrNull(index), role == Role.Tab)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SelectionPane(modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalGlassAppearance provides LocalGlassAppearance.current.copy(blur = 0f, whiteTint = 0f, contrast = 0f)) {
        LiquidGlassSurface(modifier, cornerRadius = 20.dp, pressFeedback = false)
    }
}

@Composable
internal fun SortDirection(ascending: Boolean, onSelect: (Boolean) -> Unit) {
    GlassChoices(listOf("Ascending", "Descending"), if (ascending) 0 else 1, horizontal = true) { onSelect(it == 0) }
}
