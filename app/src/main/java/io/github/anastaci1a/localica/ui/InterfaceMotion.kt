package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.FasterAnimationSpeedTenths
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.navigation.MaximumAnimationSpeedTenths
import io.github.anastaci1a.localica.navigation.MinimumAnimationSpeedTenths
import kotlin.math.ln
import kotlin.math.pow

internal object Corners {
    val artwork = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(16.dp)
    val menu = RoundedCornerShape(20.dp)
    val largeArtwork = RoundedCornerShape(24.dp)
    val panel = RoundedCornerShape(28.dp)
}
/** #860 §7.3: normal · 3/4 / s, floored as before; exact for the old Standard (1.0×) and Faster (1.5×) states. */
internal fun InterfaceSettings.motionDuration(normal: Int) =
    if (reducedMotion) 0 else normal * 30 / (4 * animationSpeedTenths.coerceIn(MinimumAnimationSpeedTenths, MaximumAnimationSpeedTenths))

/** The legacy non-engine speed input, 4/3 · s: 4/3 at 1.0× and the old Faster 2 at 1.5×. */
internal val InterfaceSettings.legacyMotionSpeed: Float
    get() = 4f * animationSpeedTenths.coerceIn(MinimumAnimationSpeedTenths, MaximumAnimationSpeedTenths) / 30f

/** The artwork swipe spring stiffness, 380 · (650/380)^(ln s / ln 1.5): exactly 380 at 1.0× and 650 at the old Faster 1.5×. */
internal fun InterfaceSettings.artworkSwipeStiffness(): Float = when (animationSpeedTenths.coerceIn(MinimumAnimationSpeedTenths, MaximumAnimationSpeedTenths)) {
    10 -> 380f
    FasterAnimationSpeedTenths -> 650f
    else -> (380.0 * (650.0 / 380.0).pow(ln(animationSpeed.toDouble()) / ln(1.5))).toFloat()
}

internal val LocalReducedMotion = androidx.compose.runtime.staticCompositionLocalOf { false }
internal val LocalMotionSpeed = androidx.compose.runtime.staticCompositionLocalOf { 4f / 3f }
/** #862: whether the platform animator scale applies; off ignores its 0 as well. */
internal val LocalRespectSystemAnimationScale = androidx.compose.runtime.staticCompositionLocalOf { true }

/** Share the window Recomposer's live system policy instead of registering another observer.
 * Its scaleFactor is snapshot state, so changes recompose the single app-level provider.
 * Isolated compositions without that element retain the explicit app preference.
 */
@Composable
internal fun rememberEffectiveReducedMotion(appReducedMotion: Boolean,
    respectSystemScale: Boolean = LocalRespectSystemAnimationScale.current): Boolean {
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    return effectiveReducedMotion(appReducedMotion, scale, respectSystemScale)
}

internal fun effectiveReducedMotion(appReducedMotion: Boolean, systemDurationScale: Float, respectSystemScale: Boolean = true) =
    appReducedMotion || respectSystemScale && systemDurationScale == 0f
