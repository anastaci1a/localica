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
            if (screen.width <= 0f || screen.height <= 0f) return@forEach
            path.addRoundRect(RoundRect(local.translate(translation),
                CornerRadius((corner.x + expansion).coerceAtLeast(0f) / scaleX, (corner.y + expansion).coerceAtLeast(0f) / scaleY)))
            any = true
        }
        return path.takeIf { any }
    }
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
            bodies.forEach { r ->
                fun expand(corner: CornerRadius) = CornerRadius((corner.x + expansion).coerceAtLeast(0f), (corner.y + expansion).coerceAtLeast(0f))
                if (r.width + 2f * expansion <= 0f || r.height + 2f * expansion <= 0f) return@forEach
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
        if (needRings) rings = List(glassShadowRingCount(reach)) { outline(glassShadowRingExpansion(it, reach), shift) }
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
/** The fixed cap bounds work; rings exceed one pixel when 7 * reach / 6 > 128. */
internal fun glassShadowRingCount(reach: Float): Int = ceil(reach * (7f / 6f)).toInt().coerceIn(16, 128)
internal fun glassShadowRingExpansion(index: Int, reach: Float): Float =
    reach * (1f - (7f / 6f) * (index + .5f) / glassShadowRingCount(reach))
private fun glassShadowRingCoverage(step: Int, count: Int, strength: Float): Int =
    (glassShadowAlpha(48f - 56f * step / count, strength) * 255f).roundToInt().coerceIn(0, 255)
internal fun glassShadowRingAlpha(index: Int, count: Int, strength: Float): Float {
    val previous = glassShadowRingCoverage(index, count, strength)
    val next = glassShadowRingCoverage(index + 1, count, strength)
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
    val reach = glassShadowReach(spread).dp.toPx()
    val shift = GlassShadowShift.dp.toPx()
    val outlines = bodies.map { it.first }
    val response = receiver?.takeIf { glassReceiverTouchesShadow(it,outlines,reach,shift) }
    val brush = response?.let { cache.receiverBrush(it) }
    val cumulative = rasterizing && response == null
    val blend = if (cumulative) BlendMode.Src else BlendMode.SrcOver
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
            if (right <= left || bottom <= top) return
            val corner = CornerRadius((uniform.topLeftCornerRadius.x + expansion).coerceAtLeast(0f),
                (uniform.topLeftCornerRadius.y + expansion).coerceAtLeast(0f))
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
    fun DrawScope.field() {
        val canvas = drawContext.canvas
        if (!rasterizing) {
            val bounds = Rect(outlines.minOf { it.left }, outlines.minOf { it.top },
                outlines.maxOf { it.right }, outlines.maxOf { it.bottom }).inflate(reach + shift + 1f)
            canvas.saveLayer(bounds, cache.layerPaint)
        }
        try { rings() } finally { if (!rasterizing) canvas.restore() }
    }
    if (excludeInterior) clipPath(cache.body, ClipOp.Difference) { field() } else field()
}
/** Paths and round rectangles share the same optical field and live/raster handoff. */
private fun DrawScope.glassPathShadow(path: Path, strength: Float, spread: Float, color: Color,
    excludeInterior: Boolean, rasterizing: Boolean = false) {
    val reach = glassShadowReach(spread).dp.toPx()
    val shift = GlassShadowShift.dp.toPx()
    val count = glassShadowRingCount(reach)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL_AND_STROKE
        strokeJoin = android.graphics.Paint.Join.ROUND
        if (rasterizing) xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
    }
    val contracted = android.graphics.Path()
    val boundary = android.graphics.Path()
    fun DrawScope.rings() = translate(0f, shift) {
        repeat(count) { index ->
            val alpha = if (rasterizing) glassShadowRingCoverage(index + 1, count, strength) / 255f
                else glassShadowRingAlpha(index, count, strength)
            if (alpha > 0f) {
                paint.color = color.copy(alpha = alpha).toArgb()
                val expansion = glassShadowRingExpansion(index, reach)
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = kotlin.math.abs(expansion) * 2f
                paint.getFillPath(path.asAndroidPath(), boundary)
                contracted.op(path.asAndroidPath(), boundary, if (expansion < 0f) android.graphics.Path.Op.DIFFERENCE else android.graphics.Path.Op.UNION)
                paint.style = android.graphics.Paint.Style.FILL
                drawContext.canvas.nativeCanvas.drawPath(contracted, paint)
            }
        }
    }
    fun DrawScope.field() {
        val canvas = drawContext.canvas
        if (!rasterizing) canvas.saveLayer(path.getBounds().inflate(reach + shift + 1f), Paint())
        try { rings() } finally { if (!rasterizing) canvas.restore() }
    }
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
            val padding = glassShadowInset(spread).toPx() + 2f
            val inset = Offset(floor(bounds.left-padding), floor(bounds.top-padding))
            val extent = IntSize(ceil(bounds.right + padding - inset.x).toInt(),
                ceil(bounds.bottom + padding - inset.y).toInt())
            val metrics = androidx.compose.ui.unit.Density(density, fontScale)
            val direction = layoutDirection
            preparation = scope.launch {
                delay(48)
                val image = withContext(Dispatchers.Default) {
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
        val prepared = raster?.takeIf { it.key == key && exclusion == null }
        if (prepared != null) translate(origin.x, origin.y) {
            if (outline == null) geometry.update(normalized.map { it.first }, glassShadowReach(spread).dp.toPx(), GlassShadowShift.dp.toPx())
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
