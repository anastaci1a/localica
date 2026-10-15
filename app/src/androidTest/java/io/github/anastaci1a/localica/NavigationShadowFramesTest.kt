package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Presented fake-media frames for inspecting real chrome/miniplayer shadow lifetimes.
 * This captures evidence; it does not infer shadow opacity from animation math. */
class NavigationShadowFramesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun collectionTransitionKeepsRealNavigationAndPlayerPixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val original = preferences.read()
        val track = Track("shadow-probe:track", "Shadow probe song", artist = "Probe artist",
            album = "Shadow probe album", fileName = "probe.mp3", relativePath = "probe.mp3")
        lateinit var view: View
        var stage by mutableIntStateOf(1)
        var markerBounds = Rect.Zero
        var rootBounds = Rect.Zero
        try {
            preferences.write(InterfaceSettings(tabs = listOf(MainTab.Library), reducedMotion = false,
                glass = GlassAppearance(shadowStrength = 1f, shadowSpread = .6f)))
            compose.setContent {
                view = LocalView.current
                Box(Modifier.fillMaxSize().testTag("nav-shadow-frame").onGloballyPositioned { rootBounds = it.boundsInRoot() }) {
                    LocalicaTheme {
                        LocalicaScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("shadow-probe:folder", "Probe music")), tracks = listOf(track)), loading = false),
                            PlaybackState(connected = true, trackId = track.id, trackUri = track.uri, title = track.title),
                            {}, {}, {}, {}, {}, {})
                    }
                    PresentationMarker(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp)
                        .onGloballyPositioned { markerBounds = it.boundsInRoot() }) { stage }
                }
            }
            compose.onNodeWithText("Albums", useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Shadow probe album").fetchSemanticsNodes().isNotEmpty() }
            val initialUtility = compose.onNodeWithTag("utility-pill").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("player-surface").assertIsDisplayed()
            compose.mainClock.autoAdvance = false
            fun record(label: String) {
                val marker = IntOffset((markerBounds.left - rootBounds.left).toInt() + 2,
                    (markerBounds.top - rootBounds.top).toInt() + 2)
                val pixels = capturePresentedPopupPixels(compose, compose.onNodeWithTag("nav-shadow-frame"), stage, marker) { view }
                // Only the fake-media bottom third is needed; retain physical pixels.
                val top = pixels.height * 2 / 3
                val colors = IntArray(pixels.width * (pixels.height - top)) { i ->
                    pixels[i % pixels.width, top + i / pixels.width].toArgb()
                }
                val bitmap = Bitmap.createBitmap(colors, pixels.width, pixels.height - top, Bitmap.Config.ARGB_8888)
                val bytes = try { java.io.ByteArrayOutputStream().also {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray() } finally { bitmap.recycle() }
                Log.i("NavigationShadowFrames", "frame=$label stage=$stage cropTop=$top root=$rootBounds initialUtility=$initialUtility bytes=${bytes.size}")
                assertTrue("Bounded fake-scene evidence must fit log capture", bytes.size < 512 * 1024)
                val chunks = Base64.encodeToString(bytes, Base64.NO_WRAP).chunked(3000)
                chunks.forEachIndexed { i, chunk -> Log.i("NavigationShadowFrames", "PNG $label ${i + 1}/${chunks.size} $chunk") }
            }
            record("rest")
            compose.onNodeWithText("Shadow probe album").performClick()
            for (frame in 1..4) {
                compose.runOnIdle { stage = frame + 1 }
                compose.mainClock.advanceTimeBy(64)
                record("opening-${frame * 64}ms")
            }
            compose.mainClock.autoAdvance = true
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("player-surface").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
            preferences.write(original)
        }
    }
}
