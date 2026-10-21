package io.github.anastaci1a.localica

import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class RepeatOptionsEnvelopeTest {
    @get:Rule val compose = createComposeRule()

    private fun scrollRange() = compose.onNodeWithTag("repeat-options-scroll")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    @Test fun nativeWindowKeepsItsBoundsWhileAdvancedTouchScrollingLeavesTheDraftUnchanged() {
        var open by mutableStateOf(true)
        var settings by mutableStateOf(QueueSettings())
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                if (open) RepeatOptionsDialog(settings, { open = false }, { settings = it(settings) })
            }
        } }
        compose.onNodeWithText("Repeat modes").assertIsDisplayed()
        val dialog = compose.onNodeWithTag("repeat-options-dialog")
        val bounds = dialog.fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("repeat-options-body").fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("repeat-options-tabs").fetchSemanticsNode().boundsInRoot
        val apply = compose.onNodeWithText("Apply").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Advanced").performClick()
        assertEquals(bounds, dialog.fetchSemanticsNode().boundsInRoot)
        assertEquals(body, compose.onNodeWithTag("repeat-options-body").fetchSemanticsNode().boundsInRoot)
        assertTrue(scrollRange().maxValue() > 0f)
        // A quick vertical drag over the choice track belongs to its scrolling parent,
        // not the selector. Both the viewport and the fixed chrome must stay put.
        compose.onNodeWithTag("repeat-options-scroll").performTouchInput { swipeUp(durationMillis = 120) }
        compose.waitUntil(5000) { scrollRange().value() > 0f }
        assertEquals(bounds, dialog.fetchSemanticsNode().boundsInRoot)
        assertEquals(body, compose.onNodeWithTag("repeat-options-body").fetchSemanticsNode().boundsInRoot)
        assertEquals(tabs, compose.onNodeWithTag("repeat-options-tabs").fetchSemanticsNode().boundsInRoot)
        assertEquals(apply, compose.onNodeWithText("Apply").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertFalse(open); assertEquals(AdvancedRepeat(), settings.advancedRepeat) }
    }

    @Test fun nativeNormalMotionReopenDuringExitDiscardsDraftsAndAppliesOnlyTheFreshSession() {
        var open by mutableStateOf(false)
        val original = QueueSettings(advancedRepeat = AdvancedRepeat(songEnd = SongEnd.NextPaused))
        var settings by mutableStateOf(original)
        var submissions = 0
        lateinit var dialogs: GlassDialogs
        compose.setContent { LocalicaTheme {
            dialogs = checkNotNull(LocalGlassDialogs.current)
            CompositionLocalProvider(LocalReducedMotion provides false) {
                ActionTextButton({ open = true }) { Text("Open Repeat") }
                if (open) RepeatOptionsDialog(settings, { open = false }, { settings = it(settings); submissions++ })
            }
        } }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText("Open Repeat").performClick()
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
            }
            val opening = compose.runOnUiThread { dialogs.entries.single().anchor }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnUiThread { opening.contact.expansion.value > 0f }
            }
            // Sample an observed moving frame, not just the native window's first attachment.
            val progress = compose.runOnUiThread { opening.contact.expansion.value }
            assertTrue("Normal opening has not reached its interactive phase", progress > 0f && progress < .85f)
            compose.onNodeWithTag("glass-morph-surface").assertExists()
            compose.onNodeWithText("Apply").assertDoesNotExist()
            compose.mainClock.autoAdvance = true
            compose.onNodeWithText("Repeat modes").assertIsDisplayed()
            compose.awaitExpectedWindowFocus(compose.onNodeWithTag("repeat-options-dialog"))
            val firstView = nativeDialogView()
            val firstWindow = (firstView.parent as DialogWindowProvider).window
            val envelope = nativeEnvelope()
            compose.onNodeWithText("Simple").performClick()
            compose.onNodeWithText("Repeat song").performClick()
            compose.onNodeWithText("Advanced").performClick()
            compose.onNodeWithText("Repeat this queue").performScrollTo().performClick()
            assertEquals(envelope, nativeEnvelope())
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithTag("repeat-options-dialog").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("glass-morph-surface").assertExists()
            compose.onNodeWithText("Apply").assertDoesNotExist()
            compose.runOnUiThread {
                assertTrue("Exit retains drawing, not native input", firstView.isAttachedToWindow)
                assertTrue(firstWindow.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                assertEquals(original, settings)
                assertEquals(0, submissions)
                open = true
            }
            // Reopen before the old exit finishes, not just after its window has disappeared.
            compose.mainClock.autoAdvance = true
            compose.onNodeWithText("Repeat modes").assertIsDisplayed()
            compose.awaitExpectedWindowFocus(compose.onNodeWithTag("repeat-options-dialog"))
            compose.waitUntil(5000) { compose.runOnUiThread { !firstView.isAttachedToWindow } }
            assertNotSame(firstView, nativeDialogView())
            assertEquals(envelope, nativeEnvelope())
            assertSingleNativeSession()
            assertEquals(0f, scrollRange().value(), 0f)
            compose.onNodeWithText("Load next song and pause").assertIsSelected()
            compose.onNodeWithText("Repeat this queue").performScrollTo().assertIsNotSelected()
            compose.onNodeWithText("Simple").performClick()
            compose.onNodeWithText("Play next song").assertIsSelected()
            compose.onNodeWithText("Repeat song").performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.runOnIdle {
                assertFalse(open)
                assertEquals(1, submissions)
                assertEquals(original.copy(repeat = QueueRepeat.One, advancedRepeat = null), settings)
            }
            compose.onNodeWithText("Open Repeat").performClick()
            compose.awaitExpectedWindowFocus(compose.onNodeWithTag("repeat-options-dialog"))
            assertEquals(envelope, nativeEnvelope())
            assertSingleNativeSession()
            compose.onNodeWithText("Simple").assertIsSelected()
            compose.onNodeWithText("Repeat song").assertIsSelected()
            assertEquals(0f, scrollRange().value(), 0f)
            compose.onNodeWithText("Cancel").performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, submissions) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun nativeNormalMotionModeSwitchDuringFlingAndScrolledResizeKeepTheEnvelopeAndDraft() {
        var open by mutableStateOf(true)
        val original = QueueSettings(advancedRepeat = AdvancedRepeat(songEnd = SongEnd.NextPaused))
        var settings by mutableStateOf(original)
        var submissions = 0
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                if (open) RepeatOptionsDialog(settings, { open = false }, { settings = it(settings); submissions++ })
            }
        } }
        compose.awaitExpectedWindowFocus(compose.onNodeWithTag("repeat-options-dialog"))
        val view = nativeDialogView()
        val window = (view.parent as DialogWindowProvider).window
        val originalWidth = window.attributes.width
        val originalHeight = window.attributes.height
        val originalViewHeight = compose.runOnUiThread { view.height }
        val envelope = nativeEnvelope()
        try {
            compose.onNodeWithText("Repeat this song").performScrollTo().performClick()
            val toTop = scrollRange().maxValue()
            compose.onNodeWithTag("repeat-options-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -toTop) }
            assertEquals(0f, scrollRange().value(), 0f)
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("repeat-options-scroll").performTouchInput {
                down(Offset(centerX, height * .8f))
                moveTo(Offset(centerX, height * .6f), delayMillis = 16)
                moveTo(Offset(centerX, height * .4f), delayMillis = 16)
                up()
            }
            val released = scrollRange().value()
            assertTrue("The body must actually consume the touch drag", released > 0f)
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                scrollRange().value() > released
            }
            assertTrue("Switch modes during travel, not after hitting the end", scrollRange().value() < scrollRange().maxValue())
            assertEquals(envelope, nativeEnvelope())
            compose.onNodeWithText("Simple").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("Simple").assertIsSelected()
            assertEquals(0f, scrollRange().value(), 0f)
            assertEquals(0f, scrollRange().maxValue(), 0f)
            assertEquals(envelope, nativeEnvelope())
            compose.onNodeWithText("Repeat song").performClick()
            compose.onNodeWithText("Advanced").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("Advanced").assertIsSelected()
            assertTrue("Advanced retains its own scrolled viewport", scrollRange().value() > 0f)
            assertEquals(envelope, nativeEnvelope())
            compose.mainClock.autoAdvance = true
            compose.onNodeWithText("Repeat this queue").performScrollTo().performClick()
            compose.onNodeWithText("Queue-end options apply after you turn off repeating the current song.")
                .performScrollTo().assertIsDisplayed()
            assertTrue(scrollRange().value() > 0f)
            compose.runOnIdle { assertEquals(original, settings); assertEquals(0, submissions) }
            // Resize the actual shared native window with its existing, already-scrolled draft.
            compose.runOnIdle { window.setLayout(originalWidth, (300 * view.resources.displayMetrics.density).roundToInt()) }
            compose.waitUntil(5000) {
                compose.runOnUiThread { view.height < originalViewHeight } && nativeEnvelope().first().height < envelope.first().height
            }
            assertSame(view, nativeDialogView())
            assertTrue("The short window retains a usable body", compose.onNodeWithTag("repeat-options-body").fetchSemanticsNode().size.height > 0)
            assertTrue("Resizing cannot reset the existing body scroll", scrollRange().value() > 0f)
            assertTrue(scrollRange().value() <= scrollRange().maxValue())
            compose.onNodeWithText("Advanced").performScrollTo().assertIsSelected()
            compose.onNodeWithText("Repeat this queue").performScrollTo().assertIsSelected()
            compose.onNodeWithText("Repeat this song").performScrollTo().assertIsSelected()
            compose.onNodeWithText("Apply").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
            assertSingleNativeSession()
            compose.runOnIdle { window.setLayout(originalWidth, originalHeight) }
            compose.waitUntil(5000) {
                compose.runOnUiThread { view.height == originalViewHeight } && nativeEnvelope() == envelope
            }
            compose.onNodeWithText("Simple").performClick()
            compose.onNodeWithText("Repeat song").assertIsSelected()
            compose.onNodeWithText("Advanced").performClick()
            assertEquals(envelope, nativeEnvelope())
            compose.onNodeWithText("Apply").performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.runOnIdle {
                assertFalse(open)
                assertEquals(1, submissions)
                assertEquals(original.copy(repeat = QueueRepeat.One,
                    advancedRepeat = AdvancedRepeat(songEnd = SongEnd.Repeat, queueEnd = QueueEnd.Repeat)), settings)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { if (view.isAttachedToWindow) window.setLayout(originalWidth, originalHeight) }
        }
    }

    private fun nativeDialogView() = (compose.onNodeWithTag("repeat-options-dialog")
        .fetchSemanticsNode().root as ViewRootForTest).view.also {
        assertTrue("Exercise the shared native host, never an inline substitute", it.parent is DialogWindowProvider)
    }

    private fun nativeEnvelope() = listOf("repeat-options-dialog", "repeat-options-body", "repeat-options-tabs")
        .map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot } +
        listOf("Cancel", "Apply").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }

    private fun assertSingleNativeSession() {
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onAllNodesWithTag("glass-morph-surface").assertCountEquals(1)
        for (text in listOf("Repeat modes", "Simple", "Advanced", "Reset", "Cancel", "Apply")) {
            compose.onAllNodesWithText(text).assertCountEquals(1)
        }
    }

    @Test fun simpleFitsAndAdvancedScrollsInsideTheSameCompactSurface() {
        var settings by mutableStateOf(QueueSettings())
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalInlineGlassDialog provides true, LocalReducedMotion provides true) {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    RepeatOptionsDialog(settings, {}, { settings = it(settings) })
                }
            }
        } }
        val dialog = compose.onNodeWithTag("repeat-options-dialog")
        val original = dialog.fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("repeat-options-tabs").fetchSemanticsNode().boundsInRoot
        val apply = compose.onNodeWithText("Apply").fetchSemanticsNode().boundsInRoot
        assertEquals("Simple needs no artificial scrolling", 0f, scrollRange().maxValue(), 0f)
        compose.onNodeWithText("Repeat song").assertIsDisplayed()
        compose.onNodeWithText("Reset").assertIsDisplayed()
        compose.onNodeWithText("Advanced").performClick()
        assertEquals(original, dialog.fetchSemanticsNode().boundsInRoot)
        assertTrue("Advanced is bounded by the compact Simple envelope", scrollRange().maxValue() > 0f)
        assertEquals(0f, scrollRange().value(), 0f)
        compose.onNodeWithText("Repeat this queue").performScrollTo().performClick()
        compose.onNodeWithText("Reset").performScrollTo().assertIsDisplayed()
        val advancedPosition = scrollRange().value()
        assertTrue(advancedPosition > 0f)
        assertEquals(tabs, compose.onNodeWithTag("repeat-options-tabs").fetchSemanticsNode().boundsInRoot)
        assertEquals(apply, compose.onNodeWithText("Apply").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Simple").performClick()
        assertEquals(original, dialog.fetchSemanticsNode().boundsInRoot)
        assertEquals(0f, scrollRange().maxValue(), 0f)
        compose.onNodeWithText("Repeat song").performClick()
        compose.onNodeWithText("Advanced").performClick()
        assertEquals(advancedPosition, scrollRange().value(), 0f)
        compose.onNodeWithText("Repeat this queue").assertIsSelected()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(AdvancedRepeat(), settings.advancedRepeat) }
        // Inert envelope faces must never duplicate accessible live controls.
        compose.onAllNodesWithText("Advanced").assertCountEquals(1)
        compose.onAllNodesWithText("Reset").assertCountEquals(1)
    }

    @Test fun envelopeSurvivesModeDraftFontAndWindowChangesWithReachableActions() {
        var width by mutableStateOf(360.dp)
        var height by mutableStateOf(640.dp)
        var scale by mutableFloatStateOf(1f)
        var initialAdvanced by mutableStateOf(false)
        var generation by mutableIntStateOf(0)
        var densityValue = 1f
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            densityValue = density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale),
                LocalInlineGlassDialog provides true, LocalReducedMotion provides true) {
                Box(Modifier.width(width).height(height)) {
                    key(generation) {
                        RepeatOptionsDialog(QueueSettings(advancedRepeat = if (initialAdvanced) AdvancedRepeat() else null), {}, {})
                    }
                }
            }
        } }
        for ((w, h, font) in listOf(Triple(360.dp, 640.dp, 1f), Triple(400.dp, 240.dp, 1.5f), Triple(220.dp, 320.dp, 2f))) {
            var envelope: IntSize? = null
            for (advanced in listOf(false, true)) {
                compose.runOnIdle { width = w; height = h; scale = font; initialAdvanced = advanced; generation++ }
                val dialog = compose.onNodeWithTag("repeat-options-dialog")
                val size = dialog.fetchSemanticsNode().size
                if (envelope == null) envelope = size else assertEquals("Initial mode does not choose the envelope", envelope, size)
                if (font == 1f) assertTrue("Compact surface wraps below the available window", size.height < h.value * densityValue)
                compose.onNodeWithText("Advanced").performScrollTo().performClick()
                compose.onNodeWithText("Repeat this song").performScrollTo().performClick()
                compose.onNodeWithText("Queue-end options apply after you turn off repeating the current song.")
                    .performScrollTo().assertIsDisplayed()
                assertEquals("Conditional explanation cannot resize the surface", envelope, dialog.fetchSemanticsNode().size)
                compose.onNodeWithText("Reset").performScrollTo().performClick()
                assertEquals(envelope, dialog.fetchSemanticsNode().size)
                compose.onNodeWithText("Simple").performScrollTo().performClick()
                compose.onNodeWithText("Repeat song").performScrollTo().performClick()
                assertEquals(envelope, dialog.fetchSemanticsNode().size)
                compose.onNodeWithText("Apply").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
            }
        }
        // Resize the existing draft back to normal: no remembered oversized high-water mark.
        compose.runOnIdle { width = 360.dp; height = 640.dp; scale = 1f }
        assertEquals(0f, scrollRange().maxValue(), 0f)
        compose.onNodeWithText("Repeat song").assertIsSelected()
        compose.onNodeWithText("Apply").assertIsDisplayed()
    }

    @Test fun cancelDiscardsBothModeDraftsAndReopeningRebuildsTheEnvelope() {
        var open by mutableStateOf(true)
        var submissions = 0
        val settings = QueueSettings(advancedRepeat = AdvancedRepeat(songEnd = SongEnd.NextPaused))
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalInlineGlassDialog provides true, LocalReducedMotion provides true) {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    if (open) RepeatOptionsDialog(settings, { open = false }, { submissions++ })
                }
            }
        } }
        val original = compose.onNodeWithTag("repeat-options-dialog").fetchSemanticsNode().size
        compose.onNodeWithText("Simple").performClick()
        compose.onNodeWithText("Repeat song").performClick()
        compose.onNodeWithText("Advanced").performClick()
        compose.onNodeWithText("Repeat this queue").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("repeat-options-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, submissions); open = true }
        assertEquals(original, compose.onNodeWithTag("repeat-options-dialog").fetchSemanticsNode().size)
        compose.onNodeWithText("Load next song and pause").assertIsSelected()
        assertEquals(0f, scrollRange().value(), 0f)
        compose.onNodeWithText("Repeat this queue").performScrollTo().assertIsNotSelected()
    }
}
