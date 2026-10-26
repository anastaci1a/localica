package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
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
        compose.setContent { LocalicaTheme {
            val scroll = rememberScrollState()
            val source = rememberFrostedBackdrop(Unit, dark = true, glassBlur = 0f, retainRaster = false, whiteTint = 0f)
            val page = rememberBrowseBackdropScene(source, null, Unit, scroll.isScrollInProgress)
            val viewport = rememberBrowseBackdropLayer(page, masked = true)
            SideEffect {
                scene = page
                moving = scroll.isScrollInProgress
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
            if (Build.VERSION.SDK_INT < 33) assertPaired(page)
            // The real drag remains acquired; no finger-up or settled refresh has run.
            assertTrue("The observation is during scrolling, not a settled refresh", moving)
            assertTrue("The moving native lens must include the newly revealed magenta glyphs",
                chroma(compose.onNodeWithTag("content-lens").captureToImage(), true) > .01f)
        } finally {
            if (contact) compose.onNodeWithTag("content-scroll").performTouchInput { up() }
            compose.mainClock.autoAdvance = true
        }
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
