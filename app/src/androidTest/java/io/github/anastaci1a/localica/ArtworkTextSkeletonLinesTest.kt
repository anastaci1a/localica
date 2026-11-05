package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.ArtworkColors
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Compare the shared row's drawn skeleton against the actual TextLayoutResult. */
class ArtworkTextSkeletonLinesTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun galleryTitleAndInformationHaveOneBarPerWrappedLine() = checkLines(
        BrowseStyle(presentation = Presentation.Standard), 160,
        "A title wrapping over two lines", "Secondary information wrapping over two lines", 2, 2)

    @Test fun shortSingleLinesUseTheirInkWidthRatherThanTheWholeSlot() = checkLines(
        BrowseStyle(listSize = ListSize.Normal), 360, "Short title", "Info", 1, 1)

    @Test fun largeListTitleUsesTwoLineBoxesAndInformationUsesOne() = checkLines(
        BrowseStyle(listSize = ListSize.Large), 360,
        "A title wrapping over two lines", "Info", 2, 1)

    @Test fun shortRtlLinesKeepTheirMeasuredStartPosition() = checkLines(
        BrowseStyle(listSize = ListSize.Normal), 360, "عنوان", "تفاصيل", 1, 1, rtl = true)

    private fun checkLines(style: BrowseStyle, width: Int, title: String, info: String, titleLines: Int, infoLines: Int,
        rtl: Boolean = false) {
        ArtworkCache.invalidate()
        val uri = "content://test.skeleton/held-590"
        ArtworkColors.publish(mapOf(uri to 0xFFCC2020.toInt()))
        val transit = ArtworkTransit()
        val hold = CoroutineScope(Dispatchers.Unconfined).launch {
            transit.during(true) { awaitCancellation() }
        }
        val item = BrowserItem(MediaRef(MediaKind.Playlist, "skeleton-lines"), title, info, emptyList(), coverUri = uri)
        try {
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalArtworkTransit provides transit, LocalReducedMotion provides true,
                    LocalLoadingMaterialOpacity provides 1f,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    LookaheadScope {
                        Box(Modifier.size(width.dp, 300.dp).background(Color.White)) {
                            BrowseItemLayout(item, style, BrowseCellGeometry(if (style.presentation == Presentation.List) 1 else 2),
                                this@LookaheadScope, true, {})
                        }
                    }
                }
            } }
            compose.waitForIdle()
            for ((label, part, expectedLines) in listOf(Triple(title, "text", titleLines), Triple(info, "info", infoLines))) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                    assertTrue("Text publishes its measured paragraph", it(layouts))
                }
                val layout = layouts.single()
                assertEquals("Fixture wraps $part as intended", expectedLines, layout.lineCount)
                val wrapper = compose.onNodeWithTag("browse-$part-${item.key}", true)
                val paragraph = compose.onNodeWithText(label, useUnmergedTree = true)
                val paragraphCoordinates = paragraph.fetchSemanticsNode().layoutInfo.coordinates
                val wrapperCoordinates = wrapper.fetchSemanticsNode().layoutInfo.coordinates
                val origin = wrapperCoordinates.localPositionOf(paragraphCoordinates, Offset.Zero)
                val image = wrapper.captureToImage()
                val output = nativeEvidenceDirectory("text-skeleton-lines",
                    InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
                val prefix = "${style.presentation}-${style.listSize}-rtl$rtl-$part"
                File(output, "$prefix.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(output, "$prefix-metrics.txt").writeText("paragraph-offset=$origin layout-size=${layout.size} paragraph-size=${paragraphCoordinates.size} wrapper-size=${wrapperCoordinates.size}\n" + (0 until layout.lineCount).joinToString("\n") {
                    "line=$it left=${layout.getLineLeft(it)} right=${layout.getLineRight(it)} top=${layout.getLineTop(it)} bottom=${layout.getLineBottom(it)}"
                })
                val pixels = image.toPixelMap()
                fun painted(x: Int, y: Int) = abs(pixels[x, y].red - 1f) + abs(pixels[x, y].green - 1f) + abs(pixels[x, y].blue - 1f) > .08f
                for (line in 0 until layout.lineCount) {
                    val left = origin.x + layout.getLineLeft(line)
                    val right = origin.x + layout.getLineRight(line)
                    val top = origin.y + layout.getLineTop(line)
                    val bottom = origin.y + layout.getLineBottom(line)
                    val y = ((top + bottom) / 2f).toInt().coerceIn(0, pixels.height - 1)
                    val paintedColumns = (0 until pixels.width).filter { painted(it, y) }
                    assertTrue("$part line $line has its own skeleton bar at y=$y", paintedColumns.isNotEmpty())
                    assertEquals("$part line $line left metric", left, paintedColumns.first().toFloat(), 2f)
                    assertEquals("$part line $line right metric", right, (paintedColumns.last() + 1).toFloat(), 2f)
                    val x = ((left + right) / 2f).toInt().coerceIn(0, pixels.width - 1)
                    assertTrue("$part line $line begins at its real top", painted(x, (floor(top).toInt() + 1).coerceIn(0, pixels.height - 1)))
                    assertTrue("$part line $line reaches its real bottom", painted(x, (ceil(bottom).toInt() - 2).coerceIn(0, pixels.height - 1)))
                }
            }
        } finally {
            hold.cancel()
            ArtworkCache.invalidate()
            ArtworkColors.publish(emptyMap())
        }
    }
}
