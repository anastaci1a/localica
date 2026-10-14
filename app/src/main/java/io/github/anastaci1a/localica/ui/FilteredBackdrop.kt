package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/** Allocation dimensions only. Optical bounds and clipping retain their exact size. */
internal fun glassLayerExtent(size: IntSize): IntSize {
    fun capacity(value: Int) = if (value <= 0) 0 else ((value + 63) / 64) * 64
    return IntSize(capacity(size.width), capacity(size.height))
}

/** Filter only a bounded low-resolution backing; sharp foregrounds never enter this pass. */
@Composable
internal fun FilteredBackdrop(modifier: Modifier, source: FrostedBackdrop, origin: () -> Offset,
    resolution: Float, padding: Float = 0f, homeSource: FrostedBackdrop? = null,
    homeWeight: () -> Float = { 0f }, average: MaterialAverageStyle? = null,
    homeBounds: (() -> androidx.compose.ui.geometry.Rect)? = null,
    effect: (Size, Float, Float) -> RenderEffect?) {
    BackdropLease(source)
    BackdropLease(homeSource?.takeUnless { it === source })
    val currentOrigin by rememberUpdatedState(origin)
    val layer = rememberGraphicsLayer()
    val primary = if (homeSource != null) rememberGraphicsLayer() else null
    val home = if (primary != null) rememberGraphicsLayer() else null
    val canUseAverage = average != null && (source.materialOnly || homeSource?.materialOnly == true)
    val averageRenderer = remember(canUseAverage) { if (android.os.Build.VERSION.SDK_INT >= 33 && canUseAverage) MaterialAverageRenderer() else null }
    val rawAverageRenderer = remember(canUseAverage) { if (android.os.Build.VERSION.SDK_INT >= 33 && canUseAverage) MaterialAverageRenderer() else null }
    val averageSource = if (average != null && source.materialOnly) rememberGraphicsLayer() else null
    val averageHome = if (average != null && homeSource?.materialOnly == true) rememberGraphicsLayer() else null
    layer.clip = true
    // Isolate unchanged filters from ancestor recordings; source RenderNode changes
    // still invalidate the sampled result, while sibling motion reuses this drawing.
    Canvas(modifier.graphicsLayer()) {
        // A list may load after this floating control's first draw. Observe the
        // first recording explicitly: GraphicsLayer.size is not snapshot state,
        // and returning before drawLayer otherwise installs no dependency.
        if (!source.recorded || source.layer.isReleased || source.layer.size.width <= 0) return@Canvas
        val blend = if (homeSource != null && !homeSource.layer.isReleased && homeSource.layer.size.width > 0)
            homeWeight().coerceIn(0f, 1f) else 0f
        val at = currentOrigin()
        val homeFootprint = homeBounds?.invoke()?.takeUnless { it.isEmpty } ?: androidx.compose.ui.geometry.Rect(at, size)
        val flatSource = homeSource == null && averageRenderer != null && source.canAverage(at, size)
        val flatHome = averageRenderer != null && homeSource?.canAverage(homeFootprint.topLeft, homeFootprint.size) == true
        val flatWeight = (if (flatSource) 1f - blend else 0f) + (if (flatHome) blend else 0f)
        val entirelyFlat = flatWeight >= 1f
        val viewport = size
        fun androidx.compose.ui.graphics.drawscope.DrawScope.crop(backing: FrostedBackdrop, isHome: Boolean = false) {
            val footprint = if (isHome) homeFootprint else androidx.compose.ui.geometry.Rect(at, viewport)
            clipRect(right = 8f, bottom = 8f) {
                scale(8f / footprint.width, 8f / footprint.height, Offset.Zero) {
                    val delta = backing.origin - footprint.topLeft
                    translate(delta.x, delta.y) { drawLayer(backing.layer) }
                }
            }
        }
        if (entirelyFlat && averageRenderer != null && average != null && android.os.Build.VERSION.SDK_INT >= 33) {
            layer.renderEffect = averageRenderer.effect(average)
            if (blend > 0f && blend < 1f && primary != null && home != null && homeSource != null) {
                primary.record(size = IntSize(8, 8)) { crop(source) }
                home.record(size = IntSize(8, 8)) { crop(homeSource, true) }
                primary.alpha = 1f - blend; home.alpha = blend
                home.blendMode = androidx.compose.ui.graphics.BlendMode.Plus
            }
            layer.record(size = IntSize(8, 8)) {
                if (blend >= 1f && homeSource != null) crop(homeSource, true)
                else if (blend > 0f && primary != null && home != null) { drawLayer(primary); drawLayer(home) }
                else crop(source)
            }
            scale(viewport.width / 8f, viewport.height / 8f, Offset.Zero) { drawLayer(layer) }
            return@Canvas
        }
        val r = resolution.coerceIn(.25f, 1f)
        val extent = IntSize(ceil((size.width + 2 * padding) * r).toInt().coerceAtLeast(1),
            ceil((size.height + 2 * padding) * r).toInt().coerceAtLeast(1))
        val allocation = glassLayerExtent(extent)
        layer.renderEffect = effect(Size(extent.width.toFloat(), extent.height.toFloat()), r, flatWeight)
        // A morph keeps its averaged home source while gradually admitting the detailed
        // destination. Endpoint appearance and return handoff use the same color mapping.
        if (android.os.Build.VERSION.SDK_INT >= 33 && rawAverageRenderer != null) {
            if (flatSource && averageSource != null) {
                averageSource.renderEffect = rawAverageRenderer.effect()
                averageSource.record(size = IntSize(8, 8)) { crop(source) }
            }
            if (flatHome && averageHome != null && homeSource != null) {
                averageHome.renderEffect = rawAverageRenderer.effect()
                averageHome.record(size = IntSize(8, 8)) { crop(homeSource, true) }
            }
        }
        fun androidx.compose.ui.graphics.drawscope.DrawScope.scene(backing: FrostedBackdrop, isHome: Boolean = false) {
            val flat = if (isHome && flatHome) averageHome else if (!isHome && flatSource) averageSource else null
            if (flat != null) {
                scale(extent.width / 8f, extent.height / 8f, Offset.Zero) { drawLayer(flat) }
                return
            }
            clipRect { scale(r, r, Offset.Zero) {
                // Refraction/blur can reach beyond a nested panel's recorded box.
                // Supply its real parent there, instead of transparent edge bands.
                val chain = mutableListOf<FrostedBackdrop>()
                var item: FrostedBackdrop? = backing
                while (item != null && item !in chain && chain.size < 8) { chain += item; item = item.parent }
                chain.asReversed().forEach { scene ->
                    if (scene.recorded && !scene.layer.isReleased) {
                        val delta = scene.origin - currentOrigin() + Offset(padding, padding)
                        if (scene === backing) translate(delta.x, delta.y) { drawLayer(scene.layer) }
                        else {
                            val inside = backing.origin - currentOrigin() + Offset(padding, padding)
                            clipRect(inside.x, inside.y, inside.x + backing.size.width, inside.y + backing.size.height,
                                androidx.compose.ui.graphics.ClipOp.Difference) {
                                translate(delta.x, delta.y) { drawLayer(scene.layer) }
                            }
                        }
                    }
                }
            } }
        }
        if (primary != null && home != null && homeSource != null && blend > 0f && blend < 1f) {
            primary.record(size = allocation) { scene(source) }
            home.record(size = allocation) { scene(homeSource, true) }
            primary.alpha = 1f - blend; home.alpha = blend
            home.blendMode = androidx.compose.ui.graphics.BlendMode.Plus
        }
        layer.record(size = allocation) {
            if (blend >= 1f && homeSource != null) scene(homeSource, true)
            else if (blend > 0f && primary != null && home != null) { drawLayer(primary); drawLayer(home) }
            else scene(source)
        }
        translate(-padding, -padding) { scale(1f / r, 1f / r, Offset.Zero) { drawLayer(layer) } }
    }
}
