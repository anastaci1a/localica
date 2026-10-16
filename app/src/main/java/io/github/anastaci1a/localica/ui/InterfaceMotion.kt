package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.InterfaceSettings

internal object Corners {
    val artwork = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(16.dp)
    val menu = RoundedCornerShape(20.dp)
    val largeArtwork = RoundedCornerShape(24.dp)
    val panel = RoundedCornerShape(28.dp)
}
internal fun InterfaceSettings.motionDuration(normal: Int) = when {
    reducedMotion -> 0
    fasterMotion -> normal / 2
    else -> normal * 3 / 4
}

internal val LocalReducedMotion = androidx.compose.runtime.staticCompositionLocalOf { false }
internal val LocalMotionSpeed = androidx.compose.runtime.staticCompositionLocalOf { 4f / 3f }

/** Share the window Recomposer's live system policy instead of registering another observer.
 * Its scaleFactor is snapshot state, so changes recompose the single app-level provider.
 * Isolated compositions without that element retain the explicit app preference.
 */
@Composable
internal fun rememberEffectiveReducedMotion(appReducedMotion: Boolean): Boolean {
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    return effectiveReducedMotion(appReducedMotion, scale)
}

internal fun effectiveReducedMotion(appReducedMotion: Boolean, systemDurationScale: Float) =
    appReducedMotion || systemDurationScale == 0f
