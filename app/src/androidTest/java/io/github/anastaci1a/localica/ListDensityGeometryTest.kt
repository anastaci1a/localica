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
                assertEquals(if (mode == ListSize.Compact) 32 * density else 0f, bounds("art").width, 1f)
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
}
