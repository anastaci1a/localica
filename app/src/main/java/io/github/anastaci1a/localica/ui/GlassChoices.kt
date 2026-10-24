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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One moving lens, with the same targets for tapping, dragging and accessibility. */
@Composable
internal fun GlassChoices(labels: List<String>, selected: Int, modifier: Modifier = Modifier, horizontal: Boolean = false, icons: List<Int>? = null, role: Role = Role.RadioButton, cellHeight: Dp? = null,
    labelStyle: TextStyle? = null, outerVerticalInset: Dp = ChoiceOuterVerticalInset, onSelect: (Int) -> Unit) {
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
    LaunchedEffect(selected, pressed, dragging, reduced, speed, settleRequest) {
        if (!pressed && !dragging) {
            if (reduced) position.snapTo(selected.toFloat())
            else position.animateTo(selected.toFloat(), spring(1f, 650f * speed * speed))
        }
    }
    val density = LocalDensity.current
    var logicalWall by remember { mutableFloatStateOf(1f) }
    val shell = rememberGlassBody({ 0f }, pressure = { pressure }, contact = { if (dragging) 1f else 0f })
    val height = cellHeight ?: glassChoiceCellHeight()
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = outerVerticalInset)) {
        val baseWidth = with(density) { maxWidth.toPx() }
        // Match the inert measurement face: round each cell and inset before summing.
        val rowHeight = with(density) { height.roundToPx().toFloat() }
        val inset = with(density) { ChoiceInnerInset.roundToPx().toFloat() }
        val baseHeight = (if (horizontal) rowHeight else rowHeight * labels.size) + 2f * inset
        val stride = if (horizontal) (baseWidth - 2f * inset) / labels.size else rowHeight
        val maximumExtension = with(density) { SelectorMaximumExtension.toPx() }
        val rawGeometry = selectorTrackGeometry(if (horizontal) baseWidth else baseHeight,
            if (horizontal) baseHeight else baseWidth,
            (-shell.value.strain).coerceIn(0f, .16f) * .25f, logicalWall,
            !horizontal, rtl, reduced, overflow = false,
            maximumExtension = maximumExtension,
            heldCrossInset = inset.takeIf { pressed || dragging },
            heldContact = if (labels.size == 1) 1f else kotlin.math.abs(glassWallAffinity(position.value, labels.lastIndex.toFloat())))
        val insets = SelectorTrackInsets(inset, 0f, inset)
        val lensBody = rememberGlassBody({ position.value }, { pressure }, { if (pressed || dragging) 1f else 0f })
        val rawRadius = (with(density) { 24.dp.toPx() } -
            (rawGeometry.heldCrossInset?.let { it * rawGeometry.pullFraction * rawGeometry.heldContact } ?: 0f)).coerceAtMost(minOf(rawGeometry.crossExtent, rawGeometry.travelExtent) / 2f)
        val rawLens = rawGeometry.lens(position.value, labels.size, insets, !horizontal, rtl, lensBody.value, rawRadius)
        val restGeometry = rawGeometry.copy(stretch = 0f, heldCrossInset = null)
        val restRadius = with(density) { 24.dp.toPx() }.coerceAtMost(minOf(restGeometry.cross, restGeometry.travel) / 2f)
        val restFrame = restGeometry.restFrame(labels.size, insets, !horizontal, restRadius)
        val frame = rememberSelectorCrossFrame(rawLens.returnFrame(rawGeometry, !horizontal), restFrame,
            pressed || dragging, shell.value == GlassShape() && lensBody.value == GlassShape(),
            SelectorReturnLimits(maximumExtension, inset, rawGeometry.wall))
        val geometry = rawGeometry.withReturnFrame(frame)
        val lensFrame = geometry.lens(position.value, labels.size, insets, !horizontal, rtl,
            GlassShape(), frame.outerCorner, presentation = frame)
        val trackBounds = if (horizontal) androidx.compose.ui.geometry.Rect(geometry.travelStart, geometry.crossStart,
            geometry.travelEnd, geometry.crossStart + geometry.crossExtent)
            else androidx.compose.ui.geometry.Rect(geometry.crossStart, geometry.travelStart,
                geometry.crossStart + geometry.crossExtent, geometry.travelEnd)
        val radius = frame.outerCorner
        Box(Modifier.fillMaxWidth().height(with(density) { baseHeight.toDp() }).selectableGroup()
            .testTag("choice-track-host").selectorTrackDrag(horizontal,
                eligible = { point -> trackBounds.contains(point) },
                contact = { pressed = it },
                onPreview = { point ->
                    // Preserve the grab offset on the moving lens; other options pick up
                    // at the finger using the undeformed host map, never animated geometry.
                    raw = if (lensFrame.bounds.contains(point)) position.value else restGeometry
                        .logicalPositionAt(point, labels.size, inset, !horizontal, rtl)
                        .coerceIn(0f, labels.lastIndex.toFloat())
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        if (reduced) position.snapTo(magneticChoicePosition(raw))
                        else position.animateTo(magneticChoicePosition(raw), spring(1f, 650f * speed * speed))
                    }
                },
                onStart = { dragging = true },
                onDelta = { delta ->
                    raw += direction * delta / stride
                    val next = raw.coerceIn(0f, labels.lastIndex.toFloat())
                    val excess = raw - next
                    if (excess != 0f) logicalWall = if (excess < 0f) -1f else 1f
                    pressure = glassEdgePressure(excess)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { position.snapTo(magneticChoicePosition(next)) }
                }, option = { magneticChoicePosition(raw.coerceIn(0f, labels.lastIndex.toFloat())).roundToInt() },
                onEnd = { owned, cancelled ->
                    val next = raw.roundToInt().coerceIn(labels.indices)
                    pressure = 0f; dragging = false; settleRequest++
                    if (owned && !cancelled) change(next)
                }, gestureKey = listOf(labels.size, stride, rtl)), contentAlignment = AbsoluteAlignment.TopLeft) {
            Box(Modifier.selectorTrackPlacement(trackBounds).testTag("choice-track-shell")
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .10f),
                    RoundedCornerShape(with(density) { radius.toDp() })))
            SelectionLens(Modifier.testTag("choice-lens"), position, vertical = !horizontal,
                pressure = { pressure }, contact = { if (pressed || dragging) 1f else 0f },
                placement = { lensFrame }, deformation = lensBody)
            labels.indices.forEach { index ->
                val cell = geometry.slot(index, labels.size, insets, !horizontal, rtl).cell
                val contentBounds = if (horizontal) androidx.compose.ui.geometry.Rect(cell.left, cell.top + inset, cell.right, cell.bottom - inset)
                    else androidx.compose.ui.geometry.Rect(cell.left + inset, cell.top, cell.right - inset, cell.bottom)
                val originalWidth = if (horizontal) stride else baseWidth - 2f * inset
                Box(Modifier.selectorTrackPlacement(contentBounds).testTag("choice-option-$index")
                    .selectable(index == selected, role = role, interactionSource = null, indication = null,
                        onClick = controlActivation { change(index) }),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.requiredSize(with(density) { originalWidth.toDp() }, height)
                        .graphicsLayer { scaleX = contentBounds.width / originalWidth; scaleY = contentBounds.height / rowHeight },
                        contentAlignment = if (horizontal) Alignment.Center else Alignment.CenterStart) {
                        Box(Modifier.testTag("choice-option-content-$index")) {
                            GlassChoiceFace(labels[index], icons?.getOrNull(index), role == Role.Tab, labelStyle)
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
