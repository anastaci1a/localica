package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class SmallestTitleGapTest {
    @get:Rule val compose = createComposeRule()

    @Test fun adjacentTitleBoundsHaveHalfTheirFormerGap() {
        var scale by mutableFloatStateOf(1f)
        var numbered by mutableStateOf(false)
        var density = 1f
        val clicks = mutableListOf<Int>()
        val entries = (0..3).map { index ->
            val track = Track("gap-$index", "Title ${index + 1}", artist = "Artist information",
                fileName = "song.mp3", relativePath = "song.mp3")
            val row = SongRow(track.id, track)
            BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, null, listOf(row), row)
        }
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) { LookaheadScope {
                Column(Modifier.width(360.dp).background(Color(0xffeeeeee)).testTag("gap-scene")) {
                    val style = BrowseStyle(listSize = ListSize.Smallest, extraInfo = ExtraInfo.Artist)
                    val geometry = BrowseCellGeometry(1)
                    entries.forEachIndexed { index, entry ->
                        Box(Modifier.fillMaxWidth().testTag("gap-row-$index").padding(
                            horizontal = geometry.cardInset.dp, vertical = geometry.cardVerticalInset(style).dp)) {
                            BrowseItemLayout(entry, style, geometry, this@LookaheadScope, true,
                                menu = { ActionIcon(AppIcon.ActionEllipsis, "Gap actions $index", quiet = true) { clicks += index } },
                                trackNumber = if (numbered) "${index + 1}" else null)
                        }
                    }
                }
            } }
        } }
        val measurements = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (fontScale in listOf(1f, 2f)) for (numbers in listOf(false, true)) {
            compose.runOnIdle { scale = fontScale; numbered = numbers }
            compose.waitForIdle()
            fun bounds(part: String, index: Int) = compose.onNodeWithTag("browse-$part-${entries[index].key}", true)
                .fetchSemanticsNode().boundsInRoot
            val first = bounds("text", 0)
            val next = bounds("text", 1)
            val info = bounds("info", 0)
            // Freeze the pre-#548 contract, independently of production metrics.
            val oldInner = maxOf((40 * density).roundToInt().toFloat(), first.height, info.height,
                if (numbers) (28 * density).roundToInt().toFloat() else 0f)
            val oldGap = oldInner + 2 * (1.2f * density).roundToInt() - first.height
            val gap = next.top - first.bottom
            measurements += "fontScale=$fontScale numbered=$numbers titleHeight=${first.height} oldGap=$oldGap gap=$gap expected=${oldGap / 2}"
            // Integer gap rounding and symmetric insets can retain at most one pixel.
            if (kotlin.math.abs(gap - oldGap / 2) > 1f) failures += measurements.last()
            val row = compose.onNodeWithTag("gap-row-0").fetchSemanticsNode().boundsInRoot
            assertTrue("Metadata stays within its row", info.top >= row.top && info.bottom <= row.bottom)
            assertTrue("Title and metadata stay separate", first.right <= info.left)
            compose.runOnIdle { clicks.clear() }
            for (index in entries.indices)
                compose.onNodeWithContentDescription("Gap actions $index").performTouchInput { click() }
            compose.runOnIdle { assertEquals("Each menu receives its own pointer tap", entries.indices.toList(), clicks) }
            val capture = compose.onRoot().captureToImage().asAndroidBitmap()
            // A pixel witness confirms the title area contains drawn text.
            assertTrue("First title must paint pixels", (first.top.toInt() until first.bottom.toInt()).any { y ->
                (first.left.toInt() until first.right.toInt()).any { x ->
                    val color = capture.getPixel(x, y)
                    android.graphics.Color.red(color) < 150 && android.graphics.Color.green(color) < 150
                }
            })
            (InstrumentationRegistry.getArguments().getString("smallestGapCapture") ?: "verified").let { label ->
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val directory = File(nativeEvidenceDirectory("smallest-gap-548", context.getExternalFilesDir(null)), label)
                    .apply { mkdirs() }
                File(directory, "font-$fontScale-numbered-$numbers.png").outputStream().use {
                    check(capture.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                File(directory, "measurements.txt").writeText(measurements.joinToString("\n", postfix = "\n"))
            }
        }
        assertTrue("Title gap must halve:\n${failures.joinToString("\n")}", failures.isEmpty())
    }
}
