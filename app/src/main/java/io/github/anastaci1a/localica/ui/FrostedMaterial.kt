package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.components.MaterialAreaReduction
import io.github.anastaci1a.localica.ui.components.rememberMaterialAreaReduction
import io.github.anastaci1a.localica.ui.components.materialReductionExtent
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.modifier.modifierLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class GlassBackdropCapture(val renderer: GlassRendererGeneration,
    val snapshot: ImageBitmap, val fallback: ImageBitmap, val extent: IntSize = IntSize(snapshot.width, snapshot.height))

/** A recorded scene, independent of every surface that samples it. Never record a surface
 * into its own source: the player and its lyric background use separate sources. */
@Stable
internal class FrostedBackdrop(val layer: GraphicsLayer, dark: Boolean,
    private val renderer: GlassRendererRuntime? = null) {
    val readback = RetainedLayerReadback()
    // Actual recorded-layer containment, independent of the sampling parent chain.
    private val recordingDebug = if (io.github.anastaci1a.localica.BuildConfig.DEBUG) BackdropRecordingDebug() else null
    val recordedLayoutChildren get() = requireNotNull(recordingDebug).layoutChildren
    val recordedChildren get() = requireNotNull(recordingDebug).children
    private val defaultRenderer = defaultGlassRendererGeneration()
    val rendererGeneration get() = renderer?.generation ?: defaultRenderer
    private val captureLock = Mutex()
    var parent by mutableStateOf<FrostedBackdrop?>(null)
    var materialOnly by mutableStateOf(false)
    var materialCorner by mutableFloatStateOf(0f)
    var consumers by mutableIntStateOf(0)
    /** How far the scene's ink is toward light ink (1) from dark ink (0); fractional while a polarity change eases. */
    var lightMix by mutableFloatStateOf(if (dark) 1f else 0f)
    var dark: Boolean
        get() = lightMix >= .5f
        set(value) { lightMix = if (value) 1f else 0f }
    val location = BackdropLocation()
    var origin: Offset
        get() = location.position()
        set(value) { location.fallback = value }
    var size by mutableStateOf(IntSize.Zero)
    private var completed by mutableStateOf<GlassBackdropCapture?>(null)
    val capture get() = completed?.takeIf { it.renderer == rendererGeneration }
    val fallback get() = capture?.fallback
    val snapshot get() = capture?.snapshot
    val snapshotExtent get() = capture?.extent ?: size
    var revision by mutableIntStateOf(0)
    // Draw invalidation only, deliberately absent from the capture key below.
    // Each already-scheduled readback asks retained drawings to publish its frame.
    var readbackRequest by mutableIntStateOf(0)
    // Main-thread diagnostic paired with snapshot publication, not observable state.
    var snapshotReadbackRequest = -1
    var readbackBarrier: BackdropReadback? = null
    var snapshotDraws: BackdropReadbackDraws? = null
    var lastReadbackAbort: String? = null
    var lastReadbackProgress: String? = null
    var recordedGeneration by mutableStateOf<GlassRendererGeneration?>(null)
    var recordedSize = IntSize.Zero
    // Completed live recordings invalidate retained consumers, including those in another window.
    var recordings by mutableIntStateOf(0)
    val recordedFrames = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val recorded get() = recordedGeneration != null
    fun refresh() { revision++; recordedFrames.trySend(Unit) }

    /** Serialize the separate thumbnail through native ownership. The displayed producer
     * keeps recording live geometry; it must never double as this capture target (#560). */
    suspend fun captureCurrent(graphics: androidx.compose.ui.graphics.GraphicsContext,
        density: androidx.compose.ui.unit.Density, direction: androidx.compose.ui.unit.LayoutDirection,
        valid: () -> Boolean, captureLayer: GraphicsLayer, record: () -> Unit,
        copy: (suspend (GraphicsLayer) -> ImageBitmap)? = null): ImageBitmap? = captureLock.withLock {
        if (!valid()) return@withLock null
        check(captureLayer !== layer) { "Capture must not own the displayed producer recording" }
        record()
        readback.capture(captureLayer, graphics, density, direction, copy)
    }

    fun publish(renderer: GlassRendererGeneration, snapshot: ImageBitmap, fallback: ImageBitmap,
        request: Int, draws: BackdropReadbackDraws?, extent: IntSize = size): Boolean {
        if (rendererGeneration != renderer) return false
        snapshotReadbackRequest = request; snapshotDraws = draws
        // Reading an average can redraw its producer through a parent recording.
        // Equal thumbnail pixels must not publish new observable state forever.
        val previous = completed?.takeIf { it.renderer == renderer }?.snapshot
        if (previous != null && previous.width == snapshot.width && previous.height == snapshot.height &&
            capture?.extent == extent && previous.asAndroidBitmap().sameAs(snapshot.asAndroidBitmap())) return true
        completed = GlassBackdropCapture(renderer, snapshot, fallback, extent)
        return true
    }


}
internal val LocalFrostedBackdrop = staticCompositionLocalOf<FrostedBackdrop?> { null }
internal val LocalBackdropRefresh = staticCompositionLocalOf<() -> Unit> { {} }

/** Resolve both sides of sampling in the same draw frame, including scroll placement. */
@Stable internal class BackdropLocation {
    /** A projected modal samples its home in the pre-departure screen basis. */
    var retainedRegistration: BackdropRegistration? = null
    var coordinates: LayoutCoordinates? = null
    var windowOffset by mutableStateOf(Offset.Zero)
    var placement by mutableIntStateOf(0)
    var transformRevision by mutableIntStateOf(0)
    var fallback by mutableStateOf(Offset.Zero)
    fun observeRecordingAcrossWindows(source: FrostedBackdrop) {
        val target = coordinates?.takeIf { it.isAttached } ?: return
        val origin = source.location.coordinates?.takeIf { it.isAttached } ?: return
        // In one window RenderNode dependencies already propagate source changes.
        if (target.findRootCoordinates() !== origin.findRootCoordinates()) source.recordings
    }

    fun position(): Offset {
        placement
        retainedRegistration?.let { return it.map(Offset.Zero) }
        val previous = fallback
        return coordinates?.takeIf { it.isAttached }?.let { it.positionInRoot() + windowOffset } ?: previous
    }

    /** Source-local to consumer-local. Never substitute a translation for an invalid basis. */
    fun registrationTo(consumer: BackdropLocation): BackdropRegistration? {
        placement; consumer.placement
        transformRevision; consumer.transformRevision
        val source = coordinates?.takeIf { it.isAttached } ?: return null
        val target = consumer.coordinates?.takeIf { it.isAttached } ?: return null
        if (retainedRegistration == null && consumer.retainedRegistration == null &&
            source.findRootCoordinates() === target.findRootCoordinates()) {
            return BackdropRegistration.fromMapping(source.size.width.toFloat(), source.size.height.toFloat()) {
                target.localPositionOf(source, it)
            }
        }
        // Separate Compose windows share the existing screen frame, not a layout ancestor.
        val sourceToScreen = registrationToScreen() ?: return null
        val screenToTarget = consumer.registrationToScreen()?.inverse() ?: return null
        return sourceToScreen.followedBy(screenToTarget)
    }

    fun registrationToScreen(): BackdropRegistration? {
        placement
        transformRevision
        retainedRegistration?.let { return it }
        val attached = coordinates?.takeIf { it.isAttached } ?: return null
        val offset = windowOffset
        return BackdropRegistration.fromMapping(attached.size.width.toFloat(), attached.size.height.toFloat()) {
            attached.localToRoot(it) + offset
        }
    }
}

/** Canvas consumes the same source-local basis used for coverage and crop selection. */
internal fun DrawScope.withBackdropRegistration(registration: BackdropRegistration, draw: DrawScope.() -> Unit) {
    val matrix = Matrix(floatArrayOf(
        registration.xx, registration.yx, 0f, 0f,
        registration.xy, registration.yy, 0f, 0f,
        0f, 0f, 1f, 0f,
        registration.tx, registration.ty, 0f, 1f
    ))
    withTransform({ transform(matrix) }, draw)
}

@Composable internal fun Modifier.backdropLocation(location: BackdropLocation): Modifier {
    val view = LocalView.current
    return onPlaced {
        location.coordinates = it
        androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { location.placement++ }
    }
        .onGloballyPositioned {
            val screen = IntArray(2)
            view.getLocationOnScreen(screen)
            location.windowOffset = Offset(screen[0].toFloat(), screen[1].toFloat())
            location.fallback = it.positionInRoot() + location.windowOffset
            // A layer-only scale can change the basis while leaving both origin and size fixed.
            androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { location.transformRevision++ }
        }
}

@Composable
internal fun rememberFrostedBackdrop(key: Any?, enabled: Boolean = true, dark: Boolean = true, lightMix: Float? = null,
    retainRaster: Boolean = true, rasterRequired: Boolean = false): FrostedBackdrop {
    val layer = rememberGraphicsLayer()
    val runtime = LocalGlassRendererRuntime.current
    val source = remember(layer, runtime) { FrostedBackdrop(layer, dark, runtime) }
    val graphicsContext = LocalGraphicsContext.current
    val currentDensity by rememberUpdatedState(LocalDensity.current)
    val currentDirection by rememberUpdatedState(LocalLayoutDirection.current)
    // An unreferenced scene needs a display list, not another full-window raster.
    // Sampled scenes retain pixels so sibling lenses never replay all their children.
    // A consumer handoff may require pixels before its new lease is applied.
    val sampled = source.consumers > 0 || rasterRequired
    SideEffect {
        if (!source.layer.isReleased) source.layer.compositingStrategy = if (retainRaster && sampled) androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
            else androidx.compose.ui.graphics.layer.CompositingStrategy.Auto
        source.lightMix = lightMix ?: if (dark) 1f else 0f
    }
    // Older Android has no runtime shader. Read a tiny shared scene thumbnail;
    // consumers average their own footprint without blur or mesh preparation.
    val thumbnail = if (Build.VERSION.SDK_INT < 33) rememberGraphicsLayer() else null
    val areaReduction = if (Build.VERSION.SDK_INT < 33) rememberMaterialAreaReduction() else null
    LaunchedEffect(source, key, enabled, sampled, graphicsContext) {
        if (thumbnail != null && enabled && sampled) {
            source.recordedFrames.trySend(Unit)
            var completedRecording = -1
            var completedRevision = -1
            for (frame in source.recordedFrames) {
                if (!source.recorded || source.recordings == completedRecording && source.revision == completedRevision ||
                    source.size.width <= 0 || source.size.height <= 0 || source.layer.isReleased) continue
                val request = source.readbackRequest + 1
                val ticket = source.readbackBarrier?.begin(request)
                source.readbackRequest = request
                try {
                if (ticket != null) { withFrameNanos { }; withFrameNanos { } }
                val draws = ticket?.awaitDraws()
                if (ticket != null && (draws == null || !ticket.isCurrent())) continue
                val extent = source.size
                val generation = source.rendererGeneration
                fun valid() = source.recorded && source.size == extent && !source.layer.isReleased && !thumbnail.isReleased
                val capturedRecording = source.recordings
                val capturedRevision = source.revision
                val image = source.captureCurrent(graphicsContext, currentDensity, currentDirection, ::valid,
                    captureLayer = thumbnail, record = {
                        thumbnail.record(currentDensity, currentDirection, IntSize(32, 32)) {
                            val reduced = with(requireNotNull(areaReduction)) { record(extent, minimum = 32) {
                                clipRect(right = extent.width.toFloat(), bottom = extent.height.toFloat()) { drawLayer(source.layer) }
                            } }
                            drawLayer(reduced)
                        }
                    }) ?: continue
                val pixels = withContext(Dispatchers.Default) { softwareGlassSnapshot(image, IntSize(32, 32)) }
                currentCoroutineContext().ensureActive()
                val padded = materialReductionExtent(extent)
                if (valid()) source.publish(generation, pixels, pixels, request, draws,
                    IntSize(padded.width.coerceAtLeast(32), padded.height.coerceAtLeast(32)))
                completedRecording = capturedRecording
                completedRevision = capturedRevision
                } finally { ticket?.close() }
            }
        }
    }
    return source
}

/** Main-thread recording stack plus retained edges between independently recorded backdrop layers.
 * A retained child may draw without recording again, so its containment edges survive that draw. */
internal class BackdropRecordingDebug {
    val layoutChildren = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FrostedBackdrop, Boolean>())
    val children = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FrostedBackdrop, Boolean>())
}

internal class BackdropRecordingScope(val source: FrostedBackdrop) {
    var parent: BackdropRecordingScope? = null
    fun sources(): List<FrostedBackdrop> {
        val result = ArrayList<FrostedBackdrop>()
        var scope: BackdropRecordingScope? = this
        while (scope != null && scope.source !in result) { result += scope.source; scope = scope.parent }
        return result
    }
    fun attach(value: BackdropRecordingScope?) {
        if (parent === value) return
        parent?.source?.recordedLayoutChildren?.remove(source)
        parent = value
        value?.source?.recordedLayoutChildren?.add(source)
        BackdropRecordingAncestry.assertInputs()
    }
}
internal val ModifierLocalBackdropRecording = modifierLocalOf<BackdropRecordingScope?> { null }

internal object BackdropRecordingAncestry {
    val inputs = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NoneAtlasInput, Boolean>())
    fun assertInputs() { if (io.github.anastaci1a.localica.BuildConfig.DEBUG) inputs.forEach { it.assertInput() } }
    private val active = ArrayList<FrostedBackdrop>()
    fun containers(): List<FrostedBackdrop> = active.toList()
    fun reference(source: FrostedBackdrop) {
        if (!io.github.anastaci1a.localica.BuildConfig.DEBUG) return
        active.lastOrNull()?.takeUnless { it === source }?.recordedChildren?.add(source)
        assertInputs()
    }
    fun begin(source: FrostedBackdrop) { source.recordedChildren.clear(); active.add(source) }
    fun end(source: FrostedBackdrop) { check(active.removeAt(active.lastIndex) === source); assertInputs() }
    fun contains(source: FrostedBackdrop, containers: List<FrostedBackdrop>): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FrostedBackdrop, Boolean>())
        fun visit(node: FrostedBackdrop): Boolean = seen.add(node) &&
            (containers.any { it === node } || node.recordedChildren.any(::visit) || node.recordedLayoutChildren.any(::visit))
        return visit(source)
    }
}

/** Lifetime of a sampling edge in the backdrop graph; no per-frame registration. */
@Composable
internal fun BackdropLease(source: FrostedBackdrop?) {
    DisposableEffect(source) {
        if (source != null) source.consumers++
        onDispose { if (source != null) source.consumers-- }
    }
}

/** Screen coordinates also align sources sampled from another Compose window. */
internal fun androidx.compose.ui.layout.LayoutCoordinates.backdropPosition(view: android.view.View): Offset {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return positionInRoot() + Offset(location[0].toFloat(), location[1].toFloat())
}

@Composable
internal fun Modifier.recordFrostedBackdrop(source: FrostedBackdrop, backing: FrostedBackdrop? = null,
    enabled: Boolean = true, displayCutout: (DrawScope.() -> Path?)? = null,
    readbackRequest: (() -> Int)? = null, onRecorded: ((Int) -> Unit)? = null,
    displayBacking: Boolean = true): Modifier {
    val renderer = currentGlassRendererGeneration()
    // Modifier ancestry remains available when HWUI reuses an independently retained child.
    // It describes recording containment, never the scene's sampling parent.
    val scope = if (io.github.anastaci1a.localica.BuildConfig.DEBUG) remember(source, enabled) { BackdropRecordingScope(source) } else null
    if (io.github.anastaci1a.localica.BuildConfig.DEBUG && scope != null) DisposableEffect(scope) { onDispose { scope.attach(null) } }
    BackdropLease(backing.takeIf { enabled })
    return this.then(if (io.github.anastaci1a.localica.BuildConfig.DEBUG && scope != null) Modifier.modifierLocalConsumer {
            if (enabled) scope.attach(ModifierLocalBackdropRecording.current)
            else scope.parent = ModifierLocalBackdropRecording.current
        }.modifierLocalProvider(ModifierLocalBackdropRecording) { if (enabled) scope else scope.parent } else Modifier)
        .backdropLocation(source.location)
        .onSizeChanged { source.size = it }
        .graphicsLayer()
        .drawWithContent {
            if (!enabled) {
                drawContent()
                return@drawWithContent
            }
            // Retained children and consumers in another window need explicit draw invalidation.
            val dependency = backing ?: source.parent
            if (Build.VERSION.SDK_INT < 33) {
                dependency?.snapshot
                dependency?.recordings
            }
            else dependency?.let { source.location.observeRecordingAcrossWindows(it) }
            val request = readbackRequest?.invoke()
            val viewport = size
            val extent = IntSize(size.width.toInt(), size.height.toInt())
            if (io.github.anastaci1a.localica.BuildConfig.DEBUG) BackdropRecordingAncestry.reference(source)
            if (io.github.anastaci1a.localica.BuildConfig.DEBUG) BackdropRecordingAncestry.begin(source)
            try {
            source.layer.record(size = if (Build.VERSION.SDK_INT >= 33) glassLayerExtent(extent) else extent) {
                clipRect(right = viewport.width, bottom = viewport.height) {
                    if (backing != null && backing.recorded && !backing.layer.isReleased) {
                        if (io.github.anastaci1a.localica.BuildConfig.DEBUG) BackdropRecordingAncestry.reference(backing)
                        backing.location.registrationTo(source.location)?.let { registration ->
                            clipRect { withBackdropRegistration(registration) { drawLayer(backing.layer) } }
                        }
                    }
                    this@drawWithContent.drawContent()
                }
            }
            } finally { if (io.github.anastaci1a.localica.BuildConfig.DEBUG) BackdropRecordingAncestry.end(source) }
            source.recordedSize = extent
            androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { source.recordings++ }
            source.recordedFrames.trySend(Unit)
            source.recordedGeneration = renderer.takeIf { backing == null || backing.recorded }
            if (request != null) onRecorded?.invoke(request)
            // A transparent optical overlay replaces the source beneath its body;
            // retain the complete source for sampling, omit only its direct display.
            if (!displayBacking) {
                drawContent()
                return@drawWithContent
            }
            val cutout = displayCutout?.invoke(this)
            if (cutout == null) drawLayer(source.layer)
            else clipPath(cutout, ClipOp.Difference) { drawLayer(source.layer) }
        }
}

/** Shared scattering, tint, broad lighting, fine non-tiled grain, and a thin illuminated edge.
 * Only the scene is filtered; controls and text are drawn afterwards at full resolution. */
@Composable
internal fun FrostedSurface(modifier: Modifier = Modifier, shape: Shape = Corners.panel,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, compact: Boolean = false,
    tint: Color = Color.Transparent, shadow: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val renderer = currentGlassRendererGeneration()
    if (renderer.backend == GlassBackend.None) BackdropLease(source)
    val location = remember { BackdropLocation() }
    val lightMix = source?.lightMix ?: 0f
    val dark = lightMix >= .5f
    val appearance = LocalGlassAppearance.current.normalized()
    val tone = GlassTone.mixed(appearance.contrast, appearance.contrastWeight, lightMix)
    val toneRenderer = remember { lazy { if (Build.VERSION.SDK_INT >= 33) GlassToneRenderer() else null } }
    val fallbackColor = tone.color(MaterialTheme.colorScheme.surface)
    val foreground = lerp(MaterialTheme.colorScheme.onSurface, Color.White, lightMix)
    // Descendant lenses sample the finished panel material, never the art below it.
    // Keep controls outside this recording to prevent feedback through nested lenses.
    val material = rememberFrostedBackdrop(tone, dark = dark, lightMix = lightMix)
    val density = LocalDensity.current
    SideEffect { material.parent = source; material.materialOnly = true; material.materialCorner = with(density) { 28.dp.toPx() } }
    Box(modifier.then(if (shadow) Modifier.shadow(if (compact) 3.dp else 8.dp, shape, clip = false) else Modifier).clip(shape)
        .backdropLocation(location)
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .8f + (.38f - .8f) * lightMix),
            Color.White.copy(alpha = .06f), Color.White.copy(alpha = .17f))), shape)) {
        Box(Modifier.matchParentSize().recordFrostedBackdrop(material)) {
        if (source != null) {
            val blur = with(LocalDensity.current) { 22.dp.toPx() }
            if (Build.VERSION.SDK_INT >= 33) {
                FilteredBackdrop(Modifier.matchParentSize(), source, location, .5f, blur * 1.5f,
                    average = MaterialAverageStyle(0f, tone, opaque = true)) { _, resolution, _ ->
                    toneRenderer.value?.effect(tone, blur * resolution)
                }
            } else AverageBackdrop(Modifier.matchParentSize(), source, location, MaterialAverageStyle(0f, tone, opaque = true))
        }
        Canvas(Modifier.matchParentSize()) {
            if (source == null) drawRect(fallbackColor)
            if (renderer.backend == GlassBackend.None) drawRect(tone.color(tint))
            else drawFrostCoating(lightMix, tone.color(tint), 1f, compact)
        }
        }
        CompositionLocalProvider(LocalContentColor provides foreground, LocalFrostedBackdrop provides material) { content() }
    }
}

/** Identical panel finish for static frost and the continuous end of a glass morph. */
internal fun DrawScope.drawFrostCoating(dark: Boolean, tint: Color,
    amount: Float, compact: Boolean = false) = drawFrostCoating(if (dark) 1f else 0f, tint, amount, compact)

/** [lightMix] is the light-ink fraction (0 dark ink, 1 light ink); the grain eases between its two strengths. */
internal fun DrawScope.drawFrostCoating(lightMix: Float, tint: Color,
    amount: Float, compact: Boolean = false) {
    drawRect(tint.copy(alpha = tint.alpha * amount))
    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .075f * amount), Color.Transparent),
        center = Offset(size.width * .12f, 0f), radius = maxOf(size.width, size.height) * .9f))
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .055f * amount), Color.Transparent, Color.Black.copy(alpha = .08f * amount))))
    drawImage(frostGrain, dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.Medium, alpha = amount * (.3f + (.5f - .3f) * lightMix.coerceIn(0f, 1f)))
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
