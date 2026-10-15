package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class GalleryGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneThroughFiveColumnsKeepOuterArtworkAlignmentAndDenseGaps() {
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val route = BrowseRoute(MainTab.Songs)
        val original = prefs.read(route)
        val originallyOverridden = prefs.overridden(route)
        val tracks = (0..19).map { Track("geometry:$it", "Song ${it.toString().padStart(2, '0')}", fileName = "$it.mp3", relativePath = "$it.mp3") }
        var density = 1f
        try {
            prefs.write(route, BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 1), false)
            compose.setContent { LocalicaTheme {
                density = LocalDensity.current.density
                Box(Modifier.width(300.dp)) {
                    BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                        PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                        reducedMotion = true, queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                }
            } }
            val galleryWidths = mutableMapOf<Int, Float>()
            for (columns in 1..5) {
                compose.runOnIdle { prefs.write(route, BrowseStyle(presentation = Presentation.Standard, itemsPerRow = columns), false) }
                compose.waitUntil(5000) {
                    compose.onAllNodesWithTag("browse-art-geometry:${columns - 1}", true).fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (columns == 1) "Gallery, 1 column" else "Gallery, $columns columns")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitForIdle()
                val viewport = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot
                val header = compose.onNodeWithTag("browse-full-span", true).fetchSemanticsNode().boundsInRoot
                assertEquals("Full-span headers keep viewport width", viewport.width, header.width, 1f)
                assertEquals("Full-span headers keep viewport position", viewport.left, header.left, 1f)
                val first = compose.onNodeWithTag("browse-art-geometry:0", true).fetchSemanticsNode().boundsInRoot
                val last = compose.onNodeWithTag("browse-art-geometry:${columns - 1}", true).fetchSemanticsNode().boundsInRoot
                assertEquals(16 * density, first.left - viewport.left, 2f)
                assertEquals(16 * density, viewport.right - last.right, 2f)
                val expectedWidth = if (columns >= 3) (viewport.width - 24 * density) / columns - 8 * density
                    else viewport.width / columns - 32 * density
                assertEquals(expectedWidth, first.width, 2f)
                galleryWidths[columns] = first.width
                if (columns >= 3) {
                    val second = compose.onNodeWithTag("browse-art-geometry:1", true).fetchSemanticsNode().boundsInRoot
                    assertEquals(8 * density, second.left - first.right, 2f)
                }
            }
            for ((size, columns) in listOf(ListSize.Normal to 5, ListSize.Large to 4)) {
                compose.runOnIdle { prefs.write(route, BrowseStyle(listSize = size), false) }
                compose.waitUntil(5000) {
                    compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "List, ${size.name}")).fetchSemanticsNodes().isNotEmpty()
                }
                compose.waitForIdle()
                val art = compose.onNodeWithTag("browse-art-geometry:0", true).fetchSemanticsNode().boundsInRoot
                assertEquals("List artwork matches Gallery $columns", galleryWidths.getValue(columns), art.width, 1f)
            }
        } finally { prefs.write(route, original, originallyOverridden) }
    }

    @Test fun gallerySourceAndDestinationInsetsSurviveEveryAdjacentTransitionAndReversal() {
        val track = Track("transition", "Song", fileName = "song.mp3", relativePath = "song.mp3")
        val row = SongRow(track.id, track)
        val item = BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, null, listOf(row), row)
        val motion = BrowsePinchMotion()
        var targetColumns by mutableIntStateOf(1)
        var density = 1f
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current.density
            val geometry = BrowseCellGeometry(targetColumns)
            val viewport = (300 * density).roundToInt()
            val width = geometry.cellWidth(viewport, density) - 2 * (geometry.cardInset * density).roundToInt()
            LookaheadScope {
                Box(Modifier.width(with(LocalDensity.current) { width.toDp() }).testTag("card")) {
                    BrowseItemLayout(item, BrowseStyle(presentation = Presentation.Standard, itemsPerRow = targetColumns),
                        geometry, this@LookaheadScope, true, menu = {}, pinch = motion)
                }
            }
        } }
        for (source in 1..5) for (target in listOf(source - 1, source + 1).filter { it in 1..5 }) {
            compose.runOnIdle {
                val geometry = BrowseCellGeometry(source)
                motion.pixelDensity = density
                motion.viewportWidth = (300 * density).roundToInt().toFloat()
                motion.sourceColumns = source
                motion.bounds = mapOf(item.key to Rect(0f, 0f, geometry.cellWidth(motion.viewportWidth.toInt(), density).toFloat(), 200f))
                motion.source = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = source)
                targetColumns = target
                runBlocking { motion.progress.snapTo(0f) }
            }
            fun check(columns: Int) {
                compose.waitForIdle()
                val geometry = BrowseCellGeometry(columns)
                val art = compose.onNodeWithTag("browse-art-${item.key}", true).fetchSemanticsNode().boundsInRoot
                val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
                assertEquals(geometry.contentInset * density, art.left - card.left, 1f)
                assertEquals(geometry.contentWidth(geometry.cellWidth(motion.viewportWidth.toInt(), density), density).toFloat(), art.width, 1f)
            }
            check(source)
            compose.runOnIdle { runBlocking { motion.progress.snapTo(1f) } }
            check(target)
            compose.runOnIdle { runBlocking { motion.progress.snapTo(0f) } }
            check(source)
        }
    }
}
