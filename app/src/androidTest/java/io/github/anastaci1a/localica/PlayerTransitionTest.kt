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
        val image = compose.onNodeWithTag("player-artwork").assertIsDisplayed().captureToImage().toPixelMap()
        val color = image[image.width / 2, image.height / 2]
        assertTrue("Artwork must already be visible during the held gesture: $color", color.red > color.blue + .2f)
        compose.onNodeWithTag("player-surface").performTouchInput { advanceEventTime(500); up() }
        compose.mainClock.autoAdvance = true
    }

    @Test fun opaquePlayerAndTransportPersistWhileOtherControlsWaitUntilLateExpansion() {
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
        assertEquals("Browsing must not bleed through the expanding player", before.red, after.red, .02f)
        assertEquals("Browsing must not bleed through the expanding player", before.blue, after.blue, .02f)
        compose.runOnIdle { scope.launch { progress.snapTo(.96f) } }
        compose.onNodeWithContentDescription("Seek").assertIsDisplayed()
        compose.onNodeWithText("Custom action").assertIsDisplayed()
    }
}
