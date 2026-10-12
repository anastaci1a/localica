package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.*

internal data class SelectionRegion(val bounds: Rect, val progress: Float)
@Stable internal class SelectionScene {
    val regions = mutableStateMapOf<Any, SelectionRegion>()
    var origin = Offset.Zero
}
internal val LocalSelectionScene = staticCompositionLocalOf<SelectionScene?> { null }

/** Record the content once; the lens samples that recording, never its own output. */
@Composable
internal fun ConnectedSelectionHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scene = remember { SelectionScene() }
    val layer = rememberGraphicsLayer()
    // Reading the registry outside nested layer recording invalidates this host on every
    // selection frame, including when all descendants otherwise reuse cached recordings.
    val regionSnapshot = scene.regions.values.toList()
    CompositionLocalProvider(LocalSelectionScene provides scene) {
        Box(modifier.onGloballyPositioned { scene.origin = it.positionInRoot() }.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            val regions = regionSnapshot.filter { it.progress > .001f }
                .map { it.copy(bounds = it.bounds.translate(-scene.origin)) }
            if (regions.isEmpty()) return@drawWithContent
            val path = connectedSelectionPath(regions, 16.dp.toPx())
            val strength = regions.maxOf { it.progress }.coerceIn(0f, 1f)
            // A shallow lens: only its narrow perimeter bends the already-rendered text/art.
            // Layer clips work on every supported Android version without bitmap readback.
            val androidOutline = android.graphics.Path()
            android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 8.dp.toPx() * strength }
                .getFillPath(path.asAndroidPath(), androidOutline)
            clipPath(path) {
                clipPath(androidOutline.asComposePath()) {
                    scale(1f + .018f * strength, 1f + .018f * strength, path.getBounds().center) { drawLayer(layer) }
                }
            }
            drawPath(path, Brush.linearGradient(listOf(Color.White.copy(alpha = .55f * strength),
                Color.White.copy(alpha = .08f * strength), Color.Black.copy(alpha = .18f * strength))), style = Stroke(.9.dp.toPx()))
        }) { content() }
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
        val radius = min(12f, min(r.width, r.height) / 5f) * region.progress.coerceIn(0f, 1f)
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
        val insetA = (a.bounds.center - from) * .12f
        val insetB = (b.bounds.center - to) * .12f
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
    val progress by animateFloatAsState(if (isSelected && !partial) 1f else 0f,
        if (LocalReducedMotion.current) tween(0) else spring(.65f, 420f), label = "Selection lens")
    // Observe animation/bounds in composition; a read only inside SideEffect does not
    // subscribe this item to animation frames and leaves the registry one tap behind.
    val region = SelectionRegion(bounds, progress)
    SideEffect { scene?.regions?.set(token, region) }
    DisposableEffect(scene, token) { onDispose { scene?.regions?.remove(token) } }
    return semantics {
        this.selected = isSelected && !partial
        if (partial) stateDescription = "Partially selected"
    }.onGloballyPositioned { bounds = Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())) }
}
