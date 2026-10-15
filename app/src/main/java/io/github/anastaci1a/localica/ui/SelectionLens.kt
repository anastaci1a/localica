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
    cornerRadius: androidx.compose.ui.unit.Dp? = null, tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent) {
    val body = rememberGlassBody({ motion.value }, pressure, contact)
    CompositionLocalProvider(LocalGlassAppearance provides if (preserveAppearance) LocalGlassAppearance.current else LocalGlassAppearance.current.copy(blur = 0f, whiteTint = 0f, shadowStrength = .04f, contrast = 0f)) {
        LiquidGlassSurface(modifier.selectorDeformation({ body.value }, vertical, wall), source = source, pressFeedback = false, shadow = false, cornerRadius = cornerRadius, tint = tint,
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

/** Experimental smooth cross-axis bound, sized by the available travel extent. */
internal fun glassCrossScale(scale: Float, extent: Float, travelExtent: Float, allowance: Float): Float {
    val size = extent.coerceAtLeast(1f)
    val cap = minOf(allowance, travelExtent.coerceAtLeast(0f) * .04f)
    if (cap <= 0f) return 1f
    val displacement = (scale - 1f) * size
    return 1f + cap * displacement / (cap + kotlin.math.abs(displacement)) / size
}

private fun GlassShape.selectorScale(vertical: Boolean, size: Size, allowance: Float): Offset {
    val allowanceX = 1f + allowance / size.width.coerceAtLeast(1f)
    val allowanceY = 1f + allowance / size.height.coerceAtLeast(1f)
    return if (vertical) Offset(
        glassCrossScale(scaleX(true), size.width, size.height, allowance),
        scaleY(true).coerceIn(.5f, allowanceY))
    else Offset(scaleX().coerceIn(.5f, allowanceX),
        glassCrossScale(scaleY(), size.height, size.width, allowance))
}
