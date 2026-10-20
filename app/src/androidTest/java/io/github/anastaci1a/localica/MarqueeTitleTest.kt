package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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

    @Test fun normalOverflowPauseReleasesTheComposeIdleClock() {
        val title = "A long title that overflows the narrow viewport"
        val started = android.os.SystemClock.uptimeMillis()
        compose.setContent { LocalicaTheme {
            MarqueeTitle(title, false, Modifier.width(100.dp).testTag("title"))
        } }
        compose.waitForIdle()
        compose.onNodeWithText(title, useUnmergedTree = true).assertExists()
        assertTrue("The resting marquee must not keep autoAdvance waiting for frames",
            android.os.SystemClock.uptimeMillis() - started < 5000)
    }

    @Test fun bothLinesKeepClipAndFadeRenderingThroughoutWidthReversals() {
        val title = "MMMMMMMMMMMMMMMMMMMMMMMM"
        val artist = "Artist"
        val style = TextStyle(fontSize = 24.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
        var width by mutableStateOf(120.dp)
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            Column(Modifier.background(Color.White)) {
                MarqueeTitle(title, false, Modifier.width(width).testTag("title"), style, motionPaused = true)
                MarqueeTitle(artist, false, Modifier.width(width).testTag("artist"), style, motionPaused = true)
            }
        } }
        for (value in listOf(120, 180, 260, 180, 120)) {
            compose.runOnIdle { width = value.dp }
            compose.mainClock.advanceTimeBy(64)
            for (text in listOf(title, artist)) {
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(text, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertFalse("Player transitions must not substitute ellipsis", results.single().isLineEllipsized(0))
                assertEquals(text.length, results.single().getLineEnd(0, visibleEnd = true))
            }
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun overflowingArtistReturnsAndStaysStillWhileTheOtherLineFits() {
        val artist = "MMMMMMMMMMMMMMMMMMMMMMMM"
        val style = TextStyle(fontSize = 24.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
        var paused by mutableStateOf(false)
        var width by mutableStateOf(120.dp)
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            Column(Modifier.background(Color.White)) {
                MarqueeTitle("Title", false, Modifier.width(width).testTag("title"), style, motionPaused = paused)
                MarqueeTitle(artist, false, Modifier.width(width).testTag("artist"), style, motionPaused = paused)
                Text(artist, Modifier.width(width).testTag("reference"), maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Clip, style = style)
            }
        } }
        compose.mainClock.advanceTimeBy(2500)
        compose.runOnIdle { paused = true }
        for (value in listOf(140, 160, 180, 160, 140, 120)) {
            compose.runOnIdle { width = value.dp }
            compose.mainClock.advanceTimeBy(32)
        }
        compose.mainClock.advanceTimeBy(600)
        val arrived = compose.onNodeWithTag("artist").captureToImage().toPixelMap()
        val reference = compose.onNodeWithTag("reference").captureToImage().toPixelMap()
        assertTrue("After return the leading glyphs are restored and unfaded", difference(arrived, reference, 16) < 2f)
        compose.mainClock.advanceTimeBy(4000)
        val held = compose.onNodeWithTag("artist").captureToImage().toPixelMap()
        assertTrue("Holding the transition cannot restart ordinary marquee", difference(arrived, held) < 2f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun hiddenAlbumSuffixDoesNotMakeAFittingArtistScroll() {
        val style = TextStyle(fontSize = 24.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
        val artist = "Artist"
        val text = buildAnnotatedString {
            append(artist)
            withStyle(SpanStyle(color = Color.Transparent)) { append(" · A very long album name that is hidden") }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            Column(Modifier.background(Color.White)) {
                MarqueeTitle(text, false, Modifier.width(120.dp).testTag("artist"), style,
                    visiblePrefixLength = artist.length, suffixVisibility = 0f)
                Text(artist, Modifier.width(120.dp).testTag("reference"), maxLines = 1, style = style)
            }
        } }
        compose.mainClock.advanceTimeBy(5000)
        val actual = compose.onNodeWithTag("artist").captureToImage().toPixelMap()
        val reference = compose.onNodeWithTag("reference").captureToImage().toPixelMap()
        assertTrue("Only visible metadata determines overflow", difference(actual, reference) < 2f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun reducedMotionRetainsEllipsisAndFullAccessibleText() {
        val title = "A title much too long to fit in its small viewport"
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MarqueeTitle(AnnotatedString(title), false, Modifier.width(100.dp).testTag("title"),
                    motionPaused = true)
            }
        } }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("title").assertTextEquals(title)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue(results.single().isLineEllipsized(0))
    }

    @Test fun pausedRtlTextKeepsItsRightLeadingEdgeUnfaded() {
        val title = "فنان البداية ثم كلمات طويلة تنتهي بموسيقى"
        val style = TextStyle(fontSize = 24.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(Modifier.background(Color.White)) {
                    MarqueeTitle(title, false, Modifier.width(120.dp).testTag("title"), style, motionPaused = true)
                    // Clip a right-aligned, intrinsically measured paragraph. A constrained
                    // Text(softWrap = false, overflow = Clip) instead clips its left end.
                    Box(Modifier.width(120.dp).clipToBounds().testTag("reference")) {
                        Text(title, Modifier.fillMaxWidth()
                            .wrapContentWidth(AbsoluteAlignment.Right, unbounded = true)
                            .testTag("reference-text"), maxLines = 1, softWrap = false, style = style)
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(64)
        val actual = compose.onNodeWithTag("title").captureToImage().toPixelMap()
        val reference = compose.onNodeWithTag("reference").captureToImage().toPixelMap()
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("reference-text", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val referenceLayout = results.single()
        assertTrue("Reference measures the entire overflowing paragraph", referenceLayout.size.width > reference.width)
        val firstGlyph = referenceLayout.getBoundingBox(0)
        assertTrue("Reference's first glyph lies in the right-leading viewport",
            firstGlyph.left >= referenceLayout.size.width - reference.width &&
                firstGlyph.right <= referenceLayout.size.width)
        assertEquals(reference.width, actual.width)
        assertEquals(reference.height, actual.height)
        var difference = 0f
        var darkPixels = 0
        for (x in actual.width - minOf(16, actual.width) until actual.width) {
            for (y in 0 until minOf(actual.height, reference.height)) {
                difference += kotlin.math.abs(actual[x, y].red - reference[x, y].red)
                if (reference[x, y].red < .5f) darkPixels++
            }
        }
        assertTrue("RTL comparison includes leading glyph ink", darkPixels > 10)
        assertTrue("RTL pause leaves its leading edge unmasked: $difference", difference < 2f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun rtlUiKeepsBothLatinAndArabicArtistPrefixesVisibleWithHiddenAlbums() {
        val style = TextStyle(fontSize = 24.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
        val artists = listOf("Artist", "فنان")
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(Modifier.background(Color.White)) {
                    artists.forEachIndexed { index, artist ->
                        val text = buildAnnotatedString {
                            append(artist)
                            withStyle(SpanStyle(color = Color.Transparent)) { append(" · A very long hidden album name") }
                        }
                        MarqueeTitle(text, false, Modifier.width(120.dp).testTag("artist-$index"), style,
                            visiblePrefixLength = artist.length, suffixVisibility = 0f)
                        // Paragraph-leading alignment is independent of the UI direction.
                        Text(artist, Modifier.width(120.dp).testTag("reference-$index"), maxLines = 1,
                            textAlign = if (index == 0) TextAlign.Left else TextAlign.Right, style = style)
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(4000)
        artists.indices.forEach { index ->
            val actual = compose.onNodeWithTag("artist-$index").captureToImage().toPixelMap()
            val reference = compose.onNodeWithTag("reference-$index").captureToImage().toPixelMap()
            assertEquals(reference.width, actual.width)
            assertEquals(reference.height, actual.height)
            assertTrue("The visible prefix keeps its paragraph's leading edge: $index",
                difference(actual, reference) < 2f)
        }
        compose.mainClock.autoAdvance = true
    }

    private fun difference(first: androidx.compose.ui.graphics.PixelMap,
        second: androidx.compose.ui.graphics.PixelMap, width: Int = first.width): Float {
        var result = 0f
        for (x in 0 until minOf(width, first.width, second.width)) {
            for (y in 0 until minOf(first.height, second.height)) {
                result += kotlin.math.abs(first[x, y].red - second[x, y].red)
            }
        }
        return result
    }

}
