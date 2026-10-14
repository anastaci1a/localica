package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.*

/** One scene is displayed once: identity outside the lens, refraction inside it.
 * Track and lens share draw-time coordinates, including between discrete values.
 * The padding accommodates a thumb centered on either end of the track. */
@Composable
internal fun GlassControlScene(modifier: Modifier, horizontalPadding: Dp = 20.dp,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current,
    geometry: DrawScope.() -> LensGeometry, body: () -> GlassShape,
    face: () -> Color = { Color.Transparent },
    scene: DrawScope.() -> Unit) {
    BackdropLease(source)
    val view = LocalView.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val appearance = LocalGlassAppearance.current
    val layer = rememberGraphicsLayer()
    val renderer = remember { if (Build.VERSION.SDK_INT >= 33) LensRenderer() else null }
    val mesh = remember { LensMeshRenderer() }
    Canvas(modifier.onGloballyPositioned { origin = it.backdropPosition(view) }) {
        val pad = horizontalPadding.toPx()
        val extent = Size(size.width + pad * 2f, size.height)
        val shape = geometry()
        val shifted = shape.copy(bounds = shape.bounds.translate(Offset(pad, 0f)))
        val thickness = body().thickness
        val flattening = ((1f - thickness) / .18f).coerceIn(0f, 1f)
        fun DrawScope.recordScene() {
            val backing = source
            if (backing != null) {
                val delta = backing.origin - origin + Offset(pad, 0f)
                if (Build.VERSION.SDK_INT >= 33 && backing.recorded && !backing.layer.isReleased) {
                    translate(delta.x, delta.y) { drawLayer(backing.layer) }
                } else backing.snapshot?.let { image ->
                    translate(delta.x, delta.y) {
                        drawImage(image, dstSize = backing.size)
                    }
                }
            }
            inset(pad, 0f, pad, 0f) { scene() }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            layer.renderEffect = renderer?.effect(shifted, 0f, appearance.depth * thickness, flattening = flattening)
            // Snapshot density before entering the nested recording scope. Passing
            // the outer mutable DrawScope as Density creates a recursive delegate.
            layer.record(Density(density, fontScale), layoutDirection,
                IntSize(kotlin.math.ceil(extent.width).toInt(), kotlin.math.ceil(extent.height).toInt())) { recordScene() }
            translate(-pad, 0f) { drawLayer(layer) }
        } else {
            // A small procedural scene; unlike general backdrops, it needs no GPU
            // readback. Both display branches use this exact image and silhouette.
            val image = ImageBitmap(kotlin.math.ceil(extent.width).toInt(), kotlin.math.ceil(extent.height).toInt())
            CanvasDrawScope().draw(Density(density, fontScale), layoutDirection, Canvas(image), extent) { recordScene() }
            val silhouette = Path().apply { addRoundRect(RoundRect(shape.bounds, CornerRadius(shape.corner))) }
            clipPath(silhouette, ClipOp.Difference) { drawImage(image, topLeft = Offset(-pad, 0f)) }
            clipPath(silhouette) {
                with(mesh) { draw(shape, image, IntSize(image.width, image.height), Offset(pad, 0f),
                    appearance.depth * thickness, flattening = flattening) }
            }
        }
        drawRoundRect(face(), shape.bounds.topLeft, shape.bounds.size, CornerRadius(shape.corner))
        drawGlassRim(shape.bounds, shape.corner)
    }
}
