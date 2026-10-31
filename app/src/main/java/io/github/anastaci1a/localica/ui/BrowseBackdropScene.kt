package io.github.anastaci1a.localica.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** A sampling-only page scene. Producers paint normally; sibling controls never enter it. */
@Stable
internal class BrowseBackdropScene(val source: FrostedBackdrop, contentKey: () -> Any?,
    schedule: (Long, () -> Unit) -> (() -> Unit)) {
    val layers = mutableStateListOf<BrowseBackdropLayer>()
    /** Hold the sampled page while it is scrolling (Modern, "Live glass while scrolling" off). */
    var holdWhileScrolling by mutableStateOf(false)
    var scrollingNow: () -> Boolean = { false }
    val frozen: Boolean get() = holdWhileScrolling && scrollingNow()
    var compositeDraws: Map<Any, BackdropProducerDraws> = emptyMap()
    val readback = BackdropReadback({ Snapshot.withoutReadObservation {
        BackdropReadbackScene(listOf(contentKey(), source.size, source.location.placement, source.rendererGeneration),
            layers.associate { layer -> layer to BackdropReadbackProducer(
                listOf(layer.source.size, layer.source.location.placement), layer.masked) })
    } }, schedule)
}

internal class BrowseBackdropLayer(val source: FrostedBackdrop, val masked: Boolean) {
    var outer = -1
    var mask: Int? = null
}

/** Observation does not acquire a lease, request a capture, or attest native consumption. */
internal val LocalBrowseBackdropObserver = staticCompositionLocalOf<((BrowseBackdropScene, Boolean) -> Unit)?> { null }

@Composable
internal fun rememberBrowseBackdropScene(source: FrostedBackdrop, backing: FrostedBackdrop?,
    contentKey: Any?, scrolling: Boolean,
    liveWhileScrolling: Boolean = LocalGlassAppearance.current.liveScrollGlass): BrowseBackdropScene {
    val current by rememberUpdatedState(scrolling)
    return rememberBrowseBackdropScene(source, backing, contentKey, liveWhileScrolling) { current }
}

/** Read scrolling inside the effect so a scroll start/stop does not recompose the caller. */
@Composable
internal fun rememberBrowseBackdropScene(source: FrostedBackdrop, backing: FrostedBackdrop?,
    contentKey: Any?, liveWhileScrolling: Boolean = LocalGlassAppearance.current.liveScrollGlass,
    scrolling: () -> Boolean): BrowseBackdropScene {
    val renderer = currentGlassRendererGeneration()
    val currentKey by rememberUpdatedState(contentKey)
    val handler = remember { Handler(Looper.getMainLooper()) }
    val scene = remember(source) { BrowseBackdropScene(source, { currentKey }) { delay, action ->
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delay)
        val cancel: () -> Unit = { handler.removeCallbacks(runnable) }
        cancel
    } }
    val observer by rememberUpdatedState(LocalBrowseBackdropObserver.current)
    val currentScrolling by rememberUpdatedState(scrolling)
    SideEffect {
        source.parent = backing
        source.readbackBarrier = scene.readback
        scene.readback.sceneChanged()
        observer?.invoke(scene, true)
        scene.scrollingNow = { currentScrolling() }
        scene.holdWhileScrolling = !liveWhileScrolling && renderer.backend == GlassBackend.Modern
    }
    DisposableEffect(scene) { onDispose {
        scene.readback.dispose()
        observer?.invoke(scene, false)
        if (source.readbackBarrier === scene.readback) source.readbackBarrier = null
    } }
    // Invalidate obsolete geometry immediately. Scroll offsets are deliberately not a
    // structural generation: a moving list must be allowed to finish a coherent draw.
    LaunchedEffect(scene) {
        snapshotFlow { listOf(currentKey, source.size, source.location.placement, source.rendererGeneration,
            scene.layers.map { listOf(it.source.size, it.source.location.placement) }) }
            .collectLatest { scene.readback.sceneChanged() }
    }
    // Coalesce movement using the existing 150ms refresh delay plus the adapter’s
    // 500ms settled interval. Never schedule a CPU readback for each scroll frame.
    // The adapter still owns both attempts and their safe completion lifetime.
    LaunchedEffect(scene, contentKey, renderer) {
        if (renderer.backend == GlassBackend.Legacy) {
            // Each start/stop restarts the same schedule the scrolling key used to restart.
            snapshotFlow { currentScrolling() }.collectLatest { moving ->
                delay(150)
                source.refresh()
                while (moving) {
                    delay(650)
                    source.refresh()
                }
            }
        }
    }
    return scene
}

@Composable
internal fun rememberBrowseBackdropLayer(scene: BrowseBackdropScene, masked: Boolean = false): BrowseBackdropLayer {
    val source = rememberFrostedBackdrop(Unit, enabled = false, retainRaster = false)
    val layer = remember(source, masked) { BrowseBackdropLayer(source, masked) }
    DisposableEffect(scene, layer) {
        scene.layers.add(layer)
        scene.readback.sceneChanged()
        onDispose { scene.layers.remove(layer); scene.readback.sceneChanged() }
    }
    return layer
}

@Composable
internal fun Modifier.recordBrowseBackdropLayer(scene: BrowseBackdropScene, layer: BrowseBackdropLayer,
    backing: FrostedBackdrop? = null): Modifier = recordFrostedBackdrop(layer.source, backing,
    readbackRequest = { scene.source.readbackRequest }, onRecorded = { request ->
        layer.outer = request
        scene.readback.producerRecorded(layer, request)
    })

/** Put inside the existing retained fade, so its completed outer record includes that mask. */
internal fun Modifier.browseBackdropMaskWitness(scene: BrowseBackdropScene, layer: BrowseBackdropLayer): Modifier =
    drawWithContent {
        val request = scene.source.readbackRequest
        drawContent()
        layer.mask = request
        scene.readback.producerMasked(layer, request)
    }

private data class BrowseReadbackDraw(val readback: BackdropReadback) : ModifierNodeElement<BrowseReadbackNode>() {
    override fun create() = BrowseReadbackNode(readback)
    override fun update(node: BrowseReadbackNode) { node.update(readback) }
    override fun InspectorInfo.inspectableProperties() { name = "browseReadback" }
}
private class BrowseReadbackNode(private var readback: BackdropReadback) : Modifier.Node(), DrawModifierNode {
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

/** Root coordinates include the top gradient even when the list has a separate viewport. */
@Composable
internal fun BoxScope.BrowseBackdropRecording(scene: BrowseBackdropScene, backing: FrostedBackdrop?) {
    val source = scene.source
    Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source, backing, displayCutout = {
        Path().apply { addRect(Rect(Offset.Zero, size)) }
    }, readbackRequest = { source.readbackRequest }, onRecorded = {
        scene.readback.compositeRecorded(it, scene.compositeDraws)
    }, frozen = { scene.frozen }).then(BrowseReadbackDraw(scene.readback))) {
        scene.compositeDraws = emptyMap()
        val included = if (scene.readback.isPending) mutableMapOf<Any, BackdropProducerDraws>() else null
        for (layer in scene.layers) {
            val producer = layer.source
            if (!producer.recorded || producer.layer.isReleased || producer.size.width <= 0 || producer.size.height <= 0) continue
            val offset = producer.origin - source.origin
            translate(offset.x, offset.y) { drawLayer(producer.layer) }
            included?.set(layer, BackdropProducerDraws(layer.outer, layer.mask))
        }
        scene.compositeDraws = included.orEmpty()
    }
}
