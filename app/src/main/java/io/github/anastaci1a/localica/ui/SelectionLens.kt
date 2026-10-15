package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** One deformation model for tab, sort and settings selectors, including edge pressure. */
@Composable
internal fun SelectionLens(modifier: Modifier, motion: Animatable<Float, AnimationVector1D>,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, preserveAppearance: Boolean = false,
    vertical: Boolean = false, depthScale: Float = 1f, pressure: () -> Float = { 0f }, wall: () -> Float = { 0f }, contact: () -> Float = { 0f },
    cornerRadius: androidx.compose.ui.unit.Dp? = null, tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    placement: ((GlassShape) -> SelectorTrackLens)? = null, deformation: State<GlassShape>? = null) {
    val body = deformation ?: rememberGlassBody({ motion.value }, pressure, contact)
    val frame = placement?.invoke(body.value)
    val density = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(LocalGlassAppearance provides if (preserveAppearance) LocalGlassAppearance.current else LocalGlassAppearance.current.copy(blur = 0f, whiteTint = 0f, shadowStrength = .04f, contrast = 0f)) {
        // Caller semantics and observers belong to the placed surface, not to the
        // outer offset/wrap coordinator (whose bounds remain at the host origin).
        LiquidGlassSurface(if (frame == null) modifier.selectorDeformation({ body.value }, vertical, wall)
            else Modifier.selectorTrackPlacement(frame.bounds).then(modifier), source = source, pressFeedback = false, shadow = false,
            cornerRadius = frame?.let { with(density) { it.corner.toDp() } } ?: cornerRadius, tint = tint,
            body = { body.value }, depthScale = depthScale,
            inkOverride = if (preserveAppearance) androidx.compose.ui.graphics.Color.Black else null) { Box(Modifier.matchParentSize().testTag("elastic-lens-face")) }
    }
}

/** Keep sampling origins in layout space while deforming the displayed body. */
internal fun Modifier.selectorDeformation(body: () -> GlassShape, vertical: Boolean,
    wall: () -> Float = { 0f }): Modifier = graphicsLayer {
    val deformation = body().selectorScale(vertical, size, 6.dp.toPx())
    // Contact translation is real movement: sampling must follow it. Scaling is
    // draw-only, as in GlassMorph, so it cannot shift the sampled center.
    translationX = if (vertical) 0f else glassWallTranslation(size.width, deformation.x, wall())
    translationY = if (vertical) glassWallTranslation(size.height, deformation.y, wall()) else 0f
}.drawWithContent {
    val deformation = body().selectorScale(vertical, size, 6.dp.toPx())
    scale(deformation.x, deformation.y) { this@drawWithContent.drawContent() }
}

private fun GlassShape.selectorScale(vertical: Boolean, size: Size, allowance: Float): Offset {
    // Only the travel axis has space to compress. Keep cross-axis strain
    // inside the host's 4 dp inset, including very wide vertical options.
    val allowanceX = 1f + allowance / size.width.coerceAtLeast(1f)
    val allowanceY = 1f + allowance / size.height.coerceAtLeast(1f)
    return Offset(
        scaleX(vertical).coerceIn(if (vertical) 2f - allowanceX else .5f, allowanceX),
        scaleY(vertical).coerceIn(if (!vertical) 2f - allowanceY else .5f, allowanceY))
}
