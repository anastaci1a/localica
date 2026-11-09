package io.github.anastaci1a.localica.ui.engine

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText

/** Text-only clipping; the material body/field never inherits this tight viewport. */
internal fun Modifier.readingTextDrawing(reader: ReadingMarquee, paragraph: TextLayoutResult,
    ink: Color, rtl: Boolean): Modifier = drawWithContent {
    val offset = reader.offset
    clipRect {
        drawText(paragraph, color = ink,
            topLeft = Offset(if (rtl) size.width - paragraph.size.width + offset else -offset, 0f))
    }
}
