package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.SongRow
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class ListDensityGeometryTest {
    @get:Rule val compose = createComposeRule()
    private val track = Track("density", "A long title that must leave room for information", artist = "Artist information",
        fileName = "song.mp3", relativePath = "song.mp3")
    private val row = SongRow(track.id, track)
    private val item = BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, null, listOf(row), row)
    private fun bounds(part: String) = compose.onNodeWithTag("browse-$part-${item.key}", true).fetchSemanticsNode().boundsInRoot

    @Test fun denseRowsKeepTrailingInformationAndRespectNarrowWidthsAndLargeFonts() {
        var size by mutableStateOf(ListSize.Compact)
        var width by mutableIntStateOf(292)
        var fontScale by mutableFloatStateOf(1f)
        var density = 1f
        var clicks = 0
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                LookaheadScope {
                    Box(Modifier.width(width.dp).testTag("density-card")) {
                        BrowseItemLayout(item, BrowseStyle(listSize = size, extraInfo = ExtraInfo.Artist),
                            BrowseCellGeometry(1), this@LookaheadScope, true, menu = { ActionIcon(R.drawable.ic_ellipsis, "Density actions", quiet = true) { clicks++ } })
                    }
                }
            }
        } }
        for (available in listOf(292, 232)) for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { width = available; fontScale = scale }
            var previousHeight = Float.POSITIVE_INFINITY
            for (mode in listOf(ListSize.Compact, ListSize.Small, ListSize.Smallest)) {
                compose.runOnIdle { size = mode }
                compose.waitForIdle()
                compose.onNodeWithText("Artist information", substring = true).assertExists()
                val title = bounds("text"); val info = bounds("info"); val menu = bounds("menu")
                val card = compose.onNodeWithTag("density-card").fetchSemanticsNode().boundsInRoot
                assertTrue("Title and metadata must not overlap", title.right <= info.left)
                assertEquals("Metadata immediately precedes menu", menu.left, info.right, 1f)
                assertEquals("Dense metadata is vertically centered", card.center.y, info.center.y, 1f)
                assertTrue("Font scaling must not clip metadata", info.top >= card.top && info.bottom <= card.bottom)
                assertTrue("Denser rows cannot become taller", card.height <= previousHeight)
                if (scale == 1f) assertTrue("Each reference density has distinct spacing", card.height < previousHeight)
                previousHeight = card.height
                val beforeClick = clicks
                compose.onNodeWithContentDescription("Density actions").performClick()
                compose.runOnIdle { assertEquals(beforeClick + 1, clicks) }
                val metrics = BrowseItemMetrics(BrowseStyle(listSize = mode), 1,
                    (available * density).roundToInt() - 2 * (12 * density).roundToInt(), density, false)
                assertEquals(metrics.art.toFloat(), bounds("art").width, 1f)
                if (mode == ListSize.Compact) {
                    assertTrue("Compact artwork grows without changing row pitch", bounds("art").width > 32 * density)
                    if (scale == 1f) assertEquals(56 * density, card.height, 1f)
                }
            }
        }
    }

    @Test fun consecutiveCoversUseHalfNormalGapAndFixedCompactPitch() {
        var size by mutableStateOf(ListSize.Normal)
        var density = 1f
        val second = item.copy(row = row.copy(id = "density-second"))
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current.density
            LookaheadScope {
                Column(Modifier.width(360.dp)) {
                    val geometry = BrowseCellGeometry(1)
                    val style = BrowseStyle(listSize = size, extraInfo = ExtraInfo.Artist)
                    for (entry in listOf(item, second)) Box(Modifier.fillMaxWidth().testTag("density-row-${entry.key}")
                        .padding(horizontal = geometry.cardInset.dp, vertical = geometry.cardVerticalInset(style).dp)) {
                        BrowseItemLayout(entry, BrowseStyle(listSize = size, extraInfo = ExtraInfo.Artist),
                            geometry, this@LookaheadScope, true, menu = {})
                    }
                }
            }
        } }
        for (mode in listOf(ListSize.Normal, ListSize.Large, ListSize.Compact, ListSize.Smallest)) {
            compose.runOnIdle { size = mode }
            compose.waitForIdle()
            val firstArt = bounds("art")
            val secondArt = compose.onNodeWithTag("browse-art-${second.key}", true).fetchSemanticsNode().boundsInRoot
            if (mode == ListSize.Compact) {
                assertEquals("Compact row pitch is unchanged", 64 * density, secondArt.top - firstArt.top, 2f)
                assertTrue(firstArt.width > 32 * density)
            } else if (mode == ListSize.Smallest) {
                val firstRow = compose.onNodeWithTag("density-row-${item.key}").fetchSemanticsNode().boundsInRoot
                val secondRow = compose.onNodeWithTag("density-row-${second.key}").fetchSemanticsNode().boundsInRoot
                val expectedPitch = (40 * density).roundToInt() + 2 * (1.2f * density).roundToInt()
                assertEquals("Smallest keeps its inner row and halves its former 2.4dp outer padding",
                    expectedPitch.toFloat(), firstRow.height, 1f)
                assertEquals(expectedPitch.toFloat(), secondRow.top - firstRow.top, 1f)
                assertEquals("Consecutive rows have no additional spacing", firstRow.bottom, secondRow.top, 1f)
                assertEquals(0f, firstArt.width, 0f)
            } else {
                assertEquals("Normal/Large inter-cover gap is halved", 12 * density, secondArt.top - firstArt.bottom, 2f)
            }
        }
    }

    @Test fun informationMovesContinuouslyBetweenSubtitleAndTrailingPositionDuringPinch() {
        val motion = BrowsePinchMotion()
        val source = BrowseStyle(listSize = ListSize.Normal, extraInfo = ExtraInfo.Artist)
        val target = source.copy(listSize = ListSize.Compact)
        var density = 1f
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current.density
            LookaheadScope {
                Box(Modifier.width(292.dp)) {
                    BrowseItemLayout(item, target, BrowseCellGeometry(1), this@LookaheadScope, true,
                        menu = { Text("⋮") }, pinch = motion)
                }
            }
        } }
        compose.runOnIdle {
            motion.pixelDensity = density
            motion.viewportWidth = 300 * density
            motion.sourceColumns = 1
            motion.bounds = mapOf(item.key to Rect(0f, 0f, 300 * density, 100 * density))
            motion.source = source
            runBlocking { motion.progress.snapTo(0f) }
        }
        compose.waitForIdle()
        val start = bounds("info")
        val centers = mutableListOf<Float>()
        for (fraction in listOf(.25f, .5f, .75f, 1f)) {
            compose.runOnIdle { runBlocking { motion.progress.snapTo(fraction) } }
            compose.waitForIdle()
            compose.onNodeWithText("Artist information", substring = true).assertExists()
            centers += bounds("info").center.x
        }
        assertTrue(centers.zipWithNext().all { (a, b) -> b > a })
        assertTrue(centers.first() > start.center.x)
        assertTrue("No final-frame horizontal snap", abs(centers.last() - centers[2]) < abs(centers.last() - start.center.x))
        compose.runOnIdle { motion.source = null }
        compose.waitForIdle()
        assertEquals(centers.last(), bounds("info").center.x, 1f)
    }
    @Test fun tracklistNumbersAndOrdinaryNormalHaveSeparateStableEndpoints() {
        var size by mutableStateOf(ListSize.Tracklist)
        var albumNumber by mutableStateOf<String?>("12")
        var mutablePosition by mutableStateOf<Int?>(null)
        var density = 1f
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current.density
            LookaheadScope {
                Box(Modifier.width(328.dp)) {
                    BrowseItemLayout(item, BrowseStyle(listSize = size, extraInfo = ExtraInfo.Artist),
                        BrowseCellGeometry(1), this@LookaheadScope, true, menu = {}, trackNumber = albumNumber, itemIndex = 4,
                        mutablePosition = mutablePosition)
                }
            }
        } }
        compose.onNodeWithText("12").assertIsDisplayed()
        assertEquals(28 * density, bounds("art").width, 1f)
        val numberedInfo = bounds("info")
        assertTrue("Tracklist retains subtitle information", numberedInfo.top >= bounds("text").bottom - 1f)
        compose.runOnIdle { size = ListSize.Normal }
        compose.waitForIdle()
        compose.onNodeWithText("12").assertDoesNotExist()
        assertTrue("Normal restores cover width", bounds("art").width > 28 * density)
        compose.runOnIdle { albumNumber = null; size = ListSize.Tracklist }
        compose.waitForIdle()
        compose.onNodeWithText("5").assertIsDisplayed()
        assertEquals(28 * density, bounds("art").width, 1f)
        compose.runOnIdle { size = ListSize.Compact }
        compose.waitForIdle()
        compose.onNodeWithText("5").assertDoesNotExist()
        assertTrue(bounds("art").width > 28 * density)
        for (mode in listOf(ListSize.Smallest, ListSize.Small)) {
            compose.runOnIdle { mutablePosition = 46; size = mode }
            compose.waitForIdle()
            compose.onNodeWithText("46").assertIsDisplayed()
            compose.onNodeWithText("5").assertDoesNotExist()
            assertEquals(28 * density, bounds("art").width, 1f)
            compose.runOnIdle { mutablePosition = 96 }
            compose.waitForIdle()
            compose.onNodeWithText("96").assertIsDisplayed()
            compose.onNodeWithText("46").assertDoesNotExist()
        }
        compose.runOnIdle { size = ListSize.Normal }
        compose.waitForIdle()
        compose.onNodeWithText("96").assertDoesNotExist()
        assertTrue("Normal keeps the artist below its title", bounds("info").top >= bounds("text").bottom - 1f)
    }

    @Test fun liftedTracklistKeepsCapturedOccurrenceNumberAfterLiveRowsChange() {
        lateinit var drag: SongDragState
        var captured by mutableStateOf<SongDragPresentation?>(null)
        compose.setContent { LocalicaTheme {
            val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            val scope = rememberCoroutineScope()
            drag = remember { SongDragState(grid, scope) }
            captured?.let { presentation -> LookaheadScope {
                BrowseItemLayout(item, presentation.style, presentation.cellGeometry, this@LookaheadScope,
                    true, menu = {}, itemIndex = presentation.indices.getValue(item.key),
                    mutablePosition = presentation.mutablePositions[item.key])
            } }
        } }
        compose.runOnIdle {
            drag.style = BrowseStyle(listSize = ListSize.Tracklist)
            drag.items = listOf(item.copy(row = row.copy(id = "earlier")), item)
            captured = drag.capturePresentation()
            drag.items = listOf(item)
        }
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("1").assertDoesNotExist()
        compose.runOnIdle {
            drag.style = BrowseStyle(listSize = ListSize.Small)
            drag.mutablePositions = mapOf(item.key to 46)
            captured = drag.capturePresentation()
            drag.mutablePositions = mapOf(item.key to 1)
        }
        compose.onNodeWithText("46").assertIsDisplayed()
        compose.onNodeWithText("1").assertDoesNotExist()
    }

}
