package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import io.github.anastaci1a.localica.navigation.GlassAppearance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** Hover-lift selection (#533): tuning shared by the list host, drag cards and tests. */
internal object HoverLift {
    /** Select and deselect use the same ease-out, matching the 220 ms selection panel. */
    const val Duration = 220
    /** An item eats this share of the gap to an unselected neighbour (0.75x remains). */
    const val GapShare = .25f
    /** Across the scroll axis, the share of the distance to the viewport edge. */
    const val EdgeShare = .5f
    val MaxLift = 4.dp
    /** Growth never leaves less than this to a neighbour's content. */
    val Clearance = 1.dp
    /** Frost blur radius at full lift (Normal renderer). */
    val Blur = 6.dp
}

/**
 * The lift shadow under selected and floating items (#553) is the Glass shadow: its spread exactly, a
 * multiple of its strength (the Glass setting "Lift shadow strength", 50-150%), and a white glow instead of
 * a dark shadow where the menu's ink is light. Shadow and glow share the strength.
 */
@Immutable internal data class LiftShadow(val strength: Float, val spread: Float, val color: Color)

internal fun liftShadow(glass: GlassAppearance, lightInk: Boolean) = LiftShadow(
    glass.shadowStrength * glass.liftShadowStrength, glass.shadowSpread, if (lightInk) Color.White else Color.Black)

/** Reads the menu's theming: light content ink means a light-icon (dark) menu. [ink] defaults to the
 * local content ink. A surface composed outside its source's theming (the foreground drag overlay)
 * passes the source's captured ink, so a lifted item keeps one glow or shadow from press to drop. */
@Composable internal fun rememberLiftShadow(ink: Color = Color.Unspecified): LiftShadow {
    val glass = LocalGlassAppearance.current
    val lightInk = ink.takeOrElse { LocalContentColor.current }.luminance() > .5f
    return remember(glass.shadowStrength, glass.shadowSpread, glass.liftShadowStrength, lightInk) { liftShadow(glass, lightInk) }
}

internal fun hoverMotionSpec(reducedMotion: Boolean) =
    tween<Float>(if (reducedMotion) 0 else HoverLift.Duration, easing = LinearOutSlowInEasing)

/** Which viewport edges limit growth: only those across the scrolling axis. */
internal enum class LiftEdges { Horizontal, Vertical }

/**
 * One hovering item. [rest] and [lifted] share coordinates; [scale] applies to the item's
 * own drawing about [pivot]. [progress] is the eased hover amount used for shadow and surface.
 */
@Immutable internal data class HoverGeometry(val rest: MediaItemOutline, val lifted: MediaItemOutline, val room: Rect,
    val scale: Float, val pivot: Offset, val progress: Float) {
    fun translate(offset: Offset) = HoverGeometry(rest.translate(offset), lifted.translate(offset), room, scale, pivot + offset, progress)
}

/** Per-side growth room in pixels: a share of the gap to the nearest neighbour on that side, or of the
 * distance to a limiting viewport edge, capped at [cap]. Neighbours are measured at their CONTENT
 * (their outline shrunk by [neighbourInset]): an unselected neighbour shows no outline and adjacent
 * selections merge. Growth never leaves less than [clearance] to that content. */
internal fun hoverRoom(rest: Rect, neighbours: List<Rect>, viewport: Rect?, edges: LiftEdges, cap: Float,
    neighbourInset: Float = 0f, clearance: Float = 0f): Rect {
    var left = Float.POSITIVE_INFINITY; var right = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY; var bottom = Float.POSITIVE_INFINITY
    for (r in neighbours) {
        if (r == rest) continue
        val rows = r.top < rest.bottom && r.bottom > rest.top
        val columns = r.left < rest.right && r.right > rest.left
        val c = if (neighbourInset > 0f) r.deflate(neighbourInset) else r
        if (rows && r.right <= rest.left + .5f) left = min(left, rest.left - c.right)
        if (rows && r.left >= rest.right - .5f) right = min(right, c.left - rest.right)
        if (columns && r.bottom <= rest.top + .5f) top = min(top, rest.top - c.bottom)
        if (columns && r.top >= rest.bottom - .5f) bottom = min(bottom, c.top - rest.bottom)
    }
    fun share(gap: Float) = if (gap.isInfinite()) cap else min(gap * HoverLift.GapShare, (gap - clearance).coerceAtLeast(0f)).coerceIn(0f, cap)
    var room = Rect(share(left), share(top), share(right), share(bottom))
    if (viewport != null) {
        fun edge(distance: Float, current: Float) = min(current, (distance * HoverLift.EdgeShare).coerceAtLeast(0f))
        room = if (edges == LiftEdges.Horizontal) room.copy(left = edge(rest.left - viewport.left, room.left),
            right = edge(viewport.right - rest.right, room.right))
        else room.copy(top = edge(rest.top - viewport.top, room.top), bottom = edge(viewport.bottom - rest.bottom, room.bottom))
    }
    return room
}

/** Apply one uniform transform to outline and content. The tighter axis limits growth,
 * preserving shared artwork/outline corner centres without entering a neighbour. */
internal fun hoverGeometry(rest: MediaItemOutline, room: Rect, progress: Float): HoverGeometry {
    val p = progress.coerceIn(0f, 1f)
    val b = rest.bounds
    val ex = min(room.left, room.right).coerceAtLeast(0f) * p
    val ey = min(room.top, room.bottom).coerceAtLeast(0f) * p
    val scale = if (b.width <= 0f || b.height <= 0f) 1f else
        max(1f, min((b.width + 2f * ex) / b.width, (b.height + 2f * ey) / b.height))
    return hoverGeometryAtScale(rest, room, p, scale)
}

/** The outline and every content pass use this same uniform transform. */
internal fun hoverGeometryAtScale(rest: MediaItemOutline, room: Rect, progress: Float, scale: Float): HoverGeometry {
    val b = rest.bounds
    if (scale == 1f) return HoverGeometry(rest, rest, room, 1f, b.center, progress.coerceIn(0f, 1f))
    val half = Offset(b.width * scale / 2f, b.height * scale / 2f)
    val lifted = MediaItemOutline(Rect(b.center - half, b.center + half), rest.corner * scale)
    return HoverGeometry(rest, lifted, room, scale, b.center, progress.coerceIn(0f, 1f))
}

/** Reflow can replace neighbouring layout slots before their presented geometry changes.
 * Start from the captured transform, then resolve the current layout's transform on the
 * same progress as its parts. Independent selection animation keeps its own live phase. */
internal fun interpolateHoverGeometry(source: HoverGeometry, current: HoverGeometry, progress: Float): HoverGeometry {
    val p = progress.coerceIn(0f, 1f)
    if (p == 1f) return current
    val sourceScale = if (source.progress > 0f)
        1f + (source.scale - 1f) * (current.progress / source.progress)
    else hoverGeometry(source.rest, source.room, current.progress).scale
    return hoverGeometryAtScale(current.rest, current.room, current.progress,
        sourceScale + (current.scale - sourceScale) * p)
}

/** Maps a group's rest bounds onto its lifted bounds: the same backdrop region, magnified. */
internal fun hoverMagnification(rest: Rect, lifted: Rect): Pair<Offset, Offset> {
    val sx = if (rest.width > 0f) lifted.width / rest.width else 1f
    val sy = if (rest.height > 0f) lifted.height / rest.height else 1f
    return Offset(sx, sy) to Offset(lifted.left - rest.left * sx, lifted.top - rest.top * sy)
}

/**
 * Bridge strength between two dragged cards. Neighbours at rest stay joined while they leave
 * (narrowing as either flies); neighbours at their destinations join as both land. Both
 * phases are zero at the stack, so the hand-over between them is continuous.
 */
internal fun hoverBridgeStrength(adjacentAtRest: Boolean, adjacentAtDestination: Boolean,
    flight: Pair<Float, Float>, landing: Pair<Float, Float>, progress: Pair<Float, Float>): Float {
    val leaving = if (adjacentAtRest) min(1f - flight.first, 1f - flight.second).coerceIn(0f, 1f) *
        (1f - max(landing.first, landing.second)).coerceIn(0f, 1f) else 0f
    val arriving = if (adjacentAtDestination) min(landing.first, landing.second).coerceIn(0f, 1f) else 0f
    return max(leaving, arriving) * min(progress.first, progress.second).coerceIn(0f, 1f)
}
