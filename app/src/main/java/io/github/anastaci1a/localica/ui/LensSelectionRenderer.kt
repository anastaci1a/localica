package io.github.anastaci1a.localica.ui

import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect

internal class LensSelectionShape {
    private var previous = emptyList<SelectionRegion>()
    private var gap = Float.NaN
    var geometry: LensGeometry? = null; private set
    var map: LensDistanceMap? = null; private set
    var rasterizations = 0; private set
    fun update(regions: List<SelectionRegion>, path: Path, joinGap: Float) {
        if (regions == previous && joinGap == gap) return
        previous = regions; gap = joinGap
        val simple = simpleLensGeometry(regions, joinGap)
        geometry = simple ?: path.getBounds().takeUnless { it.isEmpty }?.let { LensGeometry(it, joinGap * 1.5f) }
        map = if (simple == null && geometry != null) { rasterizations++; LensDistanceMap.create(path) } else null
    }
}

/** Selection only supplies a shape; the material is the same one used by controls. */
@RequiresApi(33)
internal class LensSelectionRenderer {
    private val renderer = LensRenderer()
    private val silhouette = LensSelectionShape()
    internal val rasterizations get() = silhouette.rasterizations
    fun effect(regions: List<SelectionRegion>, path: Path, joinGap: Float, origin: Offset, depth: Float, atlas: LensAtlas? = null): RenderEffect? {
        silhouette.update(regions, path, joinGap)
        val shape = silhouette.geometry ?: return null
        return renderer.effect(shape.copy(bounds = shape.bounds.translate(origin)), 0f, depth,
            map = silhouette.map, origin = origin, atlas = atlas)
    }
}
