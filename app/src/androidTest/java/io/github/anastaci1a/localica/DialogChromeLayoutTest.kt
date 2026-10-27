package io.github.anastaci1a.localica

import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Geometry on the real shared viewport and Repeat frame, not a parallel test-only layout. */
class DialogChromeLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var titleStyle: TextStyle? = null

    @Composable private fun Scene(content: @Composable () -> Unit) {
        LocalicaTheme {
            titleStyle = MaterialTheme.typography.titleLarge
            val source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source)) { drawRect(Color.White) }
                CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source,
                    LocalReducedMotion provides true, content = content)
            }
        }
    }

    private fun closeDp(expected: Dp, actual: Dp) = assertEquals(expected.value, actual.value, .6f)

    @Test fun sharedViewportKeepsRegularAllEdgeInsetsAndHeaderBodyFooterGaps() {
        compose.setContent { Scene {
            Box(Modifier.width(360.dp).height(640.dp)) {
                DialogViewport(Modifier.testTag("chrome-viewport"), header = {
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("chrome-header"))
                }, body = {
                    Box(Modifier.fillMaxWidth().height(80.dp).testTag("chrome-body"))
                }, footer = {
                    Box(Modifier.fillMaxWidth().height(48.dp).testTag("chrome-footer"))
                })
            }
        } }
        val panel = compose.onNodeWithTag("chrome-viewport").getUnclippedBoundsInRoot()
        val header = compose.onNodeWithTag("chrome-header").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithTag("chrome-body").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("chrome-footer").getUnclippedBoundsInRoot()
        closeDp(24.dp, header.top - panel.top)
        for (slot in listOf(header, body, footer)) {
            closeDp(24.dp, slot.left - panel.left); closeDp(24.dp, panel.right - slot.right)
        }
        closeDp(16.dp, body.top - header.bottom); closeDp(16.dp, footer.top - body.bottom)
        closeDp(24.dp, panel.bottom - footer.bottom)
        closeDp(248.dp, panel.bottom - panel.top)
    }

    @Test fun sharedViewportDoesNotReserveMissingHeaderOrBodyGaps() {
        var header by mutableStateOf(false); var body by mutableStateOf(false)
        compose.setContent { Scene {
            Box(Modifier.width(360.dp).height(640.dp)) {
                DialogViewport(Modifier.testTag("chrome-viewport"),
                    header = if (header) ({ Box(Modifier.fillMaxWidth().height(40.dp)) }) else null,
                    body = if (body) ({ Box(Modifier.fillMaxWidth().height(80.dp)) }) else null,
                    footer = { Box(Modifier.fillMaxWidth().height(48.dp)) })
            }
        } }
        for ((hasHeader, hasBody) in listOf(false to false, true to false, false to true, true to true)) {
            compose.runOnIdle { header = hasHeader; body = hasBody }
            val bounds = compose.onNodeWithTag("chrome-viewport").getUnclippedBoundsInRoot()
            val expected = 48 + 48 + (if (hasHeader) 40 + 16 else 0) + (if (hasBody) 80 + 16 else 0)
            closeDp(expected.dp, bounds.bottom - bounds.top)
        }
    }

    private fun childText(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("repeat-section-dialog")))

    private fun assertNoScroll() {
        val child = hasTestTag("repeat-section-dialog") or hasAnyAncestor(hasTestTag("repeat-section-dialog"))
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and child).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and child).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy) and child).assertCountEquals(0)
    }

    private fun assertFrame(profile: DialogChrome, section: RepeatSection, fontScale: Float) {
        compose.onNodeWithTag("repeat-section-insufficient-space").assertDoesNotExist()
        compose.onNodeWithTag(if (profile == DialogChrome.Compact) "repeat-section-compact" else "repeat-section-regular").assertExists()
        val panel = compose.onNodeWithTag("repeat-section-dialog").getUnclippedBoundsInRoot()
        // Measurement copies clear their merged semantics; the unmerged tree includes probes.
        val title = compose.onNodeWithTag("repeat-section-title").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithTag("repeat-section-body").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("repeat-section-footer").getUnclippedBoundsInRoot()
        closeDp(profile.perimeterDp.dp, title.left - panel.left)
        closeDp(profile.perimeterDp.dp, title.top - panel.top)
        for (slot in listOf(body, footer)) {
            closeDp(profile.perimeterDp.dp, slot.left - panel.left)
            closeDp(profile.perimeterDp.dp, panel.right - slot.right)
        }
        closeDp(profile.gapDp.dp, body.top - title.bottom)
        closeDp(profile.gapDp.dp, footer.top - body.bottom)
        closeDp(profile.perimeterDp.dp, panel.bottom - footer.bottom)
        val layouts = mutableListOf<TextLayoutResult>()
        childText(section.title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        layouts.forEach {
            assertEquals(requireNotNull(titleStyle).fontSize, it.layoutInput.style.fontSize)
            assertEquals(requireNotNull(titleStyle).lineHeight, it.layoutInput.style.lineHeight)
            assertEquals(FontWeight.Bold, it.layoutInput.style.fontWeight)
            assertEquals("The actual system font scale is unchanged", fontScale, it.layoutInput.density.fontScale, 0f)
        }
        assertTrue(layouts.isNotEmpty())
        assertNoScroll()
    }

    @Test fun actualNativeRepeatUsesRegularChromeAndTheGoodReferenceTitle() {
        compose.setContent { Scene { RepeatSectionDialog(RepeatSection.Song, AdvancedRepeat(), {}, {}) } }
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("repeat-section-dialog"))
        assertFrame(DialogChrome.Regular, RepeatSection.Song,
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale)
    }

    @Test fun measuredCompactFrameKeepsTitleAndDraftAcrossInsufficientSpaceAndResize() {
        var height by mutableStateOf(640.dp)
        var submitted: AdvancedRepeat? = null
        compose.setContent { Scene {
            CompositionLocalProvider(LocalInlineGlassDialog provides true) {
                Box(Modifier.width(360.dp).height(height)) {
                    RepeatSectionDialog(RepeatSection.Song, AdvancedRepeat(), {}, { submitted = it })
                }
            }
        } }
        val font = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale
        assertFrame(DialogChrome.Regular, RepeatSection.Song, font)
        val regular = compose.onNodeWithTag("repeat-section-dialog").getUnclippedBoundsInRoot()
        val regularHeight = regular.bottom - regular.top
        // One dp below the measured regular frame must select the measured compact frame,
        // not squeeze a live regular layout or change its font scale after measurement.
        compose.runOnIdle { height = regularHeight - 1.dp }
        assertFrame(DialogChrome.Compact, RepeatSection.Song, font)
        val compact = compose.onNodeWithTag("repeat-section-dialog").getUnclippedBoundsInRoot()
        val compactHeight = compact.bottom - compact.top
        childText(SongEnd.NextPaused.label).performClick()
        compose.runOnIdle { height = compactHeight - 1.dp }
        compose.onNodeWithTag("repeat-section-insufficient-space").assertIsDisplayed()
        childText("More space needed").assertIsDisplayed()
        childText("Cancel").assertIsDisplayed(); childText("Select").assertDoesNotExist()
        assertNoScroll()
        compose.runOnIdle { height = regularHeight }
        assertFrame(DialogChrome.Regular, RepeatSection.Song, font)
        childText(SongEnd.NextPaused.label).assertIsSelected()
        compose.runOnIdle { assertNull(submitted) }
        childText("Select").performClick()
        compose.runOnIdle { assertEquals(SongEnd.NextPaused, submitted?.songEnd) }
    }

    @Test fun native360x640Font2RetainsSharedChromeAndEveryRepeatControl() = nativeFont2(360.dp, 640.dp)
    @Test fun native400x800Font2RetainsSharedChromeAndEveryRepeatControl() = nativeFont2(400.dp, 800.dp)

    @Suppress("DEPRECATION")
    private fun nativeFont2(width: Dp, height: Dp) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val display = android.util.DisplayMetrics()
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(display)
        val font = context.resources.configuration.fontScale
        val method = "native${width.value.toInt()}x${height.value.toInt()}Font2RetainsSharedChromeAndEveryRepeatControl"
        val explicit = InstrumentationRegistry.getArguments().getString("class").orEmpty().split(',')
            .any { it.substringAfter('#', "") == method }
        val fits = display.widthPixels / display.density >= width.value && display.heightPixels / display.density >= height.value
        if (explicit) {
            assertTrue("Configure a display large enough for this actual native window", fits)
            assertEquals("Configure Android font scale, never substitute a CompositionLocal scale", 2f, font, 0f)
        } else assumeTrue("Requires sufficient display space and actual Android font scale 2", fits && font == 2f)
        var section by mutableStateOf(RepeatSection.Song)
        var generation by mutableIntStateOf(0)
        compose.setContent { Scene {
            key(generation) { RepeatSectionDialog(section, AdvancedRepeat(songEnd = SongEnd.Repeat), {}, {}) }
        } }
        for (target in RepeatSection.entries) {
            compose.runOnIdle { section = target; generation++ }
            val node = compose.onNodeWithTag("repeat-section-dialog")
            compose.awaitExpectedWindowFocus(node)
            val view = (node.fetchSemanticsNode().root as ViewRootForTest).view
            val window = (view.parent as DialogWindowProvider).window
            val density = view.resources.displayMetrics.density
            val widthPx = (width.value * density).roundToInt(); val heightPx = (height.value * density).roundToInt()
            compose.runOnIdle { window.setLayout(widthPx, heightPx) }
            compose.waitUntil(5000) { compose.runOnUiThread { view.width == widthPx && view.height == heightPx } }
            compose.runOnUiThread {
                assertEquals(widthPx, window.decorView.width); assertEquals(heightPx, window.decorView.height)
                val origin = IntArray(2).also(view::getLocationOnScreen)
                assertTrue("The real native window is on screen", origin[0] >= 0 && origin[1] >= 0 &&
                    origin[0] + widthPx <= display.widthPixels && origin[1] + heightPx <= display.heightPixels)
            }
            // Preserve the existing guaranteed-fit expectation. New chrome must not silently
            // turn a required phone profile into guidance or a smaller title/font scale.
            compose.onNodeWithTag("repeat-section-insufficient-space").assertDoesNotExist()
            val compact = compose.onAllNodesWithTag("repeat-section-compact").fetchSemanticsNodes().isNotEmpty()
            assertFrame(if (compact) DialogChrome.Compact else DialogChrome.Regular, target, 2f)
            assertCompleteControls(target)
        }
    }

    private fun assertCompleteControls(section: RepeatSection) {
        val choices = if (section == RepeatSection.Song) SongEnd.entries.map { it.label } else QueueEnd.entries.map { it.label }
        val toggles = if (section == RepeatSection.Queue) listOf("Reset current song to the first song", "Reshuffle when shuffle is enabled") else emptyList()
        val warning = if (section == RepeatSection.Queue) listOf("Queue-end options apply after you turn off repeating the current song.") else emptyList()
        val panel = compose.onNodeWithTag("repeat-section-dialog").getUnclippedBoundsInRoot()
        (listOf(section.title) + choices + toggles + warning + listOf("Cancel", "Select")).forEach { label ->
            val node = childText(label).assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$label stays inside the complete frame", bounds.left >= panel.left && bounds.right <= panel.right &&
                bounds.top >= panel.top && bounds.bottom <= panel.bottom)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertEquals(2f, layout.layoutInput.density.fontScale, 0f)
                assertFalse("$label retains every line", layout.multiParagraph.didExceedMaxLines)
                for (line in 0 until layout.lineCount) {
                    assertFalse("$label never ellipsizes", layout.isLineEllipsized(line))
                    assertTrue(layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= layout.size.width)
                    assertTrue(layout.getLineTop(line) >= 0f && layout.getLineBottom(line) <= layout.size.height)
                }
                assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
            }
        }
        (choices + toggles + listOf("Cancel", "Select")).forEach { label ->
            val node = childText(label).assertHasClickAction()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$label keeps its complete target", bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        }
    }
}
