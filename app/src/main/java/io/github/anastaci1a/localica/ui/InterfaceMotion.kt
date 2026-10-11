package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.shape.RoundedCornerShape
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
    fasterMotion -> normal * 2 / 3
    else -> normal
}

internal val LocalReducedMotion = androidx.compose.runtime.staticCompositionLocalOf { false }
