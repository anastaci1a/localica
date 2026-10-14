package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Shared lifted material; callers own the gesture and landing position. */
@Composable
internal fun GlassDragBubble(modifier: Modifier = Modifier, landing: () -> Float = { 0f },
    shadow: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    LiquidGlassSurface(modifier.graphicsLayer {
        val finish = landing().coerceIn(0f, 1f)
        alpha = 1f - finish
        scaleX = 1f - .65f * finish * finish
        scaleY = 1f - .65f * finish * finish
    }, cornerRadius = 16.dp, pressFeedback = false, shadow = shadow, content = content)
}
