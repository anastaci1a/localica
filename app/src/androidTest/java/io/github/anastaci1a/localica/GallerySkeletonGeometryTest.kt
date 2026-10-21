package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GallerySkeletonGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun galleryMeasurementsAreSharedByCellsAndReusedThroughRevealFrames() {
        var fontScale by mutableFloatStateOf(1f)
        var alpha by mutableFloatStateOf(0f)
        val cells = arrayOfNulls<GalleryLoadingTextMetrics>(20)
        lateinit var viewport: GalleryLoadingTextMetrics
        lateinit var differentTypography: GalleryLoadingTextMetrics
        val style = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 3)
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                val shared = rememberGalleryLoadingTextMetrics(style, 3)
                SideEffect { viewport = shared }
                CompositionLocalProvider(LocalGalleryLoadingTextMetrics provides shared) {
                    Box(Modifier.size(100.dp).graphicsLayer { this.alpha = alpha }.testTag("shared-measurements")) {
                        repeat(cells.size) { index ->
                            val cell = rememberGalleryLoadingTextMetrics(style, 3)
                            SideEffect { cells[index] = cell }
                        }
                        val different = rememberGalleryLoadingTextMetrics(style, 1)
                        SideEffect { differentTypography = different }
                    }
                }
            }
        } }
        compose.waitForIdle()
        val original = viewport
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            compose.waitForIdle()
            val measured = viewport
            if (scale == 2f) assertNotSame("Font-scale changes invalidate measured typography", original, measured)
            assertNotSame("Different typography cannot inherit an unrelated viewport measurement", measured, differentTypography)
            for (opacity in listOf(.1f, .5f, 1f, .3f, 0f)) {
                compose.runOnIdle { alpha = opacity }
                compose.waitForIdle()
                assertSame("Reveal frames cannot repeat paragraph measurement", measured, viewport)
                cells.forEach { assertSame("Every cell reuses the viewport's single measurement result", measured, it) }
            }
        }
    }

    @Test fun galleryShapesMatchOneLineTextHeightAndSpacingAcrossTheLoadingHandoff() {
        var columns by mutableIntStateOf(3)
        var fontScale by mutableFloatStateOf(1f)
        val reveal = BrowseLoadingReveal(columns = 1, initiallyComplete = false)
        val cell = reveal.cell(0).apply { place(0f, 0f) }
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                val style = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = columns)
                val geometry = BrowseCellGeometry(columns)
                Box(Modifier.width((320f / columns).dp)) {
                    Box(Modifier.testTag("loading-slot")) { BrowseLoadingItem(style, geometry, true) }
                    for (key in listOf("ready", "normal", "multiline")) Box(Modifier.testTag("$key-slot").padding(geometry.cardInset.dp)) {
                        LookaheadScope {
                            val text = if (key == "multiline") "I\nI" else "I"
                            BrowseItemLayout(BrowserItem(MediaRef(MediaKind.Album, key), text, text, emptyList()),
                                style, geometry, this, true, {}, loadingReveal = if (key == "ready") cell else null)
                        }
                    }
                }
            }
        } }
        fun bounds(tag: String) = compose.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
        for (count in 1..5) for (scale in listOf(1f, 1.3f, 2f)) {
            compose.runOnIdle { columns = count; fontScale = scale }
            val title = bounds("browse-skeleton-title")
            val info = bounds("browse-skeleton-info")
            val art = bounds("browse-skeleton-art")
            val normalTitle = bounds("browse-text-Album:normal")
            val normalInfo = bounds("browse-info-Album:normal")
            val normalArt = bounds("browse-art-Album:normal")
            assertEquals("Title shape has one normal text line at $count columns / $scale scale", normalTitle.height, title.height, 1f)
            assertEquals("Information shape has one normal text line at $count columns / $scale scale", normalInfo.height, info.height, 1f)
            assertEquals("Normal artwork/title gap", normalTitle.top - normalArt.bottom, title.top - art.bottom, 1f)
            assertEquals("Normal title/information gap", normalInfo.top - normalTitle.bottom, info.top - title.bottom, 1f)
            assertEquals("Loading reserves the actual two-line typography at $count columns / $scale scale",
                bounds("multiline-slot").height, bounds("loading-slot").height, 1f)
            assertEquals("The complete multiline reservation survives catalog handoff",
                bounds("loading-slot").height, bounds("ready-slot").height, 1f)
            for (part in listOf("title", "info")) {
                val before = bounds("browse-skeleton-$part")
                val after = bounds("browse-loading-$part-Album:ready")
                assertEquals("$part height at handoff", before.height, after.height, 1f)
                assertEquals("$part position at handoff", before.top - bounds("loading-slot").top,
                    after.top - bounds("ready-slot").top, 1f)
            }
        }
    }

    @Test fun completedGalleryStillLaysOutRealTwoLineTitleAndInformation() {
        val style = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 2)
        val item = BrowserItem(MediaRef(MediaKind.Album, "multiline"),
            "A long title that fills both allowed lines", "A long artist that fills both allowed lines", emptyList())
        var density = 1f
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current.density
            LookaheadScope {
                Box(Modifier.width(160.dp).testTag("real-row")) {
                    BrowseItemLayout(item, style, BrowseCellGeometry(2), this@LookaheadScope, true, {})
                }
            }
        } }
        val title = compose.onNodeWithTag("browse-text-Album:multiline", true).fetchSemanticsNode().boundsInRoot
        val information = compose.onNodeWithTag("browse-info-Album:multiline", true).fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("real-row").fetchSemanticsNode().boundsInRoot
        assertEquals(48 * density, title.height, 1f)
        assertTrue(information.height > 16 * density)
        assertTrue(information.top >= title.bottom - 1f)
        assertTrue(information.bottom <= row.bottom)
    }
}
