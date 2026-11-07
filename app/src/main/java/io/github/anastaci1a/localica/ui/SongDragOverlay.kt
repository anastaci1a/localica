package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Keep song layout, theme and accessible wording outside the shared lifted material/motion. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
internal fun BoxScope.SongDragOverlay(state: SongDragState, reducedMotion: Boolean, cancelBottomMargin: Dp = 20.dp) {
    val drag = state.drag
    val marker = drag?.let { it.presentation.currentSongKey?.takeIf(it.keys::contains) }
    if (drag != null && marker != null) {
        val fade = remember(drag) { androidx.compose.animation.core.Animatable(if (reducedMotion) 0f else 1f) }
        LaunchedEffect(drag, reducedMotion) {
            if (reducedMotion) fade.snapTo(0f)
            else fade.animateTo(0f, androidx.compose.animation.core.tween(240))
        }
        val renderer = remember(drag) { CurrentSongHighlightRenderer() }
        val color = drag.presentation.colors?.primary ?: MaterialTheme.colorScheme.primary
        val bounds = drag.initialBounds[marker]
        if (bounds != null) androidx.compose.foundation.Canvas(Modifier.matchParentSize().zIndex(4.5f)) {
            val outline = drag.lifts[marker]?.lifted?.translate(bounds.topLeft)
            with(renderer) { draw(outline?.bounds ?: bounds, color, alpha = fade.value, corner = outline?.corner) }
        }
    }
    KeyedDragOverlay(state, reducedMotion, countDescription = { "Dragging $it songs" },
        cancelBottomMargin = cancelBottomMargin, liftInk = { it.ink }) { item, presentation ->
        androidx.compose.ui.layout.LookaheadScope {
            Box(Modifier.fillMaxWidth()) {
                MaterialTheme(colorScheme = presentation.colors ?: MaterialTheme.colorScheme) {
                    CompositionLocalProvider(LocalContentColor provides presentation.ink.takeUnless { it == Color.Unspecified }
                        .let { it ?: LocalContentColor.current }) {
                        BrowseItemLayout(item, presentation.style, presentation.cellGeometry, this@LookaheadScope, true, menu = { },
                            itemIndex = presentation.indices[item.key] ?: 0, mutablePosition = presentation.mutablePositions[item.key],
                            outline = LocalHeldItemOutline.current)
                    }
                }
            }
        }
    }
}
