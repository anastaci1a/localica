package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ArtworkSwipeTest {
    @get:Rule val compose = createComposeRule()
    private fun visual(color: Color) = PlayerVisual(
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color.toArgb()) }.asImageBitmap(),
        PlayerPalette(color, color, color))
    private val red = visual(Color.Red)
    private val blue = visual(Color.Blue)
    private val green = visual(Color.Green)
    private val state = PlaybackState(connected = true, title = "Swipe", durationMs = 120000)

    @Test fun disablingArtworkSwipesLeavesBothButtonTransitionChoicesAvailable() {
        var index by mutableIntStateOf(0)
        var transition by mutableStateOf(io.github.anastaci1a.localica.navigation.ArtworkTransition.BlurThrough)
        var swipeCalls = 0
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(state, true, {}, InterfaceSettings(artworkSwipe = false, artworkTransition = transition),
                remember { Animatable(1f) }, {}, {}, { index = 0 }, { index = 1 }, {}, {},
                visual = if (index == 0) red else blue, artworkVisit = "$index",
                previousArtwork = if (index == 1) ArtworkNeighbor("0", red) else null,
                nextArtwork = if (index == 0) ArtworkNeighbor("1", blue) else null,
                onArtworkVisit = { _, complete -> swipeCalls++; complete(false) })
        } } }
        compose.onNodeWithTag("artwork-gesture").performTouchInput {
            swipe(Offset(width * .8f, centerY), Offset(width * .2f, centerY), 300)
        }
        compose.runOnIdle { assertEquals(0, index); assertEquals(0, swipeCalls) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Next").performClick()
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("player-neighbor-artwork", true).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(1, index); transition = io.github.anastaci1a.localica.navigation.ArtworkTransition.Carousel }
        compose.onNodeWithContentDescription("Previous").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Next").performClick()
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("player-neighbor-artwork", true).assertExists()
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(1, index); assertEquals(0, swipeCalls) }
    }

    @Test fun rapidDiagonalSwipesRegrabTheSettlingCarouselWithoutOpeningAnotherSurface() {
        var index by mutableIntStateOf(0)
        val pictures = listOf(red, blue, green)
        val calls = mutableListOf<String>()
        var queueOpens = 0
        var closes = 0
        var lyricOpens = 0
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(state, true, { if (!it) closes++ }, InterfaceSettings(), remember { Animatable(1f) },
                {}, {}, {}, {}, { queueOpens++ }, {}, onLyrics = { lyricOpens++ },
                visual = pictures[index], artworkVisit = "$index",
                previousArtwork = if (index > 0) ArtworkNeighbor("${index - 1}", pictures[index - 1]) else null,
                nextArtwork = if (index < 2) ArtworkNeighbor("${index + 1}", pictures[index + 1]) else null,
                onArtworkVisit = { token, complete -> calls += token; index = token.toInt(); complete(true) })
        } } }
        compose.mainClock.autoAdvance = false
        val art = compose.onNodeWithTag("artwork-gesture")
        repeat(2) {
            art.performTouchInput {
                swipe(Offset(width * .85f, height * .35f), Offset(width * .1f, height * .65f), 96)
            }
            // The next touch starts well before a carousel transition can finish.
            compose.mainClock.advanceTimeBy(48)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf("1", "2"), calls)
        assertEquals(0, queueOpens); assertEquals(0, closes); assertEquals(0, lyricOpens)
        compose.onNodeWithTag("player-neighbor-artwork", true).assertDoesNotExist()
    }

    @Test fun preparedSmallerWashKeepsTheOriginalTextureCoordinates() {
        val original = Bitmap.createBitmap(128, 320, Bitmap.Config.ARGB_8888)
        val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
        for (y in 0 until 320) for (x in 0 until 128) original.setPixel(x, y, colors[y / 80].toArgb())
        val small = Bitmap.createScaledBitmap(original, 32, 80, false)
        val palette = PlayerPalette(Color.Black, Color.Black, Color.Black, wash = original, softWash = small)
        var softness by mutableFloatStateOf(0f)
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            PlayerBackdrop(palette, Modifier.size(200.dp, 400.dp).testTag("wash"), fullArtBlend = 1f,
                artTop = with(density) { 50.dp.toPx() }, artHeight = with(density) { 200.dp.toPx() },
                artWidth = with(density) { 200.dp.toPx() }, softness = softness)
        } }
        val before = compose.onNodeWithTag("wash").captureToImage().toPixelMap()
        compose.runOnIdle { softness = 1f }
        val after = compose.onNodeWithTag("wash").captureToImage().toPixelMap()
        for (fraction in listOf(.1f, .35f, .65f, .9f)) {
            val y = (before.height * fraction).toInt()
            val first = before[before.width / 2, y]
            val second = after[after.width / 2, y]
            assertEquals(first.red, second.red, .02f)
            assertEquals(first.green, second.green, .02f)
            assertEquals(first.blue, second.blue, .02f)
        }
    }

    @Test fun coversFollowTheFingerAndCommitOnceWithoutASecondStationaryWash() {
        var visit by mutableStateOf("A")
        var current by mutableStateOf(red)
        val calls = mutableListOf<String>()
        var queueOpens = 0
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(state, true, {}, InterfaceSettings(), remember { Animatable(1f) }, {}, {}, {}, {}, { queueOpens++ }, {},
                visual = current, artworkVisit = visit, nextArtwork = ArtworkNeighbor("B", blue),
                onArtworkVisit = { token, complete -> calls += token; visit = token; current = blue; complete(true) })
        } } }
        val hit = compose.onNodeWithTag("artwork-gesture")
        val before = compose.onNodeWithTag("player-artwork", true).fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        hit.performTouchInput {
            down(Offset(width * .8f, centerY))
            repeat(10) { moveBy(Offset(-width * .05f, 0f), delayMillis = 40) }
        }
        compose.mainClock.advanceTimeBy(64)
        val surface = compose.onNodeWithTag("player-surface")
        val bounds = surface.fetchSemanticsNode().boundsInRoot
        val held = surface.captureToImage().toPixelMap()
        val y = (before.top + before.height * .45f - bounds.top).toInt()
        val left = held[(before.left + before.width * .1f - bounds.left).toInt(), y]
        val right = held[(before.left + before.width * .9f - bounds.left).toInt(), y]
        assertTrue("Held outgoing cover must remain visible: $left", left.red > .9f && left.blue < .1f)
        assertTrue("Held incoming cover must follow the finger: $right", right.blue > .9f && right.red < .1f)
        assertTrue(calls.isEmpty())
        assertEquals(0, queueOpens)
        hit.performTouchInput { advanceEventTime(300); up() }
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle { assertEquals(listOf("B"), calls) }
        repeat(8) {
            compose.mainClock.advanceTimeBy(32)
            val image = compose.onNodeWithTag("player-artwork", true).captureToImage().toPixelMap()
            val center = image[image.width / 2, image.height / 2]
            assertTrue("Committed cover must stay blue, with no second wash: $center", center.blue > .95f && center.red < .05f)
        }
        compose.onNodeWithTag("player-neighbor-artwork", true).assertDoesNotExist()
    }

    @Test fun shortPeeksCancelAndRightSwipesSelectThePreviousVisit() {
        var visit by mutableStateOf("A")
        var current by mutableStateOf(red)
        val calls = mutableListOf<String>()
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(state, true, {}, InterfaceSettings(), remember { Animatable(1f) }, {}, {}, {}, {}, {}, {},
                visual = current, artworkVisit = visit, previousArtwork = ArtworkNeighbor("previous", green),
                nextArtwork = ArtworkNeighbor("next", blue), onArtworkVisit = { token, complete ->
                    calls += token; visit = token; current = green; complete(true)
                })
        } } }
        val hit = compose.onNodeWithTag("artwork-gesture")
        hit.performTouchInput { down(center); moveBy(Offset(-width * .12f, 0f), delayMillis = 500); advanceEventTime(300); up() }
        compose.waitForIdle()
        assertTrue(calls.isEmpty())
        hit.performTouchInput { down(Offset(width * .8f, centerY)); moveBy(Offset(-width * .5f, 0f), delayMillis = 400); cancel() }
        compose.waitForIdle()
        assertTrue("An interrupted touch must never commit a song", calls.isEmpty())
        compose.onNodeWithTag("player-neighbor-artwork", true).assertDoesNotExist()
        hit.performTouchInput { swipe(Offset(width * .2f, centerY), Offset(width * .8f, centerY), 500) }
        compose.waitForIdle()
        assertEquals(listOf("previous"), calls)
        assertEquals("previous", visit)
    }

    @Test fun rejectedPreviewsReturnAndDuplicateCoversDoNotBlockFollowingSwipes() {
        var visit by mutableStateOf("A")
        var accept by mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(state, true, {}, InterfaceSettings(reducedMotion = true), remember { Animatable(1f) }, {}, {}, {}, {}, {}, {},
                visual = red, artworkVisit = visit, nextArtwork = ArtworkNeighbor(if (visit == "A") "B" else "C", red),
                onArtworkVisit = { token, complete -> calls += token; if (accept) visit = token; complete(accept) })
        } } }
        fun forward() {
            compose.onNodeWithTag("artwork-gesture").performTouchInput {
                swipe(Offset(width * .8f, centerY), Offset(width * .2f, centerY), 500)
            }
            compose.waitForIdle()
        }
        forward()
        assertEquals("A", visit)
        compose.onNodeWithTag("player-neighbor-artwork", true).assertDoesNotExist()
        compose.runOnIdle { accept = true }
        forward(); forward()
        assertEquals(listOf("B", "B", "C"), calls)
        assertEquals("C", visit)
        compose.onNodeWithTag("player-neighbor-artwork", true).assertDoesNotExist()
    }

    @Test fun diagonalCoverSwipesKeepOwnershipAndEqualArtworkDoesNotAnimateOnSkip() {
        var visit by mutableStateOf("A")
        var current by mutableStateOf(red.copy(contentKey = "same-pixels"))
        var queueOpens = 0
        val calls = mutableListOf<String>()
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(state, true, {}, InterfaceSettings(), remember { Animatable(1f) }, {}, {}, {}, {}, { queueOpens++ }, {},
                visual = current, artworkVisit = visit, nextArtwork = ArtworkNeighbor("B", blue),
                onArtworkVisit = { token, done -> calls += token; done(false) })
        } } }
        compose.onNodeWithTag("artwork-gesture").performTouchInput {
            swipe(Offset(width * .8f, height * .2f), Offset(width * .2f, height * .7f), 500)
        }
        compose.waitForIdle()
        assertEquals(listOf("B"), calls)
        assertEquals(0, queueOpens)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visit = "same-album"; current = visual(Color.Red).copy(contentKey = "same-pixels") }
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("player-neighbor-artwork", true).assertDoesNotExist()
        val cover = compose.onNodeWithTag("player-artwork", true).captureToImage().toPixelMap()
        assertTrue(cover[cover.width / 2, cover.height / 2].red > .95f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun externalPlaybackChangesCancelAHeldGesture() {
        var visit by mutableStateOf("A")
        var current by mutableStateOf(red)
        var calls = 0
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            PlayerSurface(state, true, {}, InterfaceSettings(), remember { Animatable(1f) }, {}, {}, {}, {}, {}, {},
                visual = current, artworkVisit = visit, nextArtwork = ArtworkNeighbor("B", blue),
                onArtworkVisit = { _, complete -> calls++; complete(false) })
        } } }
        val hit = compose.onNodeWithTag("artwork-gesture")
        compose.mainClock.autoAdvance = false
        hit.performTouchInput { down(Offset(width * .8f, centerY)); moveBy(Offset(-width * .4f, 0f), delayMillis = 300) }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { visit = "external"; current = green }
        compose.mainClock.advanceTimeBy(32)
        hit.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, calls)
        val image = compose.onNodeWithTag("player-artwork", true).captureToImage().toPixelMap()
        assertTrue(image[image.width / 2, image.height / 2].green > .95f)
    }
}
