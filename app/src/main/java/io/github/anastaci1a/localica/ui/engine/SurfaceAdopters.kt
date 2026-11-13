package io.github.anastaci1a.localica.ui.engine

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** The opacity is read in the draw phase from the owning surface's phase, exactly as a graphicsLayer block would. */
internal fun Modifier.presentedAlpha(alpha: () -> Float): Modifier = graphicsLayer { this.alpha = alpha() }

/** A stacking slot inside a surface; screens supply its content only. */
@Composable internal fun SurfaceLayer(modifier: Modifier = Modifier, contentAlignment: Alignment = Alignment.TopStart, content: @Composable BoxScope.() -> Unit) =
    Box(modifier, contentAlignment, content = content)
