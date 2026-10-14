package io.github.anastaci1a.localica.ui

internal const val VerticalReleaseVelocity = 300f
internal const val VerticalReleaseDistance = 72f

/** Positive travel/velocity moves toward the open anchor. Values are density independent. */
internal fun settleOpen(travel: Float, velocity: Float, progress: Float, opening: Boolean,
    velocityThreshold: Float = VerticalReleaseVelocity, distanceThreshold: Float = VerticalReleaseDistance): Boolean = when {
    velocity >= velocityThreshold -> true
    velocity <= -velocityThreshold -> false
    opening && travel >= distanceThreshold -> true
    !opening && travel <= -distanceThreshold -> false
    else -> progress >= .5f
}

internal enum class VerticalOwner { Undecided, Player, Queue }

internal val PlayerSettleEasing = androidx.compose.animation.core.CubicBezierEasing(.22f, .0f, .12f, 1f)
