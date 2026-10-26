package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

/** Real Browser headers and their actual hero draw inputs, with ordinary two-pointer input.
 * The atlas is the production capture, not another screenshot/readback path. */
class CollectionHeaderPinchFrameTest {
    @get:Rule val compose = createComposeRule()
    private var held = false
    @After fun releaseOwnedPointers() {
        compose.mainClock.autoAdvance = false
        try { if (held) compose.onNodeWithTag("browser-list").performTouchInput { cancel() } }
        finally { held = false; compose.mainClock.autoAdvance = true }
    }
    @Test fun albumHeaderRetainsPixelsAndHeroCoordinateThroughReversalAndOffscreenHandoff() = inspect(false)
    @Test fun artistHeaderRetainsPixelsAndHeroCoordinateThroughReversalAndOffscreenHandoff() = inspect(true)

    private fun inspect(artist: Boolean) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val title = if (artist) "Header Artist" else "Header Album"
        val tracks = (0..99).map { Track("header-pinch:$it", "Song ${it.toString().padStart(3, '0')}",
            artist = "Header Artist", album = "Header Album", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("header-root", "Music")), tracks = tracks)
        val group = LibraryViews.groups(snapshot, if (artist) SongListView.Artists else SongListView.Albums, LibraryQuery()).single()
        val route = BrowseRoute(if (artist) MainTab.Artists else MainTab.Albums,
            MediaRef(if (artist) MediaKind.Artist else MediaKind.Album, group.id), title)
        val preferences = BrowsePreferences(context)
        val original = preferences.read(route)
        val overridden = preferences.overridden(route)
        val sourceStyle = BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5)
        preferences.write(route, sourceStyle, true)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF102030.toInt()) }
        val artwork = BrowseArtworkScope().apply {
            tracks.forEach { hold(it.id, listOf(it.uri), ArtworkCache.CachedArtwork(bitmap, 512)) }
            keepVisual(tracks.first().uri, PlayerVisual(bitmap.asImageBitmap(), kotlinx.coroutines.runBlocking { playerPalette(bitmap) }))
        }
        val frames = mutableListOf<CollectionHeaderFrame>()
        var density = 0f
        try {
            compose.setContent { LocalicaTheme {
                val currentDensity = LocalDensity.current.density
                SideEffect { density = currentDensity }
                CompositionLocalProvider(LocalBrowseArtwork provides artwork, LocalReducedMotion provides false,
                    LocalCollectionHeaderFrameObserver provides { frames += it }) {
                    Box(Modifier.fillMaxSize()) {
                        BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route, {}, {}, {},
                            { _, _, _ -> fail("A pinch must not start playback") }, null, null, true, false,
                            queryDispatcher = Dispatchers.Unconfined)
                    }
                }
            } }
            val list = compose.onNodeWithTag("browser-list")
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browse-full-span").fetchSemanticsNodes().isNotEmpty() }
            val header = compose.onNodeWithTag("browse-full-span")
            val titleNode = compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("browse-full-span")), useUnmergedTree = true)
            val initialHeader = header.fetchSemanticsNode().layoutInfo.coordinates
            val initialTitle = titleNode.fetchSemanticsNode().layoutInfo.coordinates
            val titleOrigin = initialTitle.positionInRoot() - initialHeader.positionInRoot()
            val titleRect = Rect(titleOrigin.x, titleOrigin.y,
                titleOrigin.x + initialTitle.size.width, titleOrigin.y + initialTitle.size.height)
            // Retain a genuinely mounted source while leaving enough song rows for
            // the real finger anchor to virtualize the destination header.
            val scroll = (initialHeader.size.height - 48 * density).coerceAtLeast(0f)
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scroll) }
            compose.waitForIdle()
            val sourceRect = headerRect()
            assertTrue("Source header is partly visible", sourceRect.top < 0f && sourceRect.bottom > 0f)
            compose.mainClock.autoAdvance = false

            fun pump(predicate: () -> Boolean) = compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnUiThread(predicate)
            }
            fun start(outward: Boolean) {
                val span = if (outward) 45f else 80f
                list.performTouchInput {
                    val y = height * .82f
                    down(0, Offset(centerX - span, y)); down(1, Offset(centerX + span, y))
                }
                held = true
                spread(if (outward) 80f else 45f)
            }
            val generationBefore = compose.runOnUiThread { frames.lastOrNull()?.generation ?: -1L }
            start(true)
            pump { frames.any { it.generation > generationBefore && it.drawn && it.destinationReady && it.progress in .3f.. .99f } }
            val first = compose.runOnUiThread { frames.last { it.drawn } }
            assertTrue("Actual corrected destination virtualizes the header", requireNotNull(first.destination).bottom <= 0f)
            val high = first.progress
            spread(52f)
            pump { frames.lastOrNull()?.let { it.generation == first.generation && it.drawn && it.progress < high - .2f } == true }
            list.performTouchInput { cancel() }; held = false
            pump { frames.any { it.generation == first.generation && !it.drawn && it.handoff && it.progress == 0f } }
            compose.waitForIdle()
            assertRect(sourceRect, headerRect())
            assertEquals(sourceStyle, preferences.read(route))

            start(true)
            pump { frames.any { it.generation > first.generation && it.drawn && it.destinationReady && it.progress >= .6f } }
            val forward = compose.runOnUiThread { frames.last { it.drawn } }
            list.performTouchInput { up(0); up(1) }; held = false
            pump { frames.any { it.generation == forward.generation && !it.drawn && it.handoff && it.progress == 1f } }
            assertEquals(4, preferences.read(route).itemsPerRow)
            assertTrue("The committed endpoint was measured offscreen", requireNotNull(forward.destination).bottom <= 0f)

            // A new inverse gesture starts with the header virtualized, rather than
            // keeping a mutable source layer as a pretend immutable multi-frame copy.
            start(false)
            pump { frames.any { it.generation > forward.generation && it.drawn && it.destinationReady && it.progress >= .6f } }
            val reverse = compose.runOnUiThread { frames.last { it.drawn } }
            assertTrue("The inverse source is really offscreen", reverse.source.bottom <= 0f)
            assertTrue("Its measured endpoint reenters the viewport", requireNotNull(reverse.destination).bottom > 0f)
            list.performTouchInput { up(0); up(1) }; held = false
            pump { frames.any { it.generation == reverse.generation && !it.drawn && it.handoff && it.progress == 1f } }
            compose.waitForIdle()
            assertRect(requireNotNull(reverse.destination), headerRect())
            assertEquals(5, preferences.read(route).itemsPerRow)

            val observed = compose.runOnUiThread { frames.toList() }
            for ((generation, transaction) in observed.groupBy { it.generation }) {
                val drawing = transaction.filter { it.drawn }
                assertTrue("Observe native header draw frames for $generation", drawing.isNotEmpty())
                val retained = requireNotNull(drawing.first().retainedPart)
                assertGlyphPixels(retained, titleRect)
                for (frame in drawing) {
                    val part = requireNotNull(frame.retainedPart)
                    assertSame("Preview/reversal uses one immutable completed image", retained.image, part.image)
                    assertEquals(retained.origin, part.origin); assertEquals(retained.size, part.size)
                    if (!frame.destinationReady) assertEquals("Progress cannot outrun actual target geometry", 0f, frame.progress, 0f)
                    val destination = frame.destination
                    val expected = if (destination == null) frame.source else between(frame.source, destination, frame.progress)
                    assertRect(expected, frame.presented)
                    assertEquals("Real hero draw and header use the same viewport coordinate", frame.presented.top,
                        requireNotNull(frame.heroTop), 1.1f)
                    assertEquals("Actual placed hero artwork follows that same coordinate with existing parallax",
                        frame.heroInset + frame.presented.top * frame.parallax, requireNotNull(frame.heroArtworkTop), 1.1f)
                    if (frame.destinationReady && frame.anchorEdge == 0) assertEquals("Actual finger anchor is corrected before progress",
                        frame.anchorExpected, requireNotNull(frame.anchorPoint), 1.1f)
                }
                assertTrue("Every observed transaction has an endpoint-verified handoff", transaction.any { !it.drawn && it.handoff })
            }
        } finally {
            compose.mainClock.autoAdvance = false
            if (held) { compose.onNodeWithTag("browser-list").performTouchInput { cancel() }; held = false }
            compose.mainClock.autoAdvance = true
            preferences.write(route, original, overridden)
        }
    }

    private fun spread(span: Float) {
        compose.onNodeWithTag("browser-list").performTouchInput {
            val y = height * .82f
            updatePointerTo(0, Offset(centerX - span, y)); updatePointerTo(1, Offset(centerX + span, y))
            move(delayMillis = 32)
        }
    }
    private fun headerRect(): Rect {
        val viewport = compose.onNodeWithTag("browser-list").fetchSemanticsNode().layoutInfo.coordinates
        val header = compose.onNodeWithTag("browse-full-span").fetchSemanticsNode().layoutInfo.coordinates
        val origin = viewport.localPositionOf(header, Offset.Zero)
        return Rect(origin.x, origin.y, origin.x + header.size.width, origin.y + header.size.height)
    }
    private fun assertGlyphPixels(part: FrozenBrowsePart, title: Rect) {
        val pixels = part.image.toPixelMap()
        val left = (part.origin.x + floor(title.left).toInt()).coerceIn(0, pixels.width)
        val right = (part.origin.x + ceil(title.right).toInt()).coerceIn(0, pixels.width)
        val top = (part.origin.y + floor(title.top).toInt()).coerceIn(0, pixels.height)
        val bottom = (part.origin.y + ceil(title.bottom).toInt()).coerceIn(0, pixels.height)
        assertTrue("Measured source title lies in the frozen header", left < right && top < bottom)
        var ink = 0
        for (y in top until bottom) for (x in left until right) {
            val color = pixels[x, y]
            if (color.alpha > .2f && minOf(color.red, color.green, color.blue) > .7f) ink++
        }
        assertTrue("Actual source title glyph pixels survive the native atlas, ink=$ink", ink > 20)
    }
    private fun between(source: Rect, destination: Rect, progress: Float): Rect {
        val p = progress.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * p
        return Rect(mix(source.left, destination.left), mix(source.top, destination.top),
            mix(source.right, destination.right), mix(source.bottom, destination.bottom))
    }
    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1.1f); assertEquals(expected.top, actual.top, 1.1f)
        assertEquals(expected.right, actual.right, 1.1f); assertEquals(expected.bottom, actual.bottom, 1.1f)
    }
}
