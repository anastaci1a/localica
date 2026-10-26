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
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toIntSize
import kotlin.math.*

internal data class SelectionRegion(val bounds: Rect)
internal class SelectionEntry {
    var coordinates: LayoutCoordinates? = null
    var selected by mutableStateOf(false)
    var visibility: () -> Float by mutableStateOf({ 0f })
    var visualSize by mutableStateOf<(Size) -> Size>({ it })
    var direction by mutableStateOf(LayoutDirection.Ltr)
}
@Stable internal class SelectionScene {
    val regions = mutableStateMapOf<Any, SelectionEntry>()
    var placement by mutableIntStateOf(0)
    // Last geometry actually submitted by the host draw, not copied placement callbacks.
    // Plain state for source/native regression inspection; never drives composition or animation.
    var lastDrawnRegions: List<SelectionRegion> = emptyList()
}
internal val LocalSelectionScene = staticCompositionLocalOf<SelectionScene?> { null }

/** Scrolling content and its lenses share a single clip, fade and overscroll transform. */
@Composable
internal fun SelectionViewport(modifier: Modifier = Modifier, contentDrawing: Modifier = Modifier,
    observePlacement: () -> Unit = {}, content: @Composable (OverscrollEffect?) -> Unit) {
    val overscroll = rememberOverscrollEffect()
    val events = remember(overscroll) { overscroll?.withoutVisualEffect() }
    ConnectedSelectionHost(modifier.clipToBounds().then(if (overscroll != null) Modifier.overscroll(overscroll) else Modifier),
        contentDrawing = contentDrawing, observePlacement = observePlacement) {
        content(events)
    }
}

/** Record content once and keep it sharp above the separately refracted backing. */
@Composable
internal fun ConnectedSelectionHost(modifier: Modifier = Modifier, contentDrawing: Modifier = Modifier,
    observePlacement: () -> Unit = {}, content: @Composable () -> Unit) {
    val scene = remember { SelectionScene() }
    val contentLayer = rememberGraphicsLayer()
    val transitionLayer = rememberGraphicsLayer()
    val visibilityLayer = rememberGraphicsLayer()
    val graphics = androidx.compose.ui.platform.LocalGraphicsContext.current
    val groups = remember { mutableListOf<SelectionGroupRenderer>() }
    DisposableEffect(graphics) { onDispose { groups.forEach { graphics.releaseGraphicsLayer(it.output) } } }
    val backing = LocalFrostedBackdrop.current
    val hasSelection by remember { derivedStateOf { scene.regions.values.any { it.selected || it.visibility() > 0f } } }
    BackdropLease(backing.takeIf { hasSelection })
    val view = LocalView.current
    var coordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    val geometry = remember { SelectionPathCache() }
    val grouping = remember { SelectionComponentCache() }
    val depth = LocalGlassAppearance.current.depth
    val recordingDensity = LocalDensity.current
    val recordingDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalSelectionScene provides scene) {
        Box(modifier.onPlaced { coordinates = it }.drawWithContent {
            // Pager placement also moves unselected rows. Do not subscribe their
            // otherwise reusable display lists to the selection geometry clock.
            if (!hasSelection) {
                scene.lastDrawnRegions = emptyList()
                return@drawWithContent drawContent()
            }
            // Lazy scrolling can update retained child layers without replacing LayoutCoordinates.
            // Subscribe THIS drawing to its owner's layout result, then read live coordinates.
            observePlacement()
            // Placement runs before drawing; query live coordinates instead of copying
            // global bounds through a later callback and lagging one frame behind rows.
            scene.placement
            val host = coordinates ?: return@drawWithContent drawContent()
            val visibleEntries = scene.regions.values.mapNotNull { entry ->
                val item = entry.coordinates
                val visibility = entry.visibility().coerceIn(0f, 1f)
                if (visibility <= 0f || item == null || !item.isAttached) null else {
                    val at = host.localPositionOf(item, Offset.Zero)
                    val measured = Size(item.size.width.toFloat(), item.size.height.toFloat())
                    SelectionRegion(selectionRevealBounds(mediaItemBounds(measured, entry.visualSize(measured), entry.direction), visibility).translate(at)) to visibility
                }
            }
            val regions = visibleEntries.map { it.first }
            scene.lastDrawnRegions = regions
            if (regions.isEmpty()) { drawContent(); return@drawWithContent }
            // Normalize translation: scrolling moves the cached shape instead of
            // rasterizing a distance field and uploading another texture every frame.
            val origin = regions.first().bounds.topLeft
            val local = localLensRegions(regions, origin)
            val path = geometry.path(local, MediaSelectionGap.toPx())
            val components = grouping.get(local, MediaSelectionGap.toPx())
            while (groups.size < components.size) groups += SelectionGroupRenderer(graphics.createGraphicsLayer())
            val transitioning = visibleEntries.any { it.second < 1f }
            val recordContent = Build.VERSION.SDK_INT >= 33 || transitioning
            // Record foreground once for every reader. Settled legacy lenses keep
            // their original direct draw; only a transition needs both complete scenes.
            if (recordContent) {
                contentLayer.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
                contentLayer.record { this@drawWithContent.drawContent() }
            }
            fun DrawScope.selectedScene() {
                if (Build.VERSION.SDK_INT >= 33) {
                    translate(origin.x, origin.y) { clipPath(path, ClipOp.Difference) {
                        translate(-origin.x, -origin.y) { drawLayer(contentLayer) }
                    } }
                }
                components.forEachIndexed { index, component ->
                    val renderer = groups[index]
                    val groupOrigin = component.first().bounds.topLeft
                    val groupRegions = localLensRegions(component, groupOrigin)
                    val groupPath = renderer.path.path(groupRegions, MediaSelectionGap.toPx())
                    val absolute = origin + groupOrigin
                    val crop = groupPath.getBounds().translate(absolute).inflate(2f).intersect(Rect(Offset.Zero, size))
                    if (crop.isEmpty) return@forEachIndexed
                    if (Build.VERSION.SDK_INT >= 33) {
                        val at = Offset(floor(crop.left), floor(crop.top))
                        val visible = Size(ceil(crop.right - at.x), ceil(crop.bottom - at.y))
                        val source = backing?.takeUnless { it.layer.isReleased }
                        val sourceOffset = at + host.backdropPosition(view) - (source?.origin ?: Offset.Zero)
                        // Only upload pixels this lens can reach. The ray model's bounded
                        // entry/exit projection is below 2.1 spans, including maximum depth.
                        val reach = groupPath.getBounds().size.minDimension * 1.05f + 2f
                        val sampled = if (source == null) Rect.Zero else Rect(sourceOffset, visible).inflate(reach)
                            .intersect(Rect(Offset.Zero, Size(source.size.width.toFloat(), source.size.height.toFloat())))
                            .takeUnless { it.isEmpty } ?: Rect.Zero
                        val sampleOrigin = Offset(floor(sampled.left), floor(sampled.top))
                        val backdropSize = androidx.compose.ui.unit.IntSize(ceil(sampled.right - sampleOrigin.x).toInt(),
                            ceil(sampled.bottom - sampleOrigin.y).toInt())
                        val foregroundY = backdropSize.height + 2f
                        val atlas = LensAtlas(visible, Size(backdropSize.width.toFloat(), backdropSize.height.toFloat()),
                            sourceOffset - sampleOrigin, foregroundY)
                        val extent = androidx.compose.ui.unit.IntSize(max(visible.width.toInt(), backdropSize.width + 2),
                            foregroundY.toInt() + visible.height.toInt())
                        val output = renderer.output
                        output.renderEffect = requireNotNull(renderer.optics).effect(groupRegions, groupPath, MediaSelectionGap.toPx(), absolute - at, depth, atlas)
                        output.clip = true
                        output.record(recordingDensity, recordingDirection, glassLayerExtent(extent)) {
                            clipRect(left = 1f, top = 1f, right = backdropSize.width + 1f, bottom = backdropSize.height + 1f) {
                                if (source != null) translate(1f - sampleOrigin.x, 1f - sampleOrigin.y) { drawLayer(source.layer) }
                            }
                            clipRect(top = foregroundY) { translate(-at.x, foregroundY - at.y) { drawLayer(contentLayer) } }
                        }
                        translate(absolute.x, absolute.y) { clipPath(groupPath) {
                            translate(at.x - absolute.x, at.y - absolute.y) { drawLayer(output) }
                        } }
                    } else {
                        renderer.shape.update(groupRegions, groupPath, MediaSelectionGap.toPx())
                        val shape = renderer.shape.geometry ?: return@forEachIndexed
                        val source = backing
                        val image = source?.snapshot
                        if (image != null) translate(absolute.x, absolute.y) { clipPath(groupPath) {
                            with(renderer.mesh) { draw(shape, image, source.size, absolute + host.backdropPosition(view) - source.origin, depth,
                                field = renderer.shape.map?.let { map -> { point -> map.sample(point) } }, fieldKey = renderer.shape.map, lighting = false) }
                        } }
                    }
                }
                if (Build.VERSION.SDK_INT < 33) {
                    if (recordContent) drawLayer(contentLayer) else this@drawWithContent.drawContent()
                    components.forEachIndexed { index, component ->
                        val renderer = groups[index]
                        val shape = renderer.shape.geometry ?: return@forEachIndexed
                        val absolute = origin + component.first().bounds.topLeft
                        val groupPath = renderer.path.path(localLensRegions(component, component.first().bounds.topLeft), MediaSelectionGap.toPx())
                        translate(absolute.x, absolute.y) { clipPath(groupPath) {
                            with(renderer.mesh) { draw(shape, null, androidx.compose.ui.unit.IntSize.Zero, Offset.Zero, depth,
                                field = renderer.shape.map?.let { map -> { point -> map.sample(point) } }, fieldKey = renderer.shape.map) }
                        } }
                    }
                }
                components.forEachIndexed { index, component ->
                    val offset = origin + component.first().bounds.topLeft
                    val groupPath = groups[index].path.path(localLensRegions(component, component.first().bounds.topLeft), MediaSelectionGap.toPx())
                    translate(offset.x, offset.y) { drawGlassRim(groupPath.getBounds(), (MediaSelectionGap * 1.5f).toPx(), groupPath) }
                }
            }
            if (!transitioning) selectedScene() else {
                // DrawScope.record in Compose 1.8 redirects the node scope to itself
                // as Density. Procedural dp/mesh reads then recurse; these recordings
                // need their own scope with the immutable composition density instead.
                transitionLayer.record(recordingDensity, recordingDirection, size.toIntSize()) { selectedScene() }
                visibilityLayer.record(recordingDensity, recordingDirection, size.toIntSize()) {
                    drawSelectionVisibilityMask(visibleEntries.map { it.first.bounds to it.second }, MediaSelectionGap.toPx())
                }
                drawSelectionTransition(contentLayer, transitionLayer, visibilityLayer)
            }

        }.then(contentDrawing)) { content() }
    }
}

/** Scrolling translates a group; only a membership/layout change rebuilds connectivity. */
private class SelectionComponentCache {
    private var regions: List<SelectionRegion> = emptyList()
    private var gap = Float.NaN
    private var result: List<List<SelectionRegion>> = emptyList()
    fun get(value: List<SelectionRegion>, spacing: Float): List<List<SelectionRegion>> {
        if (value != regions || spacing != gap) {
            result = selectionComponents(value, spacing)
            regions = value; gap = spacing
        }
        return result
    }
}

private class SelectionGroupRenderer(val output: androidx.compose.ui.graphics.layer.GraphicsLayer) {
    val path = SelectionPathCache()
    val mesh = LensMeshRenderer()
    val shape = LensSelectionShape()
    val optics = if (Build.VERSION.SDK_INT >= 33) LensSelectionRenderer() else null
}

/** Optical depth belongs to each connected body, never the hull of separated selections. */
internal fun selectionComponents(regions: List<SelectionRegion>, gap: Float): List<List<SelectionRegion>> {
    val remaining = regions.toMutableList()
    val result = mutableListOf<List<SelectionRegion>>()
    while (remaining.isNotEmpty()) {
        val group = mutableListOf(remaining.removeAt(0))
        var next = 0
        while (next < group.size) {
            val a = group[next++].bounds
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val b = iterator.next()
                val x = max(a.left - b.bounds.right, b.bounds.left - a.right)
                val y = max(a.top - b.bounds.bottom, b.bounds.top - a.bottom)
                if (x <= gap && y <= gap && (x < 0 || y < 0)) { group += b; iterator.remove() }
            }
        }
        result += group
    }
    return result
}

private class SelectionPathCache {
    private var previous = emptyList<SelectionRegion>()
    private var previousGap = 0f
    private var cached = Path()
    fun path(regions: List<SelectionRegion>, gap: Float): Path {
        if (regions != previous || gap != previousGap) {
            cached = connectedSelectionPath(regions, gap)
            previous = regions; previousGap = gap
        }
        return cached
    }
}

/** Union neighbors through the gaps, across shared sides only. Never fill group hulls or corner gaps. */
internal fun connectedSelectionPath(regions: List<SelectionRegion>, gap: Float): Path {
    val path = Path()
    fun add(part: Path) { path.op(path, part, PathOperation.Union) }
    regions.forEach { region ->
        val r = region.bounds
        add(Path().apply { addRoundRect(mediaItemContour(r, gap)) })
    }
    regions.forEachIndexed { i, a -> regions.drop(i + 1).forEach { b ->
        val xGap = max(a.bounds.left - b.bounds.right, b.bounds.left - a.bounds.right)
        val yGap = max(a.bounds.top - b.bounds.bottom, b.bounds.top - a.bounds.bottom)
        if (xGap > gap || yGap > gap || (xGap < 0 && yGap < 0)) return@forEach
        if (xGap < 0 || yGap < 0) {
            // Extend through the rounded shoulders, not just across the empty gap.
            // A round line cap here is as large as half a row's width (the old circle).
            val r = gap * 1.5f
            val bridge = if (xGap < 0) Rect(max(a.bounds.left, b.bounds.left),
                min(a.bounds.bottom, b.bounds.bottom) - r,
                min(a.bounds.right, b.bounds.right), max(a.bounds.top, b.bounds.top) + r)
            else Rect(min(a.bounds.right, b.bounds.right) - r, max(a.bounds.top, b.bounds.top),
                max(a.bounds.left, b.bounds.left) + r, min(a.bounds.bottom, b.bounds.bottom))
            add(Path().apply { addRect(bridge) })
            return@forEach
        }
        // Corner-only neighbors remain separate; no diagonal connectors.
    } }
    return path
}

@Composable
internal fun Modifier.selectionLens(isSelected: Boolean, partial: Boolean,
    visualSize: (Size) -> Size = { it }): Modifier {
    val scene = LocalSelectionScene.current
    val direction = LocalLayoutDirection.current
    val token = remember { Any() }
    val entry = remember { SelectionEntry() }
    val visibility = androidx.compose.animation.core.animateFloatAsState(if (isSelected && !partial) 1f else 0f,
        selectionMotionSpec(LocalReducedMotion.current), label = "Selection lens visibility")
    SideEffect {
        entry.visibility = { visibility.value }
        entry.visualSize = visualSize
        entry.direction = direction
        val selected = isSelected && !partial
        if (entry.selected != selected) { entry.selected = selected; androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { scene?.placement = (scene?.placement ?: 0) + 1 } }
        scene?.regions?.set(token, entry)
    }
    DisposableEffect(scene, token) { onDispose { scene?.regions?.remove(token) } }
    return semantics {
        this.selected = isSelected && !partial
        if (partial) stateDescription = "Partially selected"
    }.onPlaced {
        entry.coordinates = it
        if (entry.selected || entry.visibility() > 0f) androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { scene?.placement = (scene?.placement ?: 0) + 1 }
    }
}
