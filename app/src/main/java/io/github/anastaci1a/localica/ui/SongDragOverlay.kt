package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keep song layout, theme and accessible wording outside the shared lifted material/motion. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
internal fun BoxScope.SongDragOverlay(state: SongDragState, reducedMotion: Boolean, cancelBottomMargin: Dp = 20.dp) {
    KeyedDragOverlay(state, reducedMotion, countDescription = { "Dragging $it songs" },
        cancelBottomMargin = cancelBottomMargin) { item, presentation ->
        androidx.compose.ui.layout.LookaheadScope {
            Box(Modifier.fillMaxWidth()) {
                MaterialTheme(colorScheme = presentation.colors ?: MaterialTheme.colorScheme) {
                    CompositionLocalProvider(LocalContentColor provides presentation.ink.takeUnless { it == Color.Unspecified }
                        .let { it ?: LocalContentColor.current }) {
                        BrowseItemLayout(item, presentation.style, presentation.cellGeometry, this@LookaheadScope, true, menu = { })
                    }
                }
            }
        }
    }
}
