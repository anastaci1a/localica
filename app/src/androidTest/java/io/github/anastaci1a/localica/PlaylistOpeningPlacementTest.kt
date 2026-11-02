package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** #503: a playlist opens with every cover already at its final list position, laid out and drawn. */
class PlaylistOpeningPlacementTest {
    @get:Rule val compose = createComposeRule()

    private val artTag = SemanticsMatcher("browse art") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("browse-art-") == true
    }

    @Test fun coversAreAtTheirFinalListPositionsOnEveryFrameAfterOpening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tracks = (0..11).map { Track("open:$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Opening"), tracks)
        val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id))
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        prefs.write(route, BrowseStyle(sort = TrackSort.Manual), false)
        try {
            compose.mainClock.autoAdvance = false
            compose.setContent { LocalicaTheme {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(listOf(playlist), false), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            } }
            var frames = 0
            while (compose.onAllNodes(artTag, useUnmergedTree = true).fetchSemanticsNodes().size < 3) {
                check(frames++ < 400) { "The playlist never produced rows" }
                compose.mainClock.advanceTimeByFrame()
                Thread.sleep(10)
            }
            // Every frame from the first with rows through the end of the entry and placement motion.
            val samples = mutableListOf<List<Rect>>()
            val images = mutableListOf<androidx.compose.ui.graphics.ImageBitmap>()
            repeat(70) {
                samples += compose.onAllNodes(artTag, useUnmergedTree = true).fetchSemanticsNodes().take(3).map { it.boundsInRoot }
                images += compose.onNodeWithTag("browser-list").captureToImage()
                compose.mainClock.advanceTimeByFrame()
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val finalBounds = compose.onAllNodes(artTag, useUnmergedTree = true).fetchSemanticsNodes().take(3).map { it.boundsInRoot }
            val listBounds = compose.onNodeWithTag("browser-list").fetchSemanticsNode().boundsInRoot
            samples.forEachIndexed { frame, bounds ->
                bounds.zip(finalBounds).forEachIndexed { row, (seen, final) ->
                    assertEquals("frame $frame row $row left", final.left, seen.left, 1f)
                    assertEquals("frame $frame row $row top", final.top, seen.top, 1f)
                    assertEquals("frame $frame row $row size", final.width, seen.width, 1f)
                }
            }
            // Drawn pixels too: the cover is painted inside its final bounds, not elsewhere.
            // The entry sweep fades rows in over about 40 frames; compare pixels once it has finished.
            images.forEachIndexed { frame, image ->
                if (frame < 50) return@forEachIndexed
                val pixels = image.toPixelMap()
                finalBounds.forEachIndexed { row, final ->
                    val x0 = (final.left - listBounds.left).toInt(); val y0 = (final.top - listBounds.top).toInt()
                    if (x0 < 3 || y0 < 0 || y0 + final.height.toInt() >= pixels.height) return@forEachIndexed
                    val background = pixels[x0 - 3, y0 + final.height.toInt() / 2]
                    var inside = 0
                    for (x in x0 until x0 + final.width.toInt() step 2) for (y in y0 until y0 + final.height.toInt() step 2) {
                        val c = pixels[x, y]
                        if (abs(c.red - background.red) + abs(c.green - background.green) + abs(c.blue - background.blue) > .03f) inside++
                    }
                    assertTrue("frame $frame row $row: the cover must be drawn inside its final bounds", inside > 20)
                }
            }
        } finally {
            compose.mainClock.autoAdvance = true
            prefs.write(route, old, false)
        }
    }
}
