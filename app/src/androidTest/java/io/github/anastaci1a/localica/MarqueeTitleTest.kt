package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.platform.ViewRootForTest
import kotlin.math.roundToInt
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
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
    private val measuredLayouts = linkedMapOf<String, TextLayoutResult>()
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

    @Test fun preparationFadesTheStationaryLeadingGlyphsBeforeAnyMotion() {
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
        compose.mainClock.advanceTimeBy(1910)
        val moving = compose.onNodeWithTag("moving-title").captureToImage().toPixelMap()
        val reference = compose.onNodeWithTag("reference-title").captureToImage().toPixelMap()
        var difference = 0f
        var darkPixels = 0
        for (x in 0 until minOf(16, moving.width)) for (y in 0 until minOf(moving.height, reference.height)) {
            difference += kotlin.math.abs(moving[x, y].red - reference[x, y].red)
            if (reference[x, y].red < .5f) darkPixels++
        }
        assertTrue("The comparison includes leading glyph ink", darkPixels > 10)
        assertTrue("The taper fades in over the still text before it moves: $difference", difference > 2f)
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
        var actualReferenceLayout: TextLayoutResult? = null
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(Modifier.background(Color.White)) {
                    RecordMeasuredLayout("paused-title", AnnotatedString(title), style)
                    MarqueeTitle(title, false, Modifier.width(120.dp).testTag("title"), style, motionPaused = true)
                    // Clip a right-aligned, intrinsically measured paragraph. A constrained
                    // Text(softWrap = false, overflow = Clip) instead clips its left end.
                    Box(Modifier.width(120.dp).clipToBounds().testTag("reference")) {
                        Text(title, Modifier.fillMaxWidth()
                            .wrapContentWidth(AbsoluteAlignment.Right, unbounded = true)
                            .testTag("reference-text"), maxLines = 1, softWrap = false, style = style,
                            onTextLayout = { actualReferenceLayout = it })
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(64)
        recordRtlEvidence("paused-rtl", listOf("title", "reference"))
        val actual = compose.onNodeWithTag("title").captureToImage().toPixelMap()
        val reference = compose.onNodeWithTag("reference").captureToImage().toPixelMap()
        // Read the actual layout callback: the optimized String Text semantics
        // reconstruction uses an infinite paragraph on API26 despite drawing correctly.
        val referenceLayout = compose.runOnUiThread { requireNotNull(actualReferenceLayout) }
        assertEquals("Reference paragraph matches its finite measured width",
            referenceLayout.size.width.toFloat(), referenceLayout.multiParagraph.width, .01f)
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
        val artists = listOf("Artist", "فنان", " · Artist", " · فنان")
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(Modifier.background(Color.White)) {
                    artists.forEachIndexed { index, artist ->
                        val width = if (index < 2) 120.dp else 180.dp
                        val text = buildAnnotatedString {
                            append(artist)
                            withStyle(SpanStyle(color = Color.Transparent)) { append(" · A very long hidden album name") }
                        }
                        RecordMeasuredLayout("artist-$index-full", text, style)
                        RecordMeasuredLayout("artist-$index-prefix", AnnotatedString(artist), style)
                        MarqueeTitle(text, false, Modifier.width(width).testTag("artist-$index"), style,
                            visiblePrefixLength = artist.length, suffixVisibility = 0f)
                        // Paragraph-leading alignment is independent of the UI direction.
                        Text(artist, Modifier.width(width).testTag("reference-$index"), maxLines = 1,
                            textAlign = if (index % 2 == 0) TextAlign.Left else TextAlign.Right,
                            style = style.copy(textDirection = TextDirection.Content))
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(4000)
        recordRtlEvidence("hidden-prefixes", artists.indices.flatMap { listOf("artist-$it", "reference-$it") })
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

    @Test fun nativeDrawHandsItsActualMaskAmountToOwnerBeforeFlipAndGeometryChanges() {
        val motion = MarqueeMotion()
        fun advance(time: Long, allowed: Boolean) = motion.advance(time, 500f, 200f, 100f, allowed)
        advance(0, true); advance(1800, true); advance(2020, true)
        var frame by mutableStateOf(advance(2120, true))
        var textWidth by mutableStateOf(500f)
        var fadeWidth by mutableStateOf(20f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            androidx.compose.foundation.Canvas(Modifier.size(32.dp).background(Color.White).testTag("draw-owner")) {
                val amount = marqueeLeftFade(textWidth, fadeWidth, frame)
                motion.recordRenderedLeftFade(amount)
                drawRect(Color.Black.copy(alpha = amount))
            }
        }
        compose.mainClock.advanceTimeByFrame()
        fun assertDrawn(expected: Float) {
            val pixels = compose.onNodeWithTag("draw-owner").captureToImage().toPixelMap()
            assertEquals("Native mask swatch", 1f - expected, pixels[pixels.width / 2, pixels.height / 2].red, .02f)
        }
        assertDrawn(1f)
        compose.runOnIdle {
            fadeWidth = 40f
            frame = advance(2120, false)
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, requireNotNull(frame.backwardFadeStart), 0f)
        assertDrawn(1f)
        compose.runOnIdle {
            textWidth = 5f
            fadeWidth = 80f
            frame = advance(2195, false)
        }
        compose.mainClock.advanceTimeByFrame()
        assertDrawn(.5f)
        compose.runOnIdle { frame = advance(2270, false) }
        compose.mainClock.advanceTimeByFrame()
        assertDrawn(0f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun nativeBackwardFlipAndWidthChangeReleaseStrips() = nativeTaperStrip("backward-width", false, true)
    @Test fun nativeForwardTailAndLandingStrips() = nativeTaperStrip("forward", true, false)
    @Test fun nativeNaturalCycleFirstPixelTailAndWrapStrips() = nativeTaperStrip("natural", false, false, true)

    private fun nativeTaperStrip(scene: String, forward: Boolean, resize: Boolean, natural: Boolean = false) {
        val title = "MMMMMMMMMMMMMMMMMMMMMMMM"
        val style = TextStyle(fontSize = 24.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
        var paused by mutableStateOf(false)
        var width by mutableStateOf(120.dp)
        var textWidthDp = 0f
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalicaTheme {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val measured = rememberTextMeasurer().measure(AnnotatedString(title), style, maxLines = 1, softWrap = false)
            SideEffect { textWidthDp = with(density) { measured.size.width.toDp().value } }
            Column(Modifier.background(Color.White)) {
                MarqueeTitle(title, false, Modifier.width(width).testTag("native-taper"), style, motionPaused = paused)
            }
        } }
        val directory = nativeEvidenceDirectory("marquee-716/$scene-api${android.os.Build.VERSION.SDK_INT}",
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val rows = mutableListOf<Bitmap>()
        val receipt = StringBuilder("label,composeTimeMillis,widthDp\n")
        fun capture(label: String) {
            rows += nativeTaperShot(directory, label)
            receipt.appendLine("$label,${compose.mainClock.currentTime},${width.value}")
        }
        compose.mainClock.advanceTimeBy(64)
        capture("rest")
        compose.mainClock.advanceTimeBy(1968)
        capture("pre-outgoing")
        compose.mainClock.advanceTimeByFrame()
        capture("first-outgoing-frame")
        if (natural) {
            val cycleMillis = ((textWidthDp + 40f) / 30f * 1000f).toLong()
            val start = compose.mainClock.currentTime
            for (index in 1..24) {
                val target = start + cycleMillis * index / 24
                compose.mainClock.advanceTimeBy(target - compose.mainClock.currentTime)
                capture("cycle-$index")
            }
            compose.mainClock.advanceTimeBy(600)
            capture("wrap-rest")
        } else {
            val travel = if (forward) textWidthDp * .78f else 10f
            compose.mainClock.advanceTimeBy((travel / 30f * 1000f).toLong())
            capture("before-flip")
            compose.runOnIdle { paused = true }
            compose.mainClock.advanceTimeByFrame()
            capture("flip")
            if (resize) compose.runOnIdle { width = 200.dp }
            compose.mainClock.advanceTimeBy(75, ignoreFrameDuration = true)
            capture("release-75ms")
            compose.mainClock.advanceTimeBy(75, ignoreFrameDuration = true)
            capture("release-150ms")
            compose.mainClock.advanceTimeBy(400)
            capture("landed")
            val actual = compose.onNodeWithTag("native-taper").captureToImage().toPixelMap()
            compose.mainClock.advanceTimeBy(1000)
            val held = compose.onNodeWithTag("native-taper").captureToImage().toPixelMap()
            assertTrue("Landing stays still after cancellation", difference(actual, held) < 2f)
        }
        val strip = Bitmap.createBitmap(rows.maxOf { it.width }, rows.sumOf { it.height }, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(strip)
        canvas.drawColor(android.graphics.Color.WHITE)
        var y = 0f
        rows.forEach { canvas.drawBitmap(it, 0f, y, null); y += it.height }
        File(directory, "strip.png").outputStream().use { check(strip.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(directory, "frames.csv").writeText(receipt.toString())
        rows.forEach { it.recycle() }; strip.recycle()
        compose.mainClock.autoAdvance = true
    }

    private var nativeCaptureNumber = 0
    private fun nativeTaperShot(directory: File, label: String): Bitmap {
        compose.waitForIdle()
        val node = compose.onNodeWithTag("native-taper").fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val root = view.rootView
        val origin = IntArray(2)
        val rootOrigin = IntArray(2)
        val token = ++nativeCaptureNumber * 5
        val marker = ColorDrawable(android.graphics.Color.rgb(token, 255, 0))
        var x = 0; var y = 0
        compose.runOnUiThread {
            view.getLocationOnScreen(origin); root.getLocationOnScreen(rootOrigin)
            x = rootOrigin[0] + root.width - 12; y = rootOrigin[1] + root.height - 12
            marker.setBounds(root.width - 16, root.height - 16, root.width - 8, root.height - 8)
            root.overlay.add(marker)
            root.invalidate()
        }
        var screenshot: Bitmap? = null
        try {
            compose.waitUntil(5000) {
                screenshot?.recycle()
                screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                val pixel = screenshot!!.getPixel(x, y)
                android.graphics.Color.green(pixel) >= 250 && android.graphics.Color.blue(pixel) <= 4 &&
                    kotlin.math.abs(android.graphics.Color.red(pixel) - token) <= 4
            }
            val crop = Bitmap.createBitmap(screenshot!!, origin[0] + node.boundsInRoot.left.roundToInt(),
                origin[1] + node.boundsInRoot.top.roundToInt(), node.boundsInRoot.width.roundToInt(), node.boundsInRoot.height.roundToInt())
            val result = crop.copy(Bitmap.Config.ARGB_8888, false)
            if (crop !== screenshot) crop.recycle()
            File(directory, "$label.png").outputStream().use { check(result.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return result
        } finally {
            compose.runOnUiThread { root.overlay.remove(marker) }
            screenshot?.recycle()
        }
    }

    @Composable private fun RecordMeasuredLayout(label: String, text: AnnotatedString, style: TextStyle) {
        val layout = rememberTextMeasurer().measure(text, style, maxLines = 1, softWrap = false)
        val resolved = rememberTextMeasurer().measure(text, marqueeTextStyle(style), maxLines = 1, softWrap = false)
        SideEffect {
            measuredLayouts[label] = layout
            measuredLayouts["$label-content"] = resolved
        }
    }

    private fun recordRtlEvidence(scene: String, tags: List<String>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.takeIf { it.isNotBlank() }?.let(::File)
            ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val directory = File(root, "marquee-title/$scene-api${android.os.Build.VERSION.SDK_INT}")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
        tags.forEach { tag ->
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            File(directory, "$tag.png").outputStream().use {
                check(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true)
        val layouts = buildString {
            tags.forEach { tag ->
                appendLine("$tag bounds=${compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot}")
            }
            fun appendLayout(label: String, result: TextLayoutResult) {
                appendLine("node=$label text=${result.layoutInput.text} size=${result.size} " +
                    "constraints=${result.layoutInput.constraints} direction=${result.layoutInput.layoutDirection} " +
                    "style=${result.layoutInput.style} paragraphWidth=${result.multiParagraph.width}")
                repeat(result.lineCount) { line ->
                    appendLine("line=$line left=${result.getLineLeft(line)} right=${result.getLineRight(line)} " +
                        "top=${result.getLineTop(line)} bottom=${result.getLineBottom(line)}")
                }
                result.layoutInput.text.indices.forEach { offset ->
                    appendLine("char=$offset glyph=${result.getBoundingBox(offset)} paragraph=${result.getParagraphDirection(offset)}")
                }
            }
            compose.runOnUiThread { measuredLayouts.toMap() }.forEach { (label, layout) ->
                appendLayout("measured-$label", layout)
            }
            repeat(nodes.fetchSemanticsNodes().size) { index ->
                val results = mutableListOf<TextLayoutResult>()
                nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                results.forEach { appendLayout("rendered-$index", it) }
            }
        }
        File(directory, "layouts.txt").writeText(layouts)
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
