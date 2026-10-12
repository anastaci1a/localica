package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.*

internal data class SelectionRegion(val bounds: Rect, val progress: Float)
internal data class SelectionEntry(val bounds: Rect, val progress: State<Float>)
@Stable internal class SelectionScene {
    val regions = mutableStateMapOf<Any, SelectionEntry>()
    var origin by mutableStateOf(Offset.Zero)
}
internal val LocalSelectionScene = staticCompositionLocalOf<SelectionScene?> { null }

/** Record the content once; the lens samples that recording, never its own output. */
@Composable
internal fun ConnectedSelectionHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scene = remember { SelectionScene() }
    val layer = rememberGraphicsLayer()
    val output = rememberGraphicsLayer()
    val backing = LocalFrostedBackdrop.current
    val view = LocalView.current
    var globalOrigin by remember { mutableStateOf(Offset.Zero) }
    val geometry = remember { SelectionPathCache() }
    val edgeLighting = remember { SelectionEdgeLighting() }
    val optics = remember { if (Build.VERSION.SDK_INT >= 33) ConnectedLensOptics() else null }
    CompositionLocalProvider(LocalSelectionScene provides scene) {
        Box(modifier.onGloballyPositioned { scene.origin = it.positionInRoot(); globalOrigin = it.backdropPosition(view) }.drawWithContent {
            // Observe progress in drawing: selection does not recompose/remeasure the browser.
            val regions = scene.regions.values.mapNotNull { entry ->
                val p = entry.progress.value.coerceIn(0f, 1f)
                if (p <= .001f || entry.bounds.isEmpty) null else
                    SelectionRegion(entry.bounds.translate(-scene.origin), p)
            }
            if (regions.isEmpty()) { drawContent(); return@drawWithContent }
            val path = geometry.path(regions, 16.dp.toPx())
            val strength = regions.maxOf { it.progress }
            if (Build.VERSION.SDK_INT >= 33 && optics != null) {
                layer.record {
                    // Translucent menus need their separate backing beneath recorded content.
                    if (backing != null && !backing.layer.isReleased) {
                        val offset = backing.origin - globalOrigin
                        translate(offset.x, offset.y) { drawLayer(backing.layer) }
                    }
                    this@drawWithContent.drawContent()
                }
                output.renderEffect = optics.effect(regions, path, size, 22.dp.toPx(), strength)
                output.record { drawLayer(layer) }
                drawLayer(layer)
                clipPath(path) { drawLayer(output) }
                drawPath(path, Brush.linearGradient(listOf(Color.White.copy(alpha = .65f * strength),
                    Color.White.copy(alpha = .08f * strength), Color.White.copy(alpha = .25f * strength))), style = Stroke(.7.dp.toPx()))
            } else {
                drawContent()
                edgeLighting.image(regions, path, size, density)?.let { drawImage(it, topLeft = edgeLighting.origin) }
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

/** Union neighbors through the gaps, including narrow corner bridges. Never fill group hulls. */
internal fun connectedSelectionPath(regions: List<SelectionRegion>, gap: Float): Path {
    val path = Path()
    fun add(part: Path) { path.op(path, part, PathOperation.Union) }
    regions.forEach { region ->
        val neighbor = regions.firstOrNull { other -> other !== region && other.progress > region.progress &&
            max(other.bounds.left - region.bounds.right, region.bounds.left - other.bounds.right) <= gap &&
            max(other.bounds.top - region.bounds.bottom, region.bounds.top - other.bounds.bottom) <= gap }
        val r = if (neighbor == null) region.bounds else {
            val t = region.progress.coerceIn(0f, 1f)
            val attach = Offset(neighbor.bounds.center.x.coerceIn(region.bounds.left, region.bounds.right),
                neighbor.bounds.center.y.coerceIn(region.bounds.top, region.bounds.bottom))
            Rect(attach.x + (region.bounds.left - attach.x) * t, attach.y + (region.bounds.top - attach.y) * t,
                attach.x + (region.bounds.right - attach.x) * t, attach.y + (region.bounds.bottom - attach.y) * t)
        }
        val radius = min(gap * 1.5f, min(r.width, r.height) / 2f) * region.progress.coerceIn(0f, 1f)
        add(Path().apply { addRoundRect(RoundRect(r, CornerRadius(radius))) })
    }
    regions.forEachIndexed { i, a -> regions.drop(i + 1).forEach { b ->
        val xGap = max(a.bounds.left - b.bounds.right, b.bounds.left - a.bounds.right)
        val yGap = max(a.bounds.top - b.bounds.bottom, b.bounds.top - a.bounds.bottom)
        if (xGap > gap || yGap > gap || (xGap < 0 && yGap < 0)) return@forEach
        val strength = min(a.progress, b.progress).coerceIn(0f, 1f)
        val from = Offset(b.bounds.center.x.coerceIn(a.bounds.left, a.bounds.right), b.bounds.center.y.coerceIn(a.bounds.top, a.bounds.bottom))
        val to = Offset(a.bounds.center.x.coerceIn(b.bounds.left, b.bounds.right), a.bounds.center.y.coerceIn(b.bounds.top, b.bounds.bottom))
        val width = when {
            xGap < 0 -> min(a.bounds.right, b.bounds.right) - max(a.bounds.left, b.bounds.left)
            yGap < 0 -> min(a.bounds.bottom, b.bounds.bottom) - max(a.bounds.top, b.bounds.top)
            else -> gap * .28f
        } * strength
        if (width <= 0f) return@forEach
        if (xGap < 0 || yGap < 0) {
            // Extend through the rounded shoulders, not just across the empty gap.
            // A round line cap here is as large as half a row's width (the old circle).
            val r = gap * 1.5f
            val bridge = if (xGap < 0) Rect(max(a.bounds.left, b.bounds.left),
                min(a.bounds.bottom, b.bounds.bottom) - r,
                min(a.bounds.right, b.bounds.right), max(a.bounds.top, b.bounds.top) + r)
            else Rect(min(a.bounds.right, b.bounds.right) - r, max(a.bounds.top, b.bounds.top),
                max(a.bounds.left, b.bounds.left) + r, min(a.bounds.bottom, b.bounds.bottom))
            val center = bridge.center
            val animated = Rect(center.x + (bridge.left - center.x) * strength,
                center.y + (bridge.top - center.y) * strength,
                center.x + (bridge.right - center.x) * strength,
                center.y + (bridge.bottom - center.y) * strength)
            add(Path().apply { addRect(animated) })
            return@forEach
        }
        val insetA = (a.bounds.center - from) * .3f
        val insetB = (b.bounds.center - to) * .3f
        val bridge = android.graphics.Path().apply { moveTo(from.x + insetA.x, from.y + insetA.y); lineTo(to.x + insetB.x, to.y + insetB.y) }
        val filled = android.graphics.Path()
        android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = width; strokeCap = android.graphics.Paint.Cap.ROUND }
            .getFillPath(bridge, filled)
        add(filled.asComposePath())
    } }
    return path
}

@Composable
internal fun Modifier.selectionLens(isSelected: Boolean, partial: Boolean): Modifier {
    val scene = LocalSelectionScene.current
    val token = remember { Any() }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val progress = animateFloatAsState(if (isSelected && !partial) 1f else 0f,
        if (LocalReducedMotion.current) tween(0) else tween(360, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "Selection lens")
    val region = SelectionEntry(bounds, progress)
    SideEffect { scene?.regions?.set(token, region) }
    DisposableEffect(scene, token) { onDispose { scene?.regions?.remove(token) } }
    return semantics {
        this.selected = isSelected && !partial
        if (partial) stateDescription = "Partially selected"
    }.onGloballyPositioned { bounds = Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())) }
}
