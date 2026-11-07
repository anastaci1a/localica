package io.github.anastaci1a.localica.ui

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

/** Draw the track once. Only the lens replaces pixels with the sampled track/backdrop.
 * A settled white thumb has no recording, backdrop subscription or optical renderer. */
@Composable
internal fun GlassControlScene(modifier: Modifier, horizontalPadding: Dp = 20.dp,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current,
    geometry: DrawScope.() -> LensGeometry, body: () -> GlassShape,
    active: () -> Float = { 1f }, face: () -> Color = { Color.Transparent },
    prepare: () -> Boolean = { false },
    scene: DrawScope.() -> Unit) {
    val renderer = currentGlassRendererGeneration()
    val modern = Build.VERSION.SDK_INT >= 33
    val none by androidx.compose.animation.core.animateFloatAsState(
        if (renderer.backend == GlassBackend.None) 1f else 0f,
        androidx.compose.animation.core.spring(1f, 450f), label = "Control render mode")
    val currentActive by rememberUpdatedState(active)
    val optical by remember { derivedStateOf { currentActive() > 0f } }
    val currentPrepare by rememberUpdatedState(prepare)
    val sampled by remember { derivedStateOf { currentActive() > 0f || currentPrepare() } }
    if (sampled) BackdropLease(source)
    val location = remember { BackdropLocation() }
    val lens = remember { lazy { if (Build.VERSION.SDK_INT >= 33) LensRenderer() else null } }
    val averageRenderer = remember { if (Build.VERSION.SDK_INT >= 33) MaterialAverageRenderer() else null }
    val averageLayer = if (optical && modern) rememberGraphicsLayer() else null
    val rim = remember { GlassRimCache() }
    val layer = if (optical && modern) rememberGraphicsLayer() else null
    val averageBitmap = if (optical && !modern) remember { ImageBitmap(8, 8) } else null
    val onLensDraw = LocalControlLensDraw.current
    val opticalWork = LocalGlassOpticalWork.current
    Canvas(modifier.backdropLocation(location)) {
        val shape = geometry()
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
            if (averageLayer != null && averageRenderer != null && Build.VERSION.SDK_INT >= 33) {
                averageLayer.renderEffect = averageRenderer.effect()
                averageLayer.alpha = none
                averageLayer.blendMode = if (blending) BlendMode.Plus else BlendMode.SrcOver
                averageLayer.record(Density(density, fontScale), layoutDirection, IntSize(8, 8)) {
                    scale(8f / shape.bounds.width, 8f / shape.bounds.height, Offset.Zero) {
                        translate(-pad, -pad) { recordScene() }
                    }
                }
                clipPath(silhouette) { translate(shape.bounds.left, shape.bounds.top) {
                    scale(shape.bounds.width / 8f, shape.bounds.height / 8f, Offset.Zero) { drawLayer(averageLayer) }
                } }
            } else {
                if (averageBitmap != null) {
                    averageBitmap.asAndroidBitmap().eraseColor(android.graphics.Color.TRANSPARENT)
                    CanvasDrawScope().draw(Density(density, fontScale), layoutDirection, Canvas(averageBitmap), Size(8f, 8f)) {
                        scale(8f / shape.bounds.width, 8f / shape.bounds.height, Offset.Zero) {
                            translate(-pad, -pad) { recordScene() }
                        }
                    }
                    val image = averageBitmap.asAndroidBitmap()
                    val average = averageGlassColor { x, y -> image.getPixel(x, y) }
                    clipPath(silhouette) { drawRect(average, alpha = none) }
                }
            }
        }
        if (blending) drawContext.canvas.restore()
        drawRoundRect(face(), shape.bounds.topLeft, shape.bounds.size, CornerRadius(shape.corner))
        drawGlassRim(shape.bounds, shape.corner, path = silhouette, opacity = active(), cache = rim)
    }
}
