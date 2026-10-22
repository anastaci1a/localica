package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Only lyric drawings enter this scene; their sibling return controls never do. */
@Stable
internal class LyricsBackdropScene(val source: FrostedBackdrop) {
    val layers = mutableStateListOf<LyricsBackdropLayer>()
}

internal class LyricsBackdropLayer(val source: FrostedBackdrop, val alpha: () -> Float,
    val contentKey: () -> Any?) {
    // Main-thread diagnostic of the completed masked draw, not observable state.
    var recordedReadbackRequest = -1
}

internal val LocalLyricsBackdropScene = staticCompositionLocalOf<LyricsBackdropScene?> { null }

@Composable
internal fun LyricsBackdrop(key: Any?, modifier: Modifier = Modifier, contentAlpha: () -> Float = { 1f },
    content: @Composable () -> Unit) {
    val backing = LocalFrostedBackdrop.current
    val currentAlpha by rememberUpdatedState(contentAlpha)
    val visible by remember { derivedStateOf { currentAlpha() > 0f } }
    val source = rememberFrostedBackdrop(listOf(key, backing, backing?.fallback, backing?.revision),
        enabled = visible, dark = backing?.dark == true, retainRaster = false)
    val scene = remember(source) { LyricsBackdropScene(source) }
    SideEffect { source.parent = backing }
    // Coalesce legacy scroll/opacity changes rather than capturing every frame.
    // The shared adapter's later settled capture also finishes lyric-emphasis easing.
    // Observe content changes even if a document's identity stays put.
    LaunchedEffect(scene) {
        if (Build.VERSION.SDK_INT < 33) snapshotFlow {
            currentAlpha() to scene.layers.map { layer ->
                listOf(layer.source.recorded, layer.source.size, layer.source.location.placement,
                    layer.alpha(), layer.contentKey())
            }
        }.collectLatest {
            delay(150)
            source.refresh()
        }
    }
    Box(modifier, propagateMinConstraints = true) {
        CompositionLocalProvider(LocalLyricsBackdropScene provides scene, content = content)
        // Compose the real artwork plus all retained Crossfade documents for sampling
        // only. Displaying this backing again would strengthen translucent player art.
        Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source, backing, displayCutout = {
            Path().apply { addRect(Rect(Offset.Zero, size)) }
        })) {
            source.readbackRequest
            val viewport = Rect(Offset.Zero, size)
            val opacity = currentAlpha().coerceIn(0f, 1f)
            if (opacity <= 0f) return@Canvas
            // Player opacity belongs to the combined documents, not to each one:
            // overlapping outgoing/incoming glyphs must be composed before fading.
            drawContext.canvas.saveLayer(viewport, Paint().apply { alpha = opacity })
            for (layer in scene.layers) {
                val glyphs = layer.source
                if (!glyphs.recorded || glyphs.layer.isReleased || glyphs.size.width <= 0 || glyphs.size.height <= 0) continue
                val alpha = layer.alpha().coerceIn(0f, 1f)
                if (alpha <= 0f) continue
                val offset = glyphs.origin - source.origin
                // These alphas belong to ancestors of the glyph recording. Apply them
                // to the sampled copy only; the visible tree already applies them once.
                drawContext.canvas.saveLayer(viewport, Paint().apply { this.alpha = alpha })
                translate(offset.x, offset.y) { drawLayer(glyphs.layer) }
                drawContext.canvas.restore()
            }
            drawContext.canvas.restore()
        }
    }
}

/** Record the final clipped/faded viewport, including documents retained by Crossfade. */
@Composable
internal fun Modifier.recordLyricsLayer(scene: LyricsBackdropScene, alpha: () -> Float,
    contentKey: () -> Any?): Modifier {
    // This display list is replayed by the shared scene, never read back independently.
    val source = rememberFrostedBackdrop(Unit, enabled = false, retainRaster = false)
    val currentAlpha by rememberUpdatedState(alpha)
    val currentContentKey by rememberUpdatedState(contentKey)
    val layer = remember(source) { LyricsBackdropLayer(source, { currentAlpha() }, { currentContentKey() }) }
    DisposableEffect(scene, layer) {
        scene.layers.add(layer)
        onDispose { scene.layers.remove(layer) }
    }
    return recordFrostedBackdrop(source).clipToBounds()
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            // Observe inside the retained mask layer: invalidating only its parent
            // replays the same child display list on API 26's readback surface. Both
            // existing captures must redraw this final viewport, not just its wrapper.
            // Never feed this draw-only request back into refresh/capture flows.
            val request = scene.source.readbackRequest
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.10f to Color.Black,
                0.88f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            layer.recordedReadbackRequest = request
        }
}
