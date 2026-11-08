package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Stock floating-control host; the existing control keeps its input, motion and semantics. */
@Composable internal fun FloatingControlSlot(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier, content = content)
}
