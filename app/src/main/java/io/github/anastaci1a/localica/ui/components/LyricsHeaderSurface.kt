package io.github.anastaci1a.localica.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.ui.engine.lyricsHeaderDivider
import io.github.anastaci1a.localica.ui.engine.lyricsHeaderRight

/** The joined header's two presentation layers borrow live preparation state. */
internal class LyricsHeaderSurface(reveal: () -> Float, progress: () -> Float) {
    val divider: Modifier = Modifier.lyricsHeaderDivider(reveal, progress)
    val right: Modifier = Modifier.lyricsHeaderRight(reveal, progress)
}

@Composable
internal fun rememberLyricsHeaderSurface(reveal: Float, progress: () -> Float): LyricsHeaderSurface {
    val currentReveal = rememberUpdatedState(reveal)
    val currentProgress = rememberUpdatedState(progress)
    return remember { LyricsHeaderSurface({ currentReveal.value }, { currentProgress.value() }) }
}
