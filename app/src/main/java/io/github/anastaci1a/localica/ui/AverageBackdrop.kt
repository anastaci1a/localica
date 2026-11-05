package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp

/** The same 8×8 premultiplied average as MaterialAverageRenderer, using the shared
 * tiny readback on Android versions without runtime shaders. No optical filtering. */
internal fun FrostedBackdrop.averageColor(location: BackdropLocation, bounds: Rect): Color? {
    val mapping = location.registrationTo(this.location) ?: return null
    return averageColor(mapping, bounds)
}

private fun FrostedBackdrop.averageColor(mapping: BackdropRegistration, bounds: Rect): Color? {
    val image = snapshot?.asAndroidBitmap() ?: return null
    if (size.width <= 0 || size.height <= 0) return null
    return averageGlassColor { x, y ->
        val point = mapping.map(Offset(bounds.left + bounds.width * (x + .5f) / 8,
            bounds.top + bounds.height * (y + .5f) / 8))
        image.getPixel((point.x * image.width / size.width).toInt().coerceIn(0, image.width - 1),
            (point.y * image.height / size.height).toInt().coerceIn(0, image.height - 1))
    }
}

internal inline fun averageGlassColor(sample: (Int, Int) -> Int): Color {
    var red = 0f; var green = 0f; var blue = 0f; var alpha = 0f
    for (y in 0 until 8) for (x in 0 until 8) {
        val pixel = sample(x, y)
        val a = (pixel ushr 24) / 255f
        red += (pixel ushr 16 and 255) / 255f * a
        green += (pixel ushr 8 and 255) / 255f * a
        blue += (pixel and 255) / 255f * a
        alpha += a
    }
    return if (alpha == 0f) Color.Transparent else Color(red / alpha, green / alpha, blue / alpha, alpha / 64)

}

internal fun MaterialAverageStyle.color(mean: Color): Color {
    val tint = whiteTint.coerceIn(0f, 1f)
    val y = .2126f * mean.red + .7152f * mean.green + .0722f * mean.blue
    val lifted = 1f - (1f - y) * (1f - tint) / (1f - tint * y).coerceAtLeast(.0001f)
    val amount = ((lifted - y) / (1f - y).coerceAtLeast(.0001f)).coerceIn(0f, 1f)
    return tone.color(lerp(mean, Color.White.copy(alpha = mean.alpha), amount))
}

@Composable
internal fun AverageBackdrop(modifier: Modifier, source: FrostedBackdrop, location: BackdropLocation,
    style: MaterialAverageStyle, home: FrostedBackdrop? = null, homeWeight: () -> Float = { 0f },
    homeBounds: (() -> Rect)? = null, homeFootprint: (() -> HomeBackdropFootprint?)? = null) {
    val drawObserver = LocalBackdropSamplingObserver.current
    BackdropLease(source)
    BackdropLease(home?.takeUnless { it === source })
    Canvas(modifier) {
        val bounds = Rect(Offset.Zero, size)
        val localToSource = location.registrationTo(source.location) ?: return@Canvas
        val primary = source.averageColor(localToSource, bounds) ?: return@Canvas
        val weight = homeWeight().coerceIn(0f, 1f)
        val homeColor = if (home != null && weight > 0f) {
            val footprint = homeFootprint?.invoke()
            if (homeFootprint != null && footprint == null) return@Canvas
            val logicalBounds = if (homeFootprint == null) homeBounds?.invoke()?.takeUnless { it.isEmpty } else null
            val sourceToScreen = home.location.registrationToScreen()
            val mapping = when {
                footprint != null -> footprint.sourceToLocal(sourceToScreen)?.inverse()
                logicalBounds != null -> BackdropRegistration.create(1f, 0f, 0f, 1f,
                    logicalBounds.left, logicalBounds.top)?.followedBy(sourceToScreen?.inverse() ?: return@Canvas)
                else -> location.registrationTo(home.location)
            } ?: return@Canvas
            home.averageColor(mapping, Rect(Offset.Zero, footprint?.size ?: logicalBounds?.size ?: size))
                ?: return@Canvas
        } else primary
        val color = lerp(primary, homeColor, weight)
        drawObserver?.let { observeBackdropSampling(it, location, localToSource, bounds) }
        drawRect(style.color(color))
    }
}
