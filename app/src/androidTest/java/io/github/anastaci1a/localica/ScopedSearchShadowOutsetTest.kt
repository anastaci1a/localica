package io.github.anastaci1a.localica

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class ScopedSearchShadowOutsetTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun restingScopedSearchRetainsItsFieldBelowTheOriginalSlotClip() {
        val tracks = (0..1).map { Track("search-shadow:$it", "Song $it", fileName = "$it.wav", relativePath = "$it.wav") }
        val artwork = BrowseArtworkScope()
        val cover = "test:search-shadow-outset"
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        artwork.keepVisual(cover, PlayerVisual(bitmap.asImageBitmap(), PlayerPalette(Color.White, Color.White, Color.White, wash = bitmap, collectionWash = bitmap)))
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Shadow", coverUri = cover), tracks)
        val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id), "Shadow")
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalBrowseArtwork provides artwork,
                LocalGlassAppearance provides GlassAppearance(shadowStrength = 1f, shadowSpread = 1f)) {
                Box(Modifier.size(320.dp, 640.dp).background(Color.White).testTag("scoped-shadow-scene")) {
                    BrowserScreen(LibraryUiState(loading = false), PlaylistState(listOf(playlist), false),
                        route, {}, {}, {}, { _, _, _ -> }, null, null, true, false, reducedMotion = true)
                }
            }
        } }
        compose.onNodeWithTag("list-search-pill").assertIsDisplayed()
        compose.waitForIdle()
        val scene = compose.onNodeWithTag("scoped-shadow-scene")
        val origin = scene.fetchSemanticsNode().boundsInRoot.topLeft
        val body = compose.onNodeWithTag("list-search-pill").fetchSemanticsNode().boundsInRoot.translate(-origin)
        val d = compose.density.density
        val image = scene.captureNativeFrame()
        val dir = nativeEvidenceDirectory("scoped-search-shadow-outset", File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "scoped-search-shadow-outset")).apply { mkdirs() }
        File(dir, "rest.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val pixels = image.toPixelMap()
        fun darkness(offset: Float): Float {
            val y = (body.bottom + offset*d).roundToInt()
            val x = (body.right-48*d).roundToInt()
            return (x-6..x+6).map { 1f-pixels[it,y].red }.average().toFloat()
        }
        val near = darkness(8f)
        val middle = darkness(14f)
        val far = darkness(26f)
        File(dir,"profile.txt").writeText("pill=$body density=$d near=$near middle=$middle far=$far\n")
        assertTrue("The resting home field must survive the old slot clip: near=$near middle=$middle far=$far", near > 1f/255f)
        assertTrue("The home field fades naturally: near=$near far=$far", near > far)
    }
}
