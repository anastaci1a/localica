package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*

/** An optional origin-shape stage, owned by the same lifecycle as the popup.
 * Reversals retain animation velocity; disposal abandons the old shape entirely. */
@Stable
internal class GlassOriginPreparation {
    private var motion by mutableStateOf(Animatable(0f))
    val progress: Float get() = motion.value.coerceIn(0f, 1f)

    suspend fun settle(prepared: Boolean, reduced: Boolean, speed: Float) {
        val target = if (prepared) 1f else 0f
        if (reduced) motion.snapTo(target)
        else motion.animateTo(target, glassMorphSpring(speed))
    }

    fun reset() { motion = Animatable(0f) }
}

@Composable
internal fun rememberGlassOriginPreparation() = remember { GlassOriginPreparation() }
