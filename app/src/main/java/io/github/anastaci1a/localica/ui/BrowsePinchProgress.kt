package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Stable
import io.github.anastaci1a.localica.ui.engine.PinchProgressMotion

/** One transaction's frame-driven value, shared by layout and drawing. The presented value is the Engine channel's;
 * every intent is a fresh root-clock transaction (see [PinchProgressMotion]). */
@Stable
internal class BrowsePinchProgress(initial: Float) {
    private val motion = PinchProgressMotion(initial)
    val value: Float get() = motion.value()

    fun follow(target: Float) = motion.follow(target)

    suspend fun snapTo(target: Float) = motion.snapTo(target)

    /** Honors the system animation scale, but advances at most one frame budget per frame. */
    suspend fun animateTo(target: Float, spec: AnimationSpec<Float>) = motion.animateTo(target, spec)

    /** Touch tracking keeps its 100ms clock regardless of system animation speed. */
    suspend fun catchUp(onCaughtUp: () -> Unit) = motion.catchUp(onCaughtUp)

    /** A retired layout keeps its terminal value; its channels leave the root clock at their Rest. */
    fun retire() = motion.retire()
}
