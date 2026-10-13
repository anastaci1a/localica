package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import kotlin.math.exp

/** One deformation model for tab, sort and settings selectors, including edge pressure. */
@Composable
internal fun SelectionLens(modifier: Modifier, motion: Animatable<Float, AnimationVector1D>,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, preserveAppearance: Boolean = false,
    vertical: Boolean = false, depthScale: Float = 1f, pressure: () -> Float = { 0f }, wall: () -> Float = { 0f }) {
    val body = rememberGlassBody({ motion.value }, pressure)
    CompositionLocalProvider(LocalGlassAppearance provides if (preserveAppearance) LocalGlassAppearance.current else LocalGlassAppearance.current.copy(blur = 0f, whiteTint = 0f, shadowStrength = .04f, contrast = 0f)) {
        LiquidGlassSurface(modifier.graphicsLayer {
            scaleX = body.value.scaleX(vertical); scaleY = body.value.scaleY(vertical)
            translationX = if (vertical) 0f else glassWallTranslation(size.width, scaleX, wall())
            translationY = if (vertical) glassWallTranslation(size.height, scaleY, wall()) else 0f
        }, source = source, pressFeedback = false, shadow = false,
            body = { body.value }, depthScale = depthScale,
            inkOverride = if (preserveAppearance) androidx.compose.ui.graphics.Color.Black else null,
            protectCenter = preserveAppearance && LocalGlassAppearance.current.tabStyle == io.github.anastaci1a.localica.navigation.TabLensStyle.Overlay) { Box(Modifier.matchParentSize().testTag("elastic-lens-face")) }
    }
}
