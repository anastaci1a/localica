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
import kotlin.math.pow

internal class GlassShadowEntry {
    var coordinates: LayoutCoordinates? = null
    var radius: Dp? = null
    var opacity: () -> Float = { 1f }
}
@Stable internal class GlassShadowGroup {
    val entries = mutableStateMapOf<Any, GlassShadowEntry>()
    var revision by mutableIntStateOf(0)
}
internal val LocalBottomShadows = staticCompositionLocalOf<GlassShadowGroup?> { null }

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
            bodies.forEach { r ->
                val other = Path().apply { addRoundRect(RoundRect(r.left - expansion, r.top - expansion + y,
                    r.right + expansion, r.bottom + expansion + y, CornerRadius(r.topLeftCornerRadius.x + expansion))) }
                op(this, other, PathOperation.Union)
            }
        }
        body = outline(0f)
        rings = (16 downTo 1).map { outline(reach * it / 16f, shift) }
    }
}

/** Draw the union once: neighboring shadows never accumulate opacity. */
internal fun DrawScope.glassShadows(bodies: List<Pair<RoundRect, Float>>, strength: Float, spread: Float, cache: GlassShadowGeometry) {
    if (strength <= 0f || bodies.isEmpty()) return
    val opacity = bodies.maxOf { it.second }.coerceIn(0f, 1f)
    val reach = (4f + spread * 28f).dp.toPx()
    cache.update(bodies.map { it.first }, reach, 2.dp.toPx())
    clipPath(cache.body, ClipOp.Difference) {
        cache.rings.forEachIndexed { index, ring ->
            val t = (16 - index) / 16f
            val alpha = strength * opacity * .07f * (1f - t).pow(2)
            if (alpha > 0f) drawPath(ring, Color.Black.copy(alpha = alpha))
        }
    }
}
internal fun Modifier.clearGlassShadow(strength: Float, spread: Float, radius: Dp?): Modifier {
    val cache = GlassShadowGeometry()
    return drawBehind {
    val corner = (radius?.toPx() ?: size.minDimension / 2).coerceIn(0f, size.minDimension / 2)
    glassShadows(listOf(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner)) to 1f), strength, spread, cache)
    }
}

@Composable internal fun Modifier.bottomGlassShadow(radius: Dp? = null, opacity: () -> Float = { 1f }): Modifier {
    val group = LocalBottomShadows.current
    val appearance = LocalGlassAppearance.current
    if (group == null) return clearGlassShadow(appearance.shadowStrength, appearance.shadowSpread, radius)
    val entry = remember { GlassShadowEntry() }
    val latest by rememberUpdatedState(opacity)
    SideEffect { entry.radius = radius; entry.opacity = { latest() }; group.entries[entry] = entry }
    DisposableEffect(group) { onDispose { group.entries.remove(entry) } }
    return onPlaced { entry.coordinates = it; androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { group.revision++ } }
}

@Composable internal fun Modifier.bottomGlassShadowHost(group: GlassShadowGroup): Modifier {
    var coordinates: LayoutCoordinates? by remember { mutableStateOf(null) }
    val appearance = LocalGlassAppearance.current
    val cache = remember { GlassShadowGeometry() }
    return onPlaced { coordinates = it }.drawWithContent {
        drawContent()
        group.revision
        val host = coordinates ?: return@drawWithContent
        val bodies = group.entries.values.mapNotNull { entry ->
            val item = entry.coordinates
            val alpha = entry.opacity()
            if (item == null || !item.isAttached || alpha <= .001f) null else {
                val rect = host.localBoundingBoxOf(item, clipBounds = false)
                val corner = (entry.radius?.toPx() ?: minOf(rect.width, rect.height) / 2).coerceIn(0f, minOf(rect.width, rect.height) / 2)
                RoundRect(rect, CornerRadius(corner)) to alpha
            }
        }
        glassShadows(bodies, appearance.shadowStrength, appearance.shadowSpread, cache)
    }
}
