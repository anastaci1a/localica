package io.github.anastaci1a.localica

import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.EditField
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Actual controller, host, sheet and fields. Draft/review only: never submits a tag write. */
class TagEditorChromeLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val controller get() = (instrumentation.targetContext.applicationContext as LocalicaApplication).tagEditing
    private val track = Track("chrome-layout:missing", "Chrome layout song", fileName = "chrome.mp3", relativePath = "chrome.mp3")
    private var titleStyle: TextStyle? = null

    @Before fun idleController() {
        compose.runOnIdle {
            assertFalse("The fixture must not interrupt an editing job", controller.state.value.busy)
            assertNull("The fixture must not dismiss an unresolved recovery", controller.state.value.recovery)
            controller.close(); controller.hideJobs()
        }
    }

    @After fun closeFixture() {
        compose.runOnIdle { if (!controller.state.value.busy) { controller.close(); controller.hideJobs() } }
    }

    private fun openEditor() {
        compose.setContent { LocalicaTheme {
            titleStyle = MaterialTheme.typography.titleLarge
            val source = rememberFrostedBackdrop(Unit, dark = false)
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.matchParentSize().recordFrostedBackdrop(source)) { drawRect(Color.White) }
                CompositionLocalProvider(LocalFrostedBackdrop provides source, LocalModalBackdrop provides source,
                    LocalReducedMotion provides true) {
                    TagEditorHost(controller) {
                        val edit = presentedTagEdit()
                        ActionTextButton(onClick = { edit?.invoke(listOf(track), {}) }) { Text("Open editor") }
                    }
                }
            }
        } }
        compose.onNodeWithText("Open editor").performClick()
        compose.awaitExpectedWindowFocus(node("sheet"))
        compose.runOnIdle { assertEquals(listOf(track), controller.state.value.tracks) }
    }

    private fun node(slot: String) = compose.onNodeWithTag("tag-editor-$slot", useUnmergedTree = true)
    private fun bounds(slot: String) = node(slot).getUnclippedBoundsInRoot()
    private fun closeDp(expected: Dp, actual: Dp) = assertEquals(expected.value, actual.value, .6f)
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    private data class ChromeBounds(val header: DpRect, val body: DpRect, val footer: DpRect)

    private fun assertChrome(): ChromeBounds {
        val sheet = bounds("sheet"); val frame = bounds("frame")
        val header = bounds("header"); val body = bounds("body"); val footer = bounds("footer")
        // No keyboard is requested by this fixture. The IME-aware frame is the sheet's perimeter.
        assertEquals(sheet, frame)
        closeDp(24.dp, header.top - frame.top)
        for (slot in listOf(header, body, footer)) {
            closeDp(24.dp, slot.left - frame.left); closeDp(24.dp, frame.right - slot.right)
        }
        closeDp(16.dp, body.top - header.bottom); closeDp(16.dp, footer.top - body.bottom)
        closeDp(24.dp, frame.bottom - footer.bottom)
        assertTrue("The weighted body retains a usable viewport", body.bottom > body.top)
        node("body").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        for (slot in listOf("sheet", "frame", "header", "footer")) {
            node(slot).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange))
        }
        val bodyNode = node("body").fetchSemanticsNode()
        generateSequence(bodyNode.parent) { it.parent }.forEach {
            assertFalse("No second scrolling shell surrounds the existing body", it.config.contains(SemanticsProperties.VerticalScrollAxisRange))
        }
        for (label in listOf("Close editor", "Review changes")) {
            val action = compose.onNodeWithText(label).assertIsDisplayed().assertHasClickAction().getUnclippedBoundsInRoot()
            assertTrue("$label retains a 48dp target", action.right - action.left >= 48.dp && action.bottom - action.top >= 48.dp)
            closeDp(0.dp, (if (label == "Close editor") header.right else footer.right) - action.right)
        }
        val layouts = mutableListOf<TextLayoutResult>()
        val title = compose.onNodeWithText("Edit 1 song")
        closeDp(0.dp, title.getUnclippedBoundsInRoot().left - header.left)
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach {
            assertEquals(requireNotNull(titleStyle).fontSize, it.layoutInput.style.fontSize)
            assertEquals(requireNotNull(titleStyle).lineHeight, it.layoutInput.style.lineHeight)
            assertEquals(FontWeight.Bold, it.layoutInput.style.fontWeight)
            assertEquals("Actual Android font scale is not capped", instrumentation.targetContext.resources.configuration.fontScale,
                it.layoutInput.density.fontScale, 0f)
            assertFalse("The shared title retains all lines", it.multiParagraph.didExceedMaxLines)
        }
        return ChromeBounds(header, body, footer)
    }

    private fun assertBodyHasNoSecondPerimeter() {
        val first = compose.onNodeWithText("Only checked fields change. A checked blank field removes that tag.")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val body = bounds("body")
        closeDp(0.dp, first.left - body.left); closeDp(0.dp, first.top - body.top)
    }

    private fun assertBodyScrollKeepsChrome() {
        assertBodyHasNoSecondPerimeter()
        val before = assertChrome()
        val range = node("body").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("All checked fields require scrolling rather than a second shell", range.maxValue() > 0f)
        EditField.entries.forEach { entry ->
            val input = field(entry.label).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            input.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach {
                assertEquals(instrumentation.targetContext.resources.configuration.fontScale, it.layoutInput.density.fontScale, 0f)
            }
        }
        assertTrue("The lower fields really moved the existing scroll state", range.value() > 0f)
        assertEquals("Header/body viewport/footer remain fixed during scrolling", before, assertChrome())
    }

    @Test fun actualControllerSheetUsesOneSharedPerimeterAndTwoChromeGaps() {
        openEditor()
        assertChrome()
        assertBodyHasNoSecondPerimeter()
        compose.onNodeWithText("Review changes").assertIsNotEnabled()
        compose.onNodeWithText("Close editor").performClick()
        compose.runOnIdle { assertTrue(controller.state.value.tracks.isEmpty()) }
    }

    @Test fun shortNativeSheetScrollsFieldsAndKeepsDraftAcrossResizeAndReviewCancel() {
        requireDisplay(360.dp, 480.dp, "shortNativeSheetScrollsFieldsAndKeepsDraftAcrossResizeAndReviewCancel")
        openEditor()
        val view = (node("sheet").fetchSemanticsNode().root as ViewRootForTest).view
        val original = compose.runOnUiThread { view.width to view.height }
        resizeWindow(360.dp, 480.dp)
        assertBodyScrollKeepsChrome()
        val draft = "Draft survives shared chrome"
        field(EditField.Title.label).performScrollTo().performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(AnnotatedString(draft)))
        }
        compose.onNodeWithText("Review changes").assertIsEnabled().performClick()
        compose.awaitExpectedWindowFocus(compose.onNodeWithText("Save changes"))
        compose.onNodeWithText("Name: $draft").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.awaitExpectedWindowFocus(node("sheet"))
        val density = view.resources.displayMetrics.density
        resizeWindow((original.first / density).dp, (original.second / density).dp)
        assertChrome()
        field(EditField.Title.label).performScrollTo().assertTextContains(draft)
        compose.onNodeWithText("Review changes").assertIsEnabled()
        compose.runOnIdle {
            assertFalse(controller.state.value.busy)
            assertTrue("Review cancellation never writes", controller.state.value.results.isEmpty())
        }
    }

    @Test fun native360x640Font2KeepsSharedChromeAndScrollableFullSizeFields() {
        requireDisplay(360.dp, 640.dp, "native360x640Font2KeepsSharedChromeAndScrollableFullSizeFields", fontScale = 2f)
        openEditor()
        resizeWindow(360.dp, 640.dp)
        assertBodyScrollKeepsChrome()
    }

    @Suppress("DEPRECATION")
    private fun requireDisplay(width: Dp, height: Dp, method: String, fontScale: Float? = null) {
        val context = instrumentation.targetContext
        val display = android.util.DisplayMetrics()
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(display)
        val fits = display.widthPixels / display.density >= width.value && display.heightPixels / display.density >= height.value
        val fontMatches = fontScale == null || context.resources.configuration.fontScale == fontScale
        val explicit = InstrumentationRegistry.getArguments().getString("class").orEmpty().split(',')
            .any { it.substringAfter('#', "") == method }
        if (explicit) {
            assertTrue("Configure sufficient native display space", fits)
            assertTrue("Configure the requested actual Android font scale; do not substitute LocalDensity", fontMatches)
        } else assumeTrue("Requires sufficient display space and the requested actual system font scale", fits && fontMatches)
    }

    @Suppress("DEPRECATION")
    private fun resizeWindow(width: Dp, height: Dp) {
        val view = (node("sheet").fetchSemanticsNode().root as ViewRootForTest).view
        val window = (view.parent as DialogWindowProvider).window
        val density = view.resources.displayMetrics.density
        val widthPx = (width.value * density).roundToInt(); val heightPx = (height.value * density).roundToInt()
        compose.runOnIdle { window.setLayout(widthPx, heightPx) }
        compose.waitUntil(5000) { compose.runOnUiThread { view.width == widthPx && view.height == heightPx } }
        compose.runOnUiThread {
            assertEquals(widthPx, window.decorView.width); assertEquals(heightPx, window.decorView.height)
            val display = android.util.DisplayMetrics()
            view.context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(display)
            val origin = IntArray(2).also(view::getLocationOnScreen)
            assertTrue("The requested native viewport is on screen", origin[0] >= 0 && origin[1] >= 0 &&
                origin[0] + widthPx <= display.widthPixels && origin[1] + heightPx <= display.heightPixels)
        }
    }
}
