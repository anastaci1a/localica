package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import io.github.anastaci1a.localica.navigation.TabLensStyle
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import io.github.anastaci1a.localica.navigation.MainTab
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun magneticTabPosition(raw: Float): Float {
    val center = raw.roundToInt().toFloat()
    val delta = raw - center
    return center + delta * (.12f + 1.76f * abs(delta))
}

@Composable
internal fun SlidingTabs(tabs: List<MainTab>, active: MainTab, page: Float, source: FrostedBackdrop? = LocalFrostedBackdrop.current, pageDragging: Boolean = false, shadowOpacity: () -> Float = { 1f }, onSelect: (MainTab) -> Unit) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val lens = remember { Animatable(page) }
    var dragging by remember { mutableStateOf(false) }
    var raw by remember { mutableFloatStateOf(page) }
    var pressure by remember { mutableFloatStateOf(0f) }
    var chosen by remember { mutableIntStateOf(tabs.indexOf(active).coerceAtLeast(0)) }
    val selected by rememberUpdatedState(onSelect)
    val view = LocalView.current
    val appearance = LocalGlassAppearance.current
    var requested by remember { mutableStateOf<Int?>(null) }
    fun focus(index: Int, notify: Boolean = true) {
        if (index != chosen) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        chosen = index
        if (notify) selected(tabs[index])
    }
    fun tap(index: Int) {
        requested = index
        focus(index)
        scope.launch {
            if (reduced) lens.snapTo(index.toFloat())
            else lens.animateTo(index.toFloat(), spring(1f, 650f))
        }
    }
    LaunchedEffect(page, dragging, pageDragging) {
        if (pageDragging) requested = null
        if (!dragging && requested == null) {
            lens.snapTo(magneticTabPosition(page.coerceIn(0f, tabs.lastIndex.toFloat())))
            val candidate = lens.value.roundToInt().coerceIn(tabs.indices)
            if (candidate != chosen && abs(lens.value - chosen) >= .54f) focus(candidate, false)
        }
        if (!dragging && requested != null && abs(page - requested!!) < .001f) requested = null
    }
    val composite = rememberFrostedBackdrop(appearance, dark = source?.dark == true)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fits = maxWidth >= 68.dp * tabs.size
        val width = if (fits) maxWidth / tabs.size else 68.dp
        val pixels = with(density) { width.toPx() }
        val scroll = rememberScrollState()
        val viewport = with(density) { maxWidth.toPx() }
        LiquidGlassSurface(Modifier.fillMaxSize().bottomGlassShadow(opacity = shadowOpacity), source, pressFeedback = false, shadow = false, inkOverride = Color.Black) {}
        Box(Modifier.fillMaxSize().clip(CircleShape)) {
        Box(Modifier.fillMaxHeight().nestedScroll(HorizontalGestureOwner).horizontalScroll(scroll, enabled = !fits && !dragging)
            .testTag("tab-scroll").pointerInput(tabs, pixels, viewport) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val left = pixels * lens.value
                    // Coordinates are in the scrolled content. Empty track remains a
                    // normal scroll gesture, even when it crosses the selector later.
                    if (down.position.x < left || down.position.x > left + pixels) return@awaitEachGesture
                    var started = false
                    fun move(delta: Float) {
                        raw += delta / pixels
                        val low = scroll.value / pixels
                        val high = ((scroll.value + viewport) / pixels - 1f).coerceAtLeast(low).coerceAtMost(tabs.lastIndex.toFloat())
                        val bounded = raw.coerceIn(low, high)
                        pressure = glassEdgePressure(raw - bounded)
                        val position = magneticTabPosition(bounded).coerceIn(low, high)
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { lens.snapTo(position) }
                        val candidate = position.roundToInt().coerceIn(tabs.indices)
                        if (candidate != chosen && abs(position - chosen) > .52f) focus(candidate)
                    }
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume(); started = true; requested = null; raw = lens.value; dragging = true
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { lens.stop() }
                        move(over)
                    }
                    try {
                        if (drag != null) horizontalDrag(drag.id) { change -> move(change.positionChange().x); change.consume() }
                    } finally {
                        if (started) {
                            pressure = 0f; requested = chosen; dragging = false
                            scope.launch { if (reduced) lens.snapTo(chosen.toFloat()) else lens.animateTo(chosen.toFloat(), spring(1f, 650f)) }
                        }
                    }
                }
            }) {
            @Composable fun Labels(color: Color, interactive: Boolean) {
                Row(Modifier.width(width * tabs.size).fillMaxHeight().then(if (interactive) Modifier else
                    Modifier.clearAndSetSemantics {}.drawWithContent {
                        val left = pixels * lens.value + 4.dp.toPx()
                        val path = Path().apply { addRoundRect(RoundRect(left, 4.dp.toPx(),
                            left + pixels - 8.dp.toPx(), size.height - 4.dp.toPx(), CornerRadius(size.height / 2))) }
                        clipPath(path) { this@drawWithContent.drawContent() }
                    })) {
                    tabs.forEach { tab ->
                        Column(Modifier.width(width).padding(4.dp).fillMaxHeight()
                            .then(if (interactive) Modifier.selectable(active == tab,
                                interactionSource = null, indication = null, role = Role.Tab, onClick = { tap(tabs.indexOf(tab)) }) else Modifier),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(painterResource(tab.icon()), if (interactive) tab.label else null, Modifier.size(29.dp), tint = color)
                            Text(tab.label, color = color, maxLines = 1, style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium), overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            @Composable fun Lens() {
                val modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(
                    (pixels * lens.value + with(density) { 4.dp.toPx() }).roundToInt(), with(density) { 4.dp.roundToPx() }) }
                    .width(width - 8.dp).height(NavigationPillHeight - 8.dp)
                if (appearance.tabStyle == TabLensStyle.Classic) {
                    Box(modifier.background(Color.Black.copy(alpha = .10f), androidx.compose.foundation.shape.CircleShape))
                } else CompositionLocalProvider(LocalGlassAppearance provides appearance.copy(
                    blur = if (appearance.tabStyle == TabLensStyle.Overlay) 0f else appearance.tabBlur,
                    whiteTint = if (appearance.tabStyle == TabLensStyle.Overlay) 0f else appearance.tabWhiteTint,
                    contrast = if (appearance.tabStyle == TabLensStyle.Overlay) 0f else appearance.contrast)) {
                    SelectionLens(modifier, lens,
                        if (appearance.tabStyle == TabLensStyle.Overlay) composite else source, preserveAppearance = true, pressure = { pressure })
                }
            }
            Box(Modifier.width(width * tabs.size).fillMaxHeight().recordFrostedBackdrop(composite)) {

                if (appearance.tabStyle != TabLensStyle.Overlay) Lens()
                Labels(MaterialTheme.colorScheme.onSurfaceVariant, true)
                Labels(NavigationAccent, false)
            }
            if (appearance.tabStyle == TabLensStyle.Overlay) Lens()
        }
        }
        LaunchedEffect(active, width, reduced) {
            val index = tabs.indexOf(active)
            if (!fits && index >= 0 && !dragging) {
                val target = (pixels * (index + .5f) - viewport / 2).roundToInt().coerceIn(0, scroll.maxValue)
                if (reduced) scroll.scrollTo(target) else scroll.animateScrollTo(target)
            }
        }
    }
}
