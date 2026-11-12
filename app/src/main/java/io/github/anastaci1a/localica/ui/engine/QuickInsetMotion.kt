package io.github.anastaci1a.localica.ui.engine

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import io.github.anastaci1a.localica.ui.spec.ListMotionSpec
import io.github.anastaci1a.localica.ui.spec.MotionProfile
import io.github.anastaci1a.localica.ui.spec.MotionProfileSpec
import io.github.anastaci1a.localica.ui.spec.UiSpec
import kotlin.math.roundToInt

/** Validated selection time and the same easing used by list scrolling. */
internal fun quickInsetAnimationSpec(spec: UiSpec, reduced: Boolean, speed: Float): AnimationSpec<Float> {
    val duration = (spec.motion.getValue(MotionProfile.Selection) as MotionProfileSpec).durationMs
    val easing = (spec.motion.getValue(MotionProfile.Scroll) as ListMotionSpec).easing
    return if (reduced) snap() else tween((duration / speed).roundToInt().coerceAtLeast(1),
        easing = CubicBezierEasing(easing.x1, easing.y1, easing.x2, easing.y2))
}

/** One clock for the pill and its admitted scroll room. Hosts receive only its displayed state. */
@Composable
internal fun rememberQuickInsetProgress(spec: UiSpec, visible: Boolean, measured: Boolean, reduced: Boolean, speed: Float): State<Float> {
    val animation = remember(spec, reduced, speed) { quickInsetAnimationSpec(spec, reduced, speed) }
    return rememberTweenState(if (visible && measured) 1f else 0f, animation, label = "Selection panel occupancy")
}

/** A measured occupancy destination is applied only within its owner's Foundation mutation. */
internal fun ScrollScope.consumeInsetDestination(destination: Float, admitted: Float, consumed: Float): Float =
    scrollBy(minOf(destination, admitted) - consumed)

/** The existing Compose-backed scalar evaluator is also the exact compatibility path for semantic scene tweens. */
@Composable private fun rememberTweenState(target: Float, animation: AnimationSpec<Float>, label: String): State<Float> =
    animateFloatAsState(target, animation, label = label)

internal fun sceneMotionDuration(spec: UiSpec, profile: MotionProfile, reduced: Boolean, faster: Boolean): Int {
    val normal = (spec.motion.getValue(profile) as MotionProfileSpec).durationMs
    if (reduced) return 0
    return when (profile) {
        MotionProfile.NavigationChrome, MotionProfile.MiniPlayerInk, MotionProfile.PlayerAspect, MotionProfile.NavigationRoute ->
            if (faster) normal / 2 else normal * 3 / 4
        MotionProfile.Heading -> if (faster) normal * 2 / 3 else normal
        else -> normal
    }
}
internal fun <T> sceneTween(spec: UiSpec, profile: MotionProfile, reduced: Boolean, faster: Boolean): androidx.compose.animation.core.TweenSpec<T> {
    val easing = (spec.motion.getValue(profile) as MotionProfileSpec).easing
    return tween(sceneMotionDuration(spec, profile, reduced, faster),
        easing = CubicBezierEasing(easing.x1, easing.y1, easing.x2, easing.y2))
}
@Composable internal fun rememberSceneFloat(target: Float, profile: MotionProfile, reduced: Boolean, faster: Boolean): State<Float> {
    val spec = io.github.anastaci1a.localica.ui.spec.LocalUiSpec.current.spec
    return rememberTweenState(target, sceneTween(spec, profile, reduced, faster), "Shared scene value")
}
