package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.withoutVisualEffect
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.*

/** [corner] comes from the item's shared outline (#532); null keeps the generic contour. */
internal data class SelectionRegion(val bounds: Rect, val corner: Float? = null) {
    fun radius(gap: Float): Float = corner?.let { minOf(it, bounds.width / 2f, bounds.height / 2f).coerceAtLeast(0f) }
        ?: mediaItemCornerRadius(bounds, gap)
    fun contour(gap: Float) = androidx.compose.ui.geometry.RoundRect(bounds, CornerRadius(radius(gap)))
}

internal class SelectionEntry {
    var coordinates: LayoutCoordinates? = null
    var itemKey: Any? = null
    var selected by mutableStateOf(false)
    /** Eased hover progress (#533); the name is kept for the selection inspection tests. */
    var visibility: () -> Float by mutableStateOf({ 0f })
    var visualSize by mutableStateOf<(Size) -> Size>({ it })
    var outline by mutableStateOf<((Size, LayoutDirection) -> MediaItemOutline?)?>(null)
    var direction by mutableStateOf(LayoutDirection.Ltr)
    /** The item's own drawing while it hovers; the host draws it lifted. */
    var layer: GraphicsLayer? = null
    /** The cell's own fades (entry sweep, drag handoff) that its in-place drawing would get. */
    var alpha: () -> Float = { 1f }
    var recorded = false
    /** This item's last drawn lift in its own coordinates while it hovers; null at rest (#554). */
    var presented: HoverGeometry? = null
}

@Stable internal class SelectionScene {
    val regions = mutableStateMapOf<Any, SelectionEntry>()
    var placement by mutableIntStateOf(0)
    // Last geometry actually submitted by the host draw, not copied placement callbacks.
    // Plain state for source/native regression inspection; never drives composition or animation.
    var lastDrawnRegions: List<SelectionRegion> = emptyList()
    /** Each hovering item's last drawn lift, in its own coordinates, by item key. A drag
     * starts its cards from exactly this geometry. Plain state, written while drawing. */
    val hover = mutableMapOf<Any, HoverGeometry>()
    val viewportHover = mutableMapOf<Any, HoverGeometry>()
    /** Virtualized pinch items join live bodies under the same surface and shadow. */
    var retainedHover: List<Pair<HoverGeometry, Float>> = emptyList()
}
internal val LocalSelectionScene = staticCompositionLocalOf<SelectionScene?> { null }

/** Lets a cell's other decorations (the current-occurrence frost) follow its hover. */
@Stable internal class SelectionHoverHandle {
    var progress: () -> Float = { 0f }
}

/** Scrolling content and its hovering selections share a single clip, fade and overscroll transform. */
@Composable
internal fun SelectionViewport(modifier: Modifier = Modifier, contentDrawing: Modifier = Modifier,
    observePlacement: () -> Unit = {}, visibleItemKeys: (() -> Set<Any>)? = null,
    scene: SelectionScene = remember { SelectionScene() }, liftEdges: LiftEdges = LiftEdges.Horizontal,
    content: @Composable (OverscrollEffect?) -> Unit) {
    val overscroll = rememberOverscrollEffect()
    val events = remember(overscroll) { overscroll?.withoutVisualEffect() }
    ConnectedSelectionHost(modifier.clipToBounds().then(if (overscroll != null) Modifier.overscroll(overscroll) else Modifier),
        contentDrawing = contentDrawing, observePlacement = observePlacement, visibleItemKeys = visibleItemKeys,
        scene = scene, liftEdges = liftEdges) {
        content(events)
    }
}

/**
 * Hover-lift selection (#533). Selected items rise: their outline grows into a share of the
 * gap, a shadow deepens, and the surface shows the same backdrop region magnified with a
 * slight frost. Side-adjacent hovering items share one outline. Items draw their own content
 * into a retained layer while hovering; this host draws it above the shadow and surface.
 */
@Composable
internal fun ConnectedSelectionHost(modifier: Modifier = Modifier, contentDrawing: Modifier = Modifier,
    observePlacement: () -> Unit = {}, visibleItemKeys: (() -> Set<Any>)? = null,
    scene: SelectionScene = remember { SelectionScene() }, liftEdges: LiftEdges = LiftEdges.Horizontal,
    content: @Composable () -> Unit) {
    val liftStyle = rememberLiftShadow()
    val averageProgress = glassAverageProgress()
    // Older Android uses the shared solid-average path.
    val blurEffects = Build.VERSION.SDK_INT >= 33
    val graphics = androidx.compose.ui.platform.LocalGraphicsContext.current
    val scope = rememberCoroutineScope()
    val groups = remember { mutableListOf<HoverGroupRenderer>() }
    DisposableEffect(graphics) { onDispose { groups.forEach { graphics.releaseGraphicsLayer(it.output); graphics.releaseGraphicsLayer(it.flatOutput) }; groups.clear() } }
    val backing = LocalFrostedBackdrop.current
    val hasSelection by remember { derivedStateOf { scene.regions.values.any { it.selected || it.visibility() > 0f } } }
    BackdropLease(backing.takeIf { hasSelection })
    var coordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    val location = remember { BackdropLocation() }
    val recordingDensity = LocalDensity.current
    val recordingDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalSelectionScene provides scene) {
        Box(modifier.then(contentDrawing).backdropLocation(location).onPlaced { coordinates = it }.drawWithContent {
            // Pager placement also moves unselected rows. Do not subscribe their
            // otherwise reusable display lists to the selection geometry clock.
            if (!hasSelection && scene.retainedHover.isEmpty()) {
                scene.lastDrawnRegions = emptyList()
                scene.hover.clear()
                scene.viewportHover.clear()
                return@drawWithContent drawContent()
            }
            // Lazy scrolling can update retained child layers without replacing LayoutCoordinates.
            // Subscribe THIS drawing to its owner's layout result, then read live coordinates.
            observePlacement()
            scene.placement
            // Hovering items skip their in-place drawing; everything else is unchanged.
            drawContent()
            val host = coordinates ?: return@drawWithContent
            // Lazy prefetch may keep an unplaced item's coordinates attached.
            // Its owner's public native keys, rather than attachment, admit it.
            val placedKeys = visibleItemKeys?.invoke()
            class Placed(val entry: SelectionEntry, val at: Offset, val rest: MediaItemOutline, val progress: Float)
            val placed = scene.regions.values.mapNotNull { entry ->
                val item = entry.coordinates
                if (item == null || !item.isAttached || placedKeys != null && entry.itemKey !in placedKeys) return@mapNotNull null
                val at = host.localPositionOf(item, Offset.Zero)
                val measured = Size(item.size.width.toFloat(), item.size.height.toFloat())
                val outline = entry.outline?.invoke(measured, entry.direction)
                    ?: mediaItemBounds(measured, entry.visualSize(measured), entry.direction).let {
                        MediaItemOutline(it, SelectionRegion(it).radius(MediaSelectionGap.toPx()))
                    }
                Placed(entry, at, outline.translate(at), entry.visibility().coerceIn(0f, 1f))
            }
            // Selection intent can arrive one frame before lift progress. Only a visible lift
            // contributes glass; zero-progress content still belongs in its resting position.
            val hovering = placed.filter { it.progress > 0f && it.entry.recorded && it.entry.layer?.isReleased == false }
            val hoveringKeys = hovering.mapNotNullTo(mutableSetOf()) { it.entry.itemKey }
            scene.hover.keys.retainAll(hoveringKeys)
            scene.viewportHover.keys.retainAll(hoveringKeys)
            if (hovering.isEmpty() && scene.retainedHover.isEmpty()) { scene.lastDrawnRegions = emptyList(); return@drawWithContent }
            val viewport = Rect(Offset.Zero, size)
            val cap = HoverLift.MaxLift.toPx()
            val others = placed.map { it.rest.bounds } + scene.retainedHover.map { it.first.rest.bounds }
            val liveLifts = hovering.map { item ->
                hoverGeometry(item.rest, hoverRoom(item.rest.bounds, others, viewport, liftEdges, cap,
                    neighbourInset = MediaOutlinePadding.toPx(), clearance = HoverLift.Clearance.toPx()), item.progress)
            }
            hovering.forEachIndexed { index, item ->
                item.entry.presented = liveLifts[index].translate(-item.at)
                item.entry.itemKey?.let { scene.hover[it] = liveLifts[index].translate(-item.at); scene.viewportHover[it] = liveLifts[index] }
            }
            val lifts = liveLifts + scene.retainedHover.map { it.first }
            val opacities = hovering.map { it.entry.alpha().coerceIn(0f, 1f) } + scene.retainedHover.map { it.second }
            scene.lastDrawnRegions = lifts.map { SelectionRegion(it.lifted.bounds, it.lifted.corner) }
            val gap = MediaSelectionGap.toPx()
            // Groups join by REST geometry, so growth never changes membership mid-motion.
            val components = hoverComponents(lifts.map { it.rest.bounds }, gap)
            while (groups.size < components.size) groups += HoverGroupRenderer(graphics.createGraphicsLayer(), graphics.createGraphicsLayer(), scope)
            components.forEachIndexed { index, members ->
                val group = groups[index]
                val parts = members.map { lifts[it] }
                val strengths = { a: Int, b: Int -> min(parts[a].progress, parts[b].progress) }
                val opacity = members.maxOf { opacities[it] }
                drawHoverGroup(group, parts, strengths, backing, location, blurEffects, recordingDensity, recordingDirection, gap, opacity = opacity, itemOpacity = members.map { opacities[it] }, lift = liftStyle, averageProgress = averageProgress.value)
                members.forEach { member ->
                    val item = hovering.getOrNull(member) ?: return@forEach
                    val lift = lifts[member]
                    val layer = item.entry.layer ?: return@forEach
                    translate(item.at.x, item.at.y) {
                        val pivot = lift.pivot - item.at
                        layer.alpha = item.entry.alpha().coerceIn(0f, 1f)
                        scale(lift.scale, lift.scale, pivot) { drawLayer(layer) }
                    }
                }
            }
        }) { content() }
    }
}

/** One merged group's retained resources: blur layer, shadow raster and path cache. */
internal class HoverGroupRenderer(val output: GraphicsLayer, val flatOutput: GraphicsLayer, scope: kotlinx.coroutines.CoroutineScope) {
    val shadow = RetainedGlassShadow(scope)
    val path = HoverPathCache()
    val average = if (Build.VERSION.SDK_INT >= 33) MaterialAverageRenderer() else null
}

/** Shadow, then the magnified frosted backdrop clipped to the merged outline. Contents are the caller's. */
internal fun DrawScope.drawHoverGroup(group: HoverGroupRenderer, parts: List<HoverGeometry>, strength: (Int, Int) -> Float,
    backing: FrostedBackdrop?, location: BackdropLocation, blurEffects: Boolean,
    density: androidx.compose.ui.unit.Density, direction: LayoutDirection, gap: Float,
    sampleOffset: Offset = Offset.Zero, shadow: Boolean = true, opacity: Float = 1f,
    itemOpacity: List<Float>? = null, maskVisibility: Boolean = true, lift: LiftShadow, averageProgress: Float = 0f) {
    if (parts.isEmpty()) return
    val progress = parts.maxOf { it.progress } * opacity.coerceIn(0f, 1f)
    if (progress <= 0f) return
    val visibility = parts.mapIndexed { i, part ->
        (part.progress * (itemOpacity?.get(i) ?: opacity).coerceIn(0f, 1f) / progress).coerceIn(0f, 1f)
    }
    if (maskVisibility && visibility.any { it < .9999f }) {
        // A departing member must fade independently of its still-selected neighbours.
        // Keep one material/union shadow and mask it once; never redraw foreground here.
        val padding = if (shadow) (6f + lift.spread * 28f).dp.toPx() + 2f else gap / 2f
        val bounds = parts.map { it.lifted.bounds }.reduce { a, b -> a.union(b) }.inflate(padding)
        val canvas = drawContext.canvas
        canvas.saveLayer(bounds, Paint())
        drawHoverGroup(group, parts, strength, backing, location, blurEffects, density, direction, gap,
            sampleOffset, shadow, opacity, itemOpacity, maskVisibility = false, lift = lift, averageProgress = averageProgress)
        // Complement removal also handles transparent/empty masks correctly on API 26.
        canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.DstOut })
        drawRect(Color.White, bounds.topLeft, bounds.size)
        canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.DstOut })
        drawSelectionVisibilityMask(parts.indices.map { parts[it].lifted.bounds to visibility[it] }, padding * 2f)
        canvas.restore(); canvas.restore(); canvas.restore()
        return
    }
    val regions = parts.map { SelectionRegion(it.lifted.bounds, it.lifted.corner) }
    val rest = parts.map { SelectionRegion(it.rest.bounds, it.rest.corner) }
    val path = group.path.path(regions, rest, gap, strength)
    if (shadow) with(group.shadow) {
        // Strength alone follows progress, so the shadow deepens linearly with the lift.
        draw(parts.map { it.lifted.contour to 1f } + group.path.bridges.map {
            RoundRect(it.first, CornerRadius.Zero) to 1f }, lift.strength * progress, lift.spread, color = lift.color)
    }
    val source = backing?.takeIf { it.recorded && !it.layer.isReleased } ?: return
    if (blurEffects) location.observeRecordingAcrossWindows(source)
    val registration = source.location.registrationTo(location) ?: return
    val restBounds = rest.map { it.bounds }.reduce { a, b -> a.union(b) }
    val liftedBounds = parts.map { it.lifted.bounds }.reduce { a, b -> a.union(b) }
    val (scale, translation) = hoverMagnification(restBounds, liftedBounds)
    fun DrawScope.magnified(draw: DrawScope.() -> Unit) = withTransform({
        translate(translation.x, translation.y); scale(scale.x, scale.y, Offset.Zero)
        translate(sampleOffset.x, sampleOffset.y)
    }) { withBackdropRegistration(registration, draw) }
    val blur = HoverLift.Blur.toPx() * progress
    clipPath(path) {
        val blending = averageProgress > 0f && averageProgress < 1f
        if (blending) drawContext.canvas.saveLayer(path.getBounds(), Paint())
        if (blurEffects && averageProgress < 1f && blur > .5f) {
            // Blur a crop larger than the outline, then clip: edges sample real backdrop.
            val crop = path.getBounds().inflate(blur * 2f).intersect(Rect(Offset.Zero, size))
            if (!crop.isEmpty) {
                val output = group.output
                output.renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
                output.record(density, direction, IntSize(ceil(crop.width).toInt(), ceil(crop.height).toInt())) {
                    translate(-crop.left, -crop.top) { magnified { drawLayer(source.layer) } }
                }
                translate(crop.left, crop.top) { drawLayer(output.also { it.alpha = progress * (1f - averageProgress) }) }
            }
        } else if (!blurEffects) {
            val average = source.averageColor(location, path.getBounds())
            if (average != null) drawPath(path, average, alpha = progress)
        } else if (averageProgress < 1f) {
            drawContext.canvas.saveLayer(path.getBounds(), Paint().apply { alpha = progress * (1f - averageProgress) })
            magnified { drawLayer(source.layer) }
            drawContext.canvas.restore()
        }
        if (blurEffects && averageProgress > 0f && Build.VERSION.SDK_INT >= 33) {
            val crop = path.getBounds()
            val output = group.flatOutput
            output.renderEffect = group.average?.effect()
            output.record(density, direction, IntSize(8, 8)) {
                scale(8f / crop.width, 8f / crop.height, Offset.Zero) {
                    translate(-crop.left, -crop.top) { magnified { drawLayer(source.layer) } }
                }
            }
            output.alpha = progress * averageProgress
            output.blendMode = if (blending) BlendMode.Plus else BlendMode.SrcOver
            translate(crop.left, crop.top) {
                scale(crop.width / 8f, crop.height / 8f, Offset.Zero) { drawLayer(output) }
            }
        }
        if (blending) drawContext.canvas.restore()
    }
}

private fun Rect.union(other: Rect) = Rect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

/** Rebuild the merged outline only when its relative geometry or bridge strengths change. */
internal class HoverPathCache {
    private var key: Any? = null
    private var origin = Offset.Zero
    private var cached = Path()
    private var cachedBridges: List<Pair<Rect, Float>> = emptyList()
    /** Bridge rectangles of the last path, with their strengths (for the shadow). */
    var bridges: List<Pair<Rect, Float>> = emptyList(); private set
    fun path(regions: List<SelectionRegion>, rest: List<SelectionRegion>, gap: Float, strength: (Int, Int) -> Float): Path {
        val at = regions.first().bounds.topLeft
        val local = localLensRegions(regions, at)
        val localRest = localLensRegions(rest, at)
        val strengths = regions.indices.flatMap { a -> (a + 1 until regions.size).map { b -> strength(a, b) } }
        val next = Triple(local, localRest, strengths) to gap
        if (next != key) {
            key = next
            val bridgeList = mutableListOf<Pair<Rect, Float>>()
            cached = connectedSelectionPath(local, gap, localRest, strength) { rect, value -> bridgeList += rect to value }
            cachedBridges = bridgeList
        }
        origin = at
        bridges = cachedBridges.map { it.first.translate(at) to it.second }
        return Path().apply { addPath(cached, at) }
    }
}

/** Groups by rest geometry: side-adjacent within [gap]; diagonal neighbours stay separate. */
internal fun hoverComponents(rest: List<Rect>, gap: Float): List<List<Int>> {
    val remaining = rest.indices.toMutableList()
    val result = mutableListOf<List<Int>>()
    while (remaining.isNotEmpty()) {
        val group = mutableListOf(remaining.removeAt(0))
        var next = 0
        while (next < group.size) {
            val a = rest[group[next++]]
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val index = iterator.next()
                val b = rest[index]
                val x = max(a.left - b.right, b.left - a.right)
                val y = max(a.top - b.bottom, b.top - a.bottom)
                if (x <= gap && y <= gap && (x < 0 || y < 0)) { group += index; iterator.remove() }
            }
        }
        result += group
    }
    return result
}

/** Optical depth belongs to each connected body, never the hull of separated selections. */
internal fun selectionComponents(regions: List<SelectionRegion>, gap: Float): List<List<SelectionRegion>> =
    hoverComponents(regions.map { it.bounds }, gap).map { group -> group.map { regions[it] } }

/** Union neighbors through the gaps, across shared sides only. Never fill group hulls or corner gaps.
 * Adjacency is decided by [rest] (defaults to [regions]); a bridge narrows with its [strength]. */
internal fun connectedSelectionPath(regions: List<SelectionRegion>, gap: Float,
    rest: List<SelectionRegion> = regions, strength: (Int, Int) -> Float = { _, _ -> 1f },
    onBridge: (Rect, Float) -> Unit = { _, _ -> }): Path {
    val path = Path()
    fun add(part: Path) { path.op(path, part, PathOperation.Union) }
    regions.forEach { region ->
        add(Path().apply { addRoundRect(region.contour(gap)) })
    }
    regions.indices.forEach { i -> (i + 1 until regions.size).forEach { j ->
        val ra = rest[i].bounds; val rb = rest[j].bounds
        val xRest = max(ra.left - rb.right, rb.left - ra.right)
        val yRest = max(ra.top - rb.bottom, rb.top - ra.bottom)
        if (xRest > gap || yRest > gap || (xRest < 0 && yRest < 0)) return@forEach
        if (xRest >= 0 && yRest >= 0) return@forEach // Corner-only neighbors remain separate.
        val s = strength(i, j).coerceIn(0f, 1f)
        if (s <= 0f) return@forEach
        val a = regions[i]; val b = regions[j]
        // Extend through the rounded shoulders, not just across the empty gap.
        val r = max(a.radius(gap), b.radius(gap))
        val full = if (xRest < 0) Rect(max(a.bounds.left, b.bounds.left),
            min(a.bounds.bottom, b.bounds.bottom) - r,
            min(a.bounds.right, b.bounds.right), max(a.bounds.top, b.bounds.top) + r)
        else Rect(min(a.bounds.right, b.bounds.right) - r, max(a.bounds.top, b.bounds.top),
            max(a.bounds.left, b.bounds.left) + r, min(a.bounds.bottom, b.bounds.bottom))
        if (full.width <= 0f || full.height <= 0f) return@forEach
        // A weakening bridge pinches toward its middle, like a neck, before it parts.
        val bridge = if (xRest < 0) Rect(full.center.x - full.width * s / 2f, full.top, full.center.x + full.width * s / 2f, full.bottom)
            else Rect(full.left, full.center.y - full.height * s / 2f, full.right, full.center.y + full.height * s / 2f)
        onBridge(bridge, s)
        add(Path().apply { addRect(bridge) })
    } }
    return path
}

/**
 * Registers an item with the surrounding host and lifts it while selected (#533). While its
 * hover progress is above zero the item's drawing goes to a retained layer that the host
 * draws scaled above the group's shadow and surface. A [handle] shares its progress with
 * the cell's other decorations (the current-occurrence frost).
 */
@Composable
internal fun Modifier.selectionLens(isSelected: Boolean, partial: Boolean,
    itemKey: Any? = null, outline: ((Size, LayoutDirection) -> MediaItemOutline?)? = null,
    handle: SelectionHoverHandle? = null, alpha: () -> Float = { 1f },
    anchor: GlassAnchor? = null, visualSize: (Size) -> Size = { it }): Modifier {
    val scene = LocalSelectionScene.current
    val direction = LocalLayoutDirection.current
    val token = remember { Any() }
    val entry = remember { SelectionEntry() }
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val view = androidx.compose.ui.platform.LocalView.current
    val visibility = androidx.compose.animation.core.animateFloatAsState(if (isSelected && !partial) 1f else 0f,
        hoverMotionSpec(LocalReducedMotion.current), label = "Selection hover")
    SideEffect {
        entry.itemKey = itemKey
        entry.visibility = { visibility.value }
        entry.visualSize = visualSize
        entry.outline = outline
        entry.direction = direction
        entry.layer = layer
        entry.alpha = alpha
        handle?.progress = { visibility.value }
        // Popups homed on this item follow its lift (#554), however the item is keyed.
        anchor?.presentation = { entry.presented?.let { lift -> entry.coordinates?.takeIf { it.isAttached }
            ?.let { GlassLift(lift.scale, it.backdropPosition(view) + lift.pivot) } } }
        val selected = isSelected && !partial
        if (entry.selected != selected) { entry.selected = selected; androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { scene?.placement = (scene?.placement ?: 0) + 1 } }
        // Registration is identity-stable; rewrite only a missing or replaced entry.
        if (scene != null && scene.regions[token] !== entry) scene.regions[token] = entry
    }
    DisposableEffect(scene, token) { onDispose { scene?.regions?.remove(token); entry.recorded = false } }
    return semantics {
        this.selected = isSelected && !partial
        if (partial) stateDescription = "Partially selected"
    }.onPlaced {
        entry.coordinates = it
        if (entry.selected || entry.visibility() > 0f) androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { scene?.placement = (scene?.placement ?: 0) + 1 }
    }.drawWithContent {
        if (scene == null) { drawContent(); return@drawWithContent }
        val hovering = visibility.value > 0f
        // At rest and while hovering, the content is drawn only through this one retained layer,
        // and the layer always stays in this item's own display list (#574). A RenderNode that is
        // moved between display lists, or left out of every list for a frame, loses its recording;
        // retained children such as artwork layers are then never re-recorded, so the cover
        // vanishes until it next changes. The host adds the lifted drawing; it never takes the layer.
        layer.record { this@drawWithContent.drawContent() }
        entry.recorded = true
        if (!hovering) {
            entry.presented = null
            layer.alpha = 1f
            drawLayer(layer)
            return@drawWithContent
        }
        // The host draws this recording lifted in the same frame. Keep it parented here, outside
        // the item's area, so nothing shows in place: a non-empty clip is still recorded.
        clipRect(-1f, -1f, 0f, 0f) { drawLayer(layer) }
    }
}
