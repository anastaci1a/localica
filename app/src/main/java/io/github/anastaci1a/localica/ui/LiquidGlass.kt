package io.github.anastaci1a.localica.ui

import io.github.anastaci1a.localica.ui.spec.GlassBlurDefault
import io.github.anastaci1a.localica.ui.spec.GlassWhiteTint
import io.github.anastaci1a.localica.ui.spec.GlassContrast
import io.github.anastaci1a.localica.ui.spec.GlassContrastWeightDefault
import io.github.anastaci1a.localica.ui.spec.GlassDepth

import android.os.Build
import io.github.anastaci1a.localica.ui.spec.FlexSpec
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Rect
import io.github.anastaci1a.localica.ui.engine.ContactLightBacking
import io.github.anastaci1a.localica.ui.engine.presentedAlpha
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

internal fun glassBackdropBlurRadius(density: Float): Float {
    val k = density.coerceIn(2f, 3f)
    return (if (k <= 2.625f) 5.165593576f + (5.755008754f - 5.165593576f) * ((k - 2f) / .625f) else 5.755008754f + (6.062170952f - 5.755008754f) * ((k - 2.625f) / .375f)) / (.5f * GlassBlurDefault)
}
internal val NavigationPillHeight = 64.dp
internal val CompactControlSize = NavigationPillHeight * .8f
internal const val RaisedGlassDepthScale = 1.3f
internal const val BottomPillDepthScale = .72f
internal val MiniArtworkSize = 32.dp
internal val LocalGlassContactName = staticCompositionLocalOf { "" }
internal val LocalGlassContactTrace = staticCompositionLocalOf<((String, String, Offset, Boolean) -> Unit)?> { null }

/** Passive coating-draw evidence, not a filter/shader/native-consumption receipt. All emitted
 * values are scalars or scalar lists; no scene, layer, bitmap, coordinates, View or Window escapes.
 * Identity hashes are process-local correlation hints, not collision-free resource identifiers. */
internal data class GlassSurfaceSourceDraw(
    val identity: Int, val parentIdentity: Int?, val layerIdentity: Int, val parentLayerIdentity: Int?,
    val recorded: Boolean, val released: Boolean, val parentRecorded: Boolean?, val parentReleased: Boolean?,
    val materialOnly: Boolean, val dark: Boolean, val width: Int, val height: Int,
    val layerWidth: Int, val layerHeight: Int, val revision: Int, val requestedReadback: Int,
    val publishedReadback: Int, val publishedDrawRequest: Int?, val publishedComposite: Int?,
    val snapshotIdentity: Int?, val frostIdentity: Int?, val lensIdentity: Int?,
    val sourceToConsumer: List<Float>?, val consumerCornersInSource: List<Float>?,
)
/** A passive draw receipt (#920 step 3): a glass surface draw, a home projecting a popup's recorded layer, or a popup state step. */
internal sealed interface GlassDrawReceipt
internal data class GlassProjectionReceipt(val home: Int, val flight: Int, val layer: Int, val revision: Long, val offset: List<Float>) : GlassDrawReceipt
internal data class GlassTransitionReceipt(val flight: Int, val gen: Int, val from: String, val event: String, val to: String) : GlassDrawReceipt
/** #748 R5 body ownership: withdraw (source draw entry), unlink (D emptied before B refills) and rearm. */
internal data class GlassBodyReceipt(val flight: Int, val event: String, val request: Long) : GlassDrawReceipt
internal data class GlassSurfaceDrawObservation(
    val uptime: Long, val surfaceIdentity: Int, val viewIdentity: Int, val windowIdentity: Int?, val tokenIdentity: Int?,
    val attached: Boolean, val focused: Boolean, val viewMatrixIdentity: Boolean, val visible: Boolean,
    val width: Float, val height: Float, val rootCorners: List<Float>?, val windowCorners: List<Float>?,
    val screenCorners: List<Float>?, val outlineBounds: List<Float>?,
    val source: GlassSurfaceSourceDraw?, val home: GlassSurfaceSourceDraw?,
    val inkArgb: Int, val inkOverridden: Boolean, val lightInk: Boolean,
    val toneTrim: Float, val toneWeight: Float, val tintArgb: Int,
    val frost: Float, val suppliedHomeWeight: Float,
    val blur: Float, val whiteTint: Float, val depth: Float, val depthScale: Float, val refraction: Float,
    val shadowEnabled: Boolean, val shadowStrength: Float, val shadowSpread: Float,
    val shadowGroupIdentity: Int?, val shadowInModal: Boolean?, val silhouettePresent: Boolean,
    /** Light-ink fraction of the tone and ink actually drawn (0 dark ink, 1 light ink); fractional while a polarity change eases. */
    val toneLightMix: Float = if (lightInk) 1f else 0f,
    /** Supplied home geometry and presentation, sampled at this draw. */
    val homeBounds: List<Float>? = null, val homeFootprint: List<Float>? = null, val presented: List<Float>? = null,
) : GlassDrawReceipt
internal val LocalGlassSurfaceDrawObserver = staticCompositionLocalOf<((GlassDrawReceipt) -> Unit)?> { null }
/** Tests only (like [pinchDrawProbe]): the default observer for compositions a test cannot wrap; compiled out of release. */
internal var glassDrawProbe: ((GlassDrawReceipt) -> Unit)? = null
@Composable internal fun glassDrawObserver() = glassReceipts(LocalGlassSurfaceDrawObserver.current)
/** The one receipt sink: [local] or the test probe, and null in release. [reportGlass] keeps a failing observer out of drawing and state steps. */
internal fun glassReceipts(local: ((GlassDrawReceipt) -> Unit)? = null) = if (io.github.anastaci1a.localica.BuildConfig.DEBUG) local ?: glassDrawProbe else null
internal fun reportGlass(sink: ((GlassDrawReceipt) -> Unit)?, receipt: () -> GlassDrawReceipt) { if (sink != null) try { sink(receipt()) } catch (_: Throwable) { } }

private fun surfaceDrawIdentity(value: Any?): Int? = value?.let(System::identityHashCode)
private fun List<Offset>.surfaceDrawScalars(): List<Float>? =
    takeIf { all { it.x.isFinite() && it.y.isFinite() } }?.flatMap { listOf(it.x, it.y) }
private fun androidx.compose.ui.geometry.Rect.surfaceDrawScalars(): List<Float> = listOf(left, top, right, bottom)
private fun FrostedBackdrop?.surfaceDrawSource(location: BackdropLocation, extent: Size): GlassSurfaceSourceDraw? {
    val source = this ?: return null
    val registration = source.location.registrationTo(location)
    return GlassSurfaceSourceDraw(System.identityHashCode(source), surfaceDrawIdentity(source.parent),
        System.identityHashCode(source.layer), surfaceDrawIdentity(source.parent?.layer),
        source.recorded, source.layer.isReleased, source.parent?.recorded, source.parent?.let { it.layer.isReleased },
        source.materialOnly, source.dark, source.size.width, source.size.height,
        source.layer.size.width, source.layer.size.height, source.revision, source.readbackRequest,
        source.snapshotReadbackRequest, source.snapshotDraws?.request, source.snapshotDraws?.composite,
        surfaceDrawIdentity(source.snapshot), surfaceDrawIdentity(source.fallback), null,
        registration?.let { listOf(it.xx, it.xy, it.yx, it.yy, it.tx, it.ty) },
        registration?.inverse()?.corners(androidx.compose.ui.geometry.Rect(Offset.Zero, extent))?.surfaceDrawScalars())
}

/** D26 compatibility: root controls keep a read-only view without importing Engine. */
@Stable internal class GlassExpansion {
    private val phase=io.github.anastaci1a.localica.ui.engine.GlassExpansion()
    val value get()=phase.value
    val velocity get()=phase.velocity
    val targetValue get()=phase.targetValue
    val isRunning get()=phase.isRunning
    fun bind(owner: io.github.anastaci1a.localica.ui.engine.ExpandCollapse,curve: io.github.anastaci1a.localica.ui.engine.Curve)=phase.bind(owner,curve)
    fun unbind(owner: io.github.anastaci1a.localica.ui.engine.ExpandCollapse)=phase.unbind(owner)
}

@Stable internal class GlassContact(private val inModal: () -> Boolean = { false },
    private val presentedExpansion: () -> GlassExpansion? = { null }, bodyOverride: FlexSpec? = null) {
    var pressed by mutableStateOf(false)
    var point by mutableStateOf(Offset(.5f, .5f))
    var glowTravel by mutableFloatStateOf(0f)
    var glowGeneration by mutableIntStateOf(0)
    var travel by mutableFloatStateOf(0f)
    val modal get() = inModal()
    var active by mutableStateOf(true)
    private var verticalAxis by mutableStateOf(false)
    var vertical: Boolean
        get() = verticalAxis
        set(value) {
            if (value == verticalAxis) return
            // Changing the travel axis must not rotate already-stored strain.
            state.momentum.blendFrom(state.momentum, aAxis = -1f, bAxis = -1f)
            state.output.value = state.momentum.shape
            verticalAxis = value
        }
    private val idleExpansion = GlassExpansion()
    val expansion get() = presentedExpansion() ?: idleExpansion
    val state = GlassBodyState(bodyOverride)
    val body: State<GlassShape> get() = state.output
}

@Composable
internal fun rememberGlassContact(shared: GlassContact? = null, bodyOverride: FlexSpec? = null): GlassContact {
    val contact = shared ?: remember { GlassContact(bodyOverride = bodyOverride) }
    val visible = LocalGlassVisible.current
    rememberGlassBody({ contact.travel + contact.expansion.value }, contact = { if (contact.pressed) 1f else 0f },
        state = contact.state, active = { visible && contact.active && !contact.modal }, point = { contact.point }, vertical = { contact.vertical }, lightTravel = { contact.glowTravel }, generation = { contact.glowGeneration })
    return contact
}

/** Observe touch without consuming it; children retain gesture and accessibility ownership. */
@Composable
internal fun Modifier.glassPress(contact: GlassContact = rememberGlassContact(),
    presentation: () -> GlassShape = { contact.body.value }, admit: () -> Boolean = { true },
    transform: (Size) -> GlassBodyTransform = { presentation().bodyTransform(it, contact.vertical) },
    onPressed: (Boolean) -> Unit = {}): Modifier {
    return glassBody(transform).glassTouch(contact, admit, onPressed)
}

@Composable private fun Modifier.glassTouch(contact: GlassContact, admit: () -> Boolean, onPressed: (Boolean) -> Unit = {}): Modifier =
    observeGlassContact(admit, yieldToParent = true, onPoint = { contact.point = it }, onTravel = { contact.glowTravel = it }) { if (it) { contact.glowGeneration++; contact.state.momentum.origin = contact.point }; contact.travel = 0f; contact.pressed = it; onPressed(it) }

internal fun DrawScope.drawGlassBody(transform: GlassBodyTransform, content: DrawScope.() -> Unit) =
    translate(transform.drift.x, transform.drift.y) { scale(transform.scale.x, transform.scale.y, block = content) }

private fun Modifier.glassBody(transform: (Size) -> GlassBodyTransform) = drawWithContent {
    drawGlassBody(transform(size)) { this@drawWithContent.drawContent() }
}

/** Shared contact lifetime for ordinary buttons and independently draggable glass bodies.
 * With [yieldToParent], the press lets go as soon as the shared control intent hands the
 * contact to a scrolling or moving ancestor, even while the finger stays on the control. */
@Composable
internal fun Modifier.observeGlassContact(admit: () -> Boolean = { true }, yieldToParent: Boolean = false,
    onPoint: ((Offset) -> Unit)? = null, onTravel: ((Float) -> Unit)? = null, onPressed: (Boolean) -> Unit): Modifier {
    val reportPoint by rememberUpdatedState(onPoint)
    val reportTravel by rememberUpdatedState(onTravel)
    val notify by rememberUpdatedState(onPressed)
    val admission by rememberUpdatedState(admit)
    val trace by rememberUpdatedState(LocalGlassContactTrace.current)
    val name by rememberUpdatedState(LocalGlassContactName.current)
    val parent = rememberControlScrollContext()
    return readControlScrollContext(parent).pointerInput(yieldToParent) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            runCatching { trace?.invoke(name, "down", down.position, down.isConsumed) }
            // Read the live draw owner at contact acquisition. Replacing controls
            // in composition can leave a restored visible face without input for
            // one frame. A rejected DOWN must not turn into a click after return.
            if (!admission()) {
                down.consume()
                return@awaitEachGesture
            }
            reportTravel?.invoke(0f)
            reportPoint?.invoke(Offset(down.position.x / size.width, down.position.y / size.height))
            notify(true)
            var pressed = true
            val intent = if (yieldToParent) ControlGestureIntent(viewConfiguration.touchSlop, parent.directions()) else null
            try {
                // Visual pressure belongs to the initiating finger until it lifts or a
                // parent takes the contact. Clickable still cancels activation when that
                // finger leaves the target.
                do { val event = awaitPointerEvent(PointerEventPass.Final)
                    event.changes.firstOrNull { it.id == down.id }?.let { point ->
                        runCatching { trace?.invoke(name, if (point.pressed) "held" else "up", point.position, point.isConsumed) }
                        if (pressed && point.pressed) reportPoint?.invoke(Offset(point.position.x / size.width, point.position.y / size.height))
                        val movement = point.position - down.position
                        if (pressed && point.pressed) reportTravel?.invoke(movement.getDistance() / density)
                        if (pressed && point.pressed && intent != null) {
                            if (event.changes.count { it.pressed } > 1) intent.cancel()
                            val owner = intent.move(movement.x, movement.y, point.uptimeMillis - down.uptimeMillis)
                            if (owner == ControlGestureOwner.Scroll || owner == ControlGestureOwner.Cancelled) {
                                pressed = false
                                notify(false)
                            }
                        }
                    }
                }
                while (event.changes.any { it.id == down.id && it.pressed })
            } finally { if (pressed) notify(false) }
        }
    }
}

/** One glass material record (#748): a surface draws it, and a popup morph starts from the very record its source draws.
 * [lens] is the weight of the sampled lens layer: 0 for a surface without a sampled source (#847). */
internal data class GlassSurfacePresentation(val frost: Float, val refraction: Float, val lightMix: Float, val tint: Color, val depthScale: Float, val lens: Float = 1f) {
    /** Weight 0 is this exact record and 1 is [target]; every field moves with the same weight. */
    fun towards(target: GlassSurfacePresentation, weight: Float): GlassSurfacePresentation = when {
        weight <= 0f -> this
        weight >= 1f -> target
        else -> GlassSurfacePresentation(frost + (target.frost - frost) * weight, refraction + (target.refraction - refraction) * weight,
            lightMix + (target.lightMix - lightMix) * weight, lerp(tint, target.tint, weight), depthScale + (target.depthScale - depthScale) * weight,
            lens + (target.lens - lens) * weight)
    }
}

/** Adjustable lens over a separately recorded scene. Foreground content is never filtered. */
@Composable
internal fun LiquidGlassSurface(modifier: Modifier = Modifier,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, tint: Color = Color.Transparent,
    cornerRadius: Dp? = null, frostProgress: Float = 0f, pressFeedback: Boolean = true, refraction: Float = 1f, inkOverride: Color? = null, shadow: Boolean = true,
    body: () -> GlassShape = { GlassShape() }, depthScale: Float = modifier.glassDepthScale(),
    homeSource: FrostedBackdrop? = null, homeWeight: () -> Float = { 0f },
    homeBounds: (() -> androidx.compose.ui.geometry.Rect)? = null,
    homeFootprint: (() -> HomeBackdropFootprint?)? = null,
    silhouette: GlassSilhouette? = null, sharedContact: GlassContact? = null,
    contactPresentation: (() -> GlassShape)? = null,
    contactAdmission: () -> Boolean = { true },
    presentation: (() -> GlassSurfacePresentation)? = null, presentedRadius: (() -> Dp?)? = null,
    bodyOverride: FlexSpec? = null, recording: FrostedBackdrop? = null, content: (@Composable BoxScope.() -> Unit)? = null) {
    val backend = effectiveGlassBackend(android.os.Build.VERSION.SDK_INT)
    val drawObserver = glassDrawObserver()
    // Do not create diagnostic state, closures, geometry or modifiers for ordinary/null callers.
    val observedShadowGroup = if (drawObserver == null) null else LocalSurfaceShadows.current
    val observedClock = if (drawObserver == null) null else io.github.anastaci1a.localica.ui.engine.LocalClockOwner.current
    val visible = LocalGlassVisible.current
    if (visible && backend == GlassBackend.AverageFallback) {
        BackdropLease(source)
        BackdropLease(homeSource?.takeUnless { it === source })
    }
    val location = remember { BackdropLocation() }
    var surfaceSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The page owns ink polarity. A control never flips independently as content
    // scrolls beneath it, and no GPU readback is needed to choose its foreground.
    val lightMix = source?.lightMix ?: 0f
    val ink = inkOverride ?: lerp(Color(0xFF202124), Color.White, lightMix)
    val anchor = modifier.glassSurfaceAnchor(source, cornerRadius)
    // The surface draws its own live record, and its anchor carries that same holder to the popup morph.
    val own = rememberUpdatedState(remember(frostProgress, refraction, lightMix, tint, depthScale, source != null) {
        GlassSurfacePresentation(frostProgress.coerceIn(0f, 1f), refraction, lightMix, tint, depthScale, if (source != null) 1f else 0f) })
    if (anchor != null && presentation == null) DisposableEffect(anchor, own) {
        anchor.material = own
        onDispose { if (anchor.material === own) anchor.material = null }
    }
    val material = presentation ?: own::value
    fun toneOf(record: GlassSurfacePresentation) = GlassTone.mixed(GlassContrast, GlassContrastWeightDefault, record.lightMix)
    val tone = toneOf(own.value)
    val contact = rememberGlassContact(anchor?.contact ?: sharedContact, bodyOverride)
    val contactShape = contactPresentation ?: modifier.glassSurfaceContactPresentation() ?: { contact.body.value }
    val appliedTransform: (Size) -> GlassBodyTransform = { extent -> if (pressFeedback || anchor != null)
        contactShape().bodyTransform(extent, contact.vertical, anchored = anchor != null) else GlassBodyTransform() }
    val surfaceClip = remember(silhouette, cornerRadius) {
        silhouette?.let { outline -> androidx.compose.foundation.shape.GenericShape { extent, _ ->
            addPath(outline.frame(extent).path)
        } } ?: cornerRadius?.let { RoundedCornerShape(it) } ?: CircleShape
    }
    val reduced = LocalReducedMotion.current
    val flex = FlexSpec.dynamic(maxOf(surfaceSize.width, surfaceSize.height) / density.density,
        minOf(surfaceSize.width, surfaceSize.height) / density.density)
    SideEffect {
        val momentum = contact.state.momentum
        momentum.spec = flex; momentum.extent = Size(surfaceSize.width / density.density, surfaceSize.height / density.density)
        if (bodyOverride != null && anchor == null && sharedContact == null) {
            val longSide = maxOf(surfaceSize.width, surfaceSize.height) / density.density
            momentum.bodyOverride = bodyOverride.copy(scaleGain = if (longSide > 0f) -(16f / longSide) / .24f else null)
            contact.state.output.value = if (reduced) GlassShape() else momentum.shape
        }
        if (anchor != null) {
            anchor.contact.state.impulseScale = kotlin.math.sqrt(96f * density.density / surfaceSize.height.coerceAtLeast(1)).coerceIn(1f, 1.8f)
        }
    }
    val lens = remember { lazy { if (Build.VERSION.SDK_INT >= 33) LensRenderer() else null } }
    val rim = remember { GlassRimCache() }
    val glowCache = remember { ContactGlowCache() }
    // Transform the surface and its shadow together in the draw pass; a legacy
    // ViewLayer would clip the shadow outside its bounds even with clipping disabled.
    Box(modifier.then(if (pressFeedback) Modifier.glassPress(contact, contactShape,
        admit = contactAdmission, transform = appliedTransform) else if (anchor != null) Modifier.glassBody(appliedTransform) else Modifier)
        .then(if (!pressFeedback && sharedContact == null && anchor == null) Modifier.glassTouch(contact, contactAdmission) else Modifier)
        .then(if (!shadow) Modifier else if (silhouette != null) Modifier.silhouetteGlassShadow(silhouette)
            else Modifier.surfaceGlassShadow(cornerRadius, anchor))
        .then(if (presentedRadius == null) Modifier.clip(surfaceClip) else Modifier.graphicsLayer {
            shape = if (silhouette != null) surfaceClip else presentedRadius()?.let { RoundedCornerShape(it) } ?: CircleShape
            clip = true
        })
        .backdropLocation(location).onGloballyPositioned { surfaceSize = it.size }, propagateMinConstraints = true) {
        val blurRadius = glassBackdropBlurRadius(density.density)
        fun blur(record: GlassSurfacePresentation) = blurRadius * (GlassBlurDefault + (1f - GlassBlurDefault) * record.frost)
        val padding = with(density) { 32.dp.toPx() }
        val lensLayer = if (presentation == null) Modifier.matchParentSize() else Modifier.matchParentSize().presentedAlpha { material().lens }
        ContactGlowBacking(Modifier.matchParentSize().then(recording?.let { Modifier.recordFrostedBackdrop(it) } ?: Modifier), { contact.state.glow.value }, if (pressFeedback || anchor != null) contactShape else body, contact.vertical, glowCache, sample = {
            source?.averageColor(location, androidx.compose.ui.geometry.Rect(Offset.Zero, Size(surfaceSize.width.toFloat(), surfaceSize.height.toFloat())))
        }, transform = appliedTransform) {
        if (visible && source != null && Build.VERSION.SDK_INT >= 33) {
            // Crossing into panel scattering must not swap backing resolution in
            // one frame. Clear glass stays sharp; visible blur gradually permits
            // the cheaper half-resolution recording during the same morph.
            fun resolution(record: GlassSurfacePresentation): Float {
                val scattering = ((blur(record) - 1f) / 5f).coerceIn(0f, 1f)
                return 1f - .5f * scattering * scattering * (3f - 2f * scattering)
            }
            FilteredBackdrop(lensLayer, source, location, resolution(own.value), padding,
                homeSource, homeWeight, MaterialAverageStyle(GlassWhiteTint, tone), homeBounds, homeFootprint,
                resolutionReader = presentation?.let { read -> { resolution(read()) } },
                averageReader = presentation?.let { read -> { MaterialAverageStyle(GlassWhiteTint, toneOf(read())) } }) { extent, resolution, flatWeight ->
                val frame = material()
                val cornerRadius = presentedRadius?.invoke() ?: cornerRadius
                val blur = blur(frame)
                val pad = padding * resolution
                val radius = (cornerRadius?.let { with(density) { it.toPx() } }?.times(resolution)
                    ?: ((extent.minDimension - 2 * pad) / 2)).coerceAtLeast(0f)
                val thickness = body().thickness * (if (pressFeedback || anchor != null) contactShape().thickness else 1f)
                val outline = silhouette?.frame(Size(extent.width - 2 * pad, extent.height - 2 * pad))
                if (Build.VERSION.SDK_INT >= 33) lens.value?.effect(
                    outline?.geometry?.let { it.copy(bounds = it.bounds.translate(Offset(pad, pad))) }
                        ?: LensGeometry(androidx.compose.ui.geometry.Rect(pad, pad, extent.width - pad, extent.height - pad), radius),
                    blur * resolution,
                    GlassDepth * frame.depthScale * thickness,
                    unit = density.density * resolution,
                    power = frame.refraction, whiteTint = GlassWhiteTint, illumination = 1f - flatWeight,
                    flattening = io.github.anastaci1a.localica.ui.engine.glassBodyFlattening(thickness), tone = toneOf(frame),
                    map = outline?.field, origin = Offset(pad, pad), fieldWeight = outline?.description?.fieldWeight ?: 1f,
                    circles = outline?.description?.circles?.let { (a, b) -> a + Offset(pad, pad) to b + Offset(pad, pad) },
                    splitInner = outline?.description?.splitInner)
                else null
            }
        }

        if (visible && source != null && Build.VERSION.SDK_INT < 33) {
            AverageBackdrop(lensLayer, source, location,
                MaterialAverageStyle(GlassWhiteTint, tone), homeSource, homeWeight, homeBounds, homeFootprint,
                styleReader = presentation?.let { read -> { MaterialAverageStyle(GlassWhiteTint, toneOf(read())) } })
        }
        Canvas(Modifier.matchParentSize()) {
            val frame = material()
            drawRect(toneOf(frame).color(frame.tint))
        }
        }
        Canvas(Modifier.matchParentSize()) {
            val frame = material()
            val tone = toneOf(frame)
            val cornerRadius = presentedRadius?.invoke() ?: cornerRadius
            val outline = silhouette?.frame(size)
            val splitRim = outline?.description?.rimSplit ?: 0f
            val bounds = androidx.compose.ui.geometry.Rect(Offset.Zero, size)
            val radius = cornerRadius?.toPx() ?: size.minDimension / 2f
            drawGlassRim(bounds, radius, path = outline?.path ?: glowCache.roundedPath(bounds, radius), tone = tone, opacity = 1f - splitRim, cache = rim)
            if (outline != null && splitRim > 0f) outline.bodies.forEach { part ->
                clipRect(part.left, part.top, part.right, part.bottom) {
                    drawGlassRim(androidx.compose.ui.geometry.Rect(part.left, part.top, part.right, part.bottom),
                        part.height / 2f, path = outline.path, tone = tone, opacity = splitRim)
                }
            }
            // Reuse this existing coating Canvas and its computed outline: no extra draw node,
            // readback, invalidation or filter traversal. Diagnostic reads cannot subscribe drawing
            // to new snapshot state or mark a motion presented; construction AND callback failures are isolated from rendering.
            if (drawObserver != null) try {
                observedClock?.observe {
                    val coordinates = location.coordinates?.takeIf { it.isAttached }
                    val localCorners = listOf(Offset.Zero, Offset(size.width, 0f),
                        Offset(size.width, size.height), Offset(0f, size.height))
                    val rootCorners = coordinates?.let { c -> localCorners.map(c::localToRoot) }
                    val inWindow = IntArray(2).also(view::getLocationInWindow)
                    val onScreen = IntArray(2).also(view::getLocationOnScreen)
                    val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                    drawObserver(GlassSurfaceDrawObservation(android.os.SystemClock.uptimeMillis(),
                        System.identityHashCode(location), System.identityHashCode(view), surfaceDrawIdentity(window),
                        surfaceDrawIdentity(view.windowToken), view.isAttachedToWindow, view.hasWindowFocus(), view.matrix.isIdentity, visible,
                        size.width, size.height, rootCorners?.surfaceDrawScalars(),
                        rootCorners?.map { it + Offset(inWindow[0].toFloat(), inWindow[1].toFloat()) }?.surfaceDrawScalars(),
                        rootCorners?.map { it + Offset(onScreen[0].toFloat(), onScreen[1].toFloat()) }?.surfaceDrawScalars(),
                        outline?.geometry?.bounds?.surfaceDrawScalars(),
                        source.surfaceDrawSource(location, size), homeSource.surfaceDrawSource(location, size),
                        ink.toArgb(), inkOverride != null,
                        frame.lightMix >= .5f, tone.trim, tone.weight, frame.tint.toArgb(), frame.frost,
                        // Supplied reader sampled here, NOT the private filter's admitted/clamped blend.
                        homeWeight(), GlassBlurDefault, GlassWhiteTint, GlassDepth, frame.depthScale, frame.refraction,
                        shadow, io.github.anastaci1a.localica.ui.spec.GlassShadowStrength, io.github.anastaci1a.localica.ui.spec.GlassShadowSpread,
                        surfaceDrawIdentity(observedShadowGroup),
                        anchor?.modalPresent, silhouette != null, frame.lightMix, homeBounds?.invoke()?.surfaceDrawScalars(),
                        homeFootprint?.invoke()?.let { f -> listOf(f.size.width, f.size.height) + f.localToScreen.let { listOf(it.xx, it.xy, it.yx, it.yy, it.tx, it.ty) } },
                        presentation?.let { listOf(cornerRadius?.value ?: -1f, frame.frost, frame.lightMix, frame.refraction, frame.lens) }))
                }
            } catch (_: Throwable) { /* A diagnostic must never fail or change the drawing. */ }

        }

        if (content != null) CompositionLocalProvider(LocalContentColor provides ink) {
            Box(content = content)
        }
    }
}

/** A restrained ambient reflection on the silhouette, never a plate across the face.
 * Shared by live/mesh controls and connected selections at the final display resolution. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassRim(
    bounds: androidx.compose.ui.geometry.Rect, radius: Float, path: Path? = null, tone: GlassTone = GlassTone(), opacity: Float = 1f, cache: GlassRimCache? = null) {
    val (shine, shade) = cache?.brushes(bounds.center, tone) ?: glassRimBrushes(bounds.center, tone)
    val silhouette = path ?: Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(radius))) }
    clipPath(silhouette) {
        drawPath(silhouette, shine, alpha = opacity, style = Stroke(2.6.dp.toPx()))
        drawPath(silhouette, shade, alpha = opacity, style = Stroke(1.4.dp.toPx()))
    }
}

@Composable
internal fun ContactGlowBacking(modifier: Modifier, glow: () -> GlassGlow, shape: () -> GlassShape, vertical: Boolean, cache: ContactGlowCache,
    sample: () -> Color?, transform: (Size) -> GlassBodyTransform = { shape().bodyTransform(it, vertical, anchored = true) }, geometry: DrawScope.() -> Pair<Rect, Float> = { Rect(Offset.Zero, size) to 0f }, content: @Composable () -> Unit) {
    val background = MaterialTheme.colorScheme.background
    ContactLightBacking(modifier, geometry = geometry, background = background, enabled = { val frame = glow(); frame.big > 0f || frame.little > 0f || frame.retiring.any { it.little > 0f } },
        effect = { g -> cache.effect(glow(), transform(size), g.first, density, background, size.height, g.second) }, reduction = { cache.meanEffect(background) },
        paint = { g -> contactGlowPasses(glow(), shape(), vertical, cache, g.first, sample()?.compositeOver(background) ?: background, transform(size), g.second) }, content = content)
}

internal fun DrawScope.contactGlowPasses(glow: GlassGlow, body: GlassShape, vertical: Boolean, cache: ContactGlowCache,
    bounds: Rect, sample: Color, transform: GlassBodyTransform? = null, corner: Float = 0f): List<io.github.anastaci1a.localica.ui.engine.ContactLightPass> {
    if (bounds.isEmpty) return emptyList()
    val white = cache.white(sample.luminance())
    val radius = io.github.anastaci1a.localica.ui.spec.FlexGlow.radius(minOf(bounds.width, bounds.height), density)
    val sigma = glowReducedRadius(radius)
    return (listOf(GlassGlow(big = glow.big)) + glow.retiring + glow.copy(big = 0f, retiring = emptyList())).mapNotNull { frame ->
        val local = frame.big == 0f; val alpha = if (local) frame.little else frame.big
        if (alpha <= 0f) null else {
            val point = bounds.topLeft + (transform ?: body.bodyTransform(bounds.size, vertical, anchored = true)).localPoint(frame.point, bounds.size)
            val paint = cache.paint(local, white); val mask = if (local) cache.mask(sigma) else null
            val pass: io.github.anastaci1a.localica.ui.engine.ContactLightPass = { backing ->
                clipPath(cache.roundedPath(bounds, corner)) {
                io.github.anastaci1a.localica.ui.engine.drawContactLight(this, bounds, point, radius / sigma * frame.scale,
                    if (local) 0f else alpha, if (local) alpha else 0f, paint, paint, mask, cache.composite, backing)
                }
            }
            pass
        }
    }
}

@Composable internal fun rememberGlassLight(contact: () -> Float, point: () -> Offset, travel: () -> Float, generation: () -> Int): GlassContact {
    val light = remember { GlassContact() }
    SideEffect { light.pressed = contact() > 0f; light.point = point(); light.glowTravel = travel(); light.glowGeneration = generation() }
    return light
}
