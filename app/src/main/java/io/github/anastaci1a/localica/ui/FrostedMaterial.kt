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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A recorded scene, independent of every surface that samples it. Never record a surface
 * into its own source: the player and its lyric background use separate sources. */
@Stable
internal class FrostedBackdrop(val layer: GraphicsLayer, dark: Boolean) {
    var dark by mutableStateOf(dark)
    var origin by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
    var fallback by mutableStateOf<ImageBitmap?>(null)
    var snapshot by mutableStateOf<ImageBitmap?>(null)
    var contrastPixels by mutableStateOf<PixelMap?>(null)
    var glassFallback by mutableStateOf<ImageBitmap?>(null)
    var lensSnapshot by mutableStateOf<ImageBitmap?>(null)
    var lensFrostSnapshot by mutableStateOf<ImageBitmap?>(null)
    var revision by mutableIntStateOf(0)
    var recorded by mutableStateOf(false)
    fun refresh() { revision++ }
}
internal val LocalFrostedBackdrop = staticCompositionLocalOf<FrostedBackdrop?> { null }
internal val LocalBackdropRefresh = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
internal fun rememberFrostedBackdrop(key: Any?, enabled: Boolean = true, dark: Boolean = true,
    glassBlur: Float = LocalGlassAppearance.current.blur, retainRaster: Boolean = true): FrostedBackdrop {
    val layer = rememberGraphicsLayer()
    // A shared scene is sampled by many lenses. Retain its raster, not just its
    // display list, so every lens does not replay all of the scene's drawing.
    SideEffect { layer.compositingStrategy = if (retainRaster) androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
        else androidx.compose.ui.graphics.layer.CompositingStrategy.Auto }
    val contrastLayer = rememberGraphicsLayer()
    val source = remember(layer) { FrostedBackdrop(layer, dark) }
    SideEffect { source.dark = dark }
    // Android 8–11 have no RenderEffect. Read back only when the scene/size changes,
    // then scatter a small snapshot off the main thread; never blur on each gesture frame.
    LaunchedEffect(source, key, source.size, source.revision, enabled) {
        if (enabled && source.size != IntSize.Zero) {
            suspend fun capture() {
                withFrameNanos { }; withFrameNanos { }
                // Layout can exist before the first recorded draw, or survive a restored
                // player layer briefly. ImageReader rejects a zero-sized recording.
                if (source.layer.isReleased || source.layer.size.width <= 0 || source.layer.size.height <= 0) return
                if (Build.VERSION.SDK_INT >= 33) {
                    val extent = IntSize(48, (48f * source.size.height / source.size.width).toInt().coerceIn(1, 96))
                    contrastLayer.record(androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr, extent) {
                        scale(extent.width.toFloat() / source.size.width,
                            extent.height.toFloat() / source.size.height, Offset.Zero) { drawLayer(source.layer) }
                    }
                    source.contrastPixels = contrastLayer.toImageBitmap().toPixelMap()
                } else {
                    val snapshot = source.layer.toImageBitmap()
                    source.contrastPixels = withContext(Dispatchers.Default) { snapshot.toPixelMap() }
                    source.snapshot = snapshot
                    source.fallback = withContext(Dispatchers.Default) { scatterSnapshot(snapshot) }
                }
            }
            capture()
            // Artwork/lyrics may still be easing when the new scene key is published.
            // Refresh its settled appearance once, without readback on every animation frame.
            kotlinx.coroutines.delay(500)
            capture()
        }
    }
    val blurRadius = with(LocalDensity.current) { 22.dp.toPx() } * glassBlur.coerceIn(0f, 1f)
    val whiteTint = LocalGlassAppearance.current.whiteTint
    LaunchedEffect(source, source.snapshot, source.fallback, blurRadius, whiteTint) {
        if (Build.VERSION.SDK_INT < 33) {
            val snapshot = source.snapshot
            if (snapshot == null) { source.glassFallback = null; source.lensSnapshot = null; source.lensFrostSnapshot = null }
            else {
                // Coalesce slider movement; each recorded source shares one prepared bitmap.
                kotlinx.coroutines.delay(32)
                val blurred = if (blurRadius == 0f) snapshot else withContext(Dispatchers.Default) { blurGlassSnapshot(snapshot, blurRadius) }
                val frosted = source.fallback ?: blurred
                val tinted = withContext(Dispatchers.Default) {
                    tintLensSnapshot(blurred, whiteTint) to tintLensSnapshot(frosted, whiteTint)
                }
                source.glassFallback = blurred.takeIf { blurRadius > 0f }
                source.lensSnapshot = tinted.first
                source.lensFrostSnapshot = tinted.second
            }
        }
    }
    return source
}

/** Screen coordinates also align sources sampled from another Compose window. */
internal fun androidx.compose.ui.layout.LayoutCoordinates.backdropPosition(view: android.view.View): Offset {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return positionInRoot() + Offset(location[0].toFloat(), location[1].toFloat())
}

@Composable
internal fun Modifier.recordFrostedBackdrop(source: FrostedBackdrop, backing: FrostedBackdrop? = null,
    enabled: Boolean = true): Modifier {
    val view = LocalView.current
    return onGloballyPositioned { source.origin = it.backdropPosition(view); source.size = it.size }
        .graphicsLayer()
        .drawWithContent {
            if (!enabled) {
                drawContent()
                return@drawWithContent
            }
            val viewport = size
            val extent = IntSize(size.width.toInt(), size.height.toInt())
            source.layer.record(size = if (Build.VERSION.SDK_INT >= 33) glassLayerExtent(extent) else extent) {
                clipRect(right = viewport.width, bottom = viewport.height) {
                if (backing != null && !backing.layer.isReleased) {
                    val offset = backing.origin - source.origin
                    clipRect { translate(offset.x, offset.y) { drawLayer(backing.layer) } }
                }
                this@drawWithContent.drawContent()
                }
            }
            source.recorded = true
            drawLayer(source.layer)
        }
}

/** Shared scattering, tint, broad lighting, fine non-tiled grain, and a thin illuminated edge.
 * Only the scene is filtered; controls and text are drawn afterwards at full resolution. */
@Composable
internal fun FrostedSurface(modifier: Modifier = Modifier, shape: Shape = Corners.panel,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, compact: Boolean = false,
    tint: Color = Color.Transparent, darkening: Float = .5f, shadow: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val view = LocalView.current
    val dark = source?.dark == true
    val base = if (dark) Color.Black else MaterialTheme.colorScheme.surface
    val foreground = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    // Descendant lenses sample the finished panel material, never the art below it.
    // Keep controls outside this recording to prevent feedback through nested lenses.
    val material = rememberFrostedBackdrop(source?.fallback, enabled = LocalGlassAppearance.current.blur > 0f, dark = dark)
    Box(modifier.then(if (shadow) Modifier.shadow(if (compact) 3.dp else 8.dp, shape, clip = false) else Modifier).clip(shape)
        .onGloballyPositioned { origin = it.backdropPosition(view) }
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) .38f else .8f),
            Color.White.copy(alpha = .06f), Color.White.copy(alpha = .17f))), shape)) {
        Box(Modifier.matchParentSize().recordFrostedBackdrop(material)) {
        if (source != null) {
            if (Build.VERSION.SDK_INT >= 31) {
                val blur = with(LocalDensity.current) { 22.dp.toPx() }
                FilteredBackdrop(Modifier.matchParentSize(), source, { origin }, .5f, blur * 1.5f) { _, resolution ->
                    BlurEffect(blur * resolution, blur * resolution, TileMode.Clamp)
                }
            } else Canvas(Modifier.matchParentSize()) {
                val offset = source.origin - origin
                translate(offset.x, offset.y) { source.fallback?.let { drawImage(it, dstSize = source.size, filterQuality = FilterQuality.Medium) } }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            drawFrostCoating(dark, base, tint, darkening, 1f, compact)
        }
        }
        CompositionLocalProvider(LocalContentColor provides foreground, LocalFrostedBackdrop provides material) { content() }
    }
}

/** Identical panel finish for static frost and the continuous end of a glass morph. */
internal fun DrawScope.drawFrostCoating(dark: Boolean, base: Color, tint: Color, darkening: Float,
    amount: Float, compact: Boolean = false) {
    drawRect(base.copy(alpha = amount * if (dark) { if (compact) .22f else darkening } else { if (compact) .18f else .42f }))
    drawRect(tint.copy(alpha = tint.alpha * amount))
    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .075f * amount), Color.Transparent),
        center = Offset(size.width * .12f, 0f), radius = maxOf(size.width, size.height) * .9f))
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .055f * amount), Color.Transparent, Color.Black.copy(alpha = .08f * amount))))
    drawImage(frostGrain, dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.Medium, alpha = amount * if (dark) .5f else .3f)
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
    // This panel treatment has always been opaque; preserve RGB while sharing the filter.
    pixels.indices.forEach { pixels[it] = pixels[it] or (255 shl 24) }
    repeat(3) { pixels = boxBlur(pixels, w, h, 3) }
    small.recycle()
    return android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}
