package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
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
internal fun GlassControlScene(modifier: Modifier, horizontalPadding: Dp,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current,
    geometry: DrawScope.() -> LensGeometry, body: () -> GlassShape, glow: () -> GlassGlow = { GlassGlow() },
    active: () -> Float = { 1f }, face: () -> Color = { Color.Transparent },
    prepare: () -> Boolean = { false },
    scene: DrawScope.() -> Unit) {
    val rim = remember { GlassRimCache() }
    val flat = flatGlass()
    val flatColor = flatGlassColor()
    val geometryObserver = if (io.github.anastaci1a.localica.BuildConfig.DEBUG) LocalControlGeometryDraw.current else null
    val mean = remember { arrayOfNulls<Color>(1) }
    if (flat) {
        ContactGlowBacking(modifier, glow, body, false, rim, sample = { mean[0] }, geometry = { geometry().let { it.bounds to it.corner } }) {
        // No recording, source subscription, lens, reduction or exclusive lease in None.
        Canvas(Modifier.fillMaxSize()) {
            scene()
            val shape = geometry()
            geometryObserver?.invoke(shape)
            mean[0] = flatColor
            drawRoundRect(flatColor, shape.bounds.topLeft, shape.bounds.size, CornerRadius(shape.corner))
        }
        }
        return
    }
    val modern = Build.VERSION.SDK_INT >= 33
    val currentActive by rememberUpdatedState(active)
    val optical by remember { derivedStateOf { currentActive() > 0f } }
    val currentPrepare by rememberUpdatedState(prepare)
    val sampled by remember { derivedStateOf { currentActive() > 0f || currentPrepare() } }
    if (sampled) BackdropLease(source)
    val location = remember { BackdropLocation() }
    val lens = remember { lazy { if (Build.VERSION.SDK_INT >= 33) LensRenderer() else null } }
    val layer = if (optical && modern) rememberGlassLayer() else null
    val averageBitmap = if (optical && !modern) remember { arrayOfNulls<ImageBitmap>(1) } else null
    val onLensDraw = LocalControlLensDraw.current
    val opticalWork = LocalGlassOpticalWork.current
    ContactGlowBacking(modifier, glow, body, false, rim, sample = { mean[0] }, geometry = { geometry().let { it.bounds to it.corner } }) {
    Canvas(Modifier.fillMaxSize().backdropLocation(location)) {
        val shape = geometry()
        geometryObserver?.invoke(shape)
        if (!optical) {
            scene()
            mean[0] = face()
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
        val shifted = shape.recorded(1f, pad)
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
        var average = Color.Transparent
        if (layer != null && modern) {
            opticalWork?.invoke()
            layer.renderEffect = lens.value?.effect(shifted, 0f, 1.35f * thickness, unit = density, flattening = flattening)
            layer.record(Density(density, fontScale), layoutDirection, extent) { recordScene() }
        } else if (averageBitmap != null && !shape.bounds.isEmpty) {
            val region = shape.bounds
            val sceneOffset = origin - region.topLeft
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
            average = averageGlassPixels(pixels, coveredArea)
        }
        mean[0] = if (average != Color.Transparent) average else face()
        clipPath(silhouette) {
            if (layer != null) translate(origin.x, origin.y) { drawLayer(layer) } else drawRect(average)
            drawRoundRect(face(), shape.bounds.topLeft, shape.bounds.size, CornerRadius(shape.corner))
        }
    }
    }
    Canvas(modifier) { val shape = geometry(); drawGlassRim(shape.bounds, shape.corner, path = rim.roundedPath(shape.bounds, shape.corner), opacity = active(), cache = rim) }
}
