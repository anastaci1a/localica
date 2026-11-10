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
import io.github.anastaci1a.localica.ui.spec.LocalUiSpec
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
internal fun rememberQuickInsetProgress(visible: Boolean, measured: Boolean, reduced: Boolean, speed: Float): State<Float> {
    val spec = LocalUiSpec.current
    val animation = remember(spec, reduced, speed) { quickInsetAnimationSpec(spec, reduced, speed) }
    return animateFloatAsState(if (visible && measured) 1f else 0f, animation, label = "Selection panel occupancy")
}

/** A measured occupancy destination is applied only within its owner's Foundation mutation. */
internal fun ScrollScope.consumeInsetDestination(destination: Float, admitted: Float, consumed: Float): Float =
    scrollBy(minOf(destination, admitted) - consumed)
