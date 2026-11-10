package io.github.anastaci1a.localica

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Optional test-only observation inside the unchanged player harness providers; existing fixtures retain null behavior. */
internal val LocalPlayerPresentationHarnessProbe = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/** Main publishes only immutable values. The instrumentation thread owns serialization and every evidence write. */
internal data class PlayerPresentationHarnessSnapshot(
    val generation: Int,
    val contextAccess: String,
    val specAccess: String,
    val clockAccess: String,
    val clockSpecAccess: String,
    val localReduced: Boolean,
    val ownsRootClock: Boolean,
    val displayIntervalSeconds: Float,
    val referenceTravelPx: Float,
    val density: Float,
    val fontScale: Float,
    val renderer: String,
    val contrast: Float,
    val shadowStrength: Float,
    val liftShadowStrength: Float,
)
