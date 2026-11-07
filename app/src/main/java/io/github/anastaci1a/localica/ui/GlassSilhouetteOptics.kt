package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import io.github.anastaci1a.localica.ui.engine.*

/** D17: one Engine shape follows the lyrics preparation phase with both bodies from rest (B1);
 * [follow] drives it from the laid-out size. The frozen split optics stay here and read its frame. */
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
    val silhouette = GlassSilhouette { splitPillOptics(bodies(it), it, progress()) }
}

@Composable internal fun rememberSplitPill(progress: () -> Float): SplitPill {
    val latest by rememberUpdatedState(progress)
    val (shape, follow) = rememberPhaseShape("split pill", { ShapeTarget(splitPillBodies(it, latest()), JoinPolicy.Union) }) { latest().let { it == 0f || it == 1f } }
    return remember(shape, follow) { SplitPill(shape, follow) { latest() } }
}

private val fieldWeight = Curve.window(0f, .25f) * (Curve.constant(1f) + Curve.window(.25f, .5f).scaled(-1f))
private val rimSplit = Curve.window(0f, .5f)

/** Keep ordinary pill/circle optics at both ends and an exact exposed boundary
 * through the neck. Both execution adapters blend the same analytic fields. */
internal fun splitPillOptics(bodies: List<RoundRect>, size: Size, progress: Float): GlassSilhouetteGeometry {
    val p = progress.coerceIn(0f, 1f)
    fun RoundRect.center() = Offset((left + right) / 2f, (top + bottom) / 2f)
    val centers = if (p > .25f && bodies.size == 2) bodies.first().center() to bodies.last().center() else null
    return GlassSilhouetteGeometry(bodies, fieldWeight.at(p), centers, rimSplit.at(p),
        if (p > 0f && p < .5f) size.height * p else null)
}
