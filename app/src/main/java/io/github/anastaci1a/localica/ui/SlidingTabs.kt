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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import io.github.anastaci1a.localica.navigation.TabLensStyle
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
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

/** True only while the last finger's release dispatches, so a click it produces is told
 * apart from keyboard or accessible activation. Observes without consuming. */
private class TabFingerRelease { var active = false }

private fun Modifier.markTabFingerRelease(release: TabFingerRelease): Modifier = pointerInput(release) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        while (true) {
            if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) continue
            release.active = true
            // Main (where the selectable clicks) runs between these two passes of one event.
            try { awaitPointerEvent(PointerEventPass.Final) } finally { release.active = false }
            break
        }
    }
}

/** Logical lens travel. A fitting strip spans every tab whatever offset its unused
 * scroll state kept from an earlier overflow; an overflowing one, its visible cells. */
internal fun tabSlideBounds(fits: Boolean, scroll: Float, viewport: Float, cell: Float,
    lastIndex: Int): ClosedFloatingPointRange<Float> {
    if (fits) return 0f..lastIndex.toFloat()
    val low = (scroll / cell).coerceAtMost(lastIndex.toFloat())
    return low..((scroll + viewport) / cell - 1f).coerceIn(low, lastIndex.toFloat())
}

@Composable
internal fun SlidingTabs(tabs: List<MainTab>, active: MainTab, pagePosition: () -> Float, source: FrostedBackdrop? = LocalFrostedBackdrop.current, pageDragging: Boolean = false, shadowOpacity: () -> Float = { 1f }, onReselect: (MainTab) -> Unit = {}, activity: SelectorTrackActivity? = null, noneAtlasInput: NoneAtlasInput? = null, onSelect: (MainTab) -> Unit) {
    val renderer = currentGlassRendererGeneration()
    val trackActivity = activity ?: rememberSelectorTrackActivity()
    DisposableEffect(trackActivity) { onDispose { trackActivity.clear() } }
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
    val haptics by rememberUpdatedState(rememberControlHaptics())
    val appearance = LocalGlassAppearance.current
    var requested by remember { mutableStateOf<Int?>(null) }
    fun focus(index: Int, notify: Boolean = true) {
        chosen = index
        if (notify) selected(tabs[index])
    }
    // One contact: its starting option, the option under the finger and whether it travelled.
    var origin by remember { mutableIntStateOf(chosen) }
    var pressIndex by remember { mutableIntStateOf(chosen) }
    var travelled by remember { mutableStateOf(false) }
    val fingerRelease = remember { TabFingerRelease() }
    // #664: every committing finger release, reselection included, drops exactly once.
    // Keyboard and accessible activation keep the single weak activation pulse.
    fun tap(index: Int, haptic: ControlHaptic? = ControlHaptic.Weak) {
        haptic?.let(haptics::emit)
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
    LaunchedEffect(page, dragging, pageDragging, pressed) {
        if (pageDragging) requested = null
        if (!pressed && !dragging && requested == null) {
            lens.snapTo(magneticTabPosition(page.coerceIn(0f, tabs.lastIndex.toFloat())))
            val candidate = lens.value.roundToInt().coerceIn(tabs.indices)
            if (candidate != chosen && abs(lens.value - chosen) >= .54f) focus(candidate, false)
        }
        if (!dragging && requested != null && abs(page - requested!!) < .001f) requested = null
    }
    val composite = rememberFrostedBackdrop(appearance, dark = source?.dark == true)
    val refractForeground = !flatGlass() && appearance.tabStyle == TabLensStyle.Overlay && appearance.overlayForeground
    val average = glassAverageProgress()
    val recordForeground = refractForeground
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
        // #728: this is the one fit/overflow decision; tabSlideBounds follows it.
        val fits = maxWidth >= minimumWidth * tabs.size ||
            (tabs.size <= 4 && density.fontScale <= 1.15f && maxWidth / tabs.size >= 48.dp)
        val width = if (fits) maxWidth / tabs.size else minimumWidth
        val pixels = with(density) { width.toPx() }
        val scroll = rememberScrollState()
        val viewport = with(density) { maxWidth.toPx() }
        val shell = rememberGlassBody({ 0f }, contact = { if (dragging) 1f else 0f }, pressure = { pressure })
        val baseHeight = with(density) { maxHeight.toPx() }
        val stretch = (-shell.value.strain).coerceIn(0f, .16f) * .25f
        val inset = with(density) { 4.dp.toPx() }
        val maximumExtension = with(density) { SelectorMaximumExtension.toPx() }
        val rawGeometry = selectorTrackGeometry(pixels * tabs.size, baseHeight, stretch, logicalWall,
            false, rtl, reduced, overflow = !fits, maximumExtension = maximumExtension,
            heldCrossInset = inset.takeIf { (pressed || dragging) && appearance.tabStyle != TabLensStyle.Classic },
            heldContact = if (tabs.size == 1) 1f else abs(glassWallAffinity(lens.value, tabs.lastIndex.toFloat())))
        val insets = SelectorTrackInsets(0f, inset, inset)
        val atlasGeometry = selectorTrackGeometry(pixels * tabs.size, baseHeight, 0f, 1f,
            false, rtl, reduced, overflow = !fits)
        val rawTrack = if (fits) androidx.compose.ui.geometry.Rect(rawGeometry.travelStart, rawGeometry.crossStart,
            rawGeometry.travelEnd, rawGeometry.crossStart + rawGeometry.crossExtent)
            else androidx.compose.ui.geometry.Rect(0f, 0f, viewport, baseHeight)
        val selectorBody = rememberGlassBody({ lens.value }, { pressure }, { if (pressed || dragging) 1f else 0f })
        val rawFrame = rawGeometry.lens(lens.value, tabs.size, insets, false, rtl,
            if (appearance.tabStyle == TabLensStyle.Classic) GlassShape() else selectorBody.value, rawTrack.height / 2f)
        val restGeometry = rawGeometry.copy(stretch = 0f, heldCrossInset = null)
        val restFrame = restGeometry.restFrame(tabs.size, insets, false, baseHeight / 2f)
        val coupled = fits && appearance.tabStyle != TabLensStyle.Classic
        val limits = SelectorReturnLimits(maximumExtension, inset, rawGeometry.wall)
        val frame = rememberSelectorCrossFrame(rawFrame.returnFrame(rawGeometry, false), restFrame,
            pressed || dragging, shell.value == GlassShape() && selectorBody.value == GlassShape(),
            limits, enabled = coupled)
        val moving = pressed || dragging || pageDragging || lens.isRunning ||
            shell.value != GlassShape() || selectorBody.value != GlassShape() || frame != restFrame
        SideEffect { trackActivity.update(moving) }
        val geometry = if (coupled) rawGeometry.withReturnFrame(frame) else rawGeometry
        val track = if (fits) androidx.compose.ui.geometry.Rect(geometry.travelStart, geometry.crossStart,
            geometry.travelEnd, geometry.crossStart + geometry.crossExtent) else rawTrack
        val radius = frame.outerCorner
        val selectorFrame = if (coupled) geometry.lens(lens.value, tabs.size, insets,
            false, rtl, GlassShape(), radius, presentation = frame) else rawFrame
        val selectorContour = remember(selectorFrame) { selectorFrame.contour() }
        // Fixed padded recording dimensions avoid size-driven captures during deformation.
        val recording = selectorTrackRecordingBounds(viewport, baseHeight, limits.crossClearance)
        Box(Modifier.selectorTrackPlacement(recording).recordFrostedBackdrop(composite), contentAlignment = AbsoluteAlignment.TopLeft) {
            LiquidGlassSurface(Modifier.selectorTrackPlacement(track.translate(-recording.topLeft))
                .testTag("tab-track-shell").bottomGlassShadow(opacity = shadowOpacity), source,
                pressFeedback = false, shadow = false, cornerRadius = with(density) { radius.toDp() },
                body = { shell.value }, inkOverride = Color.Black, depthScale = BottomPillDepthScale, noneAtlasInput = noneAtlasInput)
        }
        val contentOffset = selectorContentScrollOffset(scroll.value, scroll.maxValue, rtl)
        val viewportBounds = androidx.compose.ui.geometry.Rect(0f, 0f, viewport, baseHeight)
        fun move(delta: Float) {
            // Travel, not the finger-down commit, retires an unfinished tap request.
            if (delta != 0f && !travelled) { travelled = true; requested = null }
            raw += direction * delta / pixels
            val travel = tabSlideBounds(fits, scroll.value.toFloat(), viewport, pixels, tabs.lastIndex)
            val low = travel.start; val high = travel.endInclusive
            val bounded = raw.coerceIn(low, high)
            val excess = raw - bounded
            if (excess != 0f) logicalWall = if (excess < 0f) -1f else 1f
            pressure = glassEdgePressure(excess)
            val position = magneticTabPosition(bounded).coerceIn(low, high)
            // The finger-down commit keeps the preview spring already heading to this position.
            if (delta != 0f) scope.launch(start = CoroutineStart.UNDISPATCHED) { lens.snapTo(position) }
            val candidate = position.roundToInt().coerceIn(tabs.indices)
            if (candidate != chosen && abs(position - chosen) > .52f) focus(candidate)
        }
        // The fixed viewport owns lens gestures before its scrolling child. Its context
        // includes ancestors, not the strip itself: only non-lens overflow origins scroll it.
        Box(Modifier.fillMaxSize().testTag("tab-track-host")
            .markTabFingerRelease(fingerRelease).selectorTrackDrag(horizontal = true,
                eligible = { point -> if (fits) viewportBounds.contains(point) else
                    selectorVisibleLensContains(point, selectorFrame, contentOffset, viewportBounds) },
                contact = { pressed = it },
                onPreview = { point ->
                    val content = point.copy(x = point.x + if (fits) 0f else contentOffset)
                    val under = atlasGeometry.logicalPositionAt(content, tabs.size, 0f, vertical = false, rtl = rtl)
                        .coerceIn(0f, tabs.lastIndex.toFloat())
                    raw = if (selectorFrame.bounds.contains(content)) lens.value else under
                    origin = chosen; pressIndex = under.roundToInt().coerceIn(tabs.indices); travelled = false
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        if (!fits) scroll.stopScroll()
                        if (reduced) lens.snapTo(magneticTabPosition(raw))
                        else lens.animateTo(magneticTabPosition(raw), spring(1f, 650f))
                    }
                },
                onStart = { dragging = true },
                // #728: a lambda, not `::move`. A memoized reference to a local function keeps
                // its first composition's tabs and geometry after the tab count changes.
                onDelta = { move(it) }, option = { chosen },
                // #664: no scroll can claim a fitting bar or the lens (#366 keeps other
                // overflow origins ineligible), so finger-down commits and pulses at once.
                immediate = { true },
                // The shared tap path pulsed only on finger-down, so the tab bar owns this drop.
                onTap = { haptics.emit(ControlHaptic.Strong); if (chosen == origin) tap(pressIndex, haptic = null) },
                onEnd = { owned, cancelled ->
                    pressure = 0f; dragging = false
                    // A cancelled press that never travelled restores the option it replaced.
                    if (owned && cancelled && !travelled) { if (chosen != origin) focus(origin) }
                    else if (owned) requested = chosen
                    // Pending contact may interrupt an unfinished tap/page transition.
                    // Return to its existing destination without changing domain selection.
                    val target = requested?.toFloat() ?: magneticTabPosition(page.coerceIn(0f, tabs.lastIndex.toFloat()))
                    scope.launch { if (reduced) lens.snapTo(target) else lens.animateTo(target, spring(1f, 650f)) }
                }, gestureKey = listOf(tabs, pixels, viewport, fits, rtl))
            .drawWithContent {
                val path = Path().apply { addRoundRect(RoundRect(track, CornerRadius(radius))) }
                clipPath(path) { this@drawWithContent.drawContent() }
            }, contentAlignment = AbsoluteAlignment.TopLeft) {
        // horizontalScroll installs a clipping viewport even when disabled. A fitting
        // track must be free to draw its stretched end outside the fixed gesture host.
        Box(Modifier.fillMaxHeight().then(if (!fits) Modifier.nestedScroll(HorizontalGestureOwner)
            .controlHorizontalScroll(scroll, enabled = !dragging) else Modifier)
            .testTag("tab-scroll"), contentAlignment = AbsoluteAlignment.TopLeft) {
            @Composable fun Labels(color: Color, interactive: Boolean, atlas: Boolean = false) {
                Box(Modifier.width(width * tabs.size).fillMaxHeight()
                    .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})
                    .then(if (atlas) Modifier else Modifier.drawWithContent {
                        val path = Path().apply { addRoundRect(selectorContour.outline) }
                        clipPath(path, if (interactive) ClipOp.Difference else ClipOp.Intersect) { this@drawWithContent.drawContent() }
                    }), contentAlignment = AbsoluteAlignment.TopLeft) {
                    tabs.forEachIndexed { index, tab ->
                        if (interactive) DisposableEffect(trackActivity, index) {
                            onDispose { trackActivity.focus(index, false) }
                        }
                        val cell = (if (atlas) atlasGeometry else geometry).slot(index, tabs.size, insets, false, rtl).cell
                        Box(Modifier.selectorTrackPlacement(cell)
                            .then(if (interactive) Modifier.testTag("tab-option-$index")
                                .onFocusChanged { trackActivity.focus(index, it.isFocused) }.selectable(active == tab,
                                interactionSource = null, indication = null, role = Role.Tab, onClick = {
                                    // Reached by touch only from a silent origin (overflow scroll or
                                    // a scrolling ancestor), so its release is the only pulse.
                                    tap(index, if (fingerRelease.active) ControlHaptic.Strong else ControlHaptic.Weak)
                                }) else Modifier),
                            contentAlignment = Alignment.Center) {
                            Column(Modifier.requiredSize(width, with(density) { baseHeight.toDp() })
                                .graphicsLayer { scaleX = cell.width / pixels; scaleY = cell.height / baseHeight }
                                .padding(4.dp).then(if (interactive) Modifier.testTag("tab-option-content-$index") else Modifier),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(appIconPainter(tab.icon()), if (interactive) tab.label else null, Modifier.size(29.dp), tint = color)
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
                        androidx.compose.foundation.shape.GenericShape { size, _ -> addRoundRect(selectorContour.outlineAt(size)) }))
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
                if (!refractForeground) Lens()
                val foregroundRecording = selectorTrackRecordingBounds(geometry.travel, baseHeight, limits.crossClearance)
                val foregroundContent = androidx.compose.ui.geometry.Rect(
                    0f, 0f, geometry.travel, baseHeight).translate(-foregroundRecording.topLeft)
                Box(if (recordForeground) Modifier.selectorTrackPlacement(foregroundRecording)
                    .recordFrostedBackdrop(decorated, composite, displayBacking = false)
                    else Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                    // Record padded visual overflow without changing the gesture or
                    // scrolling host. Both sample origin and label coordinates move once.
                    Box(if (recordForeground) Modifier.selectorTrackPlacement(foregroundContent)
                        else Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                        Labels(MaterialTheme.colorScheme.onSurfaceVariant, true)
                        Labels(NavigationAccent, false)
                    }
                }
                if (refractForeground) {
                    Lens()
                    // None averages material, not the foreground labels. Restore the
                    // selected ink continuously as the optical renderer blends out.
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = average.value }) {
                        Labels(NavigationAccent, false)
                    }
                }
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
