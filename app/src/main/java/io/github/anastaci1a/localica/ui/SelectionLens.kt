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
    vertical: Boolean = false, pressure: () -> Float = { 0f }) {
    val strain = rememberGlassElastic({ motion.value }, pressure)
    CompositionLocalProvider(LocalGlassAppearance provides if (preserveAppearance) LocalGlassAppearance.current else LocalGlassAppearance.current.copy(blur = 0f, whiteTint = 0f, shadowStrength = .04f, contrast = 0f)) {
        LiquidGlassSurface(modifier.graphicsLayer {
            val value = strain.value * if (vertical) -1f else 1f
            scaleX = exp(value); scaleY = exp(-value)
        }, source = source, pressFeedback = false, shadow = false,
            inkOverride = if (preserveAppearance) androidx.compose.ui.graphics.Color.Black else null,
            protectCenter = preserveAppearance && LocalGlassAppearance.current.tabStyle == io.github.anastaci1a.localica.navigation.TabLensStyle.Overlay) { Box(Modifier.matchParentSize().testTag("elastic-lens-face")) }
    }
}
