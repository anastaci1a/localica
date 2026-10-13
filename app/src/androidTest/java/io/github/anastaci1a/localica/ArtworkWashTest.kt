package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ArtworkWashTest {
    @get:Rule val compose = createComposeRule()

    @Test fun blurBuildsAndResolvesWithoutMovingTheCoverOrReanimatingIdenticalArt() {
        fun striped(color: Int, key: String): PlayerVisual {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            for (y in 0 until 64) for (x in 0 until 64) bitmap.setPixel(x, y, if (x / 4 % 2 == 0) color else android.graphics.Color.BLACK)
            val image = bitmap.asImageBitmap()
            return PlayerVisual(image, softArtwork = kotlinx.coroutines.runBlocking { blurGlassSnapshot(image, 4f) }, contentKey = key)
        }
        val red = striped(android.graphics.Color.RED, "red")
        val blue = striped(android.graphics.Color.BLUE, "blue")
        var current by mutableStateOf(red)
        compose.setContent { LocalicaTheme {
            PlayerArtwork(current, Modifier.size(160.dp).testTag("blur-cover"), Corners.artwork, ContentScale.Crop, 800)
        } }
        val node = compose.onNodeWithTag("blur-cover")
        val bounds = node.fetchSemanticsNode().boundsInRoot
        fun contrast(channel: (Color) -> Float): Float {
            val pixels = node.captureToImage().toPixelMap()
            val values = (pixels.width / 4 until pixels.width * 3 / 4).map { channel(pixels[it, pixels.height / 2]) }
            return values.max() - values.min()
        }
        val sharp = contrast { it.red }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { current = blue }
        compose.mainClock.advanceTimeBy(256)
        assertTrue("Outgoing detail softens before the replacement resolves", contrast { it.red } < sharp * .85f)
        assertEquals(bounds, node.fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(640)
        assertTrue("Incoming detail resolves fully", contrast { it.blue } > sharp * .95f)
        compose.runOnIdle { current = striped(android.graphics.Color.BLUE, "blue") }
        compose.mainClock.advanceTimeBy(256)
        assertTrue("Identical cover bytes do not trigger another blur", contrast { it.blue } > sharp * .95f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun everyStyleStaysOpaqueAcrossRapidInterruptedChanges() {
        fun visual(color: Int) = PlayerVisual(Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap())
        val red = visual(android.graphics.Color.RED)
        val blue = visual(android.graphics.Color.BLUE)
        var current by mutableStateOf(red)
        var style by mutableStateOf(ArtworkTransition.BlurThrough)
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalArtworkTransition provides style) {
                Box(Modifier.size(160.dp).background(Color.White).testTag("frame")) {
                    PlayerArtwork(current, Modifier.fillMaxSize(), Corners.artwork, ContentScale.Crop, 550)
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        for (variant in ArtworkTransition.entries) {
            compose.runOnIdle { style = variant; current = blue }
            for (step in 0..5) {
                compose.mainClock.advanceTimeBy(64)
                if (step == 2) compose.runOnIdle { current = red }
                val pixels = compose.onNodeWithTag("frame").captureToImage().toPixelMap()
                for (x in listOf(.2f,.5f,.8f)) for (y in listOf(.2f,.5f,.8f)) {
                    val color = pixels[(pixels.width*x).toInt(), (pixels.height*y).toInt()]
                    assertTrue("$variant must not expose white during interruption: $color", color.green < .05f)
                    assertTrue("$variant must not leave a dark gutter: $color", color.red + color.blue > .9f)
                }
            }
            compose.mainClock.advanceTimeBy(700)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun transitionPreferenceSurvivesRecreation() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val old = preferences.read()
        try {
            context.getSharedPreferences("interface", 0).edit().putString("artwork-transition", "SoftWash").remove("artwork-swipe").commit()
            assertEquals(ArtworkTransition.BlurThrough, preferences.read().artworkTransition)
            assertTrue(preferences.read().artworkSwipe)
            for (style in ArtworkTransition.entries) {
                preferences.write(old.copy(artworkTransition = style, artworkSwipe = false))
                assertEquals(style, InterfacePreferences(context).read().artworkTransition)
                assertFalse(InterfacePreferences(context).read().artworkSwipe)
            }
        } finally { preferences.write(old) }
    }
}
