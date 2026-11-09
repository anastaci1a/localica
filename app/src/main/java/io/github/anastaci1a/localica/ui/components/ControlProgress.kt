package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/** Shared rounded progress; feature callers supply their own accent when needed. */
@Composable
internal fun ControlProgress(progress: () -> Float, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(progress = progress, modifier = modifier.clip(CircleShape),
        color = color, strokeCap = StrokeCap.Round)
}
