package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A recorded scene, independent of every surface that samples it. Never record a surface
 * into its own source: the player and its lyric background use separate sources. */
@Stable
internal class FrostedBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
    var fallback by mutableStateOf<ImageBitmap?>(null)
}
internal val LocalFrostedBackdrop = staticCompositionLocalOf<FrostedBackdrop?> { null }

@Composable
internal fun rememberFrostedBackdrop(key: Any?, enabled: Boolean = true): FrostedBackdrop {
    val layer = rememberGraphicsLayer()
    val source = remember(layer) { FrostedBackdrop(layer) }
    // Android 8–11 have no RenderEffect. Read back only when the scene/size changes,
    // then scatter a small snapshot off the main thread; never blur on each gesture frame.
    LaunchedEffect(source, key, source.size, enabled) {
        if (Build.VERSION.SDK_INT < 31 && enabled && source.size != IntSize.Zero) {
            suspend fun capture() {
                withFrameNanos { }; withFrameNanos { }
                // Layout can exist before the first recorded draw, or survive a restored
                // player layer briefly. ImageReader rejects a zero-sized recording.
                if (source.layer.isReleased || source.layer.size.width <= 0 || source.layer.size.height <= 0) return
                val snapshot = source.layer.toImageBitmap()
                source.fallback = withContext(Dispatchers.Default) { scatterSnapshot(snapshot) }
            }
            capture()
            // Artwork/lyrics may still be easing when the new scene key is published.
            // Refresh its settled appearance once, without readback on every animation frame.
            kotlinx.coroutines.delay(500)
            capture()
        }
    }
    return source
}

internal fun Modifier.recordFrostedBackdrop(source: FrostedBackdrop): Modifier =
    onGloballyPositioned { source.origin = it.positionInRoot(); source.size = it.size }
        .drawWithContent {
            source.layer.record { this@drawWithContent.drawContent() }
            drawLayer(source.layer)
        }

/** Shared scattering, tint, broad lighting, fine non-tiled grain, and a thin illuminated edge.
 * Only the scene is filtered; controls and text are drawn afterwards at full resolution. */
@Composable
internal fun FrostedSurface(modifier: Modifier = Modifier, shape: Shape = Corners.panel,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, compact: Boolean = false,
    tint: Color = Color.Transparent, content: @Composable BoxScope.() -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val dark = source != null
    val base = if (dark) Color(0xFF18212C) else MaterialTheme.colorScheme.surface
    val foreground = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val grain = remember { frostGrain }
    Box(modifier.shadow(if (compact) 3.dp else 8.dp, shape, clip = false).clip(shape)
        .onGloballyPositioned { origin = it.positionInRoot() }
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) .38f else .8f),
            Color.White.copy(alpha = .06f), Color.White.copy(alpha = .17f))), shape)) {
        if (source != null) Canvas(Modifier.matchParentSize().graphicsLayer {
            if (Build.VERSION.SDK_INT >= 31) renderEffect = BlurEffect(22.dp.toPx(), 22.dp.toPx(), TileMode.Clamp)
        }) {
            val offset = source.origin - origin
            translate(offset.x, offset.y) {
                if (Build.VERSION.SDK_INT >= 31) drawLayer(source.layer)
                else source.fallback?.let { drawImage(it, dstSize = source.size, filterQuality = FilterQuality.Medium) }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            drawRect(base.copy(alpha = if (dark) { if (compact) .26f else .64f } else { if (compact) .65f else .91f }))
            drawRect(tint)
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .075f), Color.Transparent),
                center = Offset(size.width * .12f, 0f), radius = maxOf(size.width, size.height) * .9f))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .055f), Color.Transparent, Color.Black.copy(alpha = .08f))))
            drawImage(grain, dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.Medium, alpha = if (dark) .5f else .3f)
        }
        CompositionLocalProvider(LocalContentColor provides foreground) { content() }
    }
}

private val frostGrain: ImageBitmap by lazy {
    val random = kotlin.random.Random(9137)
    android.graphics.Bitmap.createBitmap(384, 768, android.graphics.Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(384 * 768) {
            val tone = if (random.nextBoolean()) 255 else 0
            android.graphics.Color.argb(random.nextInt(5), tone, tone, tone)
        }, 0, 384, 0, 0, 384, 768)
    }.asImageBitmap()
}

private fun scatterSnapshot(image: ImageBitmap): ImageBitmap {
    val original = image.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    val w = (original.width / 8).coerceAtLeast(1)
    val h = (original.height / 8).coerceAtLeast(1)
    val small = android.graphics.Bitmap.createScaledBitmap(original, w, h, true)
    if (original !== small) original.recycle()
    var pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    fun pass(horizontal: Boolean) {
        val result = IntArray(pixels.size)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0
            for (offset in -3..3) {
                val xx = if (horizontal) (x + offset).coerceIn(0, w - 1) else x
                val yy = if (horizontal) y else (y + offset).coerceIn(0, h - 1)
                val color = pixels[yy * w + xx]
                r += (color ushr 16) and 255; g += (color ushr 8) and 255; b += color and 255
            }
            result[y * w + x] = android.graphics.Color.rgb(r / 7, g / 7, b / 7)
        }
        pixels = result
    }
    repeat(3) { pass(true); pass(false) }
    small.recycle()
    return android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}
