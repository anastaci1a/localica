package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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

class BrowsePinchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun listSizesChangeDensityWithoutLosingSavedDetailOrArtwork() {
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val route = BrowseRoute(MainTab.Songs)
        val original = prefs.read(route)
        val style = BrowseStyle(extraInfo = ExtraInfo.Artist, listSize = ListSize.Compact)
        val tracks = (0..29).map { Track("size:$it", "Song ${it.toString().padStart(2, '0')}", artist = "Artist $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        try {
            prefs.write(route, style, false)
            compose.setContent { LocalicaTheme {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            } }
            waitForView("List, Compact")
            compose.onNodeWithText("Artist 0").assertDoesNotExist()
            assertEquals(0f, compose.onNodeWithTag("browse-art-size:0", true).fetchSemanticsNode().boundsInRoot.width)
            val row = compose.onNodeWithTag("media-size:0")
            val compact = row.fetchSemanticsNode().boundsInRoot.height
            compose.runOnIdle { prefs.write(route, style.copy(listSize = ListSize.Normal), false) }
            waitForView("List, Normal")
            compose.onNodeWithText("Artist 0").assertIsDisplayed()
            val normal = row.fetchSemanticsNode().boundsInRoot.height
            assertTrue(normal > compact)
            compose.runOnIdle { prefs.write(route, style.copy(listSize = ListSize.Large), false) }
            waitForView("List, Large")
            assertTrue(row.fetchSemanticsNode().boundsInRoot.height > normal)
            compose.runOnIdle { prefs.write(route, style, false) }
            waitForView("List, Compact")
            assertEquals(compact, row.fetchSemanticsNode().boundsInRoot.height)
            assertEquals(ExtraInfo.Artist, prefs.read(route).extraInfo)
        } finally { prefs.write(route, original, false) }
    }

    @Test fun oneStepUntilEveryFingerLiftsAndDisabledViewsIgnorePinches() {
        val steps = mutableListOf<Boolean>()
        var enabled by mutableStateOf(true)
        compose.setContent { Box(Modifier.fillMaxSize().pinchToChangeView(enabled) { zoomIn, _ -> steps += zoomIn; true }.testTag("pinch")) }
        val target = compose.onNodeWithTag("pinch")
        target.performTouchInput {
            down(0, Offset(centerX - 50, centerY)); down(1, Offset(centerX + 50, centerY))
            moveTo(0, Offset(centerX - 100, centerY)); moveTo(1, Offset(centerX + 100, centerY))
            up(1)
            down(1, Offset(centerX + 50, centerY))
            moveTo(0, Offset(centerX - 150, centerY)); moveTo(1, Offset(centerX + 150, centerY))
            up(0); up(1)
        }
        assertEquals(listOf(true), steps)
        target.performTouchInput { pinch(false) }
        assertEquals(listOf(true, false), steps)
        compose.runOnIdle { enabled = false }
        target.performTouchInput { pinch(true) }
        assertEquals(2, steps.size)
    }

    @Test fun deepListKeepsItsAnchorAndSelectionAcrossAllEightViews() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        val session = SessionSelection()
        val tracks = (0..119).map { index ->
            Track("pinch:$index", "Song ${index.toString().padStart(3, '0')}", fileName = "$index.mp3", relativePath = "$index.mp3")
        }
        prefs.write(route, BrowseStyle(listSize = ListSize.Compact), false)
        try {
            compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalSessionSelection provides session) {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false)
            } } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
            val list = compose.onNodeWithTag("browser-list")
            list.performScrollToIndex(57)
            val item = compose.onNodeWithTag("media-pinch:60")
            item.performTouchInput { longClick() }
            compose.onNodeWithText("Select song").performClick()
            val original = item.fetchSemanticsNode().boundsInRoot
            val viewport = list.fetchSemanticsNode().boundsInRoot
            list.performTouchInput { pinch(true, original.center.y - viewport.top) }
            waitForView("List, Normal")
            assertEquals("Keep the content at the pinch midpoint fixed", original.center.y,
                item.fetchSemanticsNode().boundsInRoot.center.y, 4f)
            list.performTouchInput { pinch(true) }; waitForView("List, Large")
            list.performTouchInput { pinch(true) }; waitForView("Gallery, 5 columns")
            for (columns in 4 downTo 1) {
                list.performTouchInput { pinch(true) }; waitForView(if (columns == 1) "Gallery, 1 column" else "Gallery, $columns columns")
                assertEquals(columns, prefs.read(route).itemsPerRow)
            }
            for (columns in 2..5) {
                list.performTouchInput { pinch(false) }; waitForView("Gallery, $columns columns")
            }
            for (size in listOf("Large", "Normal", "Compact")) {
                list.performTouchInput { pinch(false) }; waitForView("List, $size")
            }
            assertEquals(Presentation.List, prefs.read(route).presentation)
            assertEquals(listOf("pinch:60"), session.entries.map { it.row.track.id })
        } finally { prefs.write(route, old, false) }
    }

    @Test fun twoFingerHoldInMutablePlaylistCannotBeginSongSelectionOrReordering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tracks = (0..29).map { Track("held:$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val playlist = PlaylistEdits.append(ManualPlaylist(name = "Pinch"), tracks)
        val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, playlist.id))
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        val session = SessionSelection()
        prefs.write(route, BrowseStyle(sort = TrackSort.Manual), false)
        pinchTraceEnabled = true
        try {
            compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalSessionSelection provides session) {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(listOf(playlist), false), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false)
            } } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Song 0").fetchSemanticsNodes().isNotEmpty() }
            val item = compose.onNodeWithTag("media-${playlist.entries.first().id}").fetchSemanticsNode().boundsInRoot
            val list = compose.onNodeWithTag("browser-list")
            val viewport = list.fetchSemanticsNode().boundsInRoot
            val y = item.center.y - viewport.top
            val rootView = (list.fetchSemanticsNode().root as androidx.compose.ui.platform.ViewRootForTest).view
            compose.runOnUiThread {
                android.util.Log.i("BrowsePinchTrace", "fixture density=${context.resources.displayMetrics.density} item=$item viewport=$viewport localY=$y windowFocused=${rootView.hasWindowFocus()} spans=50..100px")
            }
            list.performTouchInput {
                down(0, Offset(centerX - 50, y)); down(1, Offset(centerX + 50, y))
            }
            compose.mainClock.advanceTimeBy(800)
            compose.runOnIdle { assertFalse("Pinching must not also long-press a song", session.active) }
            list.performTouchInput {
                moveTo(0, Offset(centerX - 100, y)); moveTo(1, Offset(centerX + 100, y)); up(0); up(1)
            }
            waitForView("List, Large")
            assertFalse(session.active)
            compose.onNodeWithTag("drop-gap").assertDoesNotExist()
        } finally { pinchTraceEnabled = false; prefs.write(route, old, false) }
    }

    @Test fun heldPinchChangesArtworkGeometryBeforeRelease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        prefs.write(route, BrowseStyle(listSize = ListSize.Large), false)
        try {
            val tracks = (0..20).map { Track("live:$it", "Song ${it.toString().padStart(2, '0')}", fileName = "$it.mp3", relativePath = "$it.mp3") }
            // A bounded viewport makes the endpoint sizes distinct on both emulator densities.
            compose.setContent { LocalicaTheme { Box(Modifier.width(300.dp)) {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            } } }
            val list = compose.onNodeWithTag("browser-list")
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            val artwork = compose.onNodeWithTag("browse-art-live:0", true)
            val resting = artwork.fetchSemanticsNode().boundsInRoot
            fun changed(a: androidx.compose.ui.geometry.Rect, b: androidx.compose.ui.geometry.Rect) =
                maxOf(kotlin.math.abs(a.width - b.width), kotlin.math.abs(a.left - b.left), kotlin.math.abs(a.top - b.top)) > 1f
            list.performTouchInput {
                down(0, Offset(centerX - 50, 200f)); down(1, Offset(centerX + 50, 200f))
                moveTo(0, Offset(centerX - 65, 200f)); moveTo(1, Offset(centerX + 65, 200f))
            }
            // The frozen-source readback uses the real render thread, not the
            // synthetic animation clock. Wait for that handoff with fingers held.
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                // Large-list and five-column artwork can have nearly equal widths;
                // their movement into different positions must still follow the pinch.
                changed(artwork.fetchSemanticsNode().boundsInRoot, resting)
            }
            compose.mainClock.advanceTimeBy(64)
            val first = artwork.fetchSemanticsNode().boundsInRoot
            list.performTouchInput {
                moveTo(0, Offset(centerX - 110, 200f)); moveTo(1, Offset(centerX + 110, 200f))
            }
            compose.mainClock.advanceTimeBy(64)
            val second = artwork.fetchSemanticsNode().boundsInRoot
            assertTrue("Artwork must respond while both fingers remain down: $first / $second", changed(second, first))
            list.performTouchInput { up(0); up(1) }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals(5, prefs.read(route).itemsPerRow)
        } finally { compose.mainClock.autoAdvance = true; prefs.write(route, old, false) }
    }

    @Test fun galleryPinchCanReturnToItsOriginalLayoutWithoutLifting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val original = prefs.read(route)
        val gallery = BrowseStyle(presentation = Presentation.Compact, itemsPerRow = 5)
        prefs.write(route, gallery, false)
        try {
            val tracks = (0..59).map { Track("reverse:$it", "Song $it", fileName = "$it.mp3", relativePath = "$it.mp3") }
            compose.setContent { LocalicaTheme {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            } }
            waitForView("Gallery, 5 columns")
            val list = compose.onNodeWithTag("browser-list")
            val viewport = list.fetchSemanticsNode().boundsInRoot
            val artwork = compose.onNodeWithTag("browse-art-reverse:14", true).fetchSemanticsNode().boundsInRoot
            val beforePixels = list.captureToImage().toPixelMap()
            compose.mainClock.autoAdvance = false
            list.performTouchInput {
                down(0, Offset(centerX - 100, 200f)); down(1, Offset(centerX + 100, 200f))
                moveTo(0, Offset(centerX - 65, 200f)); moveTo(1, Offset(centerX + 65, 200f))
            }
            compose.mainClock.advanceTimeBy(96)
            list.performTouchInput {
                moveTo(0, Offset(centerX - 100, 200f)); moveTo(1, Offset(centerX + 100, 200f))
            }
            compose.mainClock.advanceTimeBy(96)
            val restoredPixels = list.captureToImage().toPixelMap()
            var difference = 0f
            for (dy in 2..8) for (dx in 2..8) {
                val x = (artwork.left - viewport.left + artwork.width * dx / 10f).toInt().coerceIn(0, beforePixels.width - 1)
                val y = (artwork.top - viewport.top + artwork.height * dy / 10f).toInt().coerceIn(0, beforePixels.height - 1)
                val old = beforePixels[x, y]; val restored = restoredPixels[x, y]
                difference += kotlin.math.abs(old.red - restored.red) + kotlin.math.abs(old.green - restored.green) + kotlin.math.abs(old.blue - restored.blue)
            }
            assertTrue("Reversing must restore virtualized artwork before release: $difference", difference / (49 * 3) < .12f)
            list.performTouchInput { up(0); up(1) }
            compose.mainClock.autoAdvance = true
            waitForView("Gallery, 5 columns")
            assertEquals(gallery.presentation, prefs.read(route).presentation)
            assertEquals(5, prefs.read(route).itemsPerRow)
            compose.onNodeWithTag("media-reverse:0").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true; prefs.write(route, original, false) }
    }

    @Test fun galleryDensityChangesRetainLowerItemsWhenReversedRepeatedly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val original = prefs.read(route)
        val tracks = (0..99).map { Track("density:$it", "Song ${it.toString().padStart(3, '0')}", fileName = "$it.mp3", relativePath = "$it.mp3") }
        try {
            prefs.write(route, BrowseStyle(presentation = Presentation.Compact, itemsPerRow = 5), false)
            compose.setContent { LocalicaTheme {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            } }
            for (columns in 5 downTo 2) {
                compose.runOnIdle { prefs.write(route, BrowseStyle(presentation = Presentation.Compact, itemsPerRow = columns), false) }
                waitForView("Gallery, $columns columns")
                val list = compose.onNodeWithTag("browser-list")
                val before = list.captureToImage().toPixelMap()
                compose.mainClock.autoAdvance = false
                list.performTouchInput { down(0, Offset(centerX - 50, 200f)); down(1, Offset(centerX + 50, 200f)) }
                repeat(2) {
                    list.performTouchInput { moveTo(0, Offset(centerX - 78, 200f)); moveTo(1, Offset(centerX + 78, 200f)) }
                    compose.mainClock.advanceTimeBy(240)
                    list.performTouchInput { moveTo(0, Offset(centerX - 50, 200f)); moveTo(1, Offset(centerX + 50, 200f)) }
                    compose.mainClock.advanceTimeBy(240)
                }
                val restored = list.captureToImage().toPixelMap()
                var error = 0f; var count = 0
                for (y in before.height / 3 until before.height * 3 / 4 step 4)
                    for (x in 8 until before.width - 8 step 4) {
                        error += kotlin.math.abs(before[x,y].red - restored[x,y].red); count++
                    }
                assertTrue("Gallery $columns reversal must retain lower items: ${error / count}", error / count < .08f)
                list.performTouchInput { up(0); up(1) }
                compose.mainClock.autoAdvance = true
                waitForView("Gallery, $columns columns")
                assertEquals(columns, prefs.read(route).itemsPerRow)
            }
        } finally { compose.mainClock.autoAdvance = true; prefs.write(route, original, false) }
    }

    @Test fun repeatedPinchesKeepTopAndBottomEdgesAnchored() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs)
        val prefs = BrowsePreferences(context)
        val old = prefs.read(route)
        prefs.write(route, BrowseStyle(listSize = ListSize.Normal), false)
        val tracks = (0..79).map { Track("edge:$it", "Song ${it.toString().padStart(3, '0')}", fileName = "$it.mp3", relativePath = "$it.mp3") }
        try {
            compose.setContent { LocalicaTheme {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
            } }
            waitForView("List, Normal")
            val list = compose.onNodeWithTag("browser-list")
            val top = compose.onNodeWithTag("media-edge:0").fetchSemanticsNode().boundsInRoot.top
            repeat(2) {
                list.performTouchInput { pinch(true) }; waitForView("List, Large")
                assertEquals(top, compose.onNodeWithTag("media-edge:0").fetchSemanticsNode().boundsInRoot.top, 3f)
                list.performTouchInput { pinch(false) }; waitForView("List, Normal")
                assertEquals(top, compose.onNodeWithTag("media-edge:0").fetchSemanticsNode().boundsInRoot.top, 3f)
            }
            list.performScrollToIndex(80)
            val bottom = compose.onNodeWithTag("media-edge:79").fetchSemanticsNode().boundsInRoot.bottom
            repeat(2) {
                list.performTouchInput { pinch(true) }; waitForView("List, Large")
                assertEquals(bottom, compose.onNodeWithTag("media-edge:79").fetchSemanticsNode().boundsInRoot.bottom, 3f)
                list.performTouchInput { pinch(false) }; waitForView("List, Normal")
                assertEquals(bottom, compose.onNodeWithTag("media-edge:79").fetchSemanticsNode().boundsInRoot.bottom, 3f)
            }
        } finally { prefs.write(route, old, false) }
    }

    private fun waitForView(label: String) {
        try {
            compose.waitUntil(5000) { compose.onNodeWithTag("browser-list").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == label }
            compose.waitForIdle()
        } catch (failure: Throwable) {
            runCatching {
                android.util.Log.e("BrowsePinchTest", "Expected view: $label; ${compose.onNodeWithTag("browser-list").printToString()}")
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun TouchInjectionScope.pinch(zoomIn: Boolean, y: Float = centerY) {
        val from = if (zoomIn) 50f else 110f
        val to = if (zoomIn) 110f else 50f
        down(0, Offset(centerX - from, y)); down(1, Offset(centerX + from, y))
        repeat(8) { n ->
            val distance = from + (to - from) * (n + 1) / 8f
            moveTo(0, Offset(centerX - distance, y), delayMillis = 24)
            moveTo(1, Offset(centerX + distance, y), delayMillis = 24)
        }
        up(0); up(1)
    }
}
