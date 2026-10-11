package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class FrostedSurfaceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun darkFrostAddsNoBlueTintToANeutralBackdrop() {
        lateinit var source: FrostedBackdrop
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            source = rememberFrostedBackdrop(Unit)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) { drawRect(Color.Gray) }
            FrostedSurface(Modifier.align(Alignment.Center).fillMaxWidth().height(200.dp).testTag("neutral-frost"), source = source) { }
        } } }
        if (Build.VERSION.SDK_INT < 31) compose.waitUntil(10000) { source.fallback != null }
        val image = compose.onNodeWithTag("neutral-frost").captureToImage().toPixelMap()
        val center = image[image.width / 2, image.height / 2]
        assertEquals("Neutral red/green", center.red, center.green, .015f)
        assertEquals("No added blue", center.red, center.blue, .015f)
        assertTrue("Darken the backdrop without hiding it", center.red in .15f.. .45f)
    }
    @Test fun listBackdropCannotPaintOverSiblingHeaderControls() {
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            val panel = rememberFrostedBackdrop(Unit, enabled = false)
            val list = rememberFrostedBackdrop(Unit, enabled = false)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(panel)) { drawRect(Color.Blue) }
            Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Red).testTag("header"))
            Canvas(Modifier.padding(top = 100.dp).fillMaxWidth().height(100.dp).recordFrostedBackdrop(list, panel)) {
                drawRect(Color.Green)
            }
        } } }
        val image = compose.onNodeWithTag("header").captureToImage().toPixelMap()
        val center = image[image.width / 2, image.height / 2]
        assertTrue("A list's backing layer must stay within its viewport: $center", center.red > .97f && center.blue < .01f)
    }
    @Test fun queueDrawerKeepsItsMaterialFromShelfThroughHeldExpansion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        val audio = File(context.cacheDir, "frosted-player.mp3")
        instrumentation.context.assets.open("tagged.mp3").use { input -> audio.outputStream().use(input::copyTo) }
        val track = io.github.anastaci1a.localica.library.Track(audio.toURI().toString(), "Evening light", artist = "Localica",
            album = "Color studies", fileName = audio.name, relativePath = audio.name, durationMs = 180000)
        val tracks = (0..24).map { track.copy(id = "frost:$it", title = if (it == 0) track.title else "Evening light · ${it + 1}") }
        val queue = io.github.anastaci1a.localica.queue.QueueEdits.create(tracks, 0, "Color studies ◀ Localica")
        lateinit var hostView: android.view.View
        try {
            compose.setContent { hostView = androidx.compose.ui.platform.LocalView.current
                LocalicaTheme { LocalicaScreen(
                LibraryUiState(io.github.anastaci1a.localica.library.LibrarySnapshot(tracks = tracks), loading = false),
                io.github.anastaci1a.localica.playback.PlaybackState(connected = true, trackId = tracks.first().id, trackUri = track.uri,
                    title = track.title, artist = track.artist, album = track.album, durationMs = 180000),
                {}, {}, {}, {}, {}, {}, queues = io.github.anastaci1a.localica.queue.QueueUiState(
                    io.github.anastaci1a.localica.queue.QueueLibrary(listOf(queue), queue.id), loading = false)) } }
            compose.onNodeWithTag("player-title", useUnmergedTree = true).performTouchInput { click() }
            compose.onNodeWithContentDescription("Open queue").assertIsDisplayed()
            fun record(name: String) {
                compose.runOnIdle {
                    var owner = hostView.context
                    while (owner is android.content.ContextWrapper && owner !is android.app.Activity) owner = owner.baseContext
                    val window = (owner as android.app.Activity).window
                    val bars = androidx.core.view.WindowCompat.getInsetsController(window, hostView)
                    assertFalse("Keep light status icons over the player and Queue", bars.isAppearanceLightStatusBars)
                    assertFalse("Keep light navigation icons over the player and Queue", bars.isAppearanceLightNavigationBars)
                }
                val image = compose.onRoot().captureToImage()
                File(context.getExternalFilesDir(null), name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (name == "queue-frosted-collapsed.png") {
                    // Attaching a new recording modifier at the end of expansion can
                    // leave descendant layers blank for a frame despite intact semantics.
                    val bounds = compose.onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
                    val pixels = image.toPixelMap()
                    var whitePixels = 0
                    for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(pixels.height)) {
                        for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(pixels.width)) {
                            val color = pixels[x, y]
                            if (color.red > .8f && color.green > .8f && color.blue > .8f) whitePixels++
                        }
                    }
                    assertTrue("Keep the Play icon painted while preparing the Queue backdrop", whitePixels > 30)
                }
                val glass = compose.onNodeWithTag("queue-surface").captureToImage().toPixelMap()
                val c = glass[glass.width / 3, 10.coerceAtMost(glass.height - 1)]
                assertTrue("Queue material must not flash white: $c", c.red + c.green + c.blue < 2f)
            }
            record("queue-frosted-collapsed.png")
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("queue-surface").performTouchInput {
                down(Offset(centerX, 32f)); repeat(20) { moveBy(Offset(0f, -14f), delayMillis = 30) }
            }
            compose.mainClock.advanceTimeBy(32)
            record("queue-frosted-held.png")
            compose.onNodeWithTag("queue-surface").performTouchInput { advanceEventTime(400); up() }
            compose.mainClock.autoAdvance = true
            if (compose.onAllNodesWithContentDescription("Open queue").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithContentDescription("Open queue").performClick()
            }
            compose.onNodeWithContentDescription("Queue actions").assertIsDisplayed()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Evening light · 2").fetchSemanticsNodes().isNotEmpty() }
            record("queue-frosted-expanded.png")
        } finally { audio.delete(); io.github.anastaci1a.localica.library.ArtworkCache.invalidate() }
    }

    @Test fun glassTransmitsTheActualSceneWithSoftEdgesAndCrispControls() {
        lateinit var source: FrostedBackdrop
        var swapped by mutableStateOf(false)
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            source = rememberFrostedBackdrop(swapped)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                drawRect(if (swapped) Color.Blue else Color.Red, size = Size(size.width / 2, size.height))
                drawRect(if (swapped) Color.Red else Color.Blue, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
            }
            FrostedSurface(Modifier.align(Alignment.Center).fillMaxWidth().height(240.dp).testTag("glass"), source = source) {
                Text("Sharp foreground", Modifier.align(Alignment.Center))
            }
        } } }
        if (Build.VERSION.SDK_INT < 31) compose.waitUntil(10000) { source.fallback != null }
        val glass = compose.onNodeWithTag("glass")
        val image = glass.captureToImage()
        val pixels = image.toPixelMap()
        val y = pixels.height / 4
        val left = pixels[pixels.width / 4, y]
        val right = pixels[pixels.width * 3 / 4, y]
        assertTrue("Transmit red from the scene: $left", left.red > left.blue + .15f)
        assertTrue("Transmit blue from the scene: $right", right.blue > right.red + .15f)
        val nearLeft = pixels[pixels.width / 2 - 8, y]
        assertTrue("Scatter across the hard scene boundary: $nearLeft vs $left", nearLeft.blue > left.blue + .04f)
        compose.onNodeWithText("Sharp foreground").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "frosted-material.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val previous = source.fallback
        compose.runOnIdle { swapped = true }
        if (Build.VERSION.SDK_INT < 31) compose.waitUntil(10000) { source.fallback !== previous }
        compose.waitUntil(5000) {
            val next = glass.captureToImage().toPixelMap()
            val color = next[next.width / 4, next.height / 4]
            color.blue > color.red + .15f
        }
    }
}
