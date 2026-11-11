package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.components.rememberMaterialAreaReduction; import io.github.anastaci1a.localica.ui.components.placeRetainedLayer
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.*
import kotlin.math.ceil

/** Optional instrumentation; no snapshot writes or work when no observer is installed. */
internal val LocalControlLensDraw = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalControlGeometryDraw = staticCompositionLocalOf<((LensGeometry) -> Unit)?> { null }

/** Draw the track once. Only the lens replaces pixels with the sampled track/backdrop.
 * A settled white thumb has no recording, backdrop subscription or optical renderer. */
@Composable
internal fun GlassControlScene(modifier: Modifier, horizontalPadding: Dp = 20.dp,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current,
    geometry: DrawScope.() -> LensGeometry, body: () -> GlassShape,
    active: () -> Float = { 1f }, face: () -> Color = { Color.Transparent },
    prepare: () -> Boolean = { false },
    scene: DrawScope.() -> Unit) {
    val flat = flatGlass()
    val flatColor = flatGlassColor()
    val geometryObserver = if (io.github.anastaci1a.localica.BuildConfig.DEBUG) LocalControlGeometryDraw.current else null
    if (flat || requestedSingleToneBlur()) {
        // No recording, source subscription, lens, reduction or exclusive lease in None.
        Canvas(modifier) {
            scene()
            val shape = geometry()
            geometryObserver?.invoke(shape)
            drawRoundRect(if (flat) flatColor else Color.White, shape.bounds.topLeft, shape.bounds.size, CornerRadius(shape.corner))
        }
        return
    }
    val renderer = currentGlassRendererGeneration()
    val modern = Build.VERSION.SDK_INT >= 33
    val none by androidx.compose.animation.core.animateFloatAsState(
        if (renderer.backend == GlassBackend.SingleToneBlur) 1f else 0f,
        androidx.compose.animation.core.spring(1f, 450f), label = "Control render mode")
    val currentActive by rememberUpdatedState(active)
    val optical by remember { derivedStateOf { currentActive() > 0f } }
    val currentPrepare by rememberUpdatedState(prepare)
    val sampled by remember { derivedStateOf { currentActive() > 0f || currentPrepare() } }
    if (sampled) BackdropLease(source)
    val location = remember { BackdropLocation() }
    val lens = remember { lazy { if (Build.VERSION.SDK_INT >= 33) LensRenderer() else null } }
    val averageRenderer = remember { if (Build.VERSION.SDK_INT >= 33) MaterialAverageRenderer(true) else null }
    val averageLayer = if (optical && modern) rememberGlassLayer() else null
    val areaReduction = if (optical && modern) rememberMaterialAreaReduction() else null
    val rim = remember { GlassRimCache() }
    val layer = if (optical && modern) rememberGlassLayer() else null
    val averageBitmap = if (optical && !modern) remember { arrayOfNulls<ImageBitmap>(1) } else null
    val onLensDraw = LocalControlLensDraw.current
    val opticalWork = LocalGlassOpticalWork.current
    Canvas(modifier.backdropLocation(location)) {
        val shape = geometry()
        geometryObserver?.invoke(shape)
        if (!optical) {
            scene()
            drawRoundRect(face(), shape.bounds.topLeft, shape.bounds.size, CornerRadius(shape.corner))
            return@Canvas
        }
        if (modern) source?.let { location.observeRecordingAcrossWindows(it) }
        val silhouette = rim.roundedPath(shape.bounds, shape.corner)
        // The capture is limited to the thumb, including reachable refraction pixels.
        val pad = horizontalPadding.toPx()
        val origin = shape.bounds.topLeft - Offset(pad, pad)
        val extent = IntSize(ceil(shape.bounds.width + pad * 2).toInt().coerceAtLeast(1),
            ceil(shape.bounds.height + pad * 2).toInt().coerceAtLeast(1))
        val shifted = shape.copy(bounds = shape.bounds.translate(-origin))
        val thickness = body().thickness
        val flattening = ((1f - thickness) / .18f).coerceIn(0f, 1f)
        val controlSize = size
        fun DrawScope.recordScene() {
            val backing = source
            if (backing != null) {
                val delta = backing.origin - location.position() - origin
                if (modern && backing.recorded && !backing.layer.isReleased) {
                    if (io.github.anastaci1a.localica.BuildConfig.DEBUG) BackdropRecordingAncestry.reference(backing)
                    translate(delta.x, delta.y) { drawLayer(backing.layer) }
                } else backing.snapshot?.let { image -> translate(delta.x, delta.y) {
                    // The shared reduction retains transparent power-of-two padding.
                    // Keep its source-pixel basis; fitting it to size compresses the ink.
                    clipRect(right = backing.size.width.toFloat(), bottom = backing.size.height.toFloat()) {
                        drawImage(image, dstSize = backing.snapshotExtent)
                    }
                } }
            }
            // scene() uses the original control's geometry, not the small capture's size.
            inset(-origin.x, -origin.y, size.width - controlSize.width + origin.x,
                size.height - controlSize.height + origin.y) { scene() }
        }
        clipPath(silhouette, ClipOp.Difference) { scene() }
        onLensDraw?.invoke()
        val blending = none > 0f && none < 1f
        if (blending) drawContext.canvas.saveLayer(shape.bounds, Paint())
        if (layer != null && modern && none < 1f) {
            opticalWork?.invoke()
            layer.alpha = 1f - none
            layer.renderEffect = lens.value?.effect(shifted, 0f, 1.35f * thickness, flattening = flattening)
            layer.record(Density(density, fontScale), layoutDirection, extent) { recordScene() }
            clipPath(silhouette) { translate(origin.x, origin.y) { drawLayer(layer) } }
        }
        if (none > 0f) {
            // None uniformly averages the material's own full bounds.
            val region = shape.bounds
            val sceneOffset = origin - region.topLeft
            if (averageLayer != null && averageRenderer != null && areaReduction != null && Build.VERSION.SDK_INT >= 33) {
                val reduced = with(areaReduction) { recordFullBounds(region.size) {
                    translate(sceneOffset.x, sceneOffset.y) { recordScene() }
                } }
                averageLayer.renderEffect = averageRenderer.effect()
                averageLayer.alpha = none
                averageLayer.blendMode = if (blending) BlendMode.Plus else BlendMode.SrcOver
                averageLayer.record(Density(density, fontScale), layoutDirection, IntSize(1, 1)) { placeRetainedLayer(reduced) }
                clipPath(silhouette) { translate(shape.bounds.left, shape.bounds.top) {
                    scale(shape.bounds.width, shape.bounds.height, Offset.Zero) { placeRetainedLayer(averageLayer) }
                } }
            } else if (averageBitmap != null && !region.isEmpty) {
                // Software path: rasterize the region at full resolution (bounded) and
                // take its exact pixel mean.
                val factor = minOf(1f, 96f / maxOf(region.width, region.height))
                val bitmapSize = IntSize(ceil(region.width * factor).toInt().coerceAtLeast(1),
                    ceil(region.height * factor).toInt().coerceAtLeast(1))
                val bitmap = averageBitmap[0]?.takeIf { it.width == bitmapSize.width && it.height == bitmapSize.height }
                    ?: ImageBitmap(bitmapSize.width, bitmapSize.height).also { averageBitmap[0] = it }
                bitmap.asAndroidBitmap().eraseColor(android.graphics.Color.TRANSPARENT)
                CanvasDrawScope().draw(Density(density, fontScale), layoutDirection, Canvas(bitmap),
                    Size(bitmapSize.width.toFloat(), bitmapSize.height.toFloat())) {
                    scale(factor, factor, Offset.Zero) {
                        clipRect(right = region.width, bottom = region.height) {
                            translate(sceneOffset.x, sceneOffset.y) { recordScene() }
                        }
                    }
                }
                val image = bitmap.asAndroidBitmap()
                val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
                val coveredArea = region.width * region.height * factor * factor
                val average = averageGlassPixels(pixels, coveredArea)
                clipPath(silhouette) { drawRect(average, alpha = none) }
            }
        }
        if (blending) drawContext.canvas.restore()
        drawRoundRect(face(), shape.bounds.topLeft, shape.bounds.size, CornerRadius(shape.corner))
        drawGlassRim(shape.bounds, shape.corner, path = silhouette, opacity = active(), cache = rim)
    }
}
