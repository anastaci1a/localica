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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.*

internal data class SelectionRegion(val bounds: Rect)
internal class SelectionEntry { var coordinates: LayoutCoordinates? = null; var selected = false }
@Stable internal class SelectionScene {
    val regions = mutableStateMapOf<Any, SelectionEntry>()
    var placement by mutableIntStateOf(0)
}
internal val LocalSelectionScene = staticCompositionLocalOf<SelectionScene?> { null }

/** Scrolling content and its lenses share a single clip, fade and overscroll transform. */
@Composable
internal fun SelectionViewport(modifier: Modifier = Modifier, content: @Composable (OverscrollEffect?) -> Unit) {
    val overscroll = rememberOverscrollEffect()
    val events = remember(overscroll) { overscroll?.withoutVisualEffect() }
    ConnectedSelectionHost(modifier.clipToBounds().then(if (overscroll != null) Modifier.overscroll(overscroll) else Modifier)) {
        content(events)
    }
}

/** Record content once and keep it sharp above the separately refracted backing. */
@Composable
internal fun ConnectedSelectionHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scene = remember { SelectionScene() }
    val contentLayer = rememberGraphicsLayer()
    val output = rememberGraphicsLayer()
    val backing = LocalFrostedBackdrop.current
    val view = LocalView.current
    var coordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    val geometry = remember { SelectionPathCache() }
    val mesh = remember { LensMeshRenderer() }
    val meshShape = remember { LensSelectionShape() }
    val optics = remember { if (Build.VERSION.SDK_INT >= 33) LensSelectionRenderer() else null }
    val depth = LocalGlassAppearance.current.depth
    CompositionLocalProvider(LocalSelectionScene provides scene) {
        Box(modifier.onPlaced { coordinates = it }.drawWithContent {
            // Placement runs before drawing; query live coordinates instead of copying
            // global bounds through a later callback and lagging one frame behind rows.
            scene.placement
            val host = coordinates ?: return@drawWithContent drawContent()
            val regions = scene.regions.values.mapNotNull { entry ->
                val item = entry.coordinates
                if (!entry.selected || item == null || !item.isAttached) null else {
                    val at = host.localPositionOf(item, Offset.Zero)
                    SelectionRegion(Rect(at, Size(item.size.width.toFloat(), item.size.height.toFloat())))
                }
            }
            if (regions.isEmpty()) { drawContent(); return@drawWithContent }
            // Normalize translation: scrolling moves the cached shape instead of
            // rasterizing a distance field and uploading another texture every frame.
            val origin = regions.first().bounds.topLeft
            val local = localLensRegions(regions, origin)
            val path = geometry.path(local, 16.dp.toPx())
            if (Build.VERSION.SDK_INT >= 33 && optics != null) {
                contentLayer.record { this@drawWithContent.drawContent() }
                val crop = path.getBounds().translate(origin).inflate(2f).intersect(Rect(Offset.Zero, size))
                // The backing is a lens input, not a replacement for the viewport.
                // Painting it across unselected space creates rectangular seams over
                // parallax artwork and translucent parent surfaces.
                translate(origin.x, origin.y) { clipPath(path, ClipOp.Difference) {
                    translate(-origin.x, -origin.y) { drawLayer(contentLayer) }
                } }
                if (!crop.isEmpty) {
                    // Keep the complete backdrop once, then only the visible ink.
                    // Padding each half by the lens radius wastes several screenfuls
                    // of pixels for a long selection without adding source detail.
                    val at = Offset(floor(crop.left), floor(crop.top))
                    val visible = Size(ceil(crop.right - at.x), ceil(crop.bottom - at.y))
                    val source = backing?.takeUnless { it.layer.isReleased }
                    val backdropSize = source?.size ?: androidx.compose.ui.unit.IntSize.Zero
                    val foregroundY = backdropSize.height + 2f
                    val atlas = LensAtlas(visible, Size(backdropSize.width.toFloat(), backdropSize.height.toFloat()),
                        at + host.backdropPosition(view) - (source?.origin ?: Offset.Zero), foregroundY)
                    val extent = androidx.compose.ui.unit.IntSize(max(visible.width.toInt(), backdropSize.width + 2),
                        foregroundY.toInt() + visible.height.toInt())
                    output.renderEffect = optics.effect(local, path, 16.dp.toPx(), origin - at, depth, atlas)
                    output.clip = true
                    output.record(size = extent) {
                        clipRect(left = 1f, top = 1f, right = backdropSize.width + 1f, bottom = backdropSize.height + 1f) {
                            if (source != null) translate(1f, 1f) { drawLayer(source.layer) }
                        }
                        clipRect(top = foregroundY) {
                            translate(-at.x, foregroundY - at.y) { drawLayer(contentLayer) }
                        }
                    }
                    translate(origin.x, origin.y) { clipPath(path) {
                        translate(at.x - origin.x, at.y - origin.y) { drawLayer(output) }
                    } }
                }

            } else {
                meshShape.update(local, path, 16.dp.toPx())
                val shape = meshShape.geometry ?: return@drawWithContent drawContent()
                val source = backing
                val image = source?.snapshot
                if (image != null) translate(origin.x, origin.y) { clipPath(path) {
                    with(mesh) { draw(shape, image, source.size, origin + host.backdropPosition(view) - source.origin, depth,
                        field = meshShape.map?.let { map -> { point -> map.sample(point) } }, fieldKey = meshShape.map, lighting = false) }
                } }
                drawContent()
                translate(origin.x, origin.y) { clipPath(path) {
                    with(mesh) { draw(shape, null, androidx.compose.ui.unit.IntSize.Zero, Offset.Zero, depth,
                        field = meshShape.map?.let { map -> { point -> map.sample(point) } }, fieldKey = meshShape.map) }
                } }
            }
        }) { content() }
    }
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
        val radius = min(gap * 1.5f, min(r.width, r.height) / 2f)
        add(Path().apply { addRoundRect(RoundRect(r, CornerRadius(radius))) })
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
internal fun Modifier.selectionLens(isSelected: Boolean, partial: Boolean): Modifier {
    val scene = LocalSelectionScene.current
    val token = remember { Any() }
    val entry = remember { SelectionEntry() }
    SideEffect {
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
        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { scene?.placement = (scene?.placement ?: 0) + 1 }
    }
}
