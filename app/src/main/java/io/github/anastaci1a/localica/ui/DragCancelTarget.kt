package io.github.anastaci1a.localica.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R

/** Same release-to-cancel affordance for reorder and playback preview. */
@Composable
internal fun DragCancelTarget(hovered: Boolean, action: String, modifier: Modifier = Modifier) {
    val player = LocalPlayerDrawTheme.current
    val error = player?.error?.invoke() ?: MaterialTheme.colorScheme.error
    val onError = if (player == null) MaterialTheme.colorScheme.onError else Color.Unspecified
    val color by animateColorAsState(if (hovered) error.copy(alpha = .7f) else Color.Transparent,
        tween(if (LocalReducedMotion.current) 0 else 160), label = "Cancel target")
    LiquidGlassSurface(modifier.size(CompactControlSize).semantics {
        contentDescription = "Release to cancel $action"
        stateDescription = if (hovered) "Cancel $action" else "Drag here to cancel"
    }, tint = color, pressFeedback = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val glassInk = LocalContentColor.current
            DrawInkIcon(appIconPainter(AppIcon.ActionClose), null, androidx.compose.ui.graphics.ColorProducer {
                if (hovered) player?.onError?.invoke() ?: onError else glassInk
            }, Modifier.size(26.dp))
        }
    }
}
