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

    @Test fun everyStyleStaysOpaqueAcrossRapidInterruptedChanges() {
        fun visual(color: Int) = PlayerVisual(Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap())
        val red = visual(android.graphics.Color.RED)
        val blue = visual(android.graphics.Color.BLUE)
        var current by mutableStateOf(red)
        var style by mutableStateOf(ArtworkTransition.SoftWash)
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
            for (style in ArtworkTransition.entries) {
                preferences.write(old.copy(artworkTransition = style))
                assertEquals(style, InterfacePreferences(context).read().artworkTransition)
            }
        } finally { preferences.write(old) }
    }
}
