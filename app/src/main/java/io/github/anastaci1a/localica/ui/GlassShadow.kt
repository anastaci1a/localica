package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.offset
import io.github.anastaci1a.localica.ui.engine.*
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

/** The drawn, shape-deformed body in [rect]'s space, or null when it casts nothing. */
internal fun glassCasterBody(rect: Rect, density: Density, radius: Dp?, anchor: GlassAnchor?, opacity: Float = 1f): RoundRect? {
    if (opacity <= .001f || anchor?.shadowInModal == true) return null
    val corner = (radius?.let { with(density) { it.toPx() } } ?: minOf(rect.width, rect.height) / 2)
        .coerceIn(0f, minOf(rect.width, rect.height) / 2)
    // Anchored controls deform in draw space. Their coordinates alone
    // do not include the rebound which persists after a modal returns.
    val shape = anchor?.contact?.body?.value ?: GlassShape()
    val sx = shape.scaleX(anchor?.contact?.vertical == true)
    val sy = shape.scaleY(anchor?.contact?.vertical == true)
    val half = Offset(rect.width * sx / 2f, rect.height * sy / 2f)
    return RoundRect(Rect(rect.center - half, rect.center + half), CornerRadius(corner * sx, corner * sy))
}

/** Screen-space neighbor bodies on the trigger's plane, excluding the trigger. */
internal fun glassShadowNeighbors(anchor: GlassAnchor, density: Density): List<RoundRect> {
    val plane = anchor.shadowPlane ?: return emptyList()
    val origin = plane.layout?.takeIf { it.isAttached }?.backdropPosition(plane.view ?: return emptyList()) ?: return emptyList()
    return plane.casters.filter { it.key !== anchor && it.owns(plane) }.mapNotNull { (it.shape as? LayoutShape)?.laidOut()?.translate(origin) }
}
internal val LocalPageShadows = staticCompositionLocalOf<ShadowPlane?> { null }
internal val LocalSurfaceShadows = staticCompositionLocalOf<ShadowPlane?> { null }

/** Only group siblings sharing a transform, opacity and visibility lifetime.
 * Never hoist a scrolling page or modal's descendants into a window-wide group. */
@Composable internal fun GlassShadowScope(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val plane = rememberShadowPlane()
    val appearance = LocalGlassAppearance.current
    CompositionLocalProvider(LocalSurfaceShadows provides plane) {
        ShadowPlaneHost(plane, appearance.shadowStrength, appearance.shadowSpread, modifier, content)
    }
}

@Composable internal fun Modifier.surfaceGlassShadow(radius: Dp?, anchor: GlassAnchor?, group: ShadowPlane? = LocalSurfaceShadows.current, opacity: () -> Float = { 1f }): Modifier {
    val appearance = LocalGlassAppearance.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val key = anchor ?: remember { Any() }
    val plane = group ?: rememberShadowPlane()
    DisposableEffect(plane, anchor) { anchor?.shadowPlane = plane; onDispose { if (anchor?.shadowPlane === plane) anchor.shadowPlane = null } }
    val host = if (group == null) Modifier.shadowPlaneHost(plane, appearance.shadowStrength, appearance.shadowSpread) else Modifier
    return this.then(host).glassCaster(plane, key, if (group == null) ({ 1f }) else opacity, onNode = anchor?.let { owner -> { node, bound ->
            if (bound) owner.surfaceNode = node else if (owner.surfaceNode === node) owner.surfaceNode = null
        } }) {
            if (group != null) glassCasterBody(it, density, radius, anchor, opacity())
            else if (anchor?.shadowInModal == true) null else glassCasterBody(it, density, radius, null)
        }
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
    receiver: () -> GlassShadowReceiver?): Modifier = clearGlassShadowImpl(strength,spread,radius,receiver,exclusion) { true }

@Composable private fun Modifier.clearGlassShadowImpl(strength: Float, spread: Float, radius: Dp?,
    receiver: (() -> GlassShadowReceiver?)?, exclusion: (() -> GlassShadowExclusion?)?, visible: () -> Boolean): Modifier {
    val cache = rememberGlassShadow()
    return drawBehind {
    if (!visible()) return@drawBehind
    val corner = (radius?.toPx() ?: size.minDimension / 2).coerceIn(0f, size.minDimension / 2)
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

/** D26: the page's existing controls register one Engine caster on their enclosing plane. */
@Composable internal fun Modifier.pageGlassCaster(radius: Dp? = null, opacity: () -> Float = { 1f }, group: ShadowPlane? = LocalPageShadows.current,
    anchorOverride: GlassAnchor? = null): Modifier {
    val anchor = anchorOverride ?: glassSurfaceAnchor()
    return surfaceGlassShadow(radius, if (group == null) null else anchor, group, opacity)
}

/** Compatibility host for existing synthetic scenes; production uses the Engine viewport host. */
@Composable internal fun Modifier.pageShadowHost(plane: ShadowPlane): Modifier {
    val appearance = LocalGlassAppearance.current
    return shadowPlaneHost(plane, appearance.shadowStrength, appearance.shadowSpread)
}
