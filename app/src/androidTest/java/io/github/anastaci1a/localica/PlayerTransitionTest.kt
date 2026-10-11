package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.navigation.InterfaceSettings
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.navigation.ArtworkLayout
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerTransitionTest {
    @get:Rule val compose = createComposeRule()
    private val state = PlaybackState(connected = true, title = "Evening", artist = "Artist", album = "Album", durationMs = 120000)

    @Test fun heldLyricsCollapseAlreadyRestoresArtworkBeforeRelease() {
        val cover = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }.asImageBitmap()
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            var expanded by remember { mutableStateOf(true) }
            PlayerSurface(state, expanded, { expanded = it }, InterfaceSettings(), remember { Animatable(1f) },
                {}, {}, {}, {}, {}, {}, lyricsOpen = true, visual = PlayerVisual(cover))
        } } }
        compose.onNodeWithTag("lyrics-panel").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("player-surface").performTouchInput {
            down(Offset(8f, centerY))
            repeat(30) { moveBy(Offset(0f, height * .014f), delayMillis = 30) }
        }
        compose.mainClock.advanceTimeBy(32)
        val image = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).assertIsDisplayed().captureToImage().toPixelMap()
        val color = image[image.width / 2, image.height / 2]
        assertTrue("Artwork must already be visible during the held gesture: $color", color.red > color.blue + .2f)
        compose.onNodeWithTag("player-surface").performTouchInput { advanceEventTime(500); up() }
        compose.mainClock.autoAdvance = true
    }

    @Test fun glassStaysTranslucentUntilLateExpansionAndTransportPersists() {
        val progress = Animatable(0f)
        var behind by mutableStateOf(Color.Red)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize().background(behind)) {
            scope = rememberCoroutineScope()
            PlayerSurface(state, false, {}, InterfaceSettings(), progress, {}, {}, {}, {}, {}, {}, visual = PlayerVisual()) { Text("Custom action") }
        } } }
        compose.runOnIdle { scope.launch { progress.snapTo(.55f) } }
        compose.onNodeWithContentDescription("Previous").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next").assertIsDisplayed()
        compose.onNodeWithContentDescription("Seek").assertDoesNotExist()
        compose.onNodeWithText("Custom action").assertDoesNotExist()
        fun panelColor(): Color = compose.onNodeWithTag("player-surface").captureToImage().let {
            it.toPixelMap()[it.width * 9 / 10, it.height / 2]
        }
        val before = panelColor()
        compose.runOnIdle { behind = Color.Blue }
        val after = panelColor()
        assertTrue("Expanding glass still transmits browsing", before.red > after.red + .2f)
        assertTrue("Expanding glass still transmits browsing", after.blue > before.blue + .2f)
        compose.runOnIdle { scope.launch { progress.snapTo(.96f) } }
        compose.onNodeWithContentDescription("Seek").assertIsDisplayed()
        compose.onNodeWithText("Custom action").assertIsDisplayed()
        val complete = panelColor()
        compose.runOnIdle { behind = Color.Red }
        val opaque = panelColor()
        assertEquals("Artwork backing becomes opaque near full size", complete.red, opaque.red, .02f)
        assertEquals("Artwork backing becomes opaque near full size", complete.blue, opaque.blue, .02f)
    }

    @Test fun glassFillsTheSharedRoundedOutlineDuringHeldExpansionAndReversal() {
        val progress = Animatable(0f)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(whiteTint = 1f, shadowStrength = 0f)) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.Magenta)) {
                    scope = rememberCoroutineScope()
                    PlayerSurface(state, false, {}, InterfaceSettings(), progress, {}, {}, {}, {}, {}, {}, visual = PlayerVisual())
                }
            }
        } }
        for (p in listOf(.15f, .5f, .15f)) {
            compose.runOnIdle { scope.launch { progress.snapTo(p) } }
            val pixels = compose.onNodeWithTag("player-surface").captureToImage().toPixelMap()
            // Inside the rounded rectangle but outside the old, taller capsule's corner.
            val edge = pixels[10, (pixels.height * .3f).toInt()]
            assertTrue("Glass must reach the shared outline at $p: $edge", edge.green > .7f)
            if (p == .5f) {
                val cover = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val title = compose.onNodeWithTag("player-title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("Metadata clears the growing artwork", title.top > cover.bottom)
            }
        }
    }

    @Test fun handleApproachesTheMovingTopAndLeavesBeforeItCanOverlapMiniPlayerControls() {
        val progress = Animatable(0f)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize()) {
            scope = rememberCoroutineScope()
            PlayerSurface(state, false, {}, InterfaceSettings(), progress, {}, {}, {}, {}, {}, {}, visual = PlayerVisual())
        } } }
        fun at(value: Float): Pair<Float, Float> {
            compose.runOnIdle { scope.launch { progress.snapTo(value) } }
            val panel = compose.onNodeWithTag("player-surface").fetchSemanticsNode().boundsInRoot
            val handle = compose.onNodeWithTag("player-handle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return panel.top to handle.top
        }
        val full = at(1f)
        val held = at(.85f)
        assertTrue("The handle approaches the panel edge", held.second - held.first < full.second - full.first)
        assertTrue("Handle travels down more slowly than the edge", held.second - full.second < held.first - full.first)
        compose.runOnIdle { scope.launch { progress.snapTo(.65f) } }
        compose.onNodeWithTag("player-handle", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Collapse player").assertDoesNotExist()
    }

    @Test fun expandingFullBackgroundSoftensCoverEdgesButFloatingArtworkStaysDefined() {
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(192 * 192) { if ((it % 192) / 3 % 2 == 0) android.graphics.Color.WHITE else android.graphics.Color.BLACK }, 0, 192, 0, 0, 192, 192)
        }.asImageBitmap()
        val soft = kotlinx.coroutines.runBlocking { blurGlassSnapshot(bitmap, 12f) }
        val visual = PlayerVisual(bitmap, softArtwork = soft)
        val progress = Animatable(0f)
        var layout by mutableStateOf(ArtworkLayout.FullBackground)
        lateinit var scope: CoroutineScope
        compose.setContent { LocalicaTheme { BoxWithConstraints(Modifier.fillMaxSize().background(Color.Gray)) {
            scope = rememberCoroutineScope()
            PlayerSurface(state, false, {}, InterfaceSettings(artworkLayout = layout), progress, {}, {}, {}, {}, {}, {}, visual = visual)
        } } }
        compose.runOnIdle { scope.launch { progress.snapTo(.65f) } }
        fun contrasts(): Pair<Float, Float> {
            val image = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).captureToImage().toPixelMap()
            fun contrast(from: Float, to: Float): Float {
                val samples = ((image.width * from).toInt()..(image.width * to).toInt()).map { image[it, image.height / 2].red }
                return samples.max() - samples.min()
            }
            return contrast(.03f, .1f) to contrast(.4f, .6f)
        }
        val softened = contrasts()
        assertTrue("Center retains detail", softened.second > .8f)
        assertTrue("Edge loses sharp detail while expanding: $softened", softened.first < softened.second * .65f)
        compose.runOnIdle { layout = ArtworkLayout.Floating }
        val floating = contrasts()
        assertTrue("Floating artwork keeps a defined edge: $floating", floating.first > floating.second * .8f)
    }
}
