package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import io.github.anastaci1a.localica.ui.engine.*

/** D17: one Engine shape follows the lyrics preparation phase with both bodies from rest (B1);
 * [follow] drives it from the laid-out size. The lens reads the same bodies as its clip. */
internal class SplitPill(val shape: ShapeView, val follow: Modifier, private val progress: () -> Float) {
    /** Solid bodies at the sampled [size]; the backdrop may sample below layout resolution. */
    fun bodies(size: Size): List<RoundRect> {
        val frame = shape.outline(); val b = frame.bounds
        val sx = size.width / b.width; val sy = size.height / b.height
        fun x(v: Float) = (v - b.left) * sx
        fun y(v: Float) = (v - b.top) * sy
        fun CornerRadius.scaled() = CornerRadius(this.x * sx, this.y * sy)
        // Bounds enclose the solid bodies; with none there is nothing to scale. Scaling by one is exact.
        return frame.bodies.filter { it.width > 0f && it.height > 0f }.map {
            RoundRect(x(it.left), y(it.top), x(it.right), y(it.bottom), it.topLeftCornerRadius.scaled(),
                it.topRightCornerRadius.scaled(), it.bottomRightCornerRadius.scaled(), it.bottomLeftCornerRadius.scaled()) }
    }
    val silhouette = GlassSilhouette { splitPillOptics(bodies(it), progress()) }
}

@Composable internal fun rememberSplitPill(progress: () -> Float): SplitPill {
    val latest by rememberUpdatedState(progress)
    val (shape, follow) = rememberPhaseShape("split pill") { ShapeTarget(splitPillBodies(it, latest()), JoinPolicy.Union) }
    return remember(shape, follow) { SplitPill(shape, follow) { latest() } }
}

private val rimSplit = Curve.window(0f, .5f)

internal fun splitPillOptics(bodies: List<RoundRect>, progress: Float): GlassSilhouetteGeometry =
    GlassSilhouetteGeometry(bodies, rimSplit.at(progress.coerceIn(0f, 1f)))

internal data class LiveLensFrame(val geometry: LensGeometry, val transform: GlassBodyTransform)

internal fun LensGeometry.deformed(transform: GlassBodyTransform, pivot: Offset): LensGeometry =
    if (transform.scale == Offset(1f, 1f) && transform.drift == Offset.Zero) this
    else LensGeometry(bodies.map { it.deformed(transform, pivot) })

internal fun LensGeometry.recorded(resolution: Float, padding: Float): LensGeometry {
    val origin = bounds.topLeft
    val translation = Offset(padding - origin.x, padding - origin.y) * resolution
    return deformed(GlassBodyTransform(Offset(resolution, resolution), translation), Offset.Zero)
}
