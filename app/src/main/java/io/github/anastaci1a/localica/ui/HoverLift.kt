package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
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
    /** Frost blur radius at full lift (Modern renderer). */
    val Blur = 6.dp
    /** Legacy renderers crossfade their prepared frosted backdrop to this opacity. */
    const val LegacyFrost = .45f
    const val ShadowStrength = .9f
    const val ShadowSpread = .35f
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

/** Per-side growth room in pixels: a share of the gap to the nearest neighbour outline on
 * that side, or of the distance to a limiting viewport edge, capped at [cap]. */
internal fun hoverRoom(rest: Rect, neighbours: List<Rect>, viewport: Rect?, edges: LiftEdges, cap: Float): Rect {
    var left = Float.POSITIVE_INFINITY; var right = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY; var bottom = Float.POSITIVE_INFINITY
    for (r in neighbours) {
        if (r == rest) continue
        val rows = r.top < rest.bottom && r.bottom > rest.top
        val columns = r.left < rest.right && r.right > rest.left
        if (rows && r.right <= rest.left + .5f) left = min(left, rest.left - r.right)
        if (rows && r.left >= rest.right - .5f) right = min(right, r.left - rest.right)
        if (columns && r.bottom <= rest.top + .5f) top = min(top, rest.top - r.bottom)
        if (columns && r.top >= rest.bottom - .5f) bottom = min(bottom, r.top - rest.bottom)
    }
    fun share(gap: Float) = if (gap.isInfinite()) cap else (gap * HoverLift.GapShare).coerceIn(0f, cap)
    var room = Rect(share(left), share(top), share(right), share(bottom))
    if (viewport != null) {
        fun edge(distance: Float, current: Float) = min(current, (distance * HoverLift.EdgeShare).coerceAtLeast(0f))
        room = if (edges == LiftEdges.Horizontal) room.copy(left = edge(rest.left - viewport.left, room.left),
            right = edge(viewport.right - rest.right, room.right))
        else room.copy(top = edge(rest.top - viewport.top, room.top), bottom = edge(viewport.bottom - rest.bottom, room.bottom))
    }
    return room
}

/** The outline grows by the symmetric room on each axis; content scales uniformly by the
 * smaller axis ratio, so it never grows past the outline. Corners stay concentric. */
internal fun hoverGeometry(rest: MediaItemOutline, room: Rect, progress: Float): HoverGeometry {
    val p = progress.coerceIn(0f, 1f)
    val b = rest.bounds
    val ex = min(room.left, room.right).coerceAtLeast(0f) * p
    val ey = min(room.top, room.bottom).coerceAtLeast(0f) * p
    val lifted = MediaItemOutline(Rect(b.left - ex, b.top - ey, b.right + ex, b.bottom + ey), rest.corner + min(ex, ey))
    val scale = if (b.width <= 0f || b.height <= 0f) 1f else min((b.width + 2f * ex) / b.width, (b.height + 2f * ey) / b.height)
    return HoverGeometry(rest, lifted, room, max(scale, 1f), b.center, p)
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
