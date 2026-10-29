package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/** Passive observation of the actual modern Canvas sampling choice. */
internal data class HomeBackdropSamplingFrame(val footprint: HomeBackdropFootprint?,
    val cornersInSource: List<Offset>?, val averaged: Boolean, val weight: Float)
internal val LocalHomeBackdropSamplingObserver = staticCompositionLocalOf<((HomeBackdropSamplingFrame) -> Unit)?> { null }

/** Allocation dimensions only. Optical bounds and clipping retain their exact size. */
internal fun glassLayerExtent(size: IntSize): IntSize {
    fun capacity(value: Int) = if (value <= 0) 0 else ((value + 63) / 64) * 64
    return IntSize(capacity(size.width), capacity(size.height))
}

/** Filter only a bounded low-resolution backing; sharp foregrounds never enter this pass. */
@Composable
internal fun FilteredBackdrop(modifier: Modifier, source: FrostedBackdrop, location: BackdropLocation,
    resolution: Float, padding: Float = 0f, homeSource: FrostedBackdrop? = null,
    homeWeight: () -> Float = { 0f }, average: MaterialAverageStyle? = null,
    homeBounds: (() -> androidx.compose.ui.geometry.Rect)? = null,
    homeFootprint: (() -> HomeBackdropFootprint?)? = null,
    effect: (Size, Float, Float) -> RenderEffect?) {
    val samplingObserver by rememberUpdatedState(LocalHomeBackdropSamplingObserver.current)
    val drawObserver = LocalBackdropSamplingObserver.current
    BackdropLease(source)
    BackdropLease(homeSource?.takeUnless { it === source })
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
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val blend = if (homeSource != null) homeWeight().coerceIn(0f, 1f) else 0f
        // A requested home is not interchangeable with the primary during an
        // epoch handoff. Observe readiness here so its first current record can
        // invalidate this draw, rather than silently changing the requested mix.
        if (blend > 0f && (homeSource == null || !homeSource.recorded || homeSource.layer.isReleased ||
                homeSource.layer.size.width <= 0 || homeSource.layer.size.height <= 0)) return@Canvas
        val sourceToLocal = source.location.registrationTo(location) ?: return@Canvas
        val localToSource = sourceToLocal.inverse() ?: return@Canvas
        val viewportBounds = Rect(Offset.Zero, size)
        val homeToLocal = homeSource?.location?.registrationTo(location)
        if (blend > 0f && homeToLocal == null) return@Canvas
        // A measured trigger retains its actual affine basis, independently of morph geometry.
        // Only callers without a physical trigger use the legacy logical bounds.
        val footprint = homeFootprint?.invoke()
        if (blend > 0f && homeFootprint != null && footprint == null) return@Canvas
        val legacyBounds = if (homeFootprint == null) homeBounds?.invoke()?.takeUnless { it.isEmpty } else null
        if (legacyBounds != null && !listOf(legacyBounds.left, legacyBounds.top,
                legacyBounds.right, legacyBounds.bottom).all { it.isFinite() }) return@Canvas
        val homeToScreen = homeSource?.location?.registrationToScreen()
        val homeToFootprint = when {
            footprint != null -> footprint.sourceToLocal(homeToScreen)
            legacyBounds != null -> homeToScreen?.followedBy(
                BackdropRegistration.create(1f, 0f, 0f, 1f, -legacyBounds.left, -legacyBounds.top)!!)
            else -> homeToLocal
        }
        if (blend > 0f && homeToFootprint == null) return@Canvas
        val homeExtent = footprint?.size ?: legacyBounds?.size ?: size
        if (!homeExtent.width.isFinite() || !homeExtent.height.isFinite() ||
            homeExtent.width <= 0f || homeExtent.height <= 0f) return@Canvas
        val homeCorners = if (footprint != null) footprint.cornersInSource(homeToScreen)
            else homeToFootprint?.inverse()?.corners(Rect(Offset.Zero, homeExtent))
        val flatSource = homeSource == null && averageRenderer != null && source.canAverage(localToSource.corners(viewportBounds))
        val flatHome = averageRenderer != null && homeSource != null && homeCorners?.let(homeSource::canAverage) == true
        samplingObserver?.let { observer ->
            androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                observer(HomeBackdropSamplingFrame(footprint, homeCorners, flatHome, blend))
            }
        }
        drawObserver?.let { observeBackdropSampling(it, location, localToSource, viewportBounds) }
        val flatWeight = (if (flatSource) 1f - blend else 0f) + (if (flatHome) blend else 0f)
        val entirelyFlat = flatWeight >= 1f
        val viewport = size
        fun androidx.compose.ui.graphics.drawscope.DrawScope.crop(backing: FrostedBackdrop, isHome: Boolean = false) {
            val registration = (if (isHome) homeToFootprint else sourceToLocal) ?: return
            val footprint = if (isHome) homeExtent else viewport
            clipRect(right = 8f, bottom = 8f) {
                scale(8f / footprint.width, 8f / footprint.height, Offset.Zero) {
                    withBackdropRegistration(registration) { drawLayer(backing.layer) }
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
                val backingToLocal = (if (isHome) homeToLocal else sourceToLocal) ?: return@scale
                val boundary = backingToLocal.corners(Rect(0f, 0f, backing.size.width.toFloat(), backing.size.height.toFloat()))
                val inside = Path().apply {
                    moveTo(boundary[0].x, boundary[0].y)
                    boundary.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                chain.asReversed().forEach { scene ->
                    if (scene.recorded && !scene.layer.isReleased) {
                        val registration = if (scene === backing) backingToLocal else scene.location.registrationTo(location)
                        if (registration != null) translate(padding, padding) {
                            if (scene === backing) withBackdropRegistration(registration) { drawLayer(scene.layer) }
                            else clipPath(inside, ClipOp.Difference) {
                                withBackdropRegistration(registration) { drawLayer(scene.layer) }
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
