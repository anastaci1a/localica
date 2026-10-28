package io.github.anastaci1a.localica.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Stable, unmasked ink coordinates and the independently moving displayed slot. */
internal data class SelectorInkSlot(val source: Rect, val displayed: Rect) {
    init {
        require(source.width > 0f && source.height > 0f && displayed.width > 0f && displayed.height > 0f)
    }
    fun sourceAt(point: Offset) = Offset(
        source.left + (point.x - displayed.left) * source.width / displayed.width,
        source.top + (point.y - displayed.top) * source.height / displayed.height)
}

internal data class SelectorSceneSample(
    val surface: Offset,
    val backdrop: Offset,
    val shellRay: LensRay?,
    val selectorRay: LensRay,
    val ink: Offset?,
    val selectedInk: Boolean,
)

/** All coordinates are in one fixed host, before either lens or a slot inverse transform.
 * A backdrop never inherits the slot stretch. Selection is evaluated at the refracted
 * point, not baked into the atlas or tested at the output pixel.
 */
internal data class SelectorSceneGeometry(
    val shell: LensGeometry,
    val selector: LensGeometry,
    val slots: List<SelectorInkSlot>,
    val shellDepth: Float,
    val selectorDepth: Float,
    val shellFlattening: Float = 0f,
    val selectorFlattening: Float = 0f,
) {
    // Each immutable scene prepares material constants once, not twice per mesh vertex.
    // Data-class copy() reconstructs these when either geometry/material changes.
    private val shellOptics = LensOpticalProfile.create(shell.span, shellDepth, shellFlattening)
    private val selectorOptics = LensOpticalProfile.create(selector.span, selectorDepth, selectorFlattening)

    fun sample(point: Offset, refractInk: Boolean, extendBoundary: Boolean = false): SelectorSceneSample {
        val field = selector.field(point)
        // Boundary triangles can straddle the final clip. Extend their ray instead
        // of interpolating a zero displacement into the visible rounded edge.
        val selectorRay = lensRay(if (extendBoundary) field.copy(distance = field.distance.coerceAtLeast(0f)) else field,
            selectorOptics)
        val surface = point + selectorRay.offset
        val shellField = shell.field(surface)
        val shellRay = if (shellField.distance >= 0f)
            lensRay(shellField, shellOptics) else null
        val ink = if (refractInk && shellRay != null)
            slots.firstOrNull { it.displayed.contains(surface) }?.sourceAt(surface) else null
        return SelectorSceneSample(surface, surface + (shellRay?.offset ?: Offset.Zero),
            shellRay, selectorRay, ink, ink != null && selector.field(surface).distance >= 0f)
    }
}
