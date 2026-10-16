package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
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

internal fun magneticTabPosition(raw: Float): Float = magneticChoicePosition(raw)

@Composable
internal fun SlidingTabs(tabs: List<MainTab>, active: MainTab, pagePosition: () -> Float, source: FrostedBackdrop? = LocalFrostedBackdrop.current, pageDragging: Boolean = false, shadowOpacity: () -> Float = { 1f }, onReselect: (MainTab) -> Unit = {}, onSelect: (MainTab) -> Unit) {
    val page = pagePosition()
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val lens = remember { Animatable(page) }
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var raw by remember { mutableFloatStateOf(page) }
    var pressure by remember { mutableFloatStateOf(0f) }
    var logicalWall by remember { mutableFloatStateOf(1f) }
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val direction = if (rtl) -1f else 1f
    var chosen by remember { mutableIntStateOf(tabs.indexOf(active).coerceAtLeast(0)) }
    val selected by rememberUpdatedState(onSelect)
    val view = LocalView.current
    val appearance = LocalGlassAppearance.current
    var requested by remember { mutableStateOf<Int?>(null) }
    fun focus(index: Int, notify: Boolean = true) {
        if (index != chosen) view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        chosen = index
        if (notify) selected(tabs[index])
    }
    fun tap(index: Int) {
        if (tabs[index] == active && abs(page - index) < .001f && !pageDragging) {
            onReselect(tabs[index]); return
        }
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
    val refractForeground = appearance.tabStyle == TabLensStyle.Overlay && appearance.overlayForeground
    val legacyOverlay = android.os.Build.VERSION.SDK_INT < 33 && appearance.tabStyle == TabLensStyle.Overlay
    val recordForeground = refractForeground && !legacyOverlay
    val decorated = rememberFrostedBackdrop(appearance, enabled = recordForeground, dark = false)
    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val minimumWidth = maxOf(68.dp, with(density) {
        tabs.maxOf { textMeasurer.measure(androidx.compose.ui.text.AnnotatedString(it.label), labelStyle,
            maxLines = 1, softWrap = false).size.width }.toDp() + 32.dp
    })
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        // Four destinations fit the normal phone shelf. Preserve larger font targets
        // by allowing the strip to overflow rather than shrinking accessible text.
        val fits = maxWidth >= minimumWidth * tabs.size ||
            (tabs.size <= 4 && density.fontScale <= 1.15f && maxWidth / tabs.size >= 48.dp)
        val width = if (fits) maxWidth / tabs.size else minimumWidth
        val pixels = with(density) { width.toPx() }
        val scroll = rememberScrollState()
        val viewport = with(density) { maxWidth.toPx() }
        val shell = rememberGlassBody({ 0f }, contact = { if (dragging) 1f else 0f }, pressure = { pressure })
        val baseHeight = with(density) { maxHeight.toPx() }
        val stretch = (-shell.value.strain).coerceIn(0f, .16f) * .25f
        val geometry = selectorTrackGeometry(pixels * tabs.size, baseHeight, stretch, logicalWall,
            false, rtl, reduced, overflow = !fits)
        val inset = with(density) { 4.dp.toPx() }
        val insets = SelectorTrackInsets(0f, inset, inset)
        val atlasGeometry = selectorTrackGeometry(pixels * tabs.size, baseHeight, 0f, 1f,
            false, rtl, reduced, overflow = !fits)
        val inkAtlas = if (legacyOverlay && refractForeground)
            rememberFrostedBackdrop(listOf(tabs, width, labelStyle, density.density, density.fontScale),
                dark = false, glassBlur = 0f, whiteTint = 0f) else null
        val track = if (fits) androidx.compose.ui.geometry.Rect(geometry.travelStart, geometry.crossStart,
            geometry.travelEnd, geometry.crossStart + geometry.crossExtent)
            else androidx.compose.ui.geometry.Rect(0f, 0f, viewport, baseHeight)
        val radius = track.height / 2f
        val selectorBody = rememberGlassBody({ lens.value }, { pressure }, { if (pressed || dragging) 1f else 0f })
        val selectorFrame = geometry.lens(lens.value, tabs.size, insets, false, rtl,
            if (appearance.tabStyle == TabLensStyle.Classic) GlassShape() else selectorBody.value, radius)
        // Fixed padded recording dimensions avoid size-driven legacy recapture on
        // each deformation frame. Dynamic legacy foreground freshness is gated separately.
        val recording = androidx.compose.ui.geometry.Rect(-viewport * .04f, 0f, viewport * 1.04f, baseHeight)
        Box(Modifier.selectorTrackPlacement(recording).recordFrostedBackdrop(composite), contentAlignment = AbsoluteAlignment.TopLeft) {
            LiquidGlassSurface(Modifier.selectorTrackPlacement(track.translate(-recording.topLeft))
                .testTag("tab-track-shell").bottomGlassShadow(opacity = shadowOpacity), source,
                pressFeedback = false, shadow = false, cornerRadius = with(density) { radius.toDp() },
                body = { shell.value }, inkOverride = Color.Black, depthScale = BottomPillDepthScale)
        }
        Box(Modifier.fillMaxSize().testTag("tab-track-host").drawWithContent {
            val path = Path().apply { addRoundRect(RoundRect(track, CornerRadius(radius))) }
            clipPath(path) { this@drawWithContent.drawContent() }
        }, contentAlignment = AbsoluteAlignment.TopLeft) {
        // horizontalScroll installs a clipping viewport even when disabled. A fitting
        // track must be free to draw its stretched end outside the fixed gesture host.
        Box(Modifier.fillMaxHeight().then(if (!fits) Modifier.nestedScroll(HorizontalGestureOwner)
            .horizontalScroll(scroll, enabled = !dragging) else Modifier)
            .testTag("tab-scroll").observeGlassContact { pressed = it }.pointerInput(tabs, pixels, viewport, fits, rtl) {
                if (!fits) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var started = false
                    fun move(delta: Float) {
                        raw += direction * delta / pixels
                        val low = scroll.value / pixels
                        val high = ((scroll.value + viewport) / pixels - 1f).coerceAtLeast(low).coerceAtMost(tabs.lastIndex.toFloat())
                        val bounded = raw.coerceIn(low, high)
                        val excess = raw - bounded
                        if (excess != 0f) logicalWall = if (excess < 0f) -1f else 1f
                        pressure = glassEdgePressure(excess)
                        val position = magneticTabPosition(bounded).coerceIn(low, high)
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { lens.snapTo(position) }
                        val candidate = position.roundToInt().coerceIn(tabs.indices)
                        if (candidate != chosen && abs(position - chosen) > .52f) focus(candidate)
                    }
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        change.consume(); started = true; requested = null
                        raw = lens.value; dragging = true
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
            }, contentAlignment = AbsoluteAlignment.TopLeft) {
            @Composable fun Labels(color: Color, interactive: Boolean, atlas: Boolean = false) {
                Box(Modifier.width(width * tabs.size).fillMaxHeight()
                    .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})
                    .then(if (atlas) Modifier else Modifier.drawWithContent {
                        val path = Path().apply { addRoundRect(RoundRect(selectorFrame.bounds, CornerRadius(selectorFrame.corner))) }
                        clipPath(path, if (interactive) ClipOp.Difference else ClipOp.Intersect) { this@drawWithContent.drawContent() }
                    }), contentAlignment = AbsoluteAlignment.TopLeft) {
                    tabs.forEachIndexed { index, tab ->
                        val cell = (if (atlas) atlasGeometry else geometry).slot(index, tabs.size, insets, false, rtl).cell
                        Box(Modifier.selectorTrackPlacement(cell)
                            .then(if (interactive) Modifier.testTag("tab-option-$index").selectable(active == tab,
                                interactionSource = null, indication = null, role = Role.Tab, onClick = { tap(index) }) else Modifier),
                            contentAlignment = Alignment.Center) {
                            Column(Modifier.requiredSize(width, with(density) { baseHeight.toDp() })
                                .graphicsLayer { scaleX = cell.width / pixels; scaleY = cell.height / baseHeight }
                                .padding(4.dp).then(if (interactive) Modifier.testTag("tab-option-content-$index") else Modifier),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(painterResource(tab.icon()), if (interactive) tab.label else null, Modifier.size(29.dp), tint = color)
                                Text(tab.label, color = color, maxLines = 1, style = labelStyle, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            @Composable fun Lens() {
                val modifier = Modifier.testTag("tab-selector")
                if (appearance.tabStyle == TabLensStyle.Classic) {
                    Box(Modifier.selectorTrackPlacement(selectorFrame.bounds).then(modifier).background(Color.Black.copy(alpha = appearance.classicOpacity),
                        androidx.compose.foundation.shape.RoundedCornerShape(with(density) { selectorFrame.corner.toDp() })))
                } else if (legacyOverlay) {
                    LegacySelectorLens(modifier, source, inkAtlas, appearance,
                        selectorFrame, LensGeometry(track, radius), shell.value, selectorBody.value,
                        tabs.indices.map { index -> SelectorInkSlot(
                            atlasGeometry.slot(index, tabs.size, insets, false, rtl).cell,
                            geometry.slot(index, tabs.size, insets, false, rtl).cell) },
                        MaterialTheme.colorScheme.onSurfaceVariant, NavigationAccent,
                        shellOrigin = { composite.origin - recording.topLeft })
                } else CompositionLocalProvider(LocalGlassAppearance provides appearance.copy(
                    blur = if (appearance.tabStyle == TabLensStyle.Overlay) 0f else appearance.tabBlur,
                    whiteTint = if (appearance.tabStyle == TabLensStyle.Overlay) 0f else appearance.tabWhiteTint,
                    contrast = if (appearance.tabStyle == TabLensStyle.Overlay) 0f else appearance.contrast)) {
                    SelectionLens(modifier, lens,
                        if (appearance.tabStyle == TabLensStyle.Overlay) { if (refractForeground) decorated else composite } else source,
                        preserveAppearance = true, depthScale = BottomPillDepthScale,
                        tint = if (appearance.tabStyle == TabLensStyle.Overlay) Color.Black.copy(alpha = appearance.overlayDarkening) else Color.Transparent,
                        pressure = { pressure }, contact = { if (pressed || dragging) 1f else 0f },
                        placement = { selectorFrame }, deformation = selectorBody)

                }
            }
            Box(Modifier.width(width * tabs.size).fillMaxHeight(), contentAlignment = AbsoluteAlignment.TopLeft) {
                if (inkAtlas != null) {
                    Box(Modifier.fillMaxSize().recordFrostedBackdrop(inkAtlas, displayCutout = {
                        Path().apply { addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }
                    })) { Labels(Color.White, false, atlas = true) }
                }
                if (!refractForeground) Lens()
                val foregroundRecording = androidx.compose.ui.geometry.Rect(
                    -geometry.travel * .04f, 0f, geometry.travel * 1.04f, baseHeight)
                val foregroundContent = androidx.compose.ui.geometry.Rect(
                    -foregroundRecording.left, 0f, geometry.travel - foregroundRecording.left, baseHeight)
                Box(if (recordForeground) Modifier.selectorTrackPlacement(foregroundRecording)
                    .recordFrostedBackdrop(decorated).drawWithContent {
                        if (composite.recorded && !composite.layer.isReleased) {
                            val delta = composite.origin - decorated.origin
                            translate(delta.x, delta.y) { drawLayer(composite.layer) }
                        }
                        drawContent()
                    } else Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                    // Record padded visual overflow without changing the gesture or
                    // scrolling host. Both sample origin and label coordinates move once.
                    Box(if (recordForeground) Modifier.selectorTrackPlacement(foregroundContent)
                        else Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                        Labels(MaterialTheme.colorScheme.onSurfaceVariant, true)
                        Labels(NavigationAccent, false)
                    }
                }
                if (refractForeground) Lens()
            }
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
