package io.github.anastaci1a.localica.ui.engine

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult

/** Layout and its finite reader share one measured-width subscription, including zero-width pause. */
@Composable internal fun ReadingTextHost(key: Any, message: String, paragraph: TextLayoutResult,
    speed: Float, paused: Boolean, pending: () -> Unit, completed: () -> Unit, ready: (Boolean) -> Unit,
    ink: Color, rtl: Boolean, modifier: Modifier) {
    val reader = rememberReadingMarquee(key, paragraph.size.width.toFloat(), speed, pending, completed)
    var width by remember(reader) { mutableIntStateOf(0) }
    // Read during composition: layout changes must re-run owner configuration and readiness.
    val measuredWidth = width
    SideEffect {
        ready(measuredWidth > 0)
        if (measuredWidth <= 0) {
            reader.configure(maxOf(1, paragraph.size.width).toFloat(), true)
            pending()
        } else reader.configure((paragraph.size.width - measuredWidth).coerceAtLeast(0).toFloat(), paused)
    }
    Layout({}, modifier.semantics { text = AnnotatedString(message) }.readingTextDrawing(reader, paragraph, ink, rtl)) { _, limits ->
        width = if (limits.hasBoundedWidth) limits.maxWidth else paragraph.size.width
        layout(width, paragraph.size.height.coerceIn(limits.minHeight, limits.maxHeight)) {}
    }
}
