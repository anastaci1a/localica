package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs

/** Travel stretches the lens; arrival compresses it against its destination. */
@Composable
internal fun SelectionLens(modifier: Modifier, motion: Animatable<Float, AnimationVector1D>, target: Float,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current, preserveAppearance: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val impactMotion = remember { Animatable(0f) }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(target, reduced) {
        if (!initialized) { initialized = true; return@LaunchedEffect }
        if (reduced) impactMotion.snapTo(0f)
        else {
            kotlinx.coroutines.delay(150)
            impactMotion.animateTo(.055f, androidx.compose.animation.core.tween(75))
            impactMotion.animateTo(0f, androidx.compose.animation.core.spring(.38f, 550f))
        }
    }
    CompositionLocalProvider(LocalGlassAppearance provides if (preserveAppearance) LocalGlassAppearance.current else LocalGlassAppearance.current.copy(blur = 0f, whiteTint = 0f, shadowStrength = .04f, contrast = 0f)) {
        LiquidGlassSurface(modifier.graphicsLayer {
            val travel = if (reduced) 0f else (abs(motion.velocity) * .012f).coerceAtMost(.075f)
            val impact = if (reduced) 0f else if ((motion.value - target) * motion.velocity < 0f)
                (abs(motion.velocity) * .02f * (1f - abs(motion.value - target).coerceIn(0f, 1f))).coerceAtMost(.08f) else 0f
            scaleX = 1f + travel - impact - impactMotion.value
            scaleY = 1f - travel + impact + impactMotion.value
        }, source = source, pressFeedback = false, inkOverride = if (preserveAppearance) androidx.compose.ui.graphics.Color.Black else null,
            protectCenter = preserveAppearance && LocalGlassAppearance.current.tabStyle == io.github.anastaci1a.localica.navigation.TabLensStyle.Overlay)
    }
}
