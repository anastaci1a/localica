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
internal val LocalControlLensAllocation = staticCompositionLocalOf<((LensAllocation) -> Unit)?> { null }

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
    val modern = renderer.backend == GlassBackend.Modern && Build.VERSION.SDK_INT >= 33
    val currentActive by rememberUpdatedState(active)
    val optical by remember { derivedStateOf { currentActive() > 0f } }
    val currentPrepare by rememberUpdatedState(prepare)
    val sampled by remember { derivedStateOf { currentActive() > 0f || currentPrepare() } }
    if (sampled) BackdropLease(source)
    val location = remember { BackdropLocation() }
    val lens = remember { lazy { if (Build.VERSION.SDK_INT >= 33) LensRenderer() else null } }
    val mesh = remember { lazy { LensMeshRenderer() } }
    val rim = remember { GlassRimCache() }
    val layer = if (optical && modern) rememberGraphicsLayer() else null
    val softwareCapture = if (optical && !modern && Build.VERSION.SDK_INT < 29)
        remember { SoftwareControlCapture() } else null
    val onLensDraw = LocalControlLensDraw.current
    val onAllocation = LocalControlLensAllocation.current
    Canvas(modifier.backdropLocation(location)) {
        val shape = geometry()
        if (!optical) {
            scene()
            drawRoundRect(face(), shape.bounds.topLeft, shape.bounds.size, CornerRadius(shape.corner))
            return@Canvas
        }
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
                } else backing.snapshot?.let { image -> translate(delta.x, delta.y) { drawImage(image, dstSize = backing.size) } }
            }
            // scene() uses the original control's geometry, not the small capture's size.
            inset(-origin.x, -origin.y, size.width - controlSize.width + origin.x,
                size.height - controlSize.height + origin.y) { scene() }
        }
        clipPath(silhouette, ClipOp.Difference) { scene() }
        onLensDraw?.invoke()
        if (layer != null && modern) {
            layer.renderEffect = lens.value?.effect(shifted, 0f, 1.35f * thickness, flattening = flattening)
            layer.record(Density(density, fontScale), layoutDirection, extent) { recordScene() }
            clipPath(silhouette) { translate(origin.x, origin.y) { drawLayer(layer) } }
        } else {
            fun drawCapture(image: ImageBitmap, mutable: Boolean) {
                clipPath(silhouette) {
                    with(mesh.value) { draw(shape, image, extent, -origin, 1.35f * thickness,
                        flattening = flattening, mutableSoftwareSnapshot = mutable, onAllocation = onAllocation) }
                }
            }
            if (softwareCapture != null) {
                softwareCapture.withScene(extent, Density(density, fontScale), layoutDirection, onAllocation,
                    record = { recordScene() }, consume = { drawCapture(it, true) })
            } else {
                // Hardware mesh drawing can retain shader input beyond this call.
                val image = ImageBitmap(extent.width, extent.height)
                onAllocation?.invoke(LensAllocation.CaptureBitmap)
                CanvasDrawScope().draw(Density(density, fontScale), layoutDirection, Canvas(image),
                    Size(extent.width.toFloat(), extent.height.toFloat())) { recordScene() }
                drawCapture(image, false)
            }
        }
        drawRoundRect(face(), shape.bounds.topLeft, shape.bounds.size, CornerRadius(shape.corner))
        drawGlassRim(shape.bounds, shape.corner, path = silhouette, opacity = active(), cache = rim)
    }
}
