package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs

/** Travel stretches the lens; spring overshoot compresses it against its destination. */
@Composable
internal fun SelectionLens(modifier: Modifier, motion: Animatable<Float, AnimationVector1D>, target: Float,
    source: FrostedBackdrop? = LocalFrostedBackdrop.current) {
    val reduced = LocalReducedMotion.current
    CompositionLocalProvider(LocalGlassAppearance provides LocalGlassAppearance.current.copy(blur = 0f, whiteTint = 0f, shadowStrength = .04f)) {
        LiquidGlassSurface(modifier.graphicsLayer {
            val travel = if (reduced) 0f else (abs(motion.velocity) * .012f).coerceAtMost(.075f)
            val impact = if (reduced) 0f else if ((motion.value - target) * motion.velocity < 0f)
                abs(motion.value - target).coerceAtMost(.1f) else 0f
            scaleX = 1f + travel - impact
            scaleY = 1f - travel + impact
        }, source = source, pressFeedback = false)
    }
}
