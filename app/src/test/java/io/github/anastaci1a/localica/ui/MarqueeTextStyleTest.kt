package io.github.anastaci1a.localica.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Test

class MarqueeTextStyleTest {
    @Test fun unspecifiedDirectionUsesContentWithoutChangingTypography() {
        val style = TextStyle(fontSize = 24.sp, color = Color.Red, textAlign = TextAlign.End)
        assertEquals(style.copy(textDirection = TextDirection.Content), marqueeTextStyle(style))
    }

    @Test fun explicitDirectionsAndFallbacksRemainAuthoritative() {
        for (direction in listOf(TextDirection.Ltr, TextDirection.Rtl, TextDirection.Content,
            TextDirection.ContentOrLtr, TextDirection.ContentOrRtl)) {
            val style = TextStyle(textDirection = direction)
            assertSame("Preserve $direction", style, marqueeTextStyle(style))
        }
    }
}
