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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlinx.coroutines.*

/** Reserve the same extent that the shadow renderer actually draws. */
@Composable internal fun glassShadowInset(): Dp = (6f + 28f * LocalGlassAppearance.current.shadowSpread).dp

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

/** Shadow extents belong to the page/window, not a clipped row or filter strip. */
@Composable internal fun GlassShadowScope(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val group = remember { GlassShadowGroup() }
    CompositionLocalProvider(LocalSurfaceShadows provides group) {
        androidx.compose.foundation.layout.Box(modifier.bottomGlassShadowHost(group), content = content)
    }
}

@Composable internal fun GlassShadowColumn(modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val group = remember { GlassShadowGroup() }
    CompositionLocalProvider(LocalSurfaceShadows provides group) {
        androidx.compose.foundation.layout.Column(modifier.bottomGlassShadowHost(group), content = content)
    }
}

@Composable internal fun Modifier.surfaceGlassShadow(radius: Dp?, anchor: GlassAnchor?): Modifier {
    val group = LocalSurfaceShadows.current
    val appearance = LocalGlassAppearance.current
    return if (group == null) clearGlassShadow(appearance.shadowStrength, appearance.shadowSpread, radius) { anchor?.shadowInModal != true }
        else bottomGlassShadow(radius, group = group, anchorOverride = anchor)
}

internal class GlassShadowGeometry {
    private var previous = emptyList<RoundRect>()
    private var previousReach = -1f
    private var previousShift = Float.NaN
    var body = Path(); private set
    var rings = emptyList<Path>(); private set
    fun update(bodies: List<RoundRect>, reach: Float, shift: Float) {
        if (bodies == previous && reach == previousReach && shift == previousShift) return
        previous = bodies; previousReach = reach; previousShift = shift
        fun outline(expansion: Float, y: Float = 0f): Path = Path().apply {
            // Non-zero winding fills overlapping clockwise silhouettes once. A
            // Boolean path union has the same coverage but rebuilds intersections
            // for every ring on every frame of a changing player/menu outline.
            bodies.forEach { r ->
                addRoundRect(RoundRect(r.left - expansion, r.top - expansion + y,
                    r.right + expansion, r.bottom + expansion + y,
                    CornerRadius(r.topLeftCornerRadius.x + expansion, r.topLeftCornerRadius.y + expansion)))
            }
        }
        body = outline(0f)
        rings = (16 downTo 1).map { outline(reach * it / 16f, shift) }
    }
}

/** Draw the union once: neighboring shadows never accumulate opacity. */
internal fun DrawScope.glassShadows(bodies: List<Pair<RoundRect, Float>>, strength: Float, spread: Float, cache: GlassShadowGeometry,
    excludeInterior: Boolean = true) {
    if (strength <= 0f || bodies.isEmpty()) return
    val opacity = bodies.maxOf { it.second }.coerceIn(0f, 1f)
    val reach = (4f + spread * 28f).dp.toPx()
    cache.update(bodies.map { it.first }, reach, 2.dp.toPx())
    fun DrawScope.rings() {
        cache.rings.forEachIndexed { index, ring ->
            val t = (16 - index) / 16f
            val alpha = strength * opacity * .07f * (1f - t).pow(2)
            if (alpha > 0f) drawPath(ring, Color.Black.copy(alpha = alpha))
        }
    }
    if (excludeInterior) clipPath(cache.body, ClipOp.Difference) { rings() } else rings()
}
/** Retain the complete, padded shadow texture. Scrolling an unchanged surface only
 * translates this texture; it does not replay sixteen overlapping path fills. */
internal class RetainedGlassShadow(private val scope: CoroutineScope) {
    private val geometry = GlassShadowGeometry()
    private data class Key(val bodies: List<Pair<RoundRect, Float>>, val strength: Float,
        val spread: Float, val density: Float)
    private data class Raster(val key: Key, val image: ImageBitmap, val inset: Offset)
    private var requested: Key? = null
    private var preparation: Job? = null
    private var raster by mutableStateOf<Raster?>(null)
    var rasterizations = 0; private set
    fun DrawScope.draw(bodies: List<Pair<RoundRect, Float>>, strength: Float, spread: Float) {
        if (bodies.isEmpty() || strength <= 0f) return
        val origin = Offset(bodies.minOf { it.first.left }, bodies.minOf { it.first.top })
        val normalized = bodies.map { (r, alpha) -> RoundRect(r.boundingRect.translate(-origin), r.topLeftCornerRadius) to alpha }
        val key = Key(normalized, strength, spread, density)
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
                            translate(-inset.x, -inset.y) { glassShadows(normalized, strength, spread, GlassShadowGeometry(), excludeInterior = false) }
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
        } else translate(origin.x, origin.y) { glassShadows(normalized, strength, spread, geometry) }
    }
}

@Composable private fun rememberGlassShadow(): RetainedGlassShadow {
    val scope = rememberCoroutineScope()
    return remember(scope) { RetainedGlassShadow(scope) }
}

@Composable internal fun Modifier.clearGlassShadow(strength: Float, spread: Float, radius: Dp?, visible: () -> Boolean = { true }): Modifier {
    val cache = rememberGlassShadow()
    return drawBehind {
    if (!visible()) return@drawBehind
    val corner = (radius?.toPx() ?: size.minDimension / 2).coerceIn(0f, size.minDimension / 2)
    with(cache) { draw(listOf(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner)) to 1f), strength, spread) }
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
    return onPlaced { entry.coordinates = it; androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { group.revision++ } }
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
