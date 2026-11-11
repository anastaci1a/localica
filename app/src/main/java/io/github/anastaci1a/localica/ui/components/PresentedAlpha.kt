package io.github.anastaci1a.localica.ui.components

import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.engine.presentedAlpha

/** Screens bind a reveal opacity through the Engine; the lambda is read in the draw phase, never at composition. */
internal fun Modifier.revealAlpha(alpha: () -> Float): Modifier = presentedAlpha(alpha)
