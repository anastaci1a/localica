package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import io.github.anastaci1a.localica.ui.spec.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.*

/** Neighboring group shadows that the trigger's own window keeps drawing while a
 * popup owns the trigger's shadow. Bodies are screen-space; [origin] is the caster's
 * untransformed screen origin and [size]/[scaleX]/[scaleY]/[shift] its draw transform.
 * Each ring is drawn at [weight] where it overlaps the same neighbor ring: 0 at home
 * reproduces the shared union exactly, 1 is the popup's independent resting field. */
internal class GlassShadowExclusion(val bodies: List<RoundRect>, private val origin: Offset,
    private val size: Size, private val scaleX: Float, private val scaleY: Float, private val shift: Float,
    val weight: Float) {
    private val path = Path()
    /** Map the cached whole-neighbor-union ring into the caster's draw coordinates. */
    fun ring(index: Int, geometry: GlassShadowGeometry, count: Int, translation: Offset): Path? {
        if (bodies.isEmpty()) return null
        val local = glassReceiverInCasterSpace(Rect(-origin, Size(1f, 1f)), size, scaleX, scaleY, shift) ?: return null
        path.asAndroidPath().set(geometry.rings[count + index].asAndroidPath())
        path.asAndroidPath().transform(android.graphics.Matrix().apply {
            setScale(1f / scaleX, 1f / scaleY)
            postTranslate(local.left + translation.x, local.top + translation.y)
        })
        return path
    }
}

internal class GlassShadowGeometry {
    val layerPaint = Paint()
    private var receiverSampler: GlassShadowReceiverBrush? = null
    internal fun receiverBrush(receiver: GlassShadowReceiver): ShaderBrush {
        return (receiverSampler ?: GlassShadowReceiverBrush().also { receiverSampler = it }).get(receiver)
    }
    private var previous: Pair<Any, GlassShadowModel>? = null
    var body = Path(); private set
    var rings = emptyList<Path>(); private set
    fun update(bodies: List<RoundRect>, model: GlassShadowModel, needRings: Boolean = true,
        path: Path? = null, neighbors: List<RoundRect> = emptyList()) {
        val input = (path ?: (bodies to neighbors)) to model
        val changed = input != previous
        if (!changed && (!needRings || rings.isNotEmpty())) return
        fun outline(rectangles: List<RoundRect>, expansion: Float): Path = Path().apply {
            // Non-zero winding fills overlapping clockwise silhouettes once. Positive
            // dilation distributes over union; negative contraction must erode its boundary.
            rectangles.forEach { r ->
                fun expand(corner: CornerRadius) = CornerRadius((corner.x + expansion).coerceAtLeast(0f), (corner.y + expansion).coerceAtLeast(0f))
                if (r.width + 2f * expansion <= 0f || r.height + 2f * expansion <= 0f) return@forEach
                addRoundRect(RoundRect(r.left - expansion, r.top - expansion, r.right + expansion, r.bottom + expansion,
                    expand(r.topLeftCornerRadius), expand(r.topRightCornerRadius), expand(r.bottomRightCornerRadius), expand(r.bottomLeftCornerRadius)))
            }
        }
        if (changed) {
            previous = input
            body = path ?: outline(bodies, 0f)
            rings = emptyList()
        }
        // Uniform shadows draw primitives; materialize paths only for a path consumer.
        fun field(contour: Path, rectangles: List<RoundRect>?): List<Path> {
            // Resolve interior joins once per geometry, never once per ring per frame.
            val union = Path().apply { op(contour, Path(), PathOperation.Union) }
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = android.graphics.Paint.Join.ROUND }
            return List(model.count) { index ->
                val expansion = model.expansion(index)
                val ring = if (expansion >= 0f && rectangles != null) outline(rectangles, expansion) else {
                    paint.style = if (expansion < 0f) android.graphics.Paint.Style.STROKE else android.graphics.Paint.Style.FILL_AND_STROKE
                    paint.strokeWidth = kotlin.math.abs(expansion) * 2f
                    val boundary = Path()
                    paint.getFillPath(union.asAndroidPath(), boundary.asAndroidPath())
                    if (expansion < 0f) Path().apply { op(union, boundary, PathOperation.Difference) } else boundary
                }
                ring.apply { translate(Offset(0f, model.shift)) }
            }
        }
        if (needRings) rings = field(body, bodies.takeIf { path == null }) +
            if (neighbors.isEmpty()) emptyList() else field(outline(neighbors, 0f), neighbors)
    }
}

private fun half(value: Float): Float {
    val unit = Math.scalb(1f, maxOf(-24, Math.getExponent(value) - 10))
    return Math.rint((value / unit).toDouble()).toFloat() * unit
}
internal fun glassShadowAlpha(distance: Float, strength: Float): Float {
    val v = half((1f / GlassShadowMaskRadius) * half(distance))
    val x = half(half(4f * half(half(v * .25f) + .5f).coerceIn(0f, 1f)) - 2f)
    val q = half(x * x)
    val p = half(half(half(.0029544830322265625f * q - .034454345703125f) * q + .168212890625f) * q - .560546875f)
    return half(half(GlassShadowFill) * half(half(p * x + .5f) * .5f)) * strength
}
/** Sample the complete physical support to one fixed shift inside the shifted body.
 * The 128-ring cap bounds work; settled fields still use one retained texture. */
internal data class GlassShadowModel(val density: Float, val spread: Float) {
    val radius get() = GlassShadowMaskRadius * spread * density
    val reach get() = 2f * radius
    val shift get() = GlassShadowShift * density
    val inset get() = reach + shift
    val count get() = ceil(inset).toInt().coerceIn(16, 128)
    fun expansion(index: Int): Float = reach - inset * (index + .5f) / count
    fun alpha(distance: Float, strength: Float): Float = glassShadowAlpha(
        if (spread == 0f) { if (distance >= 0f) 2f * GlassShadowMaskRadius else -2f * GlassShadowMaskRadius }
        else distance / (density * spread), strength)
    fun coverage(step: Int, strength: Float, count: Int = this.count): Int =
        (alpha(reach - inset * step / count, strength) * 255f).roundToInt().coerceIn(0, 255)
    fun ringAlpha(index: Int, strength: Float, count: Int = this.count): Float {
        val previous = coverage(index, strength, count)
        val next = coverage(index + 1, strength, count)
        // Quantize cumulative coverage, not each tiny increment: otherwise low-alpha
        // outer rings all round to zero before they can form the visible tail.
        return if (previous == 255) 0f else
            (255f * (next - previous) / (255 - previous)).roundToInt() / 255f
    }
}
/** Draw the union once: neighboring shadows never accumulate opacity. */
internal fun DrawScope.glassShadows(bodies: List<Pair<RoundRect, Float>>, strength: Float, spread: Float, cache: GlassShadowGeometry,
    excludeInterior: Boolean = true, receiver: GlassShadowReceiver? = null,
    exclusion: GlassShadowExclusion? = null, exclusionTranslation: Offset = Offset.Zero, color: Color = Color.Black,
    rasterizing: Boolean = false, path: Path? = null) {
    if (strength <= 0f || bodies.isEmpty() && path == null) return
    val opacity = (bodies.maxOfOrNull { it.second } ?: 1f).coerceIn(0f, 1f)
    val model = GlassShadowModel(density, spread)
    val reach = model.reach; val shift = model.shift
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
    cache.update(outlines, model, needRings = uniform == null || !exclusion?.bodies.isNullOrEmpty(),
        path = path, neighbors = exclusion?.bodies.orEmpty())
    fun DrawScope.ring(index: Int, alpha: Float) {
        val tint = color.copy(alpha = alpha)
        if (uniform == null) {
            val ring = cache.rings[index]
            if (brush == null) drawPath(ring, tint, blendMode = blend) else drawPath(ring, brush, alpha = alpha)
        } else {
            val expansion = model.expansion(index)
            val left = uniform.left - expansion
            val top = uniform.top - expansion + shift
            val right = uniform.right + expansion
            val bottom = uniform.bottom + expansion + shift
            if (right <= left || bottom <= top) return
            val corner = CornerRadius((uniform.topLeftCornerRadius.x + expansion).coerceAtLeast(0f),
                (uniform.topLeftCornerRadius.y + expansion).coerceAtLeast(0f))
            if (brush == null) drawRoundRect(tint, Offset(left, top), Size(right - left, bottom - top), corner, blendMode = blend)
            else drawRoundRect(brush, Offset(left, top), Size(right-left,bottom-top), corner, alpha = alpha)
        }
    }
    fun DrawScope.rings() {
        val count = model.count
        repeat(count) { index ->
            val alpha = if (cumulative) model.coverage(index + 1, strength * opacity) / 255f
                else model.ringAlpha(index, strength * opacity)
            if (alpha > 0f) {
                // Where a neighbor's identical ring is already drawn below, apply
                // that ring once (weight 0, the shared union) up to independently.
                val shared = exclusion?.ring(index, cache, count, exclusionTranslation)
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
            val bounds = (path?.getBounds() ?: Rect(outlines.minOf { it.left }, outlines.minOf { it.top },
                outlines.maxOf { it.right }, outlines.maxOf { it.bottom })).inflate(reach + shift + 1f)
            canvas.saveLayer(bounds, cache.layerPaint)
        }
        try { rings() } finally { if (!rasterizing) canvas.restore() }
    }
    // Resolve the cutout in destination coordinates, before the temporary layer.
    // Legacy clipping inside that layer rounds a few contour pixels differently
    // from the cached texture's destination clip, exposing a handoff fringe.
    if (excludeInterior) clipPath(cache.body, ClipOp.Difference) { field() } else field()
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
                glassShadowReach(spread).dp.toPx(), GlassShadowShift.dp.toPx())) {
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
        val (normalized, strength, spread, _, color, outline) = key
        val bounds = outline?.getBounds() ?: Rect(0f,0f,normalized.maxOf { it.first.right },normalized.maxOf { it.first.bottom })
        fun DrawScope.paintShadow(excludeInterior: Boolean, rasterizing: Boolean = false) =
            glassShadows(normalized,strength,spread,if (rasterizing) GlassShadowGeometry() else geometry,
                excludeInterior = !rasterizing && excludeInterior,exclusion = if (rasterizing) null else exclusion,exclusionTranslation = -origin,color = color,
                rasterizing = rasterizing,path = outline)
        if (key != requested) {
            requested = key
            preparation?.cancel()
            val padding = glassShadowInset(spread).toPx() + 2f
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
            if (outline == null) geometry.update(normalized.map { it.first }, GlassShadowModel(density, spread), needRings = false)
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

@Composable internal fun rememberGlassShadow(): RetainedGlassShadow {
    val scope = rememberCoroutineScope()
    return remember(scope) { RetainedGlassShadow(scope) }
}
