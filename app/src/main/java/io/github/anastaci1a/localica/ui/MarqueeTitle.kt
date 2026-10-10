package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow

@Composable
internal fun MarqueeTitle(title: String, reducedMotion: Boolean, modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleSmall) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val overflows = measurer.measure(AnnotatedString(title), style, maxLines = 1, softWrap = false).size.width >
            with(LocalDensity.current) { maxWidth.toPx() }
        val motion = if (!reducedMotion && overflows) Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.08f to Color.Black,
                    0.92f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1800, initialDelayMillis = 1800) else Modifier
        Text(title, motion, maxLines = 1, style = style, overflow = if (reducedMotion) TextOverflow.Ellipsis else TextOverflow.Clip)
    }
}
