package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GlassPressureTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @org.junit.Before fun configureWindowLikeTheApp() {
        compose.activityRule.scenario.onActivity {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it.window, false)
            it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    @Test fun heldSortLensCompressesAtTheBoundaryAndRecoversOnRelease() {
        pressureChoices(listOf("Title", "Artist", "Year"))
        val (initial, initialShell) = displayedTrackFrame()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Title").performTouchInput { down(center); moveBy(Offset(0f, -initial.height * 2)) }
        compose.mainClock.advanceTimeBy(300)
        val (held, heldShell) = displayedTrackFrame()
        assertTrue("A held overdrag compresses the vertical lens: $initial -> $held", held.height < initial.height * .97f)
        // The shared track now moves its wall under pressure (#209). Contact is
        // measured against that rendered wall, not the obsolete resting y value.
        val restingGap = initial.top - initialShell.top
        val heldGap = held.top - heldShell.top
        assertTrue("The rendered shell extends toward the pull", heldShell.top < initialShell.top - 1f)
        assertTrue("The lens remains inside the current wall: gap=$heldGap", heldGap >= -1f)
        assertTrue("The rendered contact gap closes: $restingGap -> $heldGap", heldGap < restingGap - 1f)
        compose.onNodeWithText("Title").performTouchInput { up() }
        repeat(12) {
            compose.mainClock.advanceTimeBy(32)
            val recovering = displayedLensBounds()
            assertTrue("Vertical release must not turn into a wide horizontal squeeze: $recovering",
                kotlin.math.abs(recovering.width - initial.width) < initial.width * .04f)
        }
        compose.mainClock.advanceTimeBy(1200)
        assertEquals(initial.height, displayedLensBounds().height, 1f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun selectedChoiceRespondsToContactBeforeAnyDrag() {
        pressureChoices(listOf("First", "Second"))
        val initial = displayedLensBounds()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("First").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)
        assertTrue("Contact flattens and widens the resting droplet before touch slop", displayedLensBounds().height > initial.height + 1f)
        compose.onNodeWithText("First").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1600)
        assertEquals(initial.height, displayedLensBounds().height, 1f)
        compose.mainClock.autoAdvance = true
    }
    @Test fun verticalReleaseKeepsTheRenderedReboundOnTravelAtDefaultDamping() = axisRelease(false, 8f)
    @Test fun verticalReleaseKeepsTheRenderedReboundOnTravelAtHigherDamping() = axisRelease(false, 20f)
    @Test fun horizontalReleaseUsesTheSameRenderedCrossReturnAtDefaultDamping() = axisRelease(true, 8f)
    @Test fun horizontalReleaseUsesTheSameRenderedCrossReturnAtHigherDamping() = axisRelease(true, 20f)

    @Test fun retouchAndCancelKeepThePresentedCrossContourContinuous() {
        checkRoundedContainment = true
        pressureChoices(listOf("First", "Second"))
        val rest = displayedTrackFrame()
        compose.mainClock.autoAdvance = false
        var heldPointer = false
        var failure: Throwable? = null
        try {
            compose.onNodeWithText("First").performTouchInput {
                down(center); heldPointer = true; moveBy(Offset(0f, -rest.first.height * 2f))
            }
            compose.mainClock.advanceTimeBy(400)
            displayedTrackFrame()
            compose.onNodeWithText("First").performTouchInput { up(); heldPointer = false }
            compose.mainClock.advanceTimeBy(96)
            val before = displayedTrackFrame()
            compose.onNodeWithText("First").performTouchInput { down(center); heldPointer = true }
            compose.mainClock.advanceTimeByFrame()
            val after = displayedTrackFrame()
            assertEquals("Re-touch inherits the presented lens width", before.first.width, after.first.width, 1f)
            assertEquals("Re-touch inherits the presented shell width", before.second.width, after.second.width, 1f)
            compose.mainClock.advanceTimeBy(240)
            val held = displayedTrackFrame()
            assertTrue("The re-grab stays inside the current shell", held.first.left >= held.second.left - 1f && held.first.right <= held.second.right + 1f)
            compose.onNodeWithText("First").performTouchInput { cancel(); heldPointer = false }
            compose.mainClock.advanceTimeBy(1800)
            val final = displayedTrackFrame()
            assertEquals(rest.first.width, final.first.width, 1f)
            assertEquals(rest.first.height, final.first.height, 1f)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            finishAxisInput(heldPointer, failure)
        }
    }

    private fun axisRelease(horizontal: Boolean, damping: Float) {
        checkRoundedContainment = true
        pressureChoices(listOf("First", "Second", "Third"), horizontal, damping)
        val rest = displayedLensBounds()
        fun cross(rect: Rect) = if (horizontal) rect.height else rect.width
        fun axial(rect: Rect) = if (horizontal) rect.width else rect.height
        compose.mainClock.autoAdvance = false
        var heldPointer = false
        var failure: Throwable? = null
        try {
            compose.onNodeWithText("First").performTouchInput {
                down(center); heldPointer = true
                moveBy(if (horizontal) Offset(-rest.width * 2f, 0f) else Offset(0f, -rest.height * 2f))
            }
            compose.mainClock.advanceTimeBy(400)
            val held = displayedLensBounds()
            assertTrue("Held warp remains axial: $rest -> $held", axial(held) < axial(rest) - 1f)
            compose.onNodeWithText("First").performTouchInput { up(); heldPointer = false }
            val frames = mutableListOf(held)
            var previousDistance = kotlin.math.abs(cross(held) - cross(rest))
            repeat(30) {
                compose.mainClock.advanceTimeBy(32)
                val frame = displayedLensBounds()
                frames += frame
                val distance = kotlin.math.abs(cross(frame) - cross(rest))
                assertTrue("Cross return must not grow a new lateral excursion: $held -> $frames", distance <= previousDistance + 1f)
                assertTrue("Cross return stays between held and rest, with raster tolerance: $frame",
                    cross(frame) >= minOf(cross(held), cross(rest)) - 1f &&
                        cross(frame) <= maxOf(cross(held), cross(rest)) + 1f)
                previousDistance = distance
            }
            val travelSpan = frames.maxOf(::axial) - frames.minOf(::axial)
            val crossSpan = frames.maxOf(::cross) - frames.minOf(::cross)
            android.util.Log.i("GlassAxisReturn", "horizontal=$horizontal damping=$damping rest=$rest held=$held travelSpan=$travelSpan crossSpan=$crossSpan frames=$frames")
            assertTrue("The actual rendered release must be travel-axis dominant", travelSpan > crossSpan + 1f)
            compose.mainClock.advanceTimeBy(1600)
            val final = displayedLensBounds()
            assertEquals(rest.width, final.width, 1f)
            assertEquals(rest.height, final.height, 1f)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            finishAxisInput(heldPointer, failure)
        }
    }

    private fun finishAxisInput(held: Boolean, failure: Throwable?) {
        try {
            if (held) compose.onNodeWithText("First").performTouchInput { cancel() }
        } catch (cleanup: Throwable) {
            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private var checkRoundedContainment = false
    private var horizontalChoices = false
    private var presentationStage by mutableIntStateOf(0)
    private lateinit var pressureView: View

    private fun pressureChoices(labels: List<String>, horizontal: Boolean = false, damping: Float = 8f) {
        horizontalChoices = horizontal
        var source: FrostedBackdrop? = null
        compose.setContent { LocalicaTheme {
            pressureView = LocalView.current
            val backing = rememberFrostedBackdrop(Unit, glassBlur = 0f, whiteTint = 0f)
            source = backing
            Box(Modifier.width(284.dp).padding(top = 100.dp).testTag("pressure-scene")) {
                // Record a distinct scene, then cover its direct presentation.
                // Only the real lens can display its red pixels; labels and the
                // track remain on blue, allowing a renderer-independent scan.
                Canvas(Modifier.matchParentSize().recordFrostedBackdrop(backing)) { drawRect(Color.Red) }
                Canvas(Modifier.matchParentSize()) { drawRect(Color.Blue) }
                CompositionLocalProvider(LocalFrostedBackdrop provides backing,
                    LocalGlassAppearance provides io.github.anastaci1a.localica.navigation.GlassAppearance(shapeDamping = damping)) {
                    Box(Modifier.padding(12.dp)) { GlassChoices(labels, 0, horizontal = horizontal) { } }
                }
                PresentationMarker { presentationStage }
            }
        } }
        if (android.os.Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source?.lensSnapshot != null }
        compose.waitForIdle()
    }

    private fun displayedLensBounds(): Rect = displayedTrackFrame().first

    /** Read both silhouettes from the same presented frame, never layout semantics. */
    private fun displayedTrackFrame(): Pair<Rect, Rect> {
        // The shared marker encodes one five-bit token in its red channel.
        // Reuse only after a full capture cycle, always distinct from the previous frame.
        compose.runOnIdle { presentationStage = (presentationStage + 1) % 32 }
        val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("pressure-scene"), presentationStage) { pressureView }
        fun scan(accepts: (Color) -> Boolean): Rect {
            var left = pixels.width; var top = pixels.height; var right = -1; var bottom = -1
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (accepts(pixels[x, y])) {
                    left = minOf(left, x); top = minOf(top, y)
                    right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
            assertTrue("The rendered silhouette must be visible", right >= left && bottom >= top)
            return Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
        }
        fun red(color: Color) = color.red > .6f && color.green < .4f && color.blue < .4f
        val lens = scan(::red)
        if (checkRoundedContainment) {
            val sceneBounds = compose.onNodeWithTag("pressure-scene").fetchSemanticsNode().boundsInRoot
            val layoutShell = compose.onNodeWithTag("choice-track-shell").fetchSemanticsNode().boundsInRoot
                .translate(-sceneBounds.topLeft)
            val layoutHost = compose.onNodeWithTag("choice-track-host").fetchSemanticsNode().boundsInRoot
            val hostCross = if (horizontalChoices) layoutHost.height else layoutHost.width
            val shellCross = if (horizontalChoices) layoutShell.height else layoutShell.width
            // Closing the held cross inset moves each enclosing corner inward by
            // half the shell contraction. Its captured return preserves this relation.
            val radius = (with(compose.density) { 24.dp.toPx() } - (hostCross - shellCross) / 2f)
                .coerceIn(0f, minOf(layoutShell.width, layoutShell.height) / 2f)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (red(pixels[x, y])) {
                val px = x + .5f; val py = y + .5f
                val horizontalStraight = (layoutShell.width / 2f - radius).coerceAtLeast(0f)
                val verticalStraight = (layoutShell.height / 2f - radius).coerceAtLeast(0f)
                val cx = px.coerceIn(layoutShell.center.x - horizontalStraight, layoutShell.center.x + horizontalStraight)
                val cy = py.coerceIn(layoutShell.center.y - verticalStraight, layoutShell.center.y + verticalStraight)
                val distance = kotlin.math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
                assertTrue("Rendered lens escaped the rounded wall at ($x,$y), shell=$layoutShell radius=$radius", distance <= radius + 1f)
            }
        }
        // The gray track is 10% black over the fixture's solid blue; include its
        // red lens where that covers the track. Untinted exterior blue is excluded.
        val shell = scan { color -> red(color) ||
            (color.blue > .75f && color.blue < .97f && color.red < .15f && color.green < .15f) }
        android.util.Log.i("GlassPressure", "stage=$presentationStage displayedBounds=$lens shellBounds=$shell")
        return lens to shell
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun longFieldLabelsRemainPartOfTheEditableControl() {
        var text by mutableStateOf("")
        var keyboardVisible = false
        compose.setContent { LocalicaTheme { Box(Modifier.width(240.dp)) {
            keyboardVisible = WindowInsets.isImeVisible
            GlassTextField(text, { text = it }, label = { androidx.compose.material3.Text("Maximum queues (blank = unlimited)") }, singleLine = true)
        } } }
        compose.onNodeWithText("Maximum queues (blank = unlimited)").performClick()
        compose.waitUntil(5000) { keyboardVisible }
        compose.onNodeWithText("Maximum queues (blank = unlimited)").performTextInput("123")
        compose.runOnIdle { assertEquals("123", text) }
        androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
        compose.waitUntil(5000) { !compose.onNodeWithText("Maximum queues (blank = unlimited)").fetchSemanticsNode().config[SemanticsProperties.Focused] }
        compose.onNodeWithText("Maximum queues (blank = unlimited)").assertIsNotFocused()
        compose.runOnIdle { assertEquals("123", text) }
        compose.onNodeWithText("Maximum queues (blank = unlimited)").performClick()
        compose.onNodeWithText("Maximum queues (blank = unlimited)").assertIsFocused().performTextReplacement("456")
        compose.runOnIdle { assertEquals("456", text) }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun clearingAnUnfocusedFieldRefocusesAndOpensTheKeyboard() {
        var value by mutableStateOf("Initial draft")
        var keyboardVisible = false
        compose.setContent { LocalicaTheme { Box(Modifier.width(260.dp).padding(top = 60.dp)) {
            keyboardVisible = WindowInsets.isImeVisible
            GlassTextField(value, { value = it }, label = { androidx.compose.material3.Text("Draft") })
        } } }
        compose.onNodeWithText("Draft").assertIsNotFocused()
        compose.onNodeWithContentDescription("Clear text").performClick()
        compose.waitUntil(5000) { keyboardVisible }
        compose.onNodeWithText("Draft").assertIsFocused()
        compose.onNodeWithContentDescription("Clear text").assertDoesNotExist()
        compose.runOnIdle { assertEquals("", value) }
        compose.onNodeWithText("Draft").performTextInput("Again")
        compose.onNodeWithContentDescription("Clear text").performClick()
        compose.onNodeWithText("Draft").assertIsFocused()
        compose.runOnIdle { assertEquals("", value); assertTrue(keyboardVisible) }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun backEndsEditingBeforeClosingItsDialog() {
        var open by mutableStateOf(true)
        var value by mutableStateOf("")
        var keyboardVisible = false
        compose.setContent { LocalicaTheme { if (open) GlassDialog({ open = false }) {
            keyboardVisible = WindowInsets.isImeVisible
            GlassTextField(value, { value = it }, label = { androidx.compose.material3.Text("Dialog draft") })
        } } }
        compose.onNodeWithText("Dialog draft").performClick()
        compose.waitUntil(5000) { keyboardVisible }
        compose.onNodeWithText("Dialog draft").performTextInput("Keep this")
        compose.runOnIdle { assertEquals("Keep this", value) }
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        device.pressBack()
        compose.waitForIdle()
        compose.waitUntil(5000) { !compose.onNodeWithText("Dialog draft").fetchSemanticsNode().config[SemanticsProperties.Focused] }
        compose.onNodeWithText("Dialog draft").assertIsNotFocused()
        compose.runOnIdle { assertTrue(open); assertEquals("Keep this", value) }
        device.pressBack()
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(open) }
    }
}
