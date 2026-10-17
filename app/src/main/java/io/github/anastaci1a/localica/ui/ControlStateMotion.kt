package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.roundToInt

// Semantic activation is supplied by the caller, never inferred from alpha direction.
// Normal 180/270 ms makes activation decisive while gently shortening the former
// 300 ms deactivation; Faster preserves the ratio at 120/180 ms. No onset delay.
// Input and semantics always use the logical state, independent of this clock.
internal val ControlStateEasing = LinearOutSlowInEasing
internal fun controlStateDuration(active: Boolean, reducedMotion: Boolean, speed: Float): Int =
    if (reducedMotion) 0 else ((if (active) 240f else 360f) / speed).roundToInt().coerceAtLeast(0)

@Composable
private fun <T> controlStateAnimationSpec(active: Boolean, reducedMotion: Boolean): TweenSpec<T> =
    tween(controlStateDuration(active, reducedMotion, LocalMotionSpeed.current), easing = ControlStateEasing)

/** Retargets from the displayed value, including rapid reversals; reduced motion snaps. */
@Composable
internal fun animateControlStateFloat(target: Float, active: Boolean, label: String,
    reducedMotion: Boolean = LocalReducedMotion.current): State<Float> {
    val animated = animateFloatAsState(target, controlStateAnimationSpec(active, reducedMotion), label = label)
    val immediate = rememberUpdatedState(target)
    return if (reducedMotion) immediate else animated
}

/** Inherited ink can change independently; only availability owns this clock. */
@Composable
internal fun controlStateColor(enabled: Boolean, enabledColor: Color, disabledColor: Color, label: String): Color {
    val progress by animateControlStateFloat(if (enabled) 1f else 0f, enabled, label)
    return lerp(disabledColor, enabledColor, progress)
}
