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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
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
    var vertical by mutableStateOf(false)
    val expansion = Animatable(0f)
    val state = GlassBodyState()
    val body: State<GlassShape> get() = state.output
}

@Composable
internal fun rememberGlassContact(shared: GlassContact? = null): GlassContact {
    val contact = shared ?: remember { GlassContact() }
    rememberGlassBody({ contact.travel + contact.expansion.value }, contact = { if (contact.pressed) 1f else 0f },
        state = contact.state, active = { !contact.modal })
    return contact
}

/** Observe touch without consuming it; children retain gesture and accessibility ownership. */
@Composable
internal fun Modifier.glassPress(contact: GlassContact = rememberGlassContact(), onPressed: (Boolean) -> Unit = {}): Modifier {
    return drawWithContent {
        scale(contact.body.value.scaleX(contact.vertical), contact.body.value.scaleY(contact.vertical)) { this@drawWithContent.drawContent() }
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
    content: (@Composable BoxScope.() -> Unit)? = null) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var surfaceSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val view = LocalView.current
    val appearance = LocalGlassAppearance.current.normalized()
    val samplePixels = source?.contrastPixels
    val sample = samplePixels?.let { pixels -> remember(pixels, origin, surfaceSize, source?.size) {
        source?.let { source ->
            var total = 0f
            for (dy in 1..3) for (dx in 1..3) {
                val x = ((origin.x + surfaceSize.width * dx / 4f - source.origin.x) / source.size.width.coerceAtLeast(1) * pixels.width).toInt().coerceIn(0, pixels.width - 1)
                val y = ((origin.y + surfaceSize.height * dy / 4f - source.origin.y) / source.size.height.coerceAtLeast(1) * pixels.height).toInt().coerceIn(0, pixels.height - 1)
                total += pixels[x, y].luminance()
            }
            total / 9
        }
    } }
    var sampledDark by remember(source) { mutableStateOf(source?.dark == true) }
    val filteredLight by animateFloatAsState(sample ?: if (sampledDark) 0f else 1f,
        tween(if (LocalReducedMotion.current) 0 else 120), label = "Glass contrast sampling")
    SideEffect {
        if (sample != null) {
            if (filteredLight < .25f) sampledDark = true
            else if (filteredLight > .55f) sampledDark = false
        }
    }
    val dark = inkOverride?.let { it.luminance() > .5f } ?: if (appearance.whiteTint >= .45f) false
        else if (appearance.contrast > 0f && panelProgress == 0f) sampledDark else source?.dark == true
    val frost = frostProgress.coerceIn(0f, 1f)
    val panel = panelProgress.coerceIn(0f, 1f)
    val coatingDark = panelDark ?: dark
    val panelBase = if (coatingDark) Color.Black else androidx.compose.material3.MaterialTheme.colorScheme.surface
    val anchor = modifier.glassSurfaceAnchor()
    val contact = rememberGlassContact(anchor?.contact)
    SideEffect { if (anchor != null) {
        anchor.source = source; anchor.cornerRadius = cornerRadius; anchor.depthScale = depthScale
    } }
    val lens = remember { if (Build.VERSION.SDK_INT >= 33) LensRenderer() else null }
    val mesh = remember { LensMeshRenderer() }
    // Transform the surface and its shadow together in the draw pass; a legacy
    // ViewLayer would clip the shadow outside its bounds even with clipping disabled.
    Box(modifier.then(if (pressFeedback) Modifier.glassPress(contact) else if (anchor != null) Modifier.drawWithContent {
        scale(contact.body.value.scaleX(contact.vertical), contact.body.value.scaleY(contact.vertical)) { this@drawWithContent.drawContent() }
    } else Modifier)
        .then(if (shadow) Modifier.clearGlassShadow(appearance.shadowStrength, appearance.shadowSpread, cornerRadius) else Modifier)
        .clip(cornerRadius?.let { RoundedCornerShape(it) } ?: CircleShape)
        .onGloballyPositioned { origin = it.backdropPosition(view); surfaceSize = it.size }, propagateMinConstraints = true) {
        if (source != null && Build.VERSION.SDK_INT >= 33) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val blur = with(density) { 22.dp.toPx() } * (appearance.blur + (1f - appearance.blur) * frost)
            val padding = with(density) { 32.dp.toPx() }
            // Crossing into panel scattering must not swap backing resolution in
            // one frame. Clear glass stays sharp; visible blur gradually permits
            // the cheaper half-resolution recording during the same morph.
            val scattering = ((blur - 1f) / 5f).coerceIn(0f, 1f)
            val resolution = 1f - .5f * scattering * scattering * (3f - 2f * scattering)
            FilteredBackdrop(Modifier.matchParentSize(), source, { origin }, resolution, padding,
                homeSource, homeWeight) { extent, resolution ->
                val pad = padding * resolution
                val radius = (cornerRadius?.let { with(density) { it.toPx() } }?.times(resolution)
                    ?: ((extent.minDimension - 2 * pad) / 2)).coerceAtLeast(0f)
                val thickness = body().thickness * (if (pressFeedback || anchor != null) contact.body.value.thickness else 1f)
                if (Build.VERSION.SDK_INT >= 33) lens?.effect(
                    LensGeometry(androidx.compose.ui.geometry.Rect(pad, pad, extent.width - pad, extent.height - pad), radius),
                    blur * resolution,
                    appearance.depth * depthScale * thickness,
                    power = refraction, whiteTint = appearance.whiteTint * (1f - panel),
                    flattening = ((1f - thickness) / .18f).coerceIn(0f, 1f))
                else if (blur > 0f) BlurEffect(blur * resolution, blur * resolution, TileMode.Clamp) else null
            }
        }

        if (Build.VERSION.SDK_INT < 33) Canvas(Modifier.matchParentSize()) {
            val shape = LensGeometry(androidx.compose.ui.geometry.Rect(Offset.Zero, size),
                (cornerRadius?.toPx() ?: size.minDimension / 2f).coerceIn(0f, size.minDimension / 2f))
            val thickness = body().thickness * (if (pressFeedback || anchor != null) contact.body.value.thickness else 1f)
            val depth = appearance.depth * depthScale * thickness
            val flattening = ((1f - thickness) / .18f).coerceIn(0f, 1f)
            val home = if (homeSource?.lensSnapshot != null || homeSource?.snapshot != null) homeWeight().coerceIn(0f, 1f) else 0f
            // Blur and tint are independent endpoints. Premultiplied addition
            // preserves opacity while either varies, including a source handoff.
            val samples = mutableListOf<Triple<FrostedBackdrop, ImageBitmap, Float>>()
            fun samples(backing: FrostedBackdrop?, weight: Float) {
                if (backing == null || weight <= 0f) return
                val clear = backing.glassFallback ?: backing.snapshot ?: return
                val tinted = backing.lensSnapshot ?: clear
                val frosted = backing.fallback ?: clear
                val tintedFrost = backing.lensFrostSnapshot ?: frosted
                val images = linkedMapOf<ImageBitmap, Float>()
                fun add(image: ImageBitmap, amount: Float) {
                    if (amount > 0f) images[image] = (images[image] ?: 0f) + amount * weight
                }
                add(tinted, (1f - frost) * (1f - panel))
                add(clear, (1f - frost) * panel)
                add(tintedFrost, frost * (1f - panel))
                add(frosted, frost * panel)
                images.forEach { (image, amount) -> samples += Triple(backing, image, amount) }
            }
            samples(source, 1f - home); samples(homeSource, home)
            val blending = samples.size > 1
            if (blending) drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), Paint())
            with(mesh) {
                samples.forEachIndexed { index, (backing, image, amount) ->
                    draw(shape, image, backing.size, origin - backing.origin, depth, refraction,
                        amount, lighting = false, additive = index > 0, flattening = flattening)
                }
                if (blending) drawContext.canvas.restore()
                draw(shape, null, androidx.compose.ui.unit.IntSize.Zero, Offset.Zero, depth, refraction, flattening = flattening)
            }
        }
        Canvas(Modifier.matchParentSize()) {
            drawRect(tint)
            if (panel > 0f) drawFrostCoating(coatingDark, panelBase, Color.Transparent, .5f, panel)

        }
        val ink = inkOverride ?: if (dark) Color.White else Color(0xFF202124)
        if (content != null) CompositionLocalProvider(LocalContentColor provides ink) {
            Box(Modifier.lensInkSupport(ink, appearance.contrast * (1f - panel)), content = content)
        }
    }
}
