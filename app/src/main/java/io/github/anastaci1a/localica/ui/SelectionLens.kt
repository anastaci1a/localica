package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.exp

/** One deformation model for tab, sort and settings selectors, including edge pressure. */
@Composable
internal fun SelectionLens(modifier: Modifier, motion: Animatable<Float, AnimationVector1D>,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, preserveAppearance: Boolean = false,
    vertical: Boolean = false, depthScale: Float = 1f, pressure: () -> Float = { 0f }, wall: () -> Float = { 0f }, contact: () -> Float = { 0f }) {
    val body = rememberGlassBody({ motion.value }, pressure, contact)
    CompositionLocalProvider(LocalGlassAppearance provides if (preserveAppearance) LocalGlassAppearance.current else LocalGlassAppearance.current.copy(blur = 0f, whiteTint = 0f, shadowStrength = .04f, contrast = 0f)) {
        LiquidGlassSurface(modifier.graphicsLayer {
            // Only the travel axis has space to compress. Keep cross-axis strain
            // inside the host's 4 dp inset, including very wide vertical options.
            val allowanceX = 1f + 6.dp.toPx() / size.width.coerceAtLeast(1f)
            val allowanceY = 1f + 6.dp.toPx() / size.height.coerceAtLeast(1f)
            scaleX = body.value.scaleX(vertical).coerceIn(if (vertical) 2f - allowanceX else .5f, allowanceX)
            scaleY = body.value.scaleY(vertical).coerceIn(if (!vertical) 2f - allowanceY else .5f, allowanceY)
            translationX = if (vertical) 0f else glassWallTranslation(size.width, scaleX, wall())
            translationY = if (vertical) glassWallTranslation(size.height, scaleY, wall()) else 0f
        }, source = source, pressFeedback = false, shadow = false,
            body = { body.value }, depthScale = depthScale,
            inkOverride = if (preserveAppearance) androidx.compose.ui.graphics.Color.Black else null) { Box(Modifier.matchParentSize().testTag("elastic-lens-face")) }
    }
}
