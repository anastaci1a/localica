package io.github.anastaci1a.localica.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
internal data class GlassSurfaceDrawObservation(
    val uptime: Long, val surfaceIdentity: Int, val viewIdentity: Int, val windowIdentity: Int?, val tokenIdentity: Int?,
    val attached: Boolean, val focused: Boolean, val viewMatrixIdentity: Boolean, val visible: Boolean,
    val width: Float, val height: Float, val rootCorners: List<Float>?, val windowCorners: List<Float>?,
    val screenCorners: List<Float>?, val outlineBounds: List<Float>?,
    val source: GlassSurfaceSourceDraw?, val home: GlassSurfaceSourceDraw?,
    val inkArgb: Int, val inkOverridden: Boolean, val lightInk: Boolean, val coatingDark: Boolean,
    val toneTrim: Float, val toneWeight: Float, val tintArgb: Int,
    val panel: Float, val frost: Float, val suppliedHomeWeight: Float,
    val blur: Float, val whiteTint: Float, val depth: Float, val depthScale: Float, val refraction: Float,
    val shadowEnabled: Boolean, val shadowStrength: Float, val shadowSpread: Float,
    val shadowGroupIdentity: Int?, val shadowInModal: Boolean?, val silhouettePresent: Boolean,
    /** Light-ink fraction of the tone and ink actually drawn (0 dark ink, 1 light ink); fractional while a polarity change eases. */
    val toneLightMix: Float = if (lightInk) 1f else 0f, val coatingLightMix: Float = if (coatingDark) 1f else 0f,
)
internal val LocalGlassSurfaceDrawObserver = staticCompositionLocalOf<((GlassSurfaceDrawObservation) -> Unit)?> { null }

private fun surfaceDrawIdentity(value: Any?): Int? = value?.let(System::identityHashCode)
private fun List<Offset>.surfaceDrawScalars(): List<Float>? =
    takeIf { all { it.x.isFinite() && it.y.isFinite() } }?.flatMap { listOf(it.x, it.y) }
private fun androidx.compose.ui.geometry.Rect.surfaceDrawScalars(): List<Float> = listOf(left, top, right, bottom)
private fun FrostedBackdrop?.surfaceDrawSource(location: BackdropLocation, extent: Size): GlassSurfaceSourceDraw? {
    val source = this ?: return null
    val registration = source.location.registrationTo(location)
    return GlassSurfaceSourceDraw(System.identityHashCode(source), surfaceDrawIdentity(source.parent),
        System.identityHashCode(source.layer), surfaceDrawIdentity(source.parent?.layer),
        source.recorded, source.layer.isReleased, source.parent?.recorded, source.parent?.layer?.isReleased,
        source.materialOnly, source.dark, source.size.width, source.size.height,
        source.layer.size.width, source.layer.size.height, source.revision, source.readbackRequest,
        source.snapshotReadbackRequest, source.snapshotDraws?.request, source.snapshotDraws?.composite,
        surfaceDrawIdentity(source.snapshot), surfaceDrawIdentity(source.fallback), null,
        registration?.let { listOf(it.xx, it.xy, it.yx, it.yy, it.tx, it.ty) },
        registration?.inverse()?.corners(androidx.compose.ui.geometry.Rect(Offset.Zero, extent))?.surfaceDrawScalars())
}

@Stable
internal class GlassContact {
    var pressed by mutableStateOf(false)
    var travel by mutableFloatStateOf(0f)
    var modal by mutableStateOf(false)
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
    val expansion = Animatable(0f)
    val state = GlassBodyState()
    val body: State<GlassShape> get() = state.output
}

@Composable
internal fun rememberGlassContact(shared: GlassContact? = null): GlassContact {
    val contact = shared ?: remember { GlassContact() }
    val visible = LocalGlassVisible.current
    rememberGlassBody({ contact.travel + contact.expansion.value }, contact = { if (contact.pressed) 1f else 0f },
        state = contact.state, active = { visible && contact.active && !contact.modal })
    return contact
}

/** Observe touch without consuming it; children retain gesture and accessibility ownership. */
@Composable
internal fun Modifier.glassPress(contact: GlassContact = rememberGlassContact(),
    presentation: () -> GlassShape = { contact.body.value }, admit: () -> Boolean = { true },
    onPressed: (Boolean) -> Unit = {}): Modifier {
    return drawWithContent {
        val shape = presentation()
        scale(shape.scaleX(contact.vertical), shape.scaleY(contact.vertical)) { this@drawWithContent.drawContent() }
    }.observeGlassContact(admit, yieldToParent = true) { contact.travel = 0f; contact.pressed = it; onPressed(it) }
}

/** Shared contact lifetime for ordinary buttons and independently draggable glass bodies.
 * With [yieldToParent], the press lets go as soon as the shared control intent hands the
 * contact to a scrolling or moving ancestor, even while the finger stays on the control. */
@Composable
internal fun Modifier.observeGlassContact(admit: () -> Boolean = { true }, yieldToParent: Boolean = false,
    onPressed: (Boolean) -> Unit): Modifier {
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
                        val movement = point.position - down.position
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

/** Adjustable lens over a separately recorded scene. Foreground content is never filtered. */
@Composable
internal fun LiquidGlassSurface(modifier: Modifier = Modifier,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, tint: Color = Color.Transparent,
    cornerRadius: Dp? = null, frostProgress: Float = 0f, pressFeedback: Boolean = true, refraction: Float = 1f, inkOverride: Color? = null, shadow: Boolean = true,
    body: () -> GlassShape = { GlassShape() }, panelProgress: Float = 0f, panelDark: Boolean? = null, depthScale: Float = modifier.glassDepthScale(),
    homeSource: FrostedBackdrop? = null, homeWeight: () -> Float = { 0f },
    homeBounds: (() -> androidx.compose.ui.geometry.Rect)? = null,
    homeFootprint: (() -> HomeBackdropFootprint?)? = null,
    silhouette: GlassSilhouette? = null, sharedContact: GlassContact? = null,
    contactPresentation: (() -> GlassShape)? = null,
    contactAdmission: () -> Boolean = { true },
    cornerRadiusReader: (() -> Dp?)? = null, frostProgressReader: (() -> Float)? = null,
    depthScaleReader: (() -> Float)? = null,
    content: (@Composable BoxScope.() -> Unit)? = null) {
    val renderer = currentGlassRendererGeneration()
    val drawObserver = LocalGlassSurfaceDrawObserver.current
    // Do not create diagnostic state, closures, geometry or modifiers for ordinary/null callers.
    val observedShadowGroup = if (drawObserver == null) null else LocalSurfaceShadows.current
    val observedShadowAppearance = if (drawObserver == null) null else LocalGlassAppearance.current
    val visible = LocalGlassVisible.current
    if (visible && renderer.backend == GlassBackend.None) {
        BackdropLease(source)
        BackdropLease(homeSource?.takeUnless { it === source })
    }
    val location = remember { BackdropLocation() }
    var surfaceSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val view = LocalView.current
    val appearance = LocalGlassAppearance.current.normalized()
    // The page owns ink polarity. A control never flips independently as content
    // scrolls beneath it, and no GPU readback is needed to choose its foreground.
    val lightMix = inkOverride?.let { if (it.luminance() > .5f) 1f else 0f } ?: (source?.lightMix ?: 0f)
    val dark = lightMix >= .5f
    fun frost() = (frostProgressReader?.invoke() ?: frostProgress).coerceIn(0f, 1f)
    fun radius() = cornerRadiusReader?.invoke() ?: cornerRadius
    fun depth() = depthScaleReader?.invoke() ?: depthScale
    val panel = panelProgress.coerceIn(0f, 1f)
    val tone = GlassTone.mixed(appearance.contrast, appearance.contrastWeight, lightMix)
    val coatingLightMix = panelDark?.let { if (it) 1f else 0f } ?: lightMix
    val coatingDark = coatingLightMix >= .5f
    val ink = inkOverride ?: lerp(Color(0xFF202124), Color.White, lightMix)
    val anchor = modifier.glassSurfaceAnchor()
    val contact = rememberGlassContact(anchor?.contact ?: sharedContact)
    val contactShape = contactPresentation ?: { contact.body.value }
    val surfaceClip = remember(silhouette, cornerRadius, cornerRadiusReader) {
        silhouette?.let { outline -> androidx.compose.foundation.shape.GenericShape { extent, _ ->
            addPath(outline.frame(extent).path)
        } } ?: if (cornerRadiusReader != null) object : androidx.compose.ui.graphics.Shape {
            override fun createOutline(size: Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline =
                (radius()?.let { RoundedCornerShape(it) } ?: CircleShape).createOutline(size, layoutDirection, density)
        } else cornerRadius?.let { RoundedCornerShape(it) } ?: CircleShape
    }
    SideEffect { if (anchor != null) {
        anchor.source = source; anchor.cornerRadius = cornerRadius; anchor.depthScale = depthScale
        anchor.cornerRadiusReader = cornerRadiusReader; anchor.depthScaleReader = depthScaleReader
        anchor.contact.state.impulseScale = kotlin.math.sqrt(96f * view.resources.displayMetrics.density / surfaceSize.height.coerceAtLeast(1)).coerceIn(1f, 1.8f)
    } }
    val lens = remember { lazy { if (Build.VERSION.SDK_INT >= 33) LensRenderer() else null } }
    val rim = remember { GlassRimCache() }
    val popupRim = if (panel > 0f && silhouette == null) remember { PopupGlassRimCache() } else null
    // Transform the surface and its shadow together in the draw pass; a legacy
    // ViewLayer would clip the shadow outside its bounds even with clipping disabled.
    Box(modifier.then(if (pressFeedback) Modifier.glassPress(contact, contactShape,
        admit = contactAdmission) else if (anchor != null) Modifier.drawWithContent {
        val shape = contactShape()
        scale(shape.scaleX(contact.vertical), shape.scaleY(contact.vertical)) { this@drawWithContent.drawContent() }
    } else Modifier)
        .then(if (!shadow) Modifier else if (silhouette != null) Modifier.silhouetteGlassShadow(silhouette)
            else Modifier.surfaceGlassShadow(cornerRadius, anchor, cornerRadiusReader))
        .clip(surfaceClip)
        .backdropLocation(location).onGloballyPositioned { surfaceSize = it.size }, propagateMinConstraints = true) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        fun blur() = with(density) { 22.dp.toPx() } * (appearance.blur + (1f - appearance.blur) * frost())
        val padding = with(density) { 32.dp.toPx() }
        if (visible && source != null && Build.VERSION.SDK_INT >= 33) {
            // Crossing into panel scattering must not swap backing resolution in
            // one frame. Clear glass stays sharp; visible blur gradually permits
            // the cheaper half-resolution recording during the same morph.
            fun resolution(): Float {
                val scattering = ((blur() - 1f) / 5f).coerceIn(0f, 1f)
                return 1f - .5f * scattering * scattering * (3f - 2f * scattering)
            }
            FilteredBackdrop(Modifier.matchParentSize(), source, location, 1f, padding,
                homeSource, homeWeight, MaterialAverageStyle(appearance.whiteTint * (1f - panel), tone), homeBounds, homeFootprint, resolutionReader = ::resolution) { extent, resolution, flatWeight ->
                val pad = padding * resolution
                val radius = (radius()?.let { with(density) { it.toPx() } }?.times(resolution)
                    ?: ((extent.minDimension - 2 * pad) / 2)).coerceAtLeast(0f)
                val thickness = body().thickness * (if (pressFeedback || anchor != null) contactShape().thickness else 1f)
                val outline = silhouette?.frame(Size(extent.width - 2 * pad, extent.height - 2 * pad))
                if (Build.VERSION.SDK_INT >= 33) lens.value?.effect(
                    outline?.geometry?.let { it.copy(bounds = it.bounds.translate(Offset(pad, pad))) }
                        ?: LensGeometry(androidx.compose.ui.geometry.Rect(pad, pad, extent.width - pad, extent.height - pad), radius),
                    blur() * resolution,
                    appearance.depth * depth() * thickness,
                    power = refraction, whiteTint = appearance.whiteTint * (1f - panel), illumination = 1f - flatWeight,
                    flattening = ((1f - thickness) / .18f).coerceIn(0f, 1f), tone = tone,
                    map = outline?.field, origin = Offset(pad, pad), fieldWeight = outline?.description?.fieldWeight ?: 1f,
                    circles = outline?.description?.circles?.let { (a, b) -> a + Offset(pad, pad) to b + Offset(pad, pad) },
                    splitInner = outline?.description?.splitInner)
                else if (blur() > 0f) BlurEffect(blur() * resolution, blur() * resolution, TileMode.Clamp) else null
            }
        }

        if (visible && source != null && Build.VERSION.SDK_INT < 33) {
            AverageBackdrop(Modifier.matchParentSize(), source, location,
                MaterialAverageStyle(appearance.whiteTint * (1f - panel), tone), homeSource, homeWeight, homeBounds, homeFootprint)
        }
        Canvas(Modifier.matchParentSize()) {
            drawRect(tone.color(tint))
            if (panel > 0f && renderer.backend != GlassBackend.None) drawFrostCoating(coatingLightMix, Color.Transparent, panel)
            val outline = silhouette?.frame(size)
            val splitRim = outline?.description?.rimSplit ?: 0f
            val bounds = androidx.compose.ui.geometry.Rect(Offset.Zero, size)
            val radius = radius()?.toPx() ?: size.minDimension / 2f
            // Blend the control finish into the shared panel finish during the morph.
            // Flat panel walls have constant normals; diagonal glints stay on corners.
            val panelRimWeight = if (popupRim == null) 0f else panel
            if (panelRimWeight < 1f) drawGlassRim(bounds, radius,
                path = outline?.path, tone = tone, opacity = (1f - splitRim) * (1f - panelRimWeight), cache = rim)
            popupRim?.let { drawPopupGlassRim(bounds, radius, tone, panelRimWeight, it) }
            if (outline != null && splitRim > 0f) outline.bodies.forEach { part ->
                clipRect(part.left, part.top, part.right, part.bottom) {
                    drawGlassRim(androidx.compose.ui.geometry.Rect(part.left, part.top, part.right, part.bottom),
                        part.height / 2f, path = outline.path, tone = tone, opacity = splitRim)
                }
            }
            // Reuse this existing coating Canvas and its computed outline: no extra draw node,
            // readback, invalidation or filter traversal. Diagnostic reads cannot subscribe drawing
            // to new snapshot state; construction AND callback failures are isolated from rendering.
            if (drawObserver != null) try {
                androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
                    val shadows = requireNotNull(observedShadowAppearance)
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
                        dark, coatingDark, tone.trim, tone.weight, tint.toArgb(), panel, frost(),
                        // Supplied reader sampled here, NOT the private filter's admitted/clamped blend.
                        homeWeight(), appearance.blur, appearance.whiteTint, appearance.depth, depth(), refraction,
                        shadow, shadows.shadowStrength, shadows.shadowSpread,
                        surfaceDrawIdentity(observedShadowGroup),
                        anchor?.shadowInModal, silhouette != null, lightMix, coatingLightMix))
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
    val silhouette = path ?: cache?.roundedPath(bounds, radius) ?: Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(radius))) }
    clipPath(silhouette) {
        drawPath(silhouette, shine, alpha = opacity, style = Stroke(2.6.dp.toPx()))
        drawPath(silhouette, shade, alpha = opacity, style = Stroke(1.4.dp.toPx()))
    }
}
