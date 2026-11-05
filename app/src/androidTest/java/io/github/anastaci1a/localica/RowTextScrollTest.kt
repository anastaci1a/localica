package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Newly composed and recycled rows must draw the complete paragraph on their first frame. */
class RowTextScrollTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun listTextIsCompleteOnFastScroll() = checkScroll(false)
    @Test fun galleryTextIsCompleteOnFastScroll() = checkScroll(true)
    @Test fun normalListTextIsCompleteOnFastScroll() = checkScroll(false, listSize = ListSize.Normal)
    @Test fun largeListTextIsCompleteOnFastScroll() = checkScroll(false, listSize = ListSize.Large)
    @Test fun smallListTextIsCompleteOnFastScroll() = checkScroll(false, listSize = ListSize.Small)
    @Test fun smallestListTextIsCompleteOnFastScroll() = checkScroll(false, listSize = ListSize.Smallest)
    @Test fun tracklistTextIsCompleteOnFastScroll() = checkScroll(false, listSize = ListSize.Tracklist)
    @Test fun singleColumnGalleryTextIsCompleteOnFastScroll() = checkScroll(true, columns = 1)
    @Test fun threeColumnGalleryTextIsCompleteOnFastScroll() = checkScroll(true, columns = 3)
    @Test fun fiveColumnGalleryTextIsCompleteOnFastScroll() = checkScroll(true, columns = 5)

    private fun checkScroll(gallery: Boolean, columns: Int = 2, listSize: ListSize = ListSize.Compact) {
        val items = (0 until 90).map { index -> BrowserItem(MediaRef(MediaKind.Playlist, "scroll-$index"),
            if (index % 2 == 0) "Wide complete title with several words $index" else "Short $index",
            if (index % 2 == 0) "Secondary text across the row" else "Info", emptyList()) }
        val style = BrowseStyle(presentation = if (gallery) Presentation.Standard else Presentation.List,
            listSize = listSize)
        var jump: (Int) -> Unit = {}
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalContentColor provides Color.Black) {
                LookaheadScope {
                    if (gallery) {
                        val state = rememberLazyGridState()
                        SideEffect { jump = { runBlocking { state.scrollToItem(it) } } }
                        LazyVerticalGrid(GridCells.Fixed(columns), Modifier.size(360.dp, 440.dp).background(Color.White), state) {
                            items(items.size, key = { items[it].key }) { index ->
                                BrowseItemLayout(items[index], style, BrowseCellGeometry(columns), this@LookaheadScope, false, {}, itemIndex = index)
                            }
                        }
                    } else {
                        val state = rememberLazyListState()
                        SideEffect { jump = { runBlocking { state.scrollToItem(it) } } }
                        LazyColumn(Modifier.size(360.dp, 440.dp).background(Color.White), state) {
                            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                                BrowseItemLayout(item, style, BrowseCellGeometry(1), this@LookaheadScope, false, {}, itemIndex = index)
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val mode = if (gallery) "gallery-$columns" else "list-$listSize"
        val output = nativeEvidenceDirectory("row-text-scroll/$mode", InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val failures = mutableListOf<String>()
        for (index in listOf(22, 48, 12, 64)) {
            compose.runOnIdle { jump(index) }
            val frames = mutableListOf<Pair<ImageBitmap, ImageBitmap>>()
            repeat(8) {
                compose.mainClock.advanceTimeByFrame()
                frames += capture(items[index], "text") to capture(items[index], "info")
            }
            compose.mainClock.advanceTimeBy(500)
            val settled = capture(items[index], "text") to capture(items[index], "info")
            for ((part, reference) in listOf("title" to settled.first, "info" to settled.second)) {
                val expected = columns(reference)
                assertTrue("Fixture has visible $part ink", expected.sum() > 5f)
                val prefix = "${if (gallery) "gallery" else "list"}-$index-$part"
                save(reference, File(output, "$prefix-settled.png"))
                frames.forEachIndexed { frame, pair ->
                    val image = if (part == "title") pair.first else pair.second
                    save(image, File(output, "$prefix-$frame.png"))
                    val actual = columns(image)
                    // Compare each ink-bearing column: total ink can conceal a horizontal wipe.
                    val delta = if (actual.size != expected.size) Float.POSITIVE_INFINITY else
                        actual.indices.sumOf { abs(actual[it] - expected[it]).toDouble() }.toFloat() / expected.sum()
                    if (delta > .08f) failures += "$prefix frame=$frame column-error=$delta size=${image.width}x${image.height} settled=${reference.width}x${reference.height}"
                }
            }
        }
        File(output, "${if (gallery) "gallery" else "list"}-result.txt").writeText(failures.joinToString("\n"))
        assertTrue("Text changes after entering the viewport:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    private fun capture(item: BrowserItem, part: String) = compose.onNodeWithTag("browse-$part-${item.key}", true).captureToImage()
    private fun columns(image: ImageBitmap): FloatArray {
        val pixels = image.toPixelMap()
        return FloatArray(pixels.width) { x ->
            (0 until pixels.height).sumOf { y -> (1f - pixels[x, y].red).toDouble() }.toFloat()
        }
    }
    private fun save(image: ImageBitmap, file: File) {
        file.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
