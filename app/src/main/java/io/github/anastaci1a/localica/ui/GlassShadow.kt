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
import kotlin.math.roundToInt
import kotlin.math.exp
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
    var radiusReader: (() -> Dp?)? = null
    var opacity: () -> Float = { 1f }
    var anchor: GlassAnchor? = null
    var view: android.view.View? = null

    /** The drawn, shape-deformed body in [rect]'s space, or null when it casts nothing. */
    fun body(rect: Rect, density: Density): RoundRect? {
        if (opacity() <= .001f || anchor?.shadowInModal == true) return null
        val corner = ((radiusReader?.invoke() ?: radius)?.let { with(density) { it.toPx() } } ?: minOf(rect.width, rect.height) / 2)
            .coerceIn(0f, minOf(rect.width, rect.height) / 2)
        // Anchored controls deform in draw space. Their coordinates alone
        // do not include the rebound which persists after a modal returns.
        val shape = anchor?.contact?.body?.value ?: GlassShape()
        val sx = shape.scaleX(anchor?.contact?.vertical == true)
        val sy = shape.scaleY(anchor?.contact?.vertical == true)
        val half = Offset(rect.width * sx / 2f, rect.height * sy / 2f)
        return RoundRect(Rect(rect.center - half, rect.center + half), CornerRadius(corner * sx, corner * sy))
    }
}

/** Neighboring group shadows that the trigger's own window keeps drawing while a
 * popup owns the trigger's shadow. Bodies are screen-space; [origin] is the caster's
 * untransformed screen origin and [size]/[scaleX]/[scaleY]/[shift] its draw transform.
 * Each ring is drawn at [weight] where it overlaps the same neighbor ring: 0 at home
 * reproduces the shared union exactly, 1 is the popup's independent resting field. */
internal class GlassShadowExclusion(private val bodies: List<RoundRect>, private val origin: Offset,
    private val size: Size, private val scaleX: Float, private val scaleY: Float, private val shift: Float,
    val weight: Float) {
    private val path = Path()
    /** Neighbor ring [index] in caster space, matching glassShadows' ring expansion. */
    fun ring(index: Int, reach: Float, offsetY: Float, translation: Offset = Offset.Zero): Path? {
        val expansion = glassShadowRingExpansion(index, reach)
        path.reset()
        var any = false
        bodies.forEach { r ->
            val screen = Rect(r.left - expansion, r.top - expansion + offsetY,
                r.right + expansion, r.bottom + expansion + offsetY).translate(-origin)
            val local = glassReceiverInCasterSpace(screen, size, scaleX, scaleY, shift) ?: return@forEach
            val corner = r.topLeftCornerRadius
            path.addRoundRect(RoundRect(local.translate(translation),
                CornerRadius((corner.x + expansion) / scaleX, (corner.y + expansion) / scaleY)))
            any = true
        }
        return path.takeIf { any }
    }
}

/** Screen-space neighbor bodies of [anchor]'s shared shadow group, excluding the trigger. */
internal fun glassShadowNeighbors(anchor: GlassAnchor, density: Density): List<RoundRect> {
    val group = anchor.shadowGroup ?: return emptyList()
    return group.entries.values.mapNotNull { entry ->
        if (entry.anchor === anchor) return@mapNotNull null
        val item = entry.coordinates?.takeIf { it.isAttached } ?: return@mapNotNull null
        val view = entry.view ?: return@mapNotNull null
        entry.body(Rect(item.backdropPosition(view), Size(item.size.width.toFloat(), item.size.height.toFloat())), density)
    }
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

@Composable internal fun Modifier.surfaceGlassShadow(radius: Dp?, anchor: GlassAnchor?, radiusReader: (() -> Dp?)? = null): Modifier {
    val group = LocalSurfaceShadows.current
    val appearance = LocalGlassAppearance.current
    return if (group == null) clearGlassShadowImpl(appearance.shadowStrength, appearance.shadowSpread, radius, null, null, { anchor?.shadowInModal != true }, radiusReader)
        else bottomGlassShadow(radius, group = group, anchorOverride = anchor, radiusReader = radiusReader)
}

internal class GlassShadowGeometry {
    val layerPaint = Paint()
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
        if (needRings) rings = List(glassShadowRingCount(reach)) { outline(glassShadowRingExpansion(it, reach), shift) }
    }
}

/** One sample per physical pixel prevents broad, visible terraces at high density.
 * The integrated cubic optical density has zero slope at the outer edge and keeps
 * the former field's total opacity. Settled fields still use one retained texture. */
internal fun glassShadowRingCount(reach: Float): Int = ceil(reach).toInt().coerceIn(16, 128)
internal fun glassShadowRingExpansion(index: Int, reach: Float): Float =
    reach * (1f - (index + .5f) / glassShadowRingCount(reach))
private fun glassShadowRingCoverage(step: Int, count: Int, strength: Float): Int {
    val u = step.toFloat() / count
    return ((1f - exp(-.34594453f * strength * u * u * u)) * 255f).roundToInt().coerceIn(0, 255)
}
internal fun glassShadowRingAlpha(index: Int, count: Int, strength: Float): Float {
    val previous = glassShadowRingCoverage(index, count, strength)
    val next = glassShadowRingCoverage(index + 1, count, strength)
    // Quantize cumulative coverage, not each tiny increment: otherwise low-alpha
    // outer rings all round to zero before they can form the visible tail.
    return if (previous == 255) 0f else
        (255f * (next - previous) / (255 - previous)).roundToInt() / 255f
}

/** Draw the union once: neighboring shadows never accumulate opacity. */
internal fun DrawScope.glassShadows(bodies: List<Pair<RoundRect, Float>>, strength: Float, spread: Float, cache: GlassShadowGeometry,
    excludeInterior: Boolean = true, receiver: GlassShadowReceiver? = null,
    exclusion: GlassShadowExclusion? = null, exclusionTranslation: Offset = Offset.Zero, color: Color = Color.Black,
    rasterizing: Boolean = false) {
    if (strength <= 0f || bodies.isEmpty()) return
    val opacity = bodies.maxOf { it.second }.coerceIn(0f, 1f)
    val reach = (4f + spread * 28f).dp.toPx()
    val shift = 2.dp.toPx()
    val outlines = bodies.map { it.first }
    val response = receiver?.takeIf { glassReceiverTouchesShadow(it,outlines,reach,shift) }
    val brush = response?.let { cache.receiverBrush(it) }
    // A retained uniform field can write its cumulative alpha directly. Src
    // avoids software source-over's repeated truncation of small increments.
    val cumulative = rasterizing && response == null
    val blend = if (cumulative) BlendMode.Src else BlendMode.SrcOver
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
    fun DrawScope.ring(index: Int, alpha: Float) {
        val tint = color.copy(alpha = alpha)
        if (uniform == null) {
            val ring = cache.rings[index]
            if (brush == null) drawPath(ring, tint, blendMode = blend) else drawPath(ring, brush, alpha = alpha)
        } else {
            val expansion = glassShadowRingExpansion(index, reach)
            val left = uniform.left - expansion
            val top = uniform.top - expansion + shift
            val right = uniform.right + expansion
            val bottom = uniform.bottom + expansion + shift
            val corner = CornerRadius(uniform.topLeftCornerRadius.x + expansion,
                uniform.topLeftCornerRadius.y + expansion)
            if (brush == null) drawRoundRect(tint, Offset(left, top), Size(right - left, bottom - top), corner, blendMode = blend)
            else drawRoundRect(brush, Offset(left, top), Size(right-left,bottom-top), corner, alpha = alpha)
        }
    }
    fun DrawScope.rings() {
        val count = glassShadowRingCount(reach)
        repeat(count) { index ->
            val alpha = if (cumulative) glassShadowRingCoverage(index + 1, count, strength * opacity) / 255f
                else glassShadowRingAlpha(index, count, strength * opacity)
            if (alpha > 0f) {
                // Where a neighbor's identical ring is already drawn below, apply
                // that ring once (weight 0, the shared union) up to independently.
                val shared = exclusion?.ring(index, reach, shift, exclusionTranslation)
                if (shared == null) ring(index, alpha)
                else {
                    clipPath(shared, ClipOp.Difference) { ring(index, alpha) }
                    val weighted = alpha * (exclusion?.weight ?: 1f).coerceIn(0f, 1f)
                    if (weighted > 0f) clipPath(shared) { ring(index, weighted) }
                }
            }
        }
    }
    // Live drawing and retained textures represent the same transparent
    // field. Blending every ring into the page would round differently for
    // each background and pop when the cached texture becomes ready.
    fun DrawScope.field() {
        val canvas = drawContext.canvas
        if (!rasterizing) {
            val bounds = Rect(outlines.minOf { it.left }, outlines.minOf { it.top },
                outlines.maxOf { it.right }, outlines.maxOf { it.bottom }).inflate(reach + shift + 1f)
            canvas.saveLayer(bounds, cache.layerPaint)
        }
        try { rings() } finally { if (!rasterizing) canvas.restore() }
    }
    // Resolve the cutout in destination coordinates, before the temporary layer.
    // Legacy clipping inside that layer rounds a few contour pixels differently
    // from the cached texture's destination clip, exposing a handoff fringe.
    if (excludeInterior) clipPath(cache.body, ClipOp.Difference) { field() } else field()
}
/** Paths and round rectangles share the same optical field and live/raster handoff. */
private fun DrawScope.glassPathShadow(path: Path, strength: Float, spread: Float, color: Color,
    excludeInterior: Boolean, rasterizing: Boolean = false) {
    val reach = (4f + spread * 28f).dp.toPx()
    val shift = 2.dp.toPx()
    val count = glassShadowRingCount(reach)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL_AND_STROKE
        strokeJoin = android.graphics.Paint.Join.ROUND
        if (rasterizing) xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
    }
    fun DrawScope.rings() = translate(0f, shift) {
        repeat(count) { index ->
            val alpha = if (rasterizing) glassShadowRingCoverage(index + 1, count, strength) / 255f
                else glassShadowRingAlpha(index, count, strength)
            if (alpha > 0f) {
                paint.color = color.copy(alpha = alpha).toArgb()
                paint.strokeWidth = glassShadowRingExpansion(index, reach) * 2f
                drawContext.canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
            }
        }
    }
    fun DrawScope.field() {
        val canvas = drawContext.canvas
        if (!rasterizing) canvas.saveLayer(path.getBounds().inflate(reach + shift + 1f), Paint())
        try { rings() } finally { if (!rasterizing) canvas.restore() }
    }
    // Match primitive shadows: resolve the destination cutout before the temporary field.
    if (excludeInterior) clipPath(path, ClipOp.Difference) { field() } else field()
}

/** Retain the complete, padded shadow texture. Scrolling an unchanged surface only
 * translates this texture; it does not replay overlapping path fills. */
internal class RetainedGlassShadow(private val scope: CoroutineScope) {
    private val geometry = GlassShadowGeometry()
    private data class Key(val bodies: List<Pair<RoundRect, Float>>, val strength: Float,
        val spread: Float, val density: Float, val color: Color, val path: Path? = null)
    private data class Raster(val key: Key, val image: ImageBitmap, val inset: Offset)
    private var requested: Key? = null
    private var preparation: Job? = null
    private var raster by mutableStateOf<Raster?>(null)
    var rasterizations = 0; private set
    fun DrawScope.draw(bodies: List<Pair<RoundRect, Float>>, strength: Float, spread: Float,
        receiver: GlassShadowReceiver? = null, exclusion: GlassShadowExclusion? = null, color: Color = Color.Black) {
        if (bodies.isEmpty() || strength <= 0f) return
        // Software textures change the nonlinear popup/home receiver response.
        // Keep it native and in the caller's coordinates: normalizing a fractional
        // origin also changes legacy clip coverage around an offscreen layer.
        if (receiver != null && glassReceiverTouchesShadow(receiver, bodies.map { it.first },
                (4f + spread * 28f).dp.toPx(), 2.dp.toPx())) {
            glassShadows(bodies, strength, spread, geometry, receiver = receiver, exclusion = exclusion, color = color)
            return
        }
        val origin = Offset(bodies.minOf { it.first.left }, bodies.minOf { it.first.top })
        val normalized = bodies.map { (r, alpha) -> RoundRect(r.left - origin.x, r.top - origin.y,
            r.right - origin.x, r.bottom - origin.y, r.topLeftCornerRadius, r.topRightCornerRadius,
            r.bottomRightCornerRadius, r.bottomLeftCornerRadius) to alpha }
        val key = Key(normalized, strength, spread, density, color)
        drawNormalized(key,origin,exclusion)
    }
    /** The field's normalized path is immutable until its input changes. Translation is free. */
    fun DrawScope.draw(path: Path, origin: Offset, strength: Float, spread: Float, color: Color = Color.Black) {
        if (strength <= 0f || path.isEmpty) return
        drawNormalized(Key(emptyList(),strength,spread,density,color,path),origin,null)
    }
    private fun DrawScope.drawNormalized(key: Key,origin: Offset,exclusion: GlassShadowExclusion?) {
        val normalized = key.bodies
        val strength = key.strength; val spread = key.spread; val color = key.color
        val outline = key.path
        val bounds = outline?.getBounds() ?: Rect(0f,0f,normalized.maxOf { it.first.right },normalized.maxOf { it.first.bottom })
        fun DrawScope.paintShadow(excludeInterior: Boolean, rasterizing: Boolean = false) {
            if (outline == null) {
                if (rasterizing) glassShadows(normalized,strength,spread,GlassShadowGeometry(),excludeInterior = false,color = color,rasterizing = true)
                else glassShadows(normalized,strength,spread,geometry,exclusion = exclusion,exclusionTranslation = -origin,color = color)
            } else glassPathShadow(outline,strength,spread,color,excludeInterior,rasterizing)
        }
        if (key != requested) {
            requested = key
            preparation?.cancel()
            val padding = (4f + spread * 28f).dp.toPx() + 2.dp.toPx() + 2f
            val inset = Offset(floor(bounds.left-padding), floor(bounds.top-padding))
            val extent = IntSize(ceil(bounds.right + padding - inset.x).toInt(),
                ceil(bounds.bottom + padding - inset.y).toInt())
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
                    // Write cumulative coverage without repeated software blending.
                    ImageBitmap(extent.width, extent.height).also { next ->
                        CanvasDrawScope().draw(metrics, direction, Canvas(next),
                            Size(extent.width.toFloat(), extent.height.toFloat())) {
                            translate(-inset.x, -inset.y) { paintShadow(excludeInterior = false, rasterizing = true) }
                        }
                    }
                }
                raster = Raster(key, image, inset)
                rasterizations++
            }
        }
        // A neighbor exclusion exists only while the popup is near its shared home.
        val prepared = raster?.takeIf { it.key == key && exclusion == null }
        if (prepared != null) translate(origin.x, origin.y) {
            if (outline == null) geometry.update(normalized.map { it.first }, (4f + spread * 28f).dp.toPx(), 2.dp.toPx())
            // Keep the cutout in the destination renderer. Baking a software clip
            // would change edge coverage when a fractional-position shadow moves.
            clipPath(outline ?: geometry.body, ClipOp.Difference) {
                translate(prepared.inset.x, prepared.inset.y) {
                    drawImage(prepared.image, dstSize = IntSize(prepared.image.width, prepared.image.height), filterQuality = FilterQuality.Low)
                }
            }
        } else translate(origin.x, origin.y) {
            paintShadow(excludeInterior = true)
        }
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
    clearGlassShadowImpl(strength,spread,radius,null,null,visible)

/** Receiver-aware adapter; original positional/trailing-lambda API stays unchanged. */
@Composable internal fun Modifier.clearGlassReceiverShadow(strength: Float, spread: Float, radius: Dp?,
    exclusion: () -> GlassShadowExclusion? = { null },
    receiver: () -> GlassShadowReceiver?): Modifier = clearGlassShadowImpl(strength,spread,radius,receiver,exclusion, { true })

@Composable private fun Modifier.clearGlassShadowImpl(strength: Float, spread: Float, radius: Dp?,
    receiver: (() -> GlassShadowReceiver?)?, exclusion: (() -> GlassShadowExclusion?)?, visible: () -> Boolean, radiusReader: (() -> Dp?)? = null): Modifier {
    val cache = rememberGlassShadow()
    return drawBehind {
    if (!visible()) return@drawBehind
    val corner = ((radiusReader?.invoke() ?: radius)?.toPx() ?: size.minDimension / 2).coerceIn(0f, size.minDimension / 2)
    with(cache) { draw(listOf(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner)) to 1f), strength, spread,
        receiver?.invoke(), exclusion?.invoke()) }
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
    anchorOverride: GlassAnchor? = null, radiusReader: (() -> Dp?)? = null): Modifier {
    val appearance = LocalGlassAppearance.current
    if (group == null) return clearGlassShadowImpl(appearance.shadowStrength, appearance.shadowSpread, radius, null, null, { true }, radiusReader)
    val entry = remember { GlassShadowEntry() }
    val anchor = anchorOverride ?: glassSurfaceAnchor()
    val latest by rememberUpdatedState(opacity)
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        entry.radius = radius; entry.radiusReader = radiusReader; entry.anchor = anchor; entry.opacity = { latest() }; entry.view = view
        group.entries[entry] = entry
        // A popup owning this trigger's shadow must not stack over its neighbors.
        anchor?.shadowGroup = group
    }
    DisposableEffect(group, anchor) { onDispose {
        group.entries.remove(entry)
        if (anchor?.shadowGroup === group) anchor.shadowGroup = null
    } }
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
            val item = entry.coordinates?.takeIf { it.isAttached } ?: return@mapNotNull null
            entry.body(host.localBoundingBoxOf(item, clipBounds = false), this)?.let { it to entry.opacity() }
        }
        with(cache) { draw(bodies, appearance.shadowStrength, appearance.shadowSpread) }
    }
}
