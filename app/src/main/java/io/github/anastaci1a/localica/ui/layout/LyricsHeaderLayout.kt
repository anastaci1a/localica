package io.github.anastaci1a.localica.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import io.github.anastaci1a.localica.ui.components.LyricsHeaderSurface
import io.github.anastaci1a.localica.ui.engine.originExpansion
import io.github.anastaci1a.localica.ui.engine.originGap

/** Owns the real header measurement; preparation acknowledges this same layout and draw. */
@Composable
internal fun LyricsHeaderLayout(controlSize: Dp, reveal: Float, progress: () -> Float,
    origin: Modifier, decoration: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.height(controlSize).then(origin)
        .layout { measurable, constraints ->
            val width = (controlSize * (1f + originExpansion(reveal, progress()) + originGap(progress()) / 6f)).roundToPx()
            val child = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(child.width, child.height) { child.place(0, 0) }
        }.then(decoration), content = content)
}

/** Joined faces keep two independent hit regions and a centred divider. */
@Composable
internal fun BoxScope.LyricsHeaderJoinedLayout(controlSize: Dp, showRight: Boolean, lyricsOpen: Boolean,
    surface: LyricsHeaderSurface, leftModifier: Modifier, left: @Composable () -> Unit,
    divider: @Composable (Modifier) -> Unit, right: @Composable () -> Unit) {
    Box(Modifier.align(Alignment.CenterStart).size(controlSize).then(leftModifier)) { left() }
    if (showRight) {
        divider(Modifier.align(Alignment.Center).then(surface.divider))
        Box(Modifier.align(Alignment.CenterEnd).size(controlSize).then(surface.right)
            .then(if (!lyricsOpen) Modifier.clearAndSetSemantics {} else Modifier)) { right() }
    }
}
