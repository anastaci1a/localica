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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    val snapshot: ImageBitmap, val fallback: ImageBitmap)
private data class GlassBackdropPreparation(val renderer: GlassRendererGeneration,
    val glass: ImageBitmap?, val lens: ImageBitmap, val frost: ImageBitmap)

/** A recorded scene, independent of every surface that samples it. Never record a surface
 * into its own source: the player and its lyric background use separate sources. */
@Stable
internal class FrostedBackdrop(val layer: GraphicsLayer, dark: Boolean,
    private val renderer: GlassRendererRuntime? = null) {
    val readback = RetainedLayerReadback()
    private val defaultRenderer = automaticGlassRendererGeneration()
    val rendererGeneration get() = renderer?.generation ?: defaultRenderer
    private val captureLock = Mutex()
    private var desiredCompositing = androidx.compose.ui.graphics.layer.CompositingStrategy.Auto
    var readbackPending by mutableStateOf(false)
        private set
    var parent: FrostedBackdrop? = null
    var materialOnly by mutableStateOf(false)
    var materialCorner by mutableFloatStateOf(0f)
    var consumers by mutableIntStateOf(0)
    var dark by mutableStateOf(dark)
    val location = BackdropLocation()
    var origin: Offset
        get() = location.position()
        set(value) { location.fallback = value }
    var size by mutableStateOf(IntSize.Zero)
    private var completed by mutableStateOf<GlassBackdropCapture?>(null)
    private var preparation by mutableStateOf<GlassBackdropPreparation?>(null)
    val capture get() = completed?.takeIf { it.renderer == rendererGeneration }
    // Preserve completed endpoints while a same-backend-generation replacement
    // prepares, as before. Only a renderer epoch boundary rejects those pixels.
    private val prepared get() = preparation?.takeIf { it.renderer == rendererGeneration }
    val fallback get() = capture?.fallback
    val snapshot get() = capture?.snapshot
    val glassFallback get() = prepared?.glass
    val lensSnapshot get() = prepared?.lens
    val lensFrostSnapshot get() = prepared?.frost
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
    val recorded get() = recordedGeneration == rendererGeneration
    fun refresh() { revision++ }

    fun compositing(strategy: androidx.compose.ui.graphics.layer.CompositingStrategy) {
        desiredCompositing = strategy
        if (!readbackPending && !layer.isReleased) layer.compositingStrategy = strategy
    }

    // Start the replacement's existing draw-readiness budget only after a retired
    // native copy releases its recording freeze. Do not consume both attempts
    // against a frozen old generation and then leave the new backend unprepared.
    suspend fun awaitNativeCapture() { captureLock.withLock { } }

    /** Serialize native ownership, not merely publication. A replacement effect may
     * wait here but cannot clear another generation's pending-recording freeze. */
    suspend fun captureCurrent(graphics: androidx.compose.ui.graphics.GraphicsContext,
        density: androidx.compose.ui.unit.Density, direction: androidx.compose.ui.unit.LayoutDirection,
        valid: () -> Boolean): ImageBitmap? = captureLock.withLock {
        if (!valid()) return@withLock null
        readbackPending = true
        try { readback.capture(layer, graphics, density, direction) }
        finally {
            readbackPending = false
            if (!layer.isReleased) layer.compositingStrategy = desiredCompositing
        }
    }

    fun publish(renderer: GlassRendererGeneration, snapshot: ImageBitmap, fallback: ImageBitmap,
        request: Int, draws: BackdropReadbackDraws?): Boolean {
        if (rendererGeneration != renderer) return false
        snapshotReadbackRequest = request; snapshotDraws = draws
        completed = GlassBackdropCapture(renderer, snapshot, fallback)
        if (preparation?.renderer != renderer) preparation = null
        return true
    }

    fun prepare(capture: GlassBackdropCapture, glass: ImageBitmap?, lens: ImageBitmap, frost: ImageBitmap) {
        if (this.capture === capture) preparation = GlassBackdropPreparation(capture.renderer, glass, lens, frost)
    }
}
internal val LocalFrostedBackdrop = staticCompositionLocalOf<FrostedBackdrop?> { null }
internal val LocalBackdropRefresh = staticCompositionLocalOf<() -> Unit> { {} }

/** Resolve both sides of sampling in the same draw frame, including scroll placement. */
@Stable internal class BackdropLocation {
    var coordinates: LayoutCoordinates? = null
    var windowOffset by mutableStateOf(Offset.Zero)
    var placement by mutableIntStateOf(0)
    var transformRevision by mutableIntStateOf(0)
    var fallback by mutableStateOf(Offset.Zero)
    fun position(): Offset {
        placement
        val previous = fallback
        return coordinates?.takeIf { it.isAttached }?.let { it.positionInRoot() + windowOffset } ?: previous
    }

    /** Source-local to consumer-local. Never substitute a translation for an invalid basis. */
    fun registrationTo(consumer: BackdropLocation): BackdropRegistration? {
        placement; consumer.placement
        transformRevision; consumer.transformRevision
        val source = coordinates?.takeIf { it.isAttached } ?: return null
        val target = consumer.coordinates?.takeIf { it.isAttached } ?: return null
        if (source.findRootCoordinates() === target.findRootCoordinates()) {
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
internal fun rememberFrostedBackdrop(key: Any?, enabled: Boolean = true, dark: Boolean = true,
    glassBlur: Float = LocalGlassAppearance.current.blur, retainRaster: Boolean = true,
    whiteTint: Float = LocalGlassAppearance.current.whiteTint): FrostedBackdrop {
    val layer = rememberGraphicsLayer()
    val runtime = LocalGlassRendererRuntime.current
    val renderer = currentGlassRendererGeneration()
    val source = remember(layer, runtime) { FrostedBackdrop(layer, dark, runtime) }
    val graphicsContext = LocalGraphicsContext.current
    val currentDensity by rememberUpdatedState(LocalDensity.current)
    val currentDirection by rememberUpdatedState(LocalLayoutDirection.current)
    // An unreferenced scene needs a display list, not another full-window raster.
    // Sampled scenes retain pixels so sibling lenses never replay all their children.
    val sampled = source.consumers > 0
    SideEffect {
        source.compositing(if (retainRaster && sampled) androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
            else androidx.compose.ui.graphics.layer.CompositingStrategy.Auto)
        source.dark = dark
    }
    // Legacy consumers read back only when the scene/size/backend changes,
    // then scatter a small snapshot off the main thread; never blur on each gesture frame.
    // Keep a completed sample across temporary visibility changes. Modal sources
    // with no open consumer never need a CPU readback at all.
    var captured by remember(source) { mutableStateOf<List<Any?>?>(null) }
    LaunchedEffect(source, key, enabled, sampled, graphicsContext, renderer) {
        // Size belongs to sampling, not the caller's composition. Reading it above
        // this effect would recompose the whole player host on every resize frame.
        if (renderer.backend == GlassBackend.Legacy && enabled && sampled) snapshotFlow {
            Triple(source.size, source.revision, source.recorded)
        }.collectLatest { (size, revision, recorded) ->
            val captureKey = listOf(renderer, key, size, revision)
            // Admission changes only for a new recording generation/backing, not
            // every draw or readbackRequest. A pending parent must not consume this
            // generation's two attempts before its first usable record exists.
            if (!recorded || size.width <= 0 || size.height <= 0 || captured == captureKey) return@collectLatest
            suspend fun capture(): Boolean {
                source.awaitNativeCapture()
                if (source.rendererGeneration != renderer) return false
                val request = source.readbackRequest + 1
                val ticket = source.readbackBarrier?.begin(request)
                source.readbackRequest = request
                try {
                    withFrameNanos { }; withFrameNanos { }
                    val draws = ticket?.awaitDraws()
                    if (ticket != null && (draws == null || !ticket.isCurrent())) return false
                    // Layout can precede the first recording, or survive a restored layer.
                    fun current() = source.rendererGeneration == renderer && source.recorded &&
                        source.recordedSize == size && source.size == size && !source.layer.isReleased &&
                        source.layer.size.width >= size.width && source.layer.size.height >= size.height &&
                        (ticket == null || ticket.isCurrent())
                    if (!current()) return false
                    // Draw readiness does not own native lifetime: the retained lease remains
                    // protected through completion even when this generation is cancelled.
                    val native = source.captureCurrent(graphicsContext, currentDensity, currentDirection, ::current) ?: return false
                    if (!current()) return false
                    val (snapshot, fallback) = withContext(Dispatchers.Default) {
                        val software = softwareGlassSnapshot(native, size)
                        software to scatterSnapshot(software)
                    }
                    currentCoroutineContext().ensureActive()
                    if (!current()) return false
                    // Publish one completed generation, never a new snapshot with an old fallback.
                    if (!source.publish(renderer, snapshot, fallback, request, draws)) return false
                    source.lastReadbackAbort = null
                    return true
                } finally {
                    source.lastReadbackAbort = ticket?.abortReason
                    source.lastReadbackProgress = ticket?.takeIf { it.abortReason != null }?.progress()
                    ticket?.close()
                }
            }
            capture()
            // Artwork/lyrics may still be easing when the new scene key is published.
            // Refresh its settled appearance once, without readback on every animation frame.
            kotlinx.coroutines.delay(500)
            if (capture()) captured = captureKey
        }
    }
    val blurRadius = with(LocalDensity.current) { 22.dp.toPx() } * glassBlur.coerceIn(0f, 1f)
    val capturedPixels = source.capture
    LaunchedEffect(source, capturedPixels, blurRadius, whiteTint, renderer) {
        if (renderer.backend == GlassBackend.Legacy && capturedPixels != null) {
            val snapshot = capturedPixels.snapshot
            // Coalesce slider movement; each recorded source shares one prepared bitmap.
            kotlinx.coroutines.delay(32)
            val blurred = if (blurRadius == 0f) snapshot else withContext(Dispatchers.Default) { blurGlassSnapshot(snapshot, blurRadius) }
            val frosted = capturedPixels.fallback
            val tinted = withContext(Dispatchers.Default) {
                tintLensSnapshot(blurred, whiteTint) to tintLensSnapshot(frosted, whiteTint)
            }
            currentCoroutineContext().ensureActive()
            source.prepare(capturedPixels, blurred.takeIf { blurRadius > 0f }, tinted.first, tinted.second)
        }
    }
    return source
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
    readbackRequest: (() -> Int)? = null, onRecorded: ((Int) -> Unit)? = null): Modifier {
    val renderer = currentGlassRendererGeneration()
    BackdropLease(backing.takeIf { enabled })
    return backdropLocation(source.location)
        .onSizeChanged { source.size = it }
        .graphicsLayer()
        .drawWithContent {
            if (!enabled) {
                drawContent()
                return@drawWithContent
            }
            val request = readbackRequest?.invoke()
            val viewport = size
            val extent = IntSize(size.width.toInt(), size.height.toInt())
            // Native ownership outlives cancellation. Do not mutate the retained
            // own recording while a copy (including a retired generation's copy) is pending.
            // Retained children keep their separate owner/readiness contracts.
            if (!source.readbackPending) {
                source.layer.record(size = if (renderer.backend == GlassBackend.Modern) glassLayerExtent(extent) else extent) {
                    clipRect(right = viewport.width, bottom = viewport.height) {
                        if (backing != null && backing.recorded && !backing.layer.isReleased) {
                            backing.location.registrationTo(source.location)?.let { registration ->
                                clipRect { withBackdropRegistration(registration) { drawLayer(backing.layer) } }
                            }
                        }
                        this@drawWithContent.drawContent()
                    }
                }
                source.recordedSize = extent
                source.recordedGeneration = renderer.takeIf { backing == null || backing.recorded }
                if (request != null) onRecorded?.invoke(request)
            }
            // A transparent optical overlay replaces the source beneath its body;
            // retain the complete source for sampling, omit only its direct display.
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
    if (renderer.backend == GlassBackend.Legacy) BackdropLease(source)
    val location = remember { BackdropLocation() }
    val dark = source?.dark == true
    val appearance = LocalGlassAppearance.current.normalized()
    val tone = GlassTone(appearance.contrast, appearance.contrastWeight, dark)
    val toneRenderer = remember { lazy { if (Build.VERSION.SDK_INT >= 33) GlassToneRenderer() else null } }
    val tonedFallback = key(renderer) { rememberToneSnapshot(source?.fallback ?: source?.snapshot, tone) }
    val fallbackColor = tone.color(MaterialTheme.colorScheme.surface)
    val foreground = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    // Descendant lenses sample the finished panel material, never the art below it.
    // Keep controls outside this recording to prevent feedback through nested lenses.
    val material = rememberFrostedBackdrop(source?.fallback to tone,
        enabled = renderer.backend == GlassBackend.Legacy || LocalGlassAppearance.current.blur > 0f, dark = dark)
    val density = LocalDensity.current
    SideEffect { material.parent = source; material.materialOnly = true; material.materialCorner = with(density) { 28.dp.toPx() } }
    Box(modifier.then(if (shadow) Modifier.shadow(if (compact) 3.dp else 8.dp, shape, clip = false) else Modifier).clip(shape)
        .backdropLocation(location)
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) .38f else .8f),
            Color.White.copy(alpha = .06f), Color.White.copy(alpha = .17f))), shape)) {
        Box(Modifier.matchParentSize().recordFrostedBackdrop(material)) {
        if (source != null) {
            if (renderer.backend == GlassBackend.Modern && Build.VERSION.SDK_INT >= 33) {
                val blur = with(LocalDensity.current) { 22.dp.toPx() }
                FilteredBackdrop(Modifier.matchParentSize(), source, location, .5f, blur * 1.5f) { _, resolution, _ ->
                    toneRenderer.value?.effect(tone, blur * resolution)
                }
            } else Canvas(Modifier.matchParentSize()) {
                source.location.registrationTo(location)?.let { registration ->
                    withBackdropRegistration(registration) {
                        tonedFallback?.let { drawImage(it, dstSize = source.size, filterQuality = FilterQuality.Medium) }
                    }
                }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            if (source == null) drawRect(fallbackColor)
            drawFrostCoating(dark, tone.color(tint), 1f, compact)
        }
        }
        CompositionLocalProvider(LocalContentColor provides foreground, LocalFrostedBackdrop provides material) { content() }
    }
}

/** Identical panel finish for static frost and the continuous end of a glass morph. */
internal fun DrawScope.drawFrostCoating(dark: Boolean, tint: Color,
    amount: Float, compact: Boolean = false) {
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
    val source = readGlassPixels(image, 8)
    val w = source.width
    val h = source.height
    var pixels = source.pixels
    // This panel treatment has always been opaque; preserve RGB while sharing the filter.
    pixels.indices.forEach { pixels[it] = pixels[it] or (255 shl 24) }
    repeat(3) { pixels = boxBlur(pixels, w, h, 3) }
    return android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}
