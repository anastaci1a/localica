package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
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
    private fun fixture(portraitArt: Boolean = false, landscape: Boolean = false, squareArt: Boolean = false) {
        wideViewport.value = landscape
        val bitmap = Bitmap.createBitmap(if (squareArt) 800 else if (portraitArt) 400 else 800, if (squareArt || portraitArt) 800 else 400,
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

    private fun assertOverlayTargets() {
        val page = compose.onNodeWithTag("artwork-viewer").fetchSemanticsNode().boundsInRoot
        assertEquals("Controls overlay the full image viewport", page, viewport().fetchSemanticsNode().boundsInRoot)
        val close = compose.onNodeWithContentDescription("Close artwork").assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
        val reset = compose.onNodeWithContentDescription("Reset zoom").assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
        val target = with(compose.density) { 48.dp.toPx() }
        val padding = with(compose.density) { 16.dp.toPx() }
        for (bounds in listOf(close, reset)) {
            assertTrue("Overlay retains its accessible touch target", bounds.width >= target - 1f && bounds.height >= target - 1f)
            assertTrue("Overlay target is wholly inside the viewport", bounds.left >= page.left && bounds.top >= page.top &&
                bounds.right <= page.right && bounds.bottom <= page.bottom)
        }
        assertTrue("Close keeps its safe-edge padding", close.left >= page.left + padding - 1f && close.top >= page.top + padding - 1f)
        assertTrue("Reset keeps its safe-edge padding", reset.right <= page.right - padding + 1f && reset.bottom <= page.bottom - padding + 1f)
        assertTrue("Overlay controls cannot overlap", close.right <= reset.left || close.bottom <= reset.top)
    }

    @Test fun squareArtworkClampsEveryCornerWithoutBlankPixelsInEitherViewportOrientation() {
        fixture(squareArt = true)
        zoom(5)
        compose.mainClock.autoAdvance = false
        try {
            for (landscape in listOf(false, true)) {
                compose.runOnIdle { wideViewport.value = landscape }
                compose.mainClock.advanceTimeBy(32)
                compose.waitForIdle()
                assertOverlayTargets()
                for (direction in listOf(Offset(1f, 1f), Offset(-1f, -1f), Offset(1f, -1f), Offset(-1f, 1f))) {
                    viewport().performTouchInput {
                        down(center)
                        repeat(24) { moveBy(Offset(width * .3f * direction.x, height * .3f * direction.y), delayMillis = 16) }
                        cancel()
                    }
                    compose.mainClock.advanceTimeByFrame()
                    val edge = transform()
                    val bounds = artworkPanBounds(state.viewport, Size(800f, 800f), edge.scale)
                    assertEquals(6f, edge.scale, 0f)
                    assertEquals(direction.x * bounds.x, edge.offset.x, 1f)
                    assertEquals(direction.y * bounds.y, edge.offset.y, 1f)
                    val pixels = viewport().captureToImage().toPixelMap()
                    for (x in listOf(3, pixels.width / 2, pixels.width - 4))
                        for (y in listOf(3, pixels.height / 2, pixels.height - 4)) {
                            // The two intentional control overlays/shadows occupy opposite corners.
                            if ((x == 3 && y == 3) || (x == pixels.width - 4 && y == pixels.height - 4)) continue
                            assertEquals("Clamped square cannot expose a blank edge/corner", Color.Red, pixels[x, y])
                        }
                    compose.mainClock.advanceTimeBy(160)
                    assertEquals("Cancelled edge contact cannot start kinetic travel", edge, transform())
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun savedViewerRestoresVisiblePanWithoutVelocityAndRetainsItsCenterThroughLoadingResize() {
        // StateRestorationTester uses three runOnIdle phases. With a paused clock,
        // removal and re-emission otherwise coalesce without disposing the old owner.
        val restore = StateRestorationTester(object : ComposeContentTestRule by compose {
            override fun <T> runOnIdle(action: () -> T): T {
                val result = compose.runOnIdle(action)
                if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
                return result
            }
        })
        val loaded = mutableStateOf(true)
        val wide = mutableStateOf(false)
        val raw = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
            val canvas = android.graphics.Canvas(this)
            val paint = android.graphics.Paint()
            paint.color = android.graphics.Color.BLUE
            canvas.drawRect(400f, 0f, 800f, 400f, paint)
            paint.color = android.graphics.Color.GREEN
            canvas.drawRect(0f, 200f, 400f, 400f, paint)
            paint.color = android.graphics.Color.YELLOW
            canvas.drawRect(400f, 200f, 800f, 400f, paint)
        }.asImageBitmap()
        lateinit var viewer: ArtworkViewerState
        restore.setContent { LocalicaTheme {
            val remembered = rememberArtworkViewerState()
            androidx.compose.runtime.SideEffect { viewer = remembered }
            Box(Modifier.size(if (wide.value) 360.dp else 260.dp, if (wide.value) 260.dp else 360.dp).background(Color.Black)) {
                ArtworkViewerContent(if (loaded.value) raw else null, !loaded.value, {}, state = remembered)
            }
        } }
        compose.waitForIdle()
        fun current(): ArtworkTransform {
            lateinit var value: ArtworkTransform
            compose.runOnIdle { value = viewer.transform }
            return value
        }
        fun saved(): List<Float> {
            lateinit var value: List<Float>
            compose.runOnIdle { value = viewer.savedTransform() }
            return value
        }
        fun assertTransform(expected: ArtworkTransform) {
            val actual = current()
            assertEquals(expected.scale, actual.scale, 0f)
            assertEquals(expected.offset.x, actual.offset.x, .02f)
            assertEquals(expected.offset.y, actual.offset.y, .02f)
        }
        fun assertCenter(expected: List<Float>) {
            val actual = saved()
            assertEquals(expected.size, actual.size)
            for (index in expected.indices) assertEquals(expected[index], actual[index], .00001f)
        }
        fun imageLandmarks(): List<Color> {
            val pixels = viewport().captureToImage().toPixelMap()
            return listOf(.25f, .5f, .75f).flatMap { y ->
                listOf(.25f, .5f, .75f).map { x -> pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()] }
            }
        }
        zoom()
        viewport().performTouchInput { down(center); moveBy(Offset(width * .18f, height * .08f), delayMillis = 120); cancel() }
        compose.mainClock.autoAdvance = false
        try {
            flick()
            val released = current()
            compose.mainClock.advanceTimeBy(64)
            val visible = current()
            assertTrue("Restoration interrupts genuine kinetic travel", visible.offset.x > released.offset.x)
            assertTrue("The saved frame is zoomed and panned on both axes", visible.scale > 1f && visible.offset.x > 0f && visible.offset.y > 0f)
            val center = saved()
            val landmarks = imageLandmarks()
            assertTrue("Pattern distinguishes the image transform", landmarks.toSet().size > 1)
            val old = viewer
            restore.emulateSavedInstanceStateRestore()
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            compose.runOnIdle { assertNotSame("A real saved-state recreation made a new viewer owner", old, viewer) }
            assertTransform(visible)
            assertCenter(center)
            assertEquals("Restoration preserves the same visible image landmarks", landmarks, imageLandmarks())
            compose.mainClock.advanceTimeBy(500)
            assertTransform(visible)
            assertOverlayTargets()

            compose.runOnIdle { loaded.value = false }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            assertCenter(center)
            assertEquals("An unloaded image cannot retain a stale pixel-space translation", Offset.Zero, current().offset)
            compose.onNodeWithContentDescription("Reset zoom").assertIsNotEnabled()
            assertTrue(viewport().fetchSemanticsNode().config[SemanticsActions.CustomActions].isEmpty())
            compose.runOnIdle { wide.value = true }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            val loadingOwner = viewer
            restore.emulateSavedInstanceStateRestore()
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            compose.runOnIdle { assertNotSame(loadingOwner, viewer) }
            assertCenter(center)
            assertEquals(Offset.Zero, current().offset)

            compose.runOnIdle { loaded.value = true }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            assertCenter(center)
            compose.runOnIdle {
                val fit = minOf(viewer.viewport.width / 800f, viewer.viewport.height / 400f)
                assertEquals(center[0], viewer.transform.scale, 0f)
                assertEquals("Resize maps normalized horizontal pan into the actual new viewport", center[1] * 800f * fit * center[0], viewer.transform.offset.x, .02f)
                assertEquals("Reload maps normalized vertical pan into the actual new viewport", center[2] * 400f * fit * center[0], viewer.transform.offset.y, .02f)
            }
            assertOverlayTargets()
            val reloaded = current()
            compose.mainClock.advanceTimeBy(500)
            assertTransform(reloaded)
            compose.runOnIdle { wide.value = false }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            assertTransform(visible)
            assertEquals("Returning to the original viewport recovers the same visible artwork", landmarks, imageLandmarks())
        } finally { compose.mainClock.autoAdvance = true }
    }

}
