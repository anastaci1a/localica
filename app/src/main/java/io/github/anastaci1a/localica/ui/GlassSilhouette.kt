package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation

/** A small compound control shares one outline, optical field and shadow union.
 * Keep display and sampled-resolution geometry separately; translating a control
 * never rebuilds its field. Distance pixels are only created if optics need them. */
internal data class GlassSilhouetteGeometry(val bodies: List<RoundRect>, val fieldWeight: Float = 1f,
    val circles: Pair<Offset, Offset>? = null, val rimSplit: Float = 0f, val splitInner: Float? = null)

internal class GlassSilhouette(private val describe: (Size) -> GlassSilhouetteGeometry) {
    private val frames = mutableListOf<GlassSilhouetteFrame>()
    fun frame(size: Size): GlassSilhouetteFrame {
        val next = describe(size)
        frames.firstOrNull { it.description == next }?.let { return it }
        return GlassSilhouetteFrame(next).also {
            frames += it
            if (frames.size > 2) frames.removeAt(0)
        }
    }
}

internal class GlassSilhouetteFrame(val description: GlassSilhouetteGeometry) {
    val bodies get() = description.bodies
    val path = bodies.fold(Path()) { combined, body ->
        Path.combine(PathOperation.Union, combined, Path().apply { addRoundRect(body) })
    }
    val bounds = path.getBounds()
    val geometry = LensGeometry(bounds, bounds.height / 2f)
    val field by lazy { if (description.fieldWeight > 0f && description.splitInner == null) LensDistanceMap.create(path) else null }
    fun opticalField(point: Offset): LensField {
        val analytic = description.circles?.let { centers ->
            pairedCircleLensField(point, centers, geometry.span, description.fieldWeight)
        } ?: geometry.field(point)
        val boundary = description.splitInner?.let { splitPillDistanceField(point, bounds, it) } ?: field?.sample(point)
        return boundary?.let { blendLensFields(analytic, it, description.fieldWeight) } ?: analytic
    }
}

@Composable internal fun rememberGlassSilhouette(bodies: (Size) -> List<RoundRect>): GlassSilhouette {
    val latest by rememberUpdatedState(bodies)
    return remember { GlassSilhouette { GlassSilhouetteGeometry(latest(it)) } }
}

/** Keep ordinary pill/circle optics at both ends and an exact exposed boundary
 * through the neck. Both execution adapters blend the same analytic fields. */
internal fun splitPillGeometry(size: Size, progress: Float): GlassSilhouetteGeometry {
    val p = progress.coerceIn(0f, 1f)
    val bodies = splitPillBodies(size, p)
    val raw = if (p < .25f) p * 4f else (2f - p * 4f).coerceAtLeast(0f)
    val weight = raw * raw * (3f - 2f * raw)
    val centers = if (p > .25f) {
        fun RoundRect.center() = Offset((left + right) / 2f, (top + bottom) / 2f)
        bodies.first().center() to bodies.last().center()
    } else null
    val rim = (p * 2f).coerceIn(0f, 1f)
    return GlassSilhouetteGeometry(bodies, weight, centers, rim * rim * (3f - 2f * rim),
        if (p > 0f && p < .5f) size.height * p else null)
}

@Composable internal fun rememberSplitPillSilhouette(progress: () -> Float): GlassSilhouette {
    val currentProgress by rememberUpdatedState(progress)
    return remember { GlassSilhouette { splitPillGeometry(it, currentProgress()) } }
}

/** Inner corners round first, narrowing the shared neck before a gap opens. */
internal fun splitPillBodies(size: Size, progress: Float): List<RoundRect> {
    val p = progress.coerceIn(0f, 1f)
    val radius = size.height / 2f
    if (p == 0f) return listOf(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius)))
    val gap = size.height / 6f * ((p - .5f) * 2f).coerceIn(0f, 1f)
    val half = (size.width - gap) / 2f
    // Finish closing the neck before separating the bodies. Opening a gap while
    // their inner edges are still straight would erase a finite bridge in a frame.
    val inner = CornerRadius(radius * (p * 2f).coerceIn(0f, 1f))
    val outer = CornerRadius(radius)
    return listOf(RoundRect(0f, 0f, half, size.height, outer, inner, inner, outer),
        RoundRect(half + gap, 0f, size.width, size.height, inner, outer, outer, inner))
}

/** Draw-space deformation shared by a compound body's shadow and its surface. */
internal fun RoundRect.deformed(shape: GlassShape, pivot: Offset, translation: Offset = Offset.Zero): RoundRect {
    val sx = shape.scaleX(); val sy = shape.scaleY()
    fun x(value: Float) = pivot.x + (value - pivot.x) * sx + translation.x
    fun y(value: Float) = pivot.y + (value - pivot.y) * sy + translation.y
    fun CornerRadius.scaled() = CornerRadius(this.x * sx, this.y * sy)
    return RoundRect(x(left), y(top), x(right), y(bottom), topLeftCornerRadius.scaled(),
        topRightCornerRadius.scaled(), bottomRightCornerRadius.scaled(), bottomLeftCornerRadius.scaled())
}
