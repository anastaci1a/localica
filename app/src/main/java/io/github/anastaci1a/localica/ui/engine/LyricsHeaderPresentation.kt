package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Read-only layers sample the existing preparation owner without allocating another clock. */
internal fun Modifier.lyricsHeaderDivider(reveal: () -> Float, progress: () -> Float): Modifier =
    graphicsLayer { alpha = originDividerAlpha(reveal(), progress()) }

internal fun Modifier.lyricsHeaderRight(reveal: () -> Float, progress: () -> Float): Modifier =
    graphicsLayer { alpha = originRightAlpha(reveal(), progress()) }
