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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlinx.coroutines.*

class FrostedSurfaceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun listBackdropCannotPaintOverSiblingHeaderControls() {
        lateinit var panel: FrostedBackdrop
        lateinit var list: FrostedBackdrop
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            panel = rememberFrostedBackdrop(Unit, enabled = false)
            list = rememberFrostedBackdrop(Unit, enabled = false)
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(panel)) { drawRect(Color.Blue) }
            Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Red).testTag("header"))
            Canvas(Modifier.padding(top = 100.dp).fillMaxWidth().height(100.dp).recordFrostedBackdrop(list, panel)) {
                drawRect(Color.Green)
            }
        } } }
        val image = compose.onNodeWithTag("header").captureToImage().toPixelMap()
        val center = image[image.width / 2, image.height / 2]
        assertTrue("A list's backing layer must stay within its viewport: $center", center.red > .97f && center.blue < .01f)
        compose.runOnIdle {
            val raster = Bitmap.createBitmap(intArrayOf(0xff000000.toInt(), 0xff000000.toInt(), 0xff000000.toInt(), 0xffffffff.toInt()), 2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
            val oldSize = panel.size
            val oldParent = panel.parent
            try {
                panel.size = IntSize(2, 2)
                assertTrue(panel.publish(raster, raster, 1, null))
                val whole = Rect(0f, 0f, 2f, 2f)
                val identity = requireNotNull(BackdropRegistration.create(1f, 0f, 0f, 1f, 0f, 0f))
                val quarter = Color(.25f, .25f, .25f)
                panel.materialOnly = true
                assertEquals("Finished-source contact sampling keeps the center texel", Color.White, panel.averageColor(panel.location, whole))
                assertEquals("Whole-footprint averaging never uses the center shortcut", quarter, panel.fullBoundsMean(identity, whole) { null })
                panel.materialOnly = false
                assertEquals("Raw-source contact sampling integrates the same raster", quarter, panel.averageColor(panel.location, whole))
                assertEquals("Provenance does not change full-footprint integration", quarter, panel.fullBoundsMean(identity, whole) { null })
                val diamond = requireNotNull(BackdropRegistration.create(1f, -1f, 1f, 1f, 1f, 0f))
                assertEquals("Affine coverage integrates all four equal-area quadrants", quarter, panel.fullBoundsMean(diamond, Rect(0f, 0f, 1f, 1f)) { null })
                val offset = requireNotNull(BackdropRegistration.create(1f, 0f, 0f, 1f, 1f, 1f))
                assertEquals("Offset footprint retains only the covered white cell", Color.White, panel.fullBoundsMean(offset, Rect(0f, 0f, 1f, 1f)) { null })
                assertEquals("Clipping excludes unavailable pixels from the mean", Color.White, panel.fullBoundsMean(offset, whole) { null })
                val style = MaterialAverageStyle(.06f, GlassTone(.25f, 2f, true, .75f, .1f))
                assertEquals("Raw grading remains unchanged", style, style.forBacking(panel))
                list.parent = panel
                panel.parent = list
                assertFalse("A cyclic raw ancestry does not become a finished material", list.withinFinishedMaterial())
                panel.materialOnly = true
                assertTrue("Finished ancestry survives a cycle", list.withinFinishedMaterial())
                assertEquals("Only repeated tint, trim and shift are suppressed", style.copy(whiteTint = 0f, tone = style.tone.copy(trim = 0f, shift = 0f)), style.forBacking(list))
                val alpha = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0x80ffffff.toInt()) }.asImageBitmap()
                assertTrue(panel.publish(alpha, alpha, 2, null))
                assertEquals("Mean alpha remains coverage rather than channel darkening", Color(1f, 1f, 1f, 128f / 255f), panel.fullBoundsMean(identity, whole) { null })
            } finally { panel.size = oldSize; panel.parent = oldParent; panel.materialOnly = false; list.parent = null }
        }
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
            // The Queue material samples the player scene. Start from the prepared cover and wash:
            // a cold cover that lands during the held drag (frozen clock) replaces the scene this
            // test measures with the player's own empty-to-cover transition (#645, owned by #634).
            val visual = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(15_000) { PlayerVisualCache.read(context, track.uri) }
            }
            assertNotNull("The embedded fixture cover must decode", visual.artwork)
            assertNotNull("The fixture must include its prepared wash", visual.palette.wash)
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
                File(nativeEvidenceDirectory("frosted-surface", context.getExternalFilesDir(null)), name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        lateinit var material: FrostedBackdrop
        lateinit var captureLayer: GraphicsLayer
        lateinit var graphics: GraphicsContext
        lateinit var density: androidx.compose.ui.unit.Density
        lateinit var direction: androidx.compose.ui.unit.LayoutDirection
        lateinit var scope: CoroutineScope
        var foreground by mutableStateOf(Color.Green)
        var panelVisible by mutableStateOf(true)
        var swapped by mutableStateOf(false)
        compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize()) {
            source = rememberFrostedBackdrop(swapped)
            captureLayer = rememberGraphicsLayer()
            graphics = LocalGraphicsContext.current
            density = LocalDensity.current
            direction = LocalLayoutDirection.current
            scope = rememberCoroutineScope()
            Canvas(Modifier.fillMaxSize().recordFrostedBackdrop(source)) {
                drawRect(if (swapped) Color.Blue else Color.Red, size = Size(size.width / 2, size.height))
                drawRect(if (swapped) Color.Red else Color.Blue, topLeft = Offset(size.width / 2, 0f), size = Size(size.width / 2, size.height))
            }
            if (panelVisible) GlassPanel(Modifier.align(Alignment.Center).fillMaxWidth().height(240.dp).testTag("glass"), source = source) {
                material = requireNotNull(LocalFrostedBackdrop.current)
                Text("Sharp foreground", Modifier.align(Alignment.Center))
                Canvas(Modifier.align(Alignment.Center).offset(y = 40.dp).size(20.dp).testTag("panel-foreground")) { drawRect(foreground) }
            }
        } } }
        if (Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source.fallback != null }
        val glass = compose.onNodeWithTag("glass")
        val image = glass.captureToImage()
        val pixels = image.toPixelMap()
        val y = pixels.height / 4
        val left = pixels[pixels.width / 4, y]
        val right = pixels[pixels.width * 3 / 4, y]
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue("Transmit red from the scene: $left", left.red > left.blue + .15f)
            assertTrue("Transmit blue from the scene: $right", right.blue > right.red + .15f)
            val nearLeft = pixels[pixels.width / 2 - 8, y]
            assertTrue("Scatter across the hard scene boundary: $nearLeft vs $left", nearLeft.blue > left.blue + .04f)
        } else {
            for (color in listOf(left, right)) {
                assertTrue("Solid average retains both scene colors: $color", color.red > color.green + .2f && color.blue > color.green + .2f)
                assertEquals("Equal-area red/blue have equal contributions", color.red, color.blue, .03f)
            }
            assertEquals("No spatial red/blue boundary remains", left.red, right.red, .04f)
            assertEquals("No spatial red/blue boundary remains", left.blue, right.blue, .04f)
        }
        compose.onNodeWithText("Sharp foreground").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(nativeEvidenceDirectory("frosted-surface", context.getExternalFilesDir(null)), "frosted-material.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val retainedMaterial = material
        assertNotSame("The panel owns a separate material recording", source, material)
        assertSame("The descendant sampling parent is the real scene", source, material.parent)
        assertTrue("Only finished material is published", material.materialOnly)
        assertEquals(28f * density.density, material.materialCorner, 0f)
        val registration = requireNotNull(material.location.registrationTo(source.location))
        val materialSize = material.size
        fun materialPixels(): ImageBitmap {
            lateinit var copied: Deferred<ImageBitmap?>
            compose.runOnUiThread {
                copied = scope.async(start = CoroutineStart.UNDISPATCHED) {
                    material.captureCurrent(graphics, density, direction, { material.recorded && !material.isRetired }, captureLayer,
                        record = { captureLayer.record(density, direction, material.size) { drawLayer(material.layer) } })
                }
            }
            compose.waitUntil(10000) { copied.isCompleted }
            return requireNotNull(runBlocking { copied.await() })
        }
        val beforeForeground = materialPixels()
        compose.runOnIdle { foreground = Color.Yellow }
        val witness = compose.onNodeWithTag("panel-foreground").captureToImage().toPixelMap()
        assertEquals("The foreground witness really changed", Color.Yellow, witness[witness.width / 2, witness.height / 2])
        val afterForeground = materialPixels()
        assertTrue("Foreground pixels never enter the material-only recording", beforeForeground.asAndroidBitmap().sameAs(afterForeground.asAndroidBitmap()))
        assertSame("Foreground recomposition retains material identity", retainedMaterial, material)
        assertSame("Foreground recomposition preserves the source parent", source, material.parent)
        assertEquals("Foreground recomposition preserves the physical recording bounds", materialSize, material.size)
        assertEquals("Foreground recomposition preserves registration", registration, material.location.registrationTo(source.location))
        beforeForeground.asAndroidBitmap().recycle()
        afterForeground.asAndroidBitmap().recycle()
        val previous = source.fallback
        compose.runOnIdle { swapped = true }
        if (Build.VERSION.SDK_INT < 33) compose.waitUntil(10000) { source.fallback !== previous }
        compose.waitUntil(5000) {
            val next = glass.captureToImage().toPixelMap()
            val color = next[next.width / 4, next.height / 4]
            if (Build.VERSION.SDK_INT >= 33) color.blue > color.red + .15f
            else kotlin.math.abs(color.red - left.red) < .04f && kotlin.math.abs(color.blue - left.blue) < .04f
        }
        assertSame("Scene revision does not replace the panel producer", retainedMaterial, material)
        compose.runOnIdle { panelVisible = false }
        compose.waitForIdle()
        assertTrue("Disposing the panel releases its owned recording", retainedMaterial.isRetired)
        assertEquals("Disposal leaves no descendant sampling lease", 0, retainedMaterial.consumers)
    }
}
