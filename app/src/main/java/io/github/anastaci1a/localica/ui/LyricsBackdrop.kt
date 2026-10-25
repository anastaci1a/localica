package io.github.anastaci1a.localica.ui

import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
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
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Only lyric drawings enter this scene; their sibling return controls never do. */
@Stable
internal class LyricsBackdropScene(val source: FrostedBackdrop, alpha: () -> Float,
    schedule: (Long, () -> Unit) -> (() -> Unit)) {
    val layers = mutableStateListOf<LyricsBackdropLayer>()
    var compositeDraws: Map<Any, BackdropProducerDraws> = emptyMap()
    val readback = BackdropReadback({ Snapshot.withoutReadObservation {
        BackdropReadbackScene(listOf(alpha(), source.size, source.location.placement),
            layers.filter { it.alpha() > 0f }.associate { layer ->
                layer to
                BackdropReadbackProducer(listOf(layer.alpha(), layer.source.size,
                    layer.source.location.placement, layer.contentKey()), layer.hasMask)
            })
    } }, schedule)

    fun recorded(layer: LyricsBackdropLayer, request: Int) {
        layer.recordedReadbackRequest = request
        readback.producerRecorded(layer, request)
    }
}

internal class LyricsBackdropLayer(val source: FrostedBackdrop, val alpha: () -> Float,
    val contentKey: () -> Any?, val hasMask: Boolean = true) {
    // Main-thread diagnostics of separate completed draws, never observable state.
    var recordedReadbackRequest = -1
    var maskedReadbackRequest: Int? = null
}

/** The one permitted aggregate redraw is draw-node invalidation, not snapshot feedback. */
private data class LyricsReadbackDraw(val readback: BackdropReadback) : ModifierNodeElement<LyricsReadbackNode>() {
    override fun create() = LyricsReadbackNode(readback)
    override fun update(node: LyricsReadbackNode) { node.update(readback) }
    override fun InspectorInfo.inspectableProperties() { name = "lyricsReadback" }
}
private class LyricsReadbackNode(private var readback: BackdropReadback) : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() { drawContent() }
    override fun onAttach() { readback.invalidateComposite = { invalidateDraw() } }
    override fun onDetach() { readback.invalidateComposite = null; readback.dispose() }
    fun update(value: BackdropReadback) {
        if (readback === value) return
        if (isAttached) onDetach()
        readback = value
        if (isAttached) onAttach()
    }
}

/** Optional lifecycle observation; never acquires a sampling lease or requests a refresh. */
internal val LocalLyricsBackdropObserver = staticCompositionLocalOf<((LyricsBackdropScene, Boolean) -> Unit)?> { null }
internal val LocalLyricsBackdropScene = staticCompositionLocalOf<LyricsBackdropScene?> { null }

@Composable
internal fun LyricsBackdrop(key: Any?, modifier: Modifier = Modifier, contentAlpha: () -> Float = { 1f },
    content: @Composable () -> Unit) {
    val backing = LocalFrostedBackdrop.current
    val currentAlpha by rememberUpdatedState(contentAlpha)
    val visible by remember { derivedStateOf { currentAlpha() > 0f } }
    val source = rememberFrostedBackdrop(listOf(key, backing, backing?.fallback, backing?.revision),
        enabled = visible, dark = backing?.dark == true, retainRaster = false)
    val handler = remember { Handler(Looper.getMainLooper()) }
    val scene = remember(source) { LyricsBackdropScene(source, { currentAlpha() }) { delay, action ->
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delay)
        val cancel: () -> Unit = { handler.removeCallbacks(runnable) }
        cancel
    } }
    val observer by rememberUpdatedState(LocalLyricsBackdropObserver.current)
    SideEffect { source.parent = backing; source.readbackBarrier = scene.readback; observer?.invoke(scene, true) }
    DisposableEffect(scene) { onDispose {
        scene.readback.dispose()
        observer?.invoke(scene, false)
        if (source.readbackBarrier === scene.readback) source.readbackBarrier = null
    } }
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
            scene.readback.sceneChanged()
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
        }, readbackRequest = { source.readbackRequest }, onRecorded = { request ->
            scene.readback.compositeRecorded(request, scene.compositeDraws)
        }).then(LyricsReadbackDraw(scene.readback))) {
            scene.compositeDraws = emptyMap()
            val included = if (scene.readback.isPending) mutableMapOf<Any, BackdropProducerDraws>() else null
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
                included?.set(layer, BackdropProducerDraws(layer.recordedReadbackRequest, layer.maskedReadbackRequest))
            }
            drawContext.canvas.restore()
            scene.compositeDraws = included.orEmpty()
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
        scene.readback.sceneChanged()
        onDispose { scene.layers.remove(layer); scene.readback.sceneChanged() }
    }
    return recordFrostedBackdrop(source, readbackRequest = { scene.source.readbackRequest },
        onRecorded = { scene.recorded(layer, it) }).clipToBounds()
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
            layer.maskedReadbackRequest = request
            scene.readback.producerMasked(layer, request)
        }
}
