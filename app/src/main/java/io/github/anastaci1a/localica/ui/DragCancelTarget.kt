package io.github.anastaci1a.localica.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R

/** Same release-to-cancel affordance for reorder and playback preview. */
@Composable
internal fun DragCancelTarget(hovered: Boolean, action: String, modifier: Modifier = Modifier) {
    val color by animateColorAsState(if (hovered) MaterialTheme.colorScheme.error.copy(alpha = .7f) else Color.Transparent,
        tween(if (LocalReducedMotion.current) 0 else 160), label = "Cancel target")
    LiquidGlassSurface(modifier.size(CompactControlSize).semantics {
        contentDescription = "Release to cancel $action"
        stateDescription = if (hovered) "Cancel $action" else "Drag here to cancel"
    }, tint = color, pressFeedback = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_close), null, Modifier.size(26.dp),
                tint = if (hovered) MaterialTheme.colorScheme.onError else LocalContentColor.current)
        }
    }
}
