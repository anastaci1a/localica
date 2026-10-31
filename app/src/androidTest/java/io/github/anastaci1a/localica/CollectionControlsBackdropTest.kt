package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Actual Text pixels and the ordinary glass consumer, without another readback path. */
class CollectionControlsBackdropTest {
    @get:Rule val compose = createComposeRule()

    @Test fun albumToolbarAndOverflowShareTheFinishedPageIncludingAnEmptyCollection() = collection(false)
    @Test fun artistToolbarAndOverflowShareTheFinishedPageIncludingAnEmptyCollection() = collection(true)

    private fun collection(artist: Boolean) {
        val tracks = (0..30).map { Track("backdrop:$it", "Content MMMMM $it", artist = "Artist",
            album = "Album", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val initial = LibrarySnapshot(tracks = tracks)
        val kind = if (artist) MediaKind.Artist else MediaKind.Album
        val tab = if (artist) MainTab.Artists else MainTab.Albums
        val group = LibraryViews.groups(initial, if (artist) SongListView.Artists else SongListView.Albums, LibraryQuery()).single()
        val route = BrowseRoute(tab, MediaRef(kind, group.id), group.title)
        var library by mutableStateOf(LibraryUiState(initial, loading = false))
        var scene: BrowseBackdropScene? = null
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalBrowseBackdropObserver provides { value, mounted -> if (mounted) scene = value }) {
                Box(Modifier.size(360.dp, 640.dp)) {
                    BrowserScreen(library, PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null,
                        true, false, reducedMotion = true, queryDispatcher = Dispatchers.Unconfined)
                }
            }
        } }
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sort").assertIsDisplayed()
        compose.onNodeWithContentDescription(if (artist) "Artist actions" else "Album actions").assertIsDisplayed()
        compose.waitUntil(10_000) {
            scene?.let { it.layers.size == 2 && it.source.recorded && it.layers.all { layer -> layer.source.recorded } } == true
        }
        val retained = requireNotNull(scene)
        assertNull("The aggregate never samples itself", retained.source.parent?.takeIf { it === retained.source })
        assertTrue("One viewport and one gradient, not controls", retained.layers.all { it.source !== retained.source })
        if (Build.VERSION.SDK_INT < 33) {
            compose.waitUntil(10_000) { retained.source.snapshot != null }
            assertPaired(retained)
        }
        // No song rows is still a collection page with a real header and top gradient.
        compose.runOnIdle { library = LibraryUiState(LibrarySnapshot(), loading = false) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Empty list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sort").assertIsDisplayed()
        compose.onNodeWithContentDescription(if (artist) "Artist actions" else "Album actions").assertIsDisplayed()
        compose.runOnIdle {
            assertSame("Empty pages retain their shared scene", retained, scene)
            assertEquals(2, retained.layers.size)
            assertTrue(retained.layers.all { it.source.recorded })
        }
    }

    @Test fun firstPublishedAndMovingSceneContainGlyphsRatherThanOnlyTheirBackground() {
        var scene: BrowseBackdropScene? = null
        var firstSnapshot: ImageBitmap? = null
        var moving = false
        var scrollState: ScrollState? = null
        var pixelDensity = 0f
        var rootView: android.view.View? = null
        compose.setContent { LocalicaTheme {
            val scroll = rememberScrollState()
            val density = LocalDensity.current.density
            val view = LocalView.current
            val source = rememberFrostedBackdrop(Unit, dark = true, glassBlur = 0f, retainRaster = false, whiteTint = 0f)
            // This test observes the live moving scene; the default setting now holds it while scrolling.
            val page = rememberBrowseBackdropScene(source, null, Unit, scroll.isScrollInProgress, liveWhileScrolling = true)
            val viewport = rememberBrowseBackdropLayer(page, masked = true)
            SideEffect {
                scene = page
                moving = scroll.isScrollInProgress
                scrollState = scroll
                pixelDensity = density
                rootView = view
            }
            LaunchedEffect(source) {
                snapshotFlow { source.snapshot }.filterNotNull().collect { if (firstSnapshot == null) firstSnapshot = it }
            }
            CompositionLocalProvider(LocalGlassAppearance provides GlassAppearance(depth = .6f, blur = 0f,
                whiteTint = 0f, contrast = 0f, shadowStrength = 0f)) {
                Box(Modifier.size(320.dp, 360.dp)) {
                    Column(Modifier.fillMaxSize().recordBrowseBackdropLayer(page, viewport)
                        .background(Color.Black).clipToBounds().listTopFade()
                        .browseBackdropMaskWitness(page, viewport).testTag("content-scroll").verticalScroll(scroll)) {
                        repeat(80) { index ->
                            Text("MMMMMMMMMMMMMMMM", color = if (index < 10) Color.Green else Color.Magenta,
                                fontSize = 30.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                                modifier = Modifier.height(36.dp))
                        }
                    }
                    CompositionLocalProvider(LocalFrostedBackdrop provides source) {
                        // No text/icon foreground can manufacture the glyph chroma below.
                        LiquidGlassSurface(Modifier.align(Alignment.Center).size(160.dp, 72.dp).testTag("content-lens")) {}
                    }
                    BrowseBackdropRecording(page, null)
                }
            }
        } }
        compose.waitUntil(10_000) {
            scene?.let { it.source.recorded && it.layers.single().source.recorded &&
                (Build.VERSION.SDK_INT >= 33 || firstSnapshot != null && it.source.lensSnapshot != null) } == true
        }
        val page = requireNotNull(scene)
        if (Build.VERSION.SDK_INT < 33) {
            assertTrue("The FIRST published bitmap must contain actual green Text glyphs", chroma(requireNotNull(firstSnapshot), false) > .01f)
            assertPaired(page)
        }
        assertTrue("Native glass must sample glyphs, not just the black background",
            chroma(compose.onNodeWithTag("content-lens").captureToImage(), false) > .01f)
        val before = page.source.snapshot
        compose.mainClock.autoAdvance = false
        var contact = false
        try {
            compose.onNodeWithTag("content-scroll").performTouchInput {
                down(Offset(width * .08f, height * .92f))
                moveTo(Offset(width * .08f, height * .12f), delayMillis = 240)
            }
            contact = true
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                moving && (Build.VERSION.SDK_INT >= 33 && page.layers.single().outer >= 0 ||
                    page.source.snapshot?.let { it !== before && page.source.lensSnapshot === it && chroma(it, true) > .01f } == true)
            }
            // CPU preparation can finish after this predicate's last clock tick.
            // Commit its image into the consumer composition, then await native draw.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (Build.VERSION.SDK_INT < 33) assertPaired(page)
            // The real drag remains acquired; no finger-up or settled refresh has run.
            assertTrue("The observation is during scrolling, not a settled refresh", moving)
            val lensBounds = compose.onNodeWithTag("content-lens").fetchSemanticsNode().boundsInRoot
            fun state(label: String) = compose.runOnUiThread {
                val source = page.source
                "$label scrollPx=${scrollState?.value} density=$pixelDensity lensRoot=$lensBounds " +
                    "sourceOrigin=${source.origin} sourceSize=${source.size} " +
                    "snapshot=${source.snapshot?.let(System::identityHashCode)} " +
                    "lensSnapshot=${source.lensSnapshot?.let(System::identityHashCode)} " +
                    "request=${source.readbackRequest} published=${source.snapshotReadbackRequest} draws=${source.snapshotDraws}"
            }
            android.util.Log.i("CollectionBackdropFrame", state("pre-final-capture"))
            val finalImage = compose.onNodeWithTag("content-lens").captureToImage()
            val sourceImage = compose.runOnUiThread { page.source.snapshot }
            val projected = compose.runOnUiThread {
                val screen = IntArray(2)
                requireNotNull(rootView).getLocationOnScreen(screen)
                val source = page.source
                val xScale = sourceImage?.width?.toFloat()?.div(source.size.width.coerceAtLeast(1)) ?: 1f
                val yScale = sourceImage?.height?.toFloat()?.div(source.size.height.coerceAtLeast(1)) ?: 1f
                val left = lensBounds.left.roundToInt() + screen[0] - source.origin.x
                val top = lensBounds.top.roundToInt() + screen[1] - source.origin.y
                Rect((left + finalImage.width / 4) * xScale, (top + finalImage.height / 4) * yScale,
                    (left + finalImage.width * 3 / 4) * xScale, (top + finalImage.height * 3 / 4) * yScale)
            }
            val finalChroma = chroma(finalImage, true)
            android.util.Log.i("CollectionBackdropFrame", state("post-final-capture") +
                " finalSize=${finalImage.width}x${finalImage.height} " +
                "finalRoi=[${finalImage.width / 4},${finalImage.height / 4},${finalImage.width * 3 / 4},${finalImage.height * 3 / 4}] " +
                "unrefractedSourceRoi=$projected " +
                "observedSource=${sourceImage?.let(System::identityHashCode)} sourceImageSize=${sourceImage?.width}x${sourceImage?.height} " +
                "sourceMagenta=${sourceImage?.let { chroma(it, true) }} " +
                "sourceGreen=${sourceImage?.let { chroma(it, false) }} " +
                "projectedSourceMagenta=${sourceImage?.let { regionChroma(it, projected) }} " +
                "finalMagenta=$finalChroma finalGreen=${chroma(finalImage, false)}")
            assertTrue("The moving native lens must include the newly revealed magenta glyphs",
                finalChroma > .01f)
        } finally {
            if (contact) compose.onNodeWithTag("content-scroll").performTouchInput { up() }
            compose.mainClock.autoAdvance = true
        }
    }

    /** Diagnostic projection only, not a claim about the lens's optical displacement. */
    private fun regionChroma(image: ImageBitmap, region: Rect): Float? {
        val pixels = image.toPixelMap()
        val left = floor(region.left).toInt().coerceIn(0, pixels.width)
        val right = ceil(region.right).toInt().coerceIn(0, pixels.width)
        val top = floor(region.top).toInt().coerceIn(0, pixels.height)
        val bottom = ceil(region.bottom).toInt().coerceIn(0, pixels.height)
        if (left >= right || top >= bottom) return null
        var sum = 0f
        for (y in top until bottom) for (x in left until right) {
            val color = pixels[x, y]
            sum += (minOf(color.red, color.blue) - color.green).coerceAtLeast(0f)
        }
        return sum / ((right - left) * (bottom - top))
    }

    private fun assertPaired(scene: BrowseBackdropScene) {
        val source = scene.source
        val draws = requireNotNull(source.snapshotDraws)
        assertEquals(source.snapshotReadbackRequest, draws.request)
        assertEquals(draws.request, draws.composite)
        assertEquals(scene.layers.toSet(), draws.producers.keys)
        for (layer in scene.layers) {
            assertEquals(draws.request, draws.producers.getValue(layer).outer)
            assertEquals(draws.request.takeIf { layer.masked }, draws.producers.getValue(layer).mask)
        }
    }

    private fun chroma(image: ImageBitmap, magenta: Boolean): Float {
        val pixels = image.toPixelMap()
        var sum = 0f
        var count = 0
        for (y in pixels.height / 4 until pixels.height * 3 / 4) {
            for (x in pixels.width / 4 until pixels.width * 3 / 4) {
                val color = pixels[x, y]
                sum += if (magenta) (minOf(color.red, color.blue) - color.green).coerceAtLeast(0f)
                    else (color.green - maxOf(color.red, color.blue)).coerceAtLeast(0f)
                count++
            }
        }
        return sum / count.coerceAtLeast(1)
    }
}
