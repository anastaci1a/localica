package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The action face exists only for positive opacity; its caller retains modifier sampling order. */
@Composable
internal fun PlayerActionRow(alpha: Float, modifier: @Composable () -> Modifier,
    content: @Composable BoxScope.() -> Unit) {
    if (alpha > 0f) Box(modifier(), content = content)
}
