package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.components.TriggerRole

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ListDensityRevisionTest {
    @get:Rule val compose = createComposeRule()
    private val track = Track("density-revision", "A long album title that wraps onto a second line in Large presentation",
        artist = "Artist information", fileName = "song.mp3", relativePath = "song.mp3")
    private val row = SongRow(track.id, track)
    private val item = BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, null, listOf(row), row)
    private val modes = listOf(ListSize.Large, ListSize.Normal, ListSize.Compact, ListSize.Small, ListSize.Smallest)
    private fun node(part: String) = compose.onNodeWithTag("browse-$part-${item.key}", true)
    private fun bounds(part: String) = node(part).fetchSemanticsNode().boundsInRoot
    private fun lines(part: String): Int {
        val results = mutableListOf<TextLayoutResult>()
        node(part).onChildren().filter(hasText(if (part == "text") item.title else "Artist information"))
            .onFirst().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single().lineCount
    }

    @Test fun fiveDensitiesMatchDev53RevisionAndKeepActionsAtLargeFonts() {
        var size by mutableStateOf(ListSize.Normal)
        var scale by mutableFloatStateOf(1f)
        var density = 1f
        var clicks = 0
        val heights = mutableMapOf<ListSize, Float>()
        val titleLines = mutableMapOf<ListSize, Int>()
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) { LookaheadScope {
                Column(Modifier.width(360.dp).background(Color(0xffeeeeee)).testTag("density-revision")) {
                    val style = BrowseStyle(listSize = size, extraInfo = ExtraInfo.Artist)
                    val geometry = BrowseCellGeometry(1)
                    for (index in 0..3) {
                        val entry = if (index == 0) item else item.copy(row = row.copy(id = "density-$index"))
                        Box(Modifier.fillMaxWidth().padding(horizontal = geometry.cardInset.dp,
                            vertical = geometry.cardVerticalInset(style).dp)) {
                            BrowseItemLayout(entry, style, geometry, this@LookaheadScope, true,
                                menu = { ActionIcon(AppIcon.ActionEllipsis, "Density revision actions $index", role = TriggerRole.PlainIcon) { clicks++ } })
                        }
                    }
                }
            } }
        } }
        for (mode in modes) {
            compose.runOnIdle { size = mode }
            compose.waitForIdle()
            titleLines[mode] = lines("text")
            heights[mode] = compose.onNodeWithTag("density-revision").fetchSemanticsNode().boundsInRoot.height / 4 / density
            val args = InstrumentationRegistry.getArguments()
            args.getString("densityRevisionCapture")?.let { label ->
                val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "density-309/$label")
                root.mkdirs()
                File(root, "${mode.name}.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
        // Capture every baseline density before asserting the revised acceptance.
        assertEquals("Large allows two title lines", 2, titleLines.getValue(ListSize.Large))
        for (mode in modes.drop(1)) assertEquals("$mode stays single-line", 1, titleLines.getValue(mode))
        assertEquals("Small matches dev53 Smallest: 40 dp inner + 8 dp outer", 48f, heights.getValue(ListSize.Small), 1f)
        assertTrue(heights.getValue(ListSize.Smallest) < heights.getValue(ListSize.Small))
        for (mode in modes) {
            compose.runOnIdle { size = mode; scale = 2f }
            compose.waitForIdle()
            assertEquals(1, lines("info"))
            val title = bounds("text"); val info = bounds("info")
            if (mode.trailingInformation) assertTrue(title.right <= info.left)
            else assertTrue(title.bottom <= info.top + 1f)
            compose.onNodeWithContentDescription("Density revision actions 0").performClick()
        }
        compose.runOnIdle { assertEquals(5, clicks) }
    }

    @Test fun normalLargeLongTitleEndpointsRetraceAndReleaseWithoutGeometryJump() {
        val motion = BrowsePinchMotion()
        var size by mutableStateOf(ListSize.Large)
        var density = 1f
        compose.setContent { LocalicaTheme { density = LocalDensity.current.density; LookaheadScope {
            Box(Modifier.width(328.dp)) {
                BrowseItemLayout(item, BrowseStyle(listSize = size, extraInfo = ExtraInfo.Artist),
                    BrowseCellGeometry(1), this@LookaheadScope, true, menu = {}, pinch = motion)
            }
        } } }
        for ((source, target) in listOf(ListSize.Normal to ListSize.Large, ListSize.Large to ListSize.Normal,
            ListSize.Small to ListSize.Smallest, ListSize.Smallest to ListSize.Small)) {
            compose.runOnIdle {
                size = target
                motion.pixelDensity = density
                motion.viewportWidth = 336 * density
                motion.sourceColumns = 1
                motion.bounds = mapOf(item.key to Rect(0f, 0f, 336 * density, 120 * density))
                motion.source = BrowseStyle(listSize = source, extraInfo = ExtraInfo.Artist)
                runBlocking { motion.progress.snapTo(0f) }
            }
            val forward = mutableMapOf<Float, Rect>()
            for (fraction in listOf(0f, .25f, .5f, .75f, 1f, .75f, .5f, .25f, 0f, 1f)) {
                compose.runOnIdle { runBlocking { motion.progress.snapTo(fraction) } }
                compose.waitForIdle()
                val rect = bounds("text")
                forward[fraction]?.let { assertEquals("Reversal retraces $fraction", it, rect) } ?: run { forward[fraction] = rect }
            }
            val held = bounds("text")
            val heldInfo = bounds("info")
            compose.runOnIdle { motion.source = null }
            compose.waitForIdle()
            assertEquals(held, bounds("text"))
            assertEquals(heldInfo, bounds("info"))
            assertEquals(if (target == ListSize.Large) 2 else 1, lines("text"))
        }
    }
}
