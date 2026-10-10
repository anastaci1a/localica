package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

@Stable
internal class SongDragHost {
    var state by mutableStateOf<SongDragState?>(null)
    var reducedMotion by mutableStateOf(false)
}
internal val LocalSongDragHost = staticCompositionLocalOf<SongDragHost?> { null }

/** Draw outside pager/list clipping and above player/selection panels; cancel stays above cards. */
@Composable
internal fun ForegroundSongDragOverlay(host: SongDragHost) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().zIndex(100f).onGloballyPositioned { origin = it.positionInRoot() }
        .testTag("drag-foreground")) {
        host.state?.let { state ->
            val offset = state.viewportOrigin - origin
            Box(Modifier.offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .size(with(density) { state.viewportWidth.toDp() }, with(density) { state.viewportHeight.toDp() })) {
                SongDragOverlay(state, host.reducedMotion)
            }
        }
    }
}
