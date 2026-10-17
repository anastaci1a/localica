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
    presentation: () -> GlassShape = { contact.body.value }, onPressed: (Boolean) -> Unit = {}): Modifier {
    return drawWithContent {
        val shape = presentation()
        scale(shape.scaleX(contact.vertical), shape.scaleY(contact.vertical)) { this@drawWithContent.drawContent() }
    }.observeGlassContact { contact.travel = 0f; contact.pressed = it; onPressed(it) }
}

/** Shared contact lifetime for ordinary buttons and independently draggable glass bodies. */
@Composable
internal fun Modifier.observeGlassContact(onPressed: (Boolean) -> Unit): Modifier {
    val notify by rememberUpdatedState(onPressed)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            notify(true)
            try {
                // Visual pressure belongs to the initiating finger until it lifts.
                // Clickable still cancels activation when that finger leaves the target.
                do { val event = awaitPointerEvent(PointerEventPass.Final) }
                while (event.changes.any { it.id == down.id && it.pressed })
            } finally { notify(false) }
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
    silhouette: GlassSilhouette? = null, sharedContact: GlassContact? = null,
    contactPresentation: (() -> GlassShape)? = null,
    content: (@Composable BoxScope.() -> Unit)? = null) {
    val visible = LocalGlassVisible.current
    if (visible && Build.VERSION.SDK_INT < 33) { BackdropLease(source); BackdropLease(homeSource?.takeUnless { it === source }) }
    val location = remember { BackdropLocation() }
    var surfaceSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val view = LocalView.current
    val appearance = LocalGlassAppearance.current.normalized()
    // The page owns ink polarity. A control never flips independently as content
    // scrolls beneath it, and no GPU readback is needed to choose its foreground.
    val dark = inkOverride?.let { it.luminance() > .5f } ?: (source?.dark == true)
    val frost = frostProgress.coerceIn(0f, 1f)
    val panel = panelProgress.coerceIn(0f, 1f)
    val tone = GlassTone(appearance.contrast * (1f - panel), appearance.contrastWeight, dark)
    val coatingDark = panelDark ?: dark
    val panelBase = if (coatingDark) Color.Black else androidx.compose.material3.MaterialTheme.colorScheme.surface
    val anchor = modifier.glassSurfaceAnchor()
    val contact = rememberGlassContact(anchor?.contact ?: sharedContact)
    val contactShape = contactPresentation ?: { contact.body.value }
    val surfaceClip = remember(silhouette, cornerRadius) {
        silhouette?.let { outline -> androidx.compose.foundation.shape.GenericShape { extent, _ ->
            addPath(outline.frame(extent).path)
        } } ?: cornerRadius?.let { RoundedCornerShape(it) } ?: CircleShape
    }
    SideEffect { if (anchor != null) {
        anchor.source = source; anchor.cornerRadius = cornerRadius; anchor.depthScale = depthScale
        anchor.contact.state.impulseScale = kotlin.math.sqrt(96f * view.resources.displayMetrics.density / surfaceSize.height.coerceAtLeast(1)).coerceIn(1f, 1.8f)
    } }
    val lens = remember { lazy { if (Build.VERSION.SDK_INT >= 33) LensRenderer() else null } }
    val mesh = remember { lazy { LensMeshRenderer() } }
    // Transform the surface and its shadow together in the draw pass; a legacy
    // ViewLayer would clip the shadow outside its bounds even with clipping disabled.
    Box(modifier.then(if (pressFeedback) Modifier.glassPress(contact, contactShape) else if (anchor != null) Modifier.drawWithContent {
        val shape = contactShape()
        scale(shape.scaleX(contact.vertical), shape.scaleY(contact.vertical)) { this@drawWithContent.drawContent() }
    } else Modifier)
        .then(if (!shadow) Modifier else if (silhouette != null) Modifier.silhouetteGlassShadow(silhouette)
            else Modifier.surfaceGlassShadow(cornerRadius, anchor))
        .clip(surfaceClip)
        .backdropLocation(location).onGloballyPositioned { surfaceSize = it.size }, propagateMinConstraints = true) {
        if (visible && source != null && Build.VERSION.SDK_INT >= 33) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val blur = with(density) { 22.dp.toPx() } * (appearance.blur + (1f - appearance.blur) * frost)
            val padding = with(density) { 32.dp.toPx() }
            // Crossing into panel scattering must not swap backing resolution in
            // one frame. Clear glass stays sharp; visible blur gradually permits
            // the cheaper half-resolution recording during the same morph.
            val scattering = ((blur - 1f) / 5f).coerceIn(0f, 1f)
            val resolution = 1f - .5f * scattering * scattering * (3f - 2f * scattering)
            FilteredBackdrop(Modifier.matchParentSize(), source, location::position, resolution, padding,
                homeSource, homeWeight, MaterialAverageStyle(appearance.whiteTint * (1f - panel), tone), homeBounds) { extent, resolution, flatWeight ->
                val pad = padding * resolution
                val radius = (cornerRadius?.let { with(density) { it.toPx() } }?.times(resolution)
                    ?: ((extent.minDimension - 2 * pad) / 2)).coerceAtLeast(0f)
                val thickness = body().thickness * (if (pressFeedback || anchor != null) contactShape().thickness else 1f)
                val outline = silhouette?.frame(Size(extent.width - 2 * pad, extent.height - 2 * pad))
                if (Build.VERSION.SDK_INT >= 33) lens.value?.effect(
                    outline?.geometry?.let { it.copy(bounds = it.bounds.translate(Offset(pad, pad))) }
                        ?: LensGeometry(androidx.compose.ui.geometry.Rect(pad, pad, extent.width - pad, extent.height - pad), radius),
                    blur * resolution,
                    appearance.depth * depthScale * thickness,
                    power = refraction, whiteTint = appearance.whiteTint * (1f - panel), illumination = 1f - flatWeight,
                    flattening = ((1f - thickness) / .18f).coerceIn(0f, 1f), tone = tone,
                    map = outline?.field, origin = Offset(pad, pad), fieldWeight = outline?.description?.fieldWeight ?: 1f,
                    circles = outline?.description?.circles?.let { (a, b) -> a + Offset(pad, pad) to b + Offset(pad, pad) },
                    splitInner = outline?.description?.splitInner)
                else if (blur > 0f) BlurEffect(blur * resolution, blur * resolution, TileMode.Clamp) else null
            }
        }

        if (visible && Build.VERSION.SDK_INT < 33) {
        val endpointTone = GlassTone(appearance.contrast, appearance.contrastWeight, dark)
        @Composable fun prepared(backing: FrostedBackdrop?): List<ImageBitmap?> {
            val clear = backing?.glassFallback ?: backing?.snapshot
            val frosted = backing?.fallback ?: clear
            // Morphs blend fixed prepared endpoints; progress must never schedule
            // a new pixel filter on every animation frame.
            return listOf(clear,
                rememberToneSnapshot((backing?.lensSnapshot ?: clear).takeIf { frost < 1f && panel < 1f }, endpointTone),
                frosted,
                rememberToneSnapshot((backing?.lensFrostSnapshot ?: frosted).takeIf { frost > 0f && panel < 1f }, endpointTone))
        }
        val sourceImages = prepared(source)
        val homeImages = prepared(homeSource)
        Canvas(Modifier.matchParentSize()) {
            val outline = silhouette?.frame(size)
            val shape = outline?.geometry ?: LensGeometry(androidx.compose.ui.geometry.Rect(Offset.Zero, size),
                (cornerRadius?.toPx() ?: size.minDimension / 2f).coerceIn(0f, size.minDimension / 2f))
            val thickness = body().thickness * (if (pressFeedback || anchor != null) contactShape().thickness else 1f)
            val depth = appearance.depth * depthScale * thickness
            val flattening = ((1f - thickness) / .18f).coerceIn(0f, 1f)
            val home = if (homeSource?.lensSnapshot != null || homeSource?.snapshot != null) homeWeight().coerceIn(0f, 1f) else 0f
            // Blur and tint are independent endpoints. Premultiplied addition
            // preserves opacity while either varies, including a source handoff.
            val samples = mutableListOf<Triple<FrostedBackdrop, ImageBitmap, Float>>()
            fun samples(backing: FrostedBackdrop?, imagesByRole: List<ImageBitmap?>, weight: Float) {
                if (backing == null || weight <= 0f) return
                val images = linkedMapOf<ImageBitmap, Float>()
                fun add(image: ImageBitmap?, amount: Float) {
                    if (image != null && amount > 0f) images[image] = (images[image] ?: 0f) + amount * weight
                }
                add(imagesByRole[1], (1f - frost) * (1f - panel))
                add(imagesByRole[0], (1f - frost) * panel)
                add(imagesByRole[3], frost * (1f - panel))
                add(imagesByRole[2], frost * panel)
                images.forEach { (image, amount) -> samples += Triple(backing, image, amount) }
            }
            samples(source, sourceImages, 1f - home); samples(homeSource, homeImages, home)
            val blending = samples.size > 1
            if (blending) drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), Paint())
            with(mesh.value) {
                samples.forEachIndexed { index, (backing, image, amount) ->
                    draw(shape, image, backing.size, location.position() - backing.origin, depth, refraction,
                        amount, lighting = false, additive = index > 0, flattening = flattening,
                        field = outline?.let { frame -> { point -> frame.opticalField(point) } }, fieldKey = outline,
                        outlines = outline?.bodies)
                }
                if (blending) drawContext.canvas.restore()
                draw(shape, null, androidx.compose.ui.unit.IntSize.Zero, Offset.Zero, depth, refraction, flattening = flattening, tone = tone,
                    field = outline?.let { frame -> { point -> frame.opticalField(point) } }, fieldKey = outline,
                    outlines = outline?.bodies)
            }
        }
        }
        Canvas(Modifier.matchParentSize()) {
            drawRect(tone.color(tint))
            if (panel > 0f) drawFrostCoating(coatingDark, panelBase, Color.Transparent, .5f, panel)
            val outline = silhouette?.frame(size)
            val splitRim = outline?.description?.rimSplit ?: 0f
            drawGlassRim(androidx.compose.ui.geometry.Rect(Offset.Zero, size), cornerRadius?.toPx() ?: size.minDimension / 2f,
                path = outline?.path, tone = tone, opacity = 1f - splitRim)
            if (outline != null && splitRim > 0f) outline.bodies.forEach { part ->
                clipRect(part.left, part.top, part.right, part.bottom) {
                    drawGlassRim(androidx.compose.ui.geometry.Rect(part.left, part.top, part.right, part.bottom),
                        part.height / 2f, path = outline.path, tone = tone, opacity = splitRim)
                }
            }

        }
        val ink = inkOverride ?: if (dark) Color.White else Color(0xFF202124)
        if (content != null) CompositionLocalProvider(LocalContentColor provides ink) {
            Box(content = content)
        }
    }
}

/** A restrained ambient reflection on the silhouette, never a plate across the face.
 * Shared by live/mesh controls and connected selections at the final display resolution. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassRim(
    bounds: androidx.compose.ui.geometry.Rect, radius: Float, path: Path? = null, tone: GlassTone = GlassTone(), opacity: Float = 1f) {
    val white = tone.color(Color.White)
    val black = tone.color(Color.Black)
    // Diagonally opposed highlights taper into quiet, slightly shaded corners.
    // The silhouette clips both strokes inward; neither changes the hit geometry.
    val shine = Brush.sweepGradient(
        0f to white.copy(alpha = .025f), .125f to white.copy(alpha = .32f),
        .25f to white.copy(alpha = .025f), .375f to Color.Transparent,
        .5f to white.copy(alpha = .025f), .625f to white.copy(alpha = .32f),
        .75f to white.copy(alpha = .025f), .875f to Color.Transparent,
        1f to white.copy(alpha = .025f), center = bounds.center)
    val shade = Brush.sweepGradient(
        0f to Color.Transparent, .125f to Color.Transparent, .25f to Color.Transparent,
        .375f to black.copy(alpha = .11f), .5f to Color.Transparent,
        .625f to Color.Transparent, .75f to Color.Transparent, .875f to black.copy(alpha = .11f),
        1f to Color.Transparent, center = bounds.center)
    val silhouette = path ?: Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(radius))) }
    clipPath(silhouette) {
        drawPath(silhouette, shine, alpha = opacity, style = Stroke(2.6.dp.toPx()))
        drawPath(silhouette, shade, alpha = opacity, style = Stroke(1.4.dp.toPx()))
    }
}
