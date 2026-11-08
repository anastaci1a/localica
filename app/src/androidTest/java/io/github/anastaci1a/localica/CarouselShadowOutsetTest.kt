package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.GlassAppearance
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/** The real artist carousel must let its white selection field fade beyond the row. */
class CarouselShadowOutsetTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun selectedCarouselGlowFadesBelowTheRowBoundary() {
        val albums = LibraryViews.groups(LibrarySnapshot(tracks = listOf("First", "Second").mapIndexed { i, title ->
            Track("shadow-outset:$i", title, album = title, artist = "Artist", fileName = "$i", relativePath = "$i")
        }), SongListView.Albums, LibraryQuery())
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalContentColor provides Color.White,
                LocalGlassAppearance provides GlassAppearance(shadowStrength = 1f, shadowSpread = 1f)) {
                Box(Modifier.size(360.dp, 300.dp).background(Color.Black).testTag("outset-scene")) {
                    ArtistAlbumChoices(albums, emptyList()) {}
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1000)
        val scene = compose.onNodeWithTag("outset-scene")
        val all = compose.onNodeWithText("All albums").fetchSemanticsNode().boundsInRoot
        val origin = scene.fetchSemanticsNode().boundsInRoot.topLeft
        val d = compose.density.density
        val image = scene.captureNativeFrame()
        val directory = nativeEvidenceDirectory("carousel-shadow-outset", File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "carousel-shadow-outset")).apply { mkdirs() }
        File(directory, "rest.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val pixels = image.toPixelMap()
        // selectionLens occupies the whole clickable column. The production row adds 8 dp below it.
        val rowBottom = all.bottom - origin.y + 8*d
        fun luminance(offset: Float): Float {
            val y = (rowBottom + offset*d).roundToInt()
            val center = (all.center.x-origin.x).roundToInt()
            return (center-10..center+10).map { x -> pixels[x,y].red }.average().toFloat()
        }
        val near = luminance(2f)
        val middle = luminance(8f)
        val far = luminance(20f)
        File(directory,"profile.txt").writeText("all=$all origin=$origin density=$d rowBottom=$rowBottom near=$near middle=$middle far=$far\n")
        assertTrue("The glow must survive 2 dp below the row boundary: near=$near middle=$middle far=$far", near > 1f/255f)
        assertTrue("The overflow field must fade naturally: near=$near far=$far", near > far)
    }
}
