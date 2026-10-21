package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import java.io.File
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real viewer content, pointer recognizer and frame clock; no synthetic drag callbacks. */
class ArtworkViewerGestureTest {
    @get:Rule val compose = createComposeRule()
    private val state = ArtworkViewerState()
    private val reducedMotion = mutableStateOf(false)
    private var closed = 0
    private val wideViewport = mutableStateOf(false)
    private fun fixture(portraitArt: Boolean = false, landscape: Boolean = false) {
        wideViewport.value = landscape
        val bitmap = Bitmap.createBitmap(if (portraitArt) 400 else 800, if (portraitArt) 800 else 400,
            Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }.asImageBitmap()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion.value) {
                Box(Modifier.size(if (wideViewport.value) 360.dp else 260.dp, if (wideViewport.value) 260.dp else 360.dp)
                    .background(Color.Black)) {
                    ArtworkViewerContent(bitmap, false, { closed++ }, state = state)
                }
            }
        } }
        compose.waitForIdle()
    }
    private fun viewport() = compose.onNodeWithTag("artwork-viewer-viewport")
    private fun transform(): ArtworkTransform {
        lateinit var result: ArtworkTransform
        compose.runOnIdle { result = state.transform }
        return result
    }
    private fun action(label: String) {
        val actions = viewport().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == label }.action()) }
    }
    private fun zoom(times: Int = 3) { repeat(times) { action("Zoom in") } }
    private fun flick() = viewport().performTouchInput {
        down(center)
        repeat(4) { moveBy(Offset(width * .04f, 0f), delayMillis = 20) }
        up()
    }

    @Test fun realSongInfoViewerLoadsArtworkAndCloseOrBackRestoresInfo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "playlist-covers").apply { mkdirs() }
        val file = File.createTempFile("viewer-regression-", ".png", directory)
        try {
            Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(android.graphics.Color.RED)
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
            val track = Track(android.net.Uri.fromFile(file).toString(), "Viewer fixture", fileName = file.name, relativePath = file.name)
            compose.setContent { LocalicaTheme { SongInformation(track, { closed++ }, {}) {} } }
            repeat(2) { attempt ->
                val entry = compose.onNode(SemanticsMatcher("View artwork action") {
                    it.config.getOrNull(SemanticsActions.OnClick)?.label == "View artwork"
                })
                compose.awaitExpectedWindowFocus(entry)
                entry.performTouchInput { click(center) }
                compose.waitUntil(10000) {
                    compose.onAllNodesWithContentDescription("Zoom in").fetchSemanticsNodes().any {
                        !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                    }
                }
                compose.awaitExpectedWindowFocus(viewport())
                viewport().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "100% zoom"))
                compose.onNodeWithContentDescription("Zoom in").performTouchInput { click(center) }
                viewport().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "200% zoom"))
                viewport().performTouchInput {
                    down(center); moveBy(Offset(width * .12f, 0f), delayMillis = 50); up()
                }
                if (attempt == 0) compose.onNodeWithContentDescription("Close artwork").performTouchInput { click(center) }
                else pressSystemBack()
                compose.waitUntil(5000) { compose.onAllNodesWithTag("artwork-viewer").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithText("Song information").assertIsDisplayed()
                compose.runOnIdle { assertEquals("Closing artwork must not dismiss Info", 0, closed) }
            }
        } finally { file.delete() }
    }

    @Test fun fittedImageIsCenteredInTheFullPageAndZoomDrawsAboveTheOldToolbarShelf() {
        fixture()
        val page = compose.onNodeWithTag("artwork-viewer").fetchSemanticsNode().boundsInRoot
        val viewportBounds = viewport().fetchSemanticsNode().boundsInRoot
        assertEquals(page, viewportBounds)
        val pixels = viewport().captureToImage().toPixelMap()
        val x = pixels.width / 2
        val halfImageHeight = pixels.width / 4
        assertEquals(Color.Red, pixels[x, pixels.height / 2])
        assertEquals(Color.Red, pixels[x, pixels.height / 2 - halfImageHeight + 3])
        assertEquals(Color.Red, pixels[x, pixels.height / 2 + halfImageHeight - 3])
        assertEquals(Color.Black, pixels[x, pixels.height / 2 - halfImageHeight - 3])
        assertEquals(Color.Black, pixels[x, pixels.height / 2 + halfImageHeight + 3])
        zoom(5)
        val zoomed = viewport().captureToImage().toPixelMap()
        assertEquals(Color.Red, zoomed[x, 3])
        assertEquals(Color.Red, zoomed[x, zoomed.height - 4])
        compose.onNodeWithContentDescription("Close artwork").performClick()
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun accessibleZoomAndCompactResetKeepNonSquareBoundsAndLandscapeLayout() {
        fixture(portraitArt = true, landscape = true)
        compose.onNodeWithContentDescription("Zoom in").performClick()
        assertEquals(2f, transform().scale, 0f)
        action("Zoom in")
        action("Zoom out")
        viewport().performTouchInput {
            down(center); moveBy(Offset(width * .2f, height * .2f), delayMillis = 300); cancel()
        }
        assertEquals("Portrait art still fits the landscape width at 2x", 0f, transform().offset.x, 0f)
        assertTrue(transform().offset.y > 0f)
        compose.onNodeWithContentDescription("Reset zoom").performClick()
        assertEquals(ArtworkTransform(), transform())
        zoom()
        action("Reset zoom")
        assertEquals(ArtworkTransform(), transform())
    }

    @Test fun releaseDeceleratesAndRegrabFreezesTheDisplayedFrameWithoutJumping() {
        fixture()
        zoom()
        compose.mainClock.autoAdvance = false
        flick()
        val released = transform().offset.x
        compose.mainClock.advanceTimeBy(64)
        val first = transform().offset.x
        compose.mainClock.advanceTimeBy(64)
        val second = transform().offset.x
        compose.mainClock.advanceTimeBy(64)
        val third = transform().offset.x
        assertTrue(first > released)
        assertTrue(second > first)
        assertTrue(third - second < second - first)
        viewport().performTouchInput { down(center) }
        val grabbed = transform()
        compose.mainClock.advanceTimeBy(240)
        assertEquals(grabbed, transform())
        viewport().performTouchInput { moveBy(Offset(-width * .12f, 0f), delayMillis = 100); cancel() }
        val reversed = transform()
        assertTrue(reversed.offset.x < grabbed.offset.x)
        compose.mainClock.advanceTimeBy(500)
        assertEquals("Cancellation cannot fling", reversed, transform())
    }

    @Test fun holdingStillBeforeReleaseDoesNotReuseTheEarlierSwipeVelocity() {
        fixture()
        zoom()
        compose.mainClock.autoAdvance = false
        viewport().performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(width * .04f, 0f), delayMillis = 20) }
            advanceEventTime(300)
            up()
        }
        val released = transform()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(released, transform())
    }

    @Test fun pinchKeepsItsFocusAndFingerCountChangesDoNotLaunchAFalseFling() {
        fixture()
        zoom()
        compose.mainClock.autoAdvance = false
        viewport().performTouchInput {
            down(0, Offset(width * .3f, centerY))
            down(1, Offset(width * .5f, centerY))
            repeat(4) { step ->
                moveTo(0, Offset(width * (.3f - (step + 1) * .015f), centerY), delayMillis = 20)
                moveTo(1, Offset(width * (.5f + (step + 1) * .015f), centerY), delayMillis = 20)
            }
            up(0); up(1)
        }
        val pinched = transform()
        assertTrue(pinched.scale > 4f)
        assertTrue("Zoom left of center moves the image right", pinched.offset.x > 0f)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(pinched, transform())
        action("Reset zoom")
        assertEquals(ArtworkTransform(), transform())
    }

    @Test fun diagonalReleaseClampsAtTheActualNonSquareCornerAndCanReverse() {
        fixture()
        zoom(5)
        compose.mainClock.autoAdvance = false
        viewport().performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(width * .3f, height * .3f), delayMillis = 16) }
            up()
        }
        compose.mainClock.advanceTimeBy(500)
        val bounds = artworkPanBounds(state.viewport, androidx.compose.ui.geometry.Size(800f, 400f), 6f)
        assertEquals(Offset(bounds.x, bounds.y), transform().offset)
        viewport().performTouchInput {
            down(center); moveBy(Offset(-width * .1f, -height * .1f), delayMillis = 100); cancel()
        }
        val reversed = transform().offset
        assertTrue(reversed.x < bounds.x)
        assertTrue(reversed.y < bounds.y)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(reversed, transform().offset)
    }

    @Test fun reducedMotionStopsAnExistingFlingAndKeepsDirectManipulationAvailable() {
        fixture()
        zoom()
        compose.mainClock.autoAdvance = false
        flick()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { reducedMotion.value = true }
        compose.mainClock.advanceTimeByFrame()
        val stopped = transform()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(stopped, transform())
        flick()
        val dragged = transform()
        assertTrue(dragged.offset.x > stopped.offset.x)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(dragged, transform())
    }

    @Test fun zoomChangeAndViewportResizeStopTheOldFlingWithinTheNewBounds() {
        fixture()
        zoom()
        compose.mainClock.autoAdvance = false
        flick()
        compose.mainClock.advanceTimeBy(64)
        action("Zoom out")
        val zoomed = transform()
        assertEquals(3f, zoomed.scale, 0f)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(zoomed, transform())
        flick()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { wideViewport.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val resized = transform()
        val bounds = artworkPanBounds(state.viewport, androidx.compose.ui.geometry.Size(800f, 400f), resized.scale)
        assertEquals(bounds.clamp(resized.offset), resized.offset)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(resized, transform())
    }

    @Test fun resetInterruptsKineticTravelAndAHeldPointerCannotUndoIt() {
        fixture()
        zoom()
        compose.mainClock.autoAdvance = false
        flick()
        compose.mainClock.advanceTimeBy(64)
        action("Reset zoom")
        compose.mainClock.advanceTimeBy(500)
        assertEquals(ArtworkTransform(), transform())
        zoom()
        viewport().performTouchInput { down(center); moveBy(Offset(width * .12f, 0f), delayMillis = 80) }
        action("Reset zoom")
        viewport().performTouchInput { moveBy(Offset(width * .08f, 0f), delayMillis = 20); up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(ArtworkTransform(), transform())
    }
}
