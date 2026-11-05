package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Test observation only; an idle scene has no drag recording or sampling allocation. */
internal val LocalSongDragSceneObserver = staticCompositionLocalOf<((FrostedBackdrop?) -> Unit)?> { null }

/** Capture page, player and chrome together, with the held stack outside its own source. */
@Composable
internal fun SongDragScene(host: SongDragHost, modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit) {
    val dragging = host.state?.drag != null
    val source = if (dragging) rememberFrostedBackdrop(host.state?.drag,
        dark = host.source?.dark == true, retainRaster = false) else null
    val observer = LocalSongDragSceneObserver.current
    SideEffect { observer?.invoke(source) }
    Box(modifier) {
        Box(Modifier.fillMaxSize().then(if (source != null)
            Modifier.recordFrostedBackdrop(source) else Modifier), content = content)
        CompositionLocalProvider(LocalFrostedBackdrop provides source) {
            ForegroundSongDragOverlay(host)
        }
    }
}
