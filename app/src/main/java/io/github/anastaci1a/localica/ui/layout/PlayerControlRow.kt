package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Seek and action faces exist only for positive opacity, with the original container and sampling order. */
@Composable
internal fun PlayerControlRow(alpha: Float, modifier: @Composable () -> Modifier, column: Boolean = false,
    content: @Composable () -> Unit) {
    if (alpha > 0f) {
        if (column) Column(modifier()) { content() }
        else Box(modifier()) { content() }
    }
}
