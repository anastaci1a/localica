package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MarqueeTitleTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pausedTitleKeepsItsLeadingGlyphsUnfaded() {
        val title = "MMMMMMMMMMMMMMMMMMMMMMMM"
        val style = TextStyle(fontSize = 24.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            Column(Modifier.background(Color.White)) {
                MarqueeTitle(title, false, Modifier.width(120.dp).testTag("moving-title"), style)
                Text(title, Modifier.width(120.dp).testTag("reference-title"), maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Clip, style = style)
            }
        } }
        compose.mainClock.advanceTimeBy(64)
        val moving = compose.onNodeWithTag("moving-title").captureToImage().toPixelMap()
        val reference = compose.onNodeWithTag("reference-title").captureToImage().toPixelMap()
        var difference = 0f
        var darkPixels = 0
        for (x in 0 until minOf(16, moving.width)) for (y in 0 until minOf(moving.height, reference.height)) {
            difference += kotlin.math.abs(moving[x, y].red - reference[x, y].red)
            if (reference[x, y].red < .5f) darkPixels++
        }
        assertTrue("The comparison includes leading glyph ink", darkPixels > 10)
        assertTrue("The pause must match unfaded text: $difference", difference < 2f)
        compose.mainClock.autoAdvance = true
    }
}
