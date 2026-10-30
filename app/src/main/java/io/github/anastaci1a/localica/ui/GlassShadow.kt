package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.offset
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlinx.coroutines.*

/** Reserve the same extent that the shadow renderer actually draws. */
@Composable internal fun glassShadowInset(): Dp = (6f + 28f * LocalGlassAppearance.current.shadowSpread).dp

/** Lambda offset uses a surface-sized placement layer, clipping overflowing shadows
 * on legacy Android even without an alpha layer. Keep its placement-phase reads,
 * dimensions and RTL behavior, but draw into the enclosing window's display list. */
internal fun Modifier.glassShadowOffset(offset: Density.() -> IntOffset): Modifier = layout { measurable, constraints ->
    val child = measurable.measure(constraints)
    layout(child.width, child.height) {
        val position = offset()
        child.placeRelative(position.x, position.y)
    }
}

/** Expand a scrolling strip's clip, without changing its content's layout slot.
 * Pair with matching inner padding after horizontalScroll. */
internal fun Modifier.shadowViewportOutset(inset: Dp, horizontal: Boolean = false): Modifier = layout { measurable, constraints ->
    val pad = inset.roundToPx()
    val side = if (horizontal) pad else 0
    val child = measurable.measure(constraints.offset(horizontal = side * 2, vertical = pad * 2))
    layout((child.width - side * 2).coerceAtLeast(0), (child.height - pad * 2).coerceAtLeast(0)) { child.place(-side, -pad) }
}

internal class GlassShadowEntry {
    var coordinates: LayoutCoordinates? = null
    var radius: Dp? = null
    var opacity: () -> Float = { 1f }
    var anchor: GlassAnchor? = null
}
@Stable internal class GlassShadowGroup {
    val entries = mutableStateMapOf<Any, GlassShadowEntry>()
    var revision by mutableIntStateOf(0)
}
internal val LocalBottomShadows = staticCompositionLocalOf<GlassShadowGroup?> { null }
internal val LocalSurfaceShadows = staticCompositionLocalOf<GlassShadowGroup?> { null }

/** Only group siblings sharing a transform, opacity and visibility lifetime.
 * Never hoist a scrolling page or modal's descendants into a window-wide group. */
@Composable internal fun GlassShadowScope(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val group = remember { GlassShadowGroup() }
    CompositionLocalProvider(LocalSurfaceShadows provides group) {
        androidx.compose.foundation.layout.Box(modifier.bottomGlassShadowHost(group), content = content)
    }
}

@Composable internal fun Modifier.surfaceGlassShadow(radius: Dp?, anchor: GlassAnchor?): Modifier {
    val group = LocalSurfaceShadows.current
    val appearance = LocalGlassAppearance.current
    return if (group == null) clearGlassShadow(appearance.shadowStrength, appearance.shadowSpread, radius) { anchor?.shadowInModal != true }
        else bottomGlassShadow(radius, group = group, anchorOverride = anchor)
}

internal class GlassShadowGeometry {
    private var receiverSampler: GlassShadowReceiverBrush? = null
    internal fun receiverBrush(receiver: GlassShadowReceiver): ShaderBrush {
        val sampler = receiverSampler ?: GlassShadowReceiverBrush().also { receiverSampler = it }
        return sampler.get(receiver)
    }
    private var previous = emptyList<RoundRect>()
    private var previousReach = -1f
    private var previousShift = Float.NaN
    var body = Path(); private set
    var rings = emptyList<Path>(); private set
    fun update(bodies: List<RoundRect>, reach: Float, shift: Float, needRings: Boolean = true) {
        val changed = bodies != previous || reach != previousReach || shift != previousShift
        if (!changed && (!needRings || rings.isNotEmpty())) return
        fun outline(expansion: Float, y: Float = 0f): Path = Path().apply {
            // Non-zero winding fills overlapping clockwise silhouettes once. A
            // Boolean path union has the same coverage but rebuilds intersections
            // for every ring on every frame of a changing player/menu outline.
            bodies.forEach { r ->
                fun expand(corner: CornerRadius) = CornerRadius(corner.x + expansion, corner.y + expansion)
                addRoundRect(RoundRect(r.left - expansion, r.top - expansion + y,
                    r.right + expansion, r.bottom + expansion + y,
                    expand(r.topLeftCornerRadius), expand(r.topRightCornerRadius),
                    expand(r.bottomRightCornerRadius), expand(r.bottomLeftCornerRadius)))
            }
        }
        if (changed) {
            previous = bodies; previousReach = reach; previousShift = shift
            body = outline(0f)
            rings = emptyList()
        }
        // Uniform shadows draw primitives; materialize paths only for a path consumer.
        if (needRings) rings = (16 downTo 1).map { outline(reach * it / 16f, shift) }
    }
}

/** Draw the union once: neighboring shadows never accumulate opacity. */
internal fun DrawScope.glassShadows(bodies: List<Pair<RoundRect, Float>>, strength: Float, spread: Float, cache: GlassShadowGeometry,
    excludeInterior: Boolean = true, receiver: GlassShadowReceiver? = null) {
    if (strength <= 0f || bodies.isEmpty()) return
    val opacity = bodies.maxOf { it.second }.coerceIn(0f, 1f)
    val reach = (4f + spread * 28f).dp.toPx()
    val shift = 2.dp.toPx()
    val outlines = bodies.map { it.first }
    val response = receiver?.takeIf { glassReceiverTouchesShadow(it,outlines,reach,shift) }
    val brush = response?.let { cache.receiverBrush(it) }
    // One uniform, already-valid outline needs no generic path fill. Keep the
    // original path for clipping and for shapes requiring radius normalization:
    // platform rectXY and per-corner path normalization need not be equivalent.
    val uniform = bodies.singleOrNull()?.first?.takeIf { r ->
        val corner = r.topLeftCornerRadius
        corner == r.topRightCornerRadius && corner == r.bottomRightCornerRadius &&
            corner == r.bottomLeftCornerRadius &&
            listOf(r.left, r.top, r.right, r.bottom, r.width, r.height, corner.x, corner.y, reach, shift,
                r.left - reach, r.top - reach + shift, r.right + reach, r.bottom + reach + shift,
                (r.right + reach) - (r.left - reach), (r.bottom + reach + shift) - (r.top - reach + shift),
                corner.x + reach, corner.y + reach).all { it.isFinite() } &&
            r.width > 0f && r.height > 0f && reach >= 0f &&
            corner.x >= 0f && corner.y >= 0f && corner.x <= r.width / 2f && corner.y <= r.height / 2f
    }
    cache.update(outlines, reach, shift, needRings = uniform == null)
    fun DrawScope.rings() {
        repeat(16) { index ->
            val t = (16 - index) / 16f
            val alpha = strength * opacity * .07f * (1f - t).pow(2)
            if (alpha > 0f) {
                val color = Color.Black.copy(alpha = alpha)
                if (uniform == null) {
                    val ring = cache.rings[index]
                    if (brush == null) drawPath(ring, color) else drawPath(ring, brush, alpha = alpha)
                } else {
                    val expansion = reach * (16 - index) / 16f
                    val left = uniform.left - expansion
                    val top = uniform.top - expansion + shift
                    val right = uniform.right + expansion
                    val bottom = uniform.bottom + expansion + shift
                    val corner = CornerRadius(uniform.topLeftCornerRadius.x + expansion,
                        uniform.topLeftCornerRadius.y + expansion)
                    if (brush == null) drawRoundRect(color, Offset(left, top), Size(right - left, bottom - top), corner)
                    else drawRoundRect(brush, Offset(left, top), Size(right-left,bottom-top), corner, alpha = alpha)
                }
            }
        }
    }
    if (excludeInterior) clipPath(cache.body, ClipOp.Difference) { rings() } else rings()
}
/** Retain the complete, padded shadow texture. Scrolling an unchanged surface only
 * translates this texture; it does not replay sixteen overlapping path fills. */
internal class RetainedGlassShadow(private val scope: CoroutineScope) {
    private val geometry = GlassShadowGeometry()
    private data class Key(val bodies: List<Pair<RoundRect, Float>>, val strength: Float,
        val spread: Float, val density: Float, val receiver: GlassShadowReceiver?)
    private data class Raster(val key: Key, val image: ImageBitmap, val inset: Offset)
    private var requested: Key? = null
    private var preparation: Job? = null
    private var raster by mutableStateOf<Raster?>(null)
    var rasterizations = 0; private set
    fun DrawScope.draw(bodies: List<Pair<RoundRect, Float>>, strength: Float, spread: Float,
        receiver: GlassShadowReceiver? = null) {
        if (bodies.isEmpty() || strength <= 0f) return
        val origin = Offset(bodies.minOf { it.first.left }, bodies.minOf { it.first.top })
        val normalized = bodies.map { (r, alpha) -> RoundRect(r.left - origin.x, r.top - origin.y,
            r.right - origin.x, r.bottom - origin.y, r.topLeftCornerRadius, r.topRightCornerRadius,
            r.bottomRightCornerRadius, r.bottomLeftCornerRadius) to alpha }
        val response = receiver?.let { GlassShadowReceiver(it.bounds.translate(-origin),it.mask,it.sampleBounds.translate(-origin)) }
            ?.takeIf { glassReceiverTouchesShadow(it,normalized.map { body -> body.first },
                (4f+spread*28f).dp.toPx(),2.dp.toPx()) }
        val key = Key(normalized, strength, spread, density, response)
        if (key != requested) {
            requested = key
            preparation?.cancel()
            val padding = (4f + spread * 28f).dp.toPx() + 2.dp.toPx() + 2f
            val inset = Offset(floor(-padding), floor(-padding))
            val extent = IntSize(ceil(normalized.maxOf { it.first.right } + padding - inset.x).toInt(),
                ceil(normalized.maxOf { it.first.bottom } + padding - inset.y).toInt())
            // A retained RenderNode is a display list, not a guaranteed cached
            // raster: traces still showed every shadow ring drawn during scrolling.
            // Prepare settled geometry off Main. While the outline is morphing,
            // draw that exact current outline directly instead of allocating a
            // window-sized bitmap every frame or stretching an obsolete shadow.
            val metrics = androidx.compose.ui.unit.Density(density, fontScale)
            val direction = layoutDirection
            preparation = scope.launch {
                delay(48)
                val image = withContext(Dispatchers.Default) {
                    // Preserve the low-opacity accumulation instead of rounding
                    // every ring into an 8-bit transparent intermediate.
                    ImageBitmap(extent.width, extent.height, ImageBitmapConfig.F16).also { next ->
                        CanvasDrawScope().draw(metrics, direction, Canvas(next),
                            Size(extent.width.toFloat(), extent.height.toFloat())) {
                            translate(-inset.x, -inset.y) { glassShadows(normalized, strength, spread, GlassShadowGeometry(), excludeInterior = false, receiver = key.receiver) }
                        }
                    }
                }
                raster = Raster(key, image, inset)
                rasterizations++
            }
        }
        val prepared = raster?.takeIf { it.key == key }
        if (prepared != null) translate(origin.x, origin.y) {
            geometry.update(normalized.map { it.first }, (4f + spread * 28f).dp.toPx(), 2.dp.toPx())
            // Keep the cutout in the destination renderer. Baking a software clip
            // would change edge coverage when a fractional-position shadow moves.
            clipPath(geometry.body, ClipOp.Difference) {
                translate(prepared.inset.x, prepared.inset.y) {
                    drawImage(prepared.image, dstSize = IntSize(prepared.image.width, prepared.image.height), filterQuality = FilterQuality.Low)
                }
            }
        } else translate(origin.x, origin.y) { glassShadows(normalized, strength, spread, geometry, receiver = key.receiver) }
    }
}

@Composable private fun rememberGlassShadow(): RetainedGlassShadow {
    val scope = rememberCoroutineScope()
    return remember(scope) { RetainedGlassShadow(scope) }
}

/** Keep visibility compositing outside the surface clip without a tight ViewLayer.
 * A popup-sized graphicsLayer clips its overflowing shadow on legacy Android.
 * Binary visibility needs no layer. Fractional opacity keeps the entire padded
 * field in one alpha operation, including overlapping rings and foreground. */
@Composable internal fun Modifier.glassShadowOpacity(opacity: () -> Float): Modifier {
    val inset = glassShadowInset()
    val paint = remember { Paint() }
    return drawWithContent {
        val alpha = opacity().coerceIn(0f, 1f)
        if (alpha <= 0f) return@drawWithContent
        if (alpha >= 1f) drawContent()
        else {
            val padding = inset.toPx() + 2f
            paint.alpha = alpha
            drawContext.canvas.saveLayer(Rect(-padding, -padding, size.width + padding, size.height + padding), paint)
            try { drawContent() } finally { drawContext.canvas.restore() }
        }
    }
}

@Composable internal fun Modifier.clearGlassShadow(strength: Float, spread: Float, radius: Dp?, visible: () -> Boolean = { true }): Modifier =
    clearGlassShadowImpl(strength,spread,radius,null,visible)

/** Receiver-aware adapter; original positional/trailing-lambda API stays unchanged. */
@Composable internal fun Modifier.clearGlassReceiverShadow(strength: Float, spread: Float, radius: Dp?,
    receiver: () -> GlassShadowReceiver?): Modifier = clearGlassShadowImpl(strength,spread,radius,receiver) { true }

@Composable private fun Modifier.clearGlassShadowImpl(strength: Float, spread: Float, radius: Dp?,
    receiver: (() -> GlassShadowReceiver?)?, visible: () -> Boolean): Modifier {
    val cache = rememberGlassShadow()
    return drawBehind {
    if (!visible()) return@drawBehind
    val corner = (radius?.toPx() ?: size.minDimension / 2).coerceIn(0f, size.minDimension / 2)
    with(cache) { draw(listOf(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner)) to 1f), strength, spread, receiver?.invoke()) }
    }
}

@Composable internal fun Modifier.silhouetteGlassShadow(shape: GlassSilhouette): Modifier {
    val cache = rememberGlassShadow()
    val appearance = LocalGlassAppearance.current
    return drawBehind {
        with(cache) { draw(shape.frame(size).bodies.map { it to 1f }, appearance.shadowStrength, appearance.shadowSpread) }
    }
}

@Composable internal fun Modifier.bottomGlassShadow(radius: Dp? = null, opacity: () -> Float = { 1f }, group: GlassShadowGroup? = LocalBottomShadows.current,
    anchorOverride: GlassAnchor? = null): Modifier {
    val appearance = LocalGlassAppearance.current
    if (group == null) return clearGlassShadow(appearance.shadowStrength, appearance.shadowSpread, radius)
    val entry = remember { GlassShadowEntry() }
    val anchor = anchorOverride ?: glassSurfaceAnchor()
    val latest by rememberUpdatedState(opacity)
    SideEffect { entry.radius = radius; entry.anchor = anchor; entry.opacity = { latest() }; group.entries[entry] = entry }
    DisposableEffect(group) { onDispose { group.entries.remove(entry) } }
    return onGloballyPositioned { entry.coordinates = it; androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { group.revision++ } }
}

@Composable internal fun Modifier.bottomGlassShadowHost(group: GlassShadowGroup): Modifier {
    var coordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    val appearance = LocalGlassAppearance.current
    val cache = rememberGlassShadow()
    return onPlaced { coordinates = it }.drawWithContent {
        drawContent()
        group.revision
        val host = coordinates ?: return@drawWithContent
        val bodies = group.entries.values.mapNotNull { entry ->
            val item = entry.coordinates
            val alpha = entry.opacity()
            val anchor = entry.anchor
            if (item == null || !item.isAttached || alpha <= .001f || anchor?.shadowInModal == true) null else {
                val rect = host.localBoundingBoxOf(item, clipBounds = false)
                val corner = (entry.radius?.toPx() ?: minOf(rect.width, rect.height) / 2).coerceIn(0f, minOf(rect.width, rect.height) / 2)
                // Anchored controls deform in draw space. Their coordinates alone
                // do not include the rebound which persists after a modal returns.
                val shape = anchor?.contact?.body?.value ?: GlassShape()
                val sx = shape.scaleX(anchor?.contact?.vertical == true)
                val sy = shape.scaleY(anchor?.contact?.vertical == true)
                val half = Offset(rect.width * sx / 2f, rect.height * sy / 2f)
                RoundRect(Rect(rect.center - half, rect.center + half), CornerRadius(corner * sx, corner * sy)) to alpha
            }
        }
        with(cache) { draw(bodies, appearance.shadowStrength, appearance.shadowSpread) }
    }
}

