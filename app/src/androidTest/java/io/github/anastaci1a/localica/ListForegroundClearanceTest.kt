package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ListForegroundClearanceTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun scrollToEndThenPinchKeepsLastRowAnchoredAboveMeasuredReturnControl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = BrowseRoute(MainTab.Songs)
        val preferences = BrowsePreferences(context)
        val original = preferences.read(route)
        preferences.write(route, BrowseStyle(listSize = ListSize.Normal), false)
        val tracks = List(80) { Track("pinch-clear:$it", "Song ${it.toString().padStart(2, '0')}",
            fileName = "$it.mp3", relativePath = "$it.mp3") }
        try {
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalBrowseObscuredInset provides 120.dp) {
                    BrowserScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false),
                        PlaylistState(loading = false), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                        queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                }
            } }
            val list = compose.onNodeWithTag("browser-list")
            fun waitForView(label: String) {
                compose.waitUntil(5000) {
                    list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == label
                }
                compose.waitForIdle()
            }
            waitForView("List, Normal")
            // No second scroll after the return arrow first appears: the initial
            // scroll-to-end must already include its measured clearance.
            list.performScrollToIndex(tracks.size)
            val bottom = compose.onNodeWithTag("media-pinch-clear:79").fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("The first scroll must clear the measured arrow", bottom <=
                compose.onNodeWithTag("floating-return-control").fetchSemanticsNode().boundsInRoot.top)
            repeat(2) {
                for ((zoomIn, label) in listOf(true to "List, Large", false to "List, Normal")) {
                    list.performTouchInput {
                        val from = if (zoomIn) 50f else 110f
                        val to = if (zoomIn) 110f else 50f
                        down(0, Offset(centerX - from, centerY)); down(1, Offset(centerX + from, centerY))
                        repeat(8) { step ->
                            val distance = from + (to - from) * (step + 1) / 8f
                            moveTo(0, Offset(centerX - distance, centerY), delayMillis = 24)
                            moveTo(1, Offset(centerX + distance, centerY), delayMillis = 24)
                        }
                        up(0); up(1)
                    }
                    waitForView(label)
                    assertEquals("The last row must stay anchored through $label", bottom,
                        compose.onNodeWithTag("media-pinch-clear:79").fetchSemanticsNode().boundsInRoot.bottom, 3f)
                }
            }
        } finally { preferences.write(route, original, false) }
    }

    @Test fun albumLastRowClearsMeasuredArrowAndOverflowAboveLowerChrome() {
        val tracks = List(30) { Track("clear:$it", "Song ${it.toString().padStart(2, '0')}",
            album = "Clearance", artist = "Artist", trackNumber = it + 1,
            fileName = "$it.mp3", relativePath = "$it.mp3") }
        val snapshot = LibrarySnapshot(tracks = tracks)
        val album = LibraryViews.groups(snapshot, SongListView.Albums, LibraryQuery()).single()
        val route = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, album.id), album.title)
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("browse-style", 0).edit().clear().commit()
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalBrowseObscuredInset provides 120.dp) {
                Box(Modifier.fillMaxSize()) {
                    BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), route,
                        {}, {}, {}, { _, _, _ -> }, null, null, true, false, reducedMotion = true)
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp).testTag("lower-chrome"))
                }
            }
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onNodeWithTag("browser-list")
        list.performScrollToIndex(tracks.size)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100000f) }
        compose.waitForIdle()
        val last = compose.onNodeWithTag("media-clear:29").fetchSemanticsNode().boundsInRoot
        val foreground = listOf(compose.onNodeWithTag("floating-return-control"),
            compose.onNodeWithContentDescription("Album actions"), compose.onNodeWithTag("lower-chrome"))
        for (control in foreground) {
            val bounds = control.fetchSemanticsNode().boundsInRoot
            assertTrue("Last row $last must clear foreground $bounds", last.bottom <= bounds.top)
        }
    }

    private fun scrollbar(count: Int): () -> Unit {
        val state = LazyGridState()
        lateinit var scope: CoroutineScope
        val items = List(count) { BrowserItem(MediaRef(MediaKind.Album, "$it"), "Album $it", null, emptyList()) }
        compose.setContent { LocalicaTheme {
            scope = rememberCoroutineScope()
            Box(Modifier.width(300.dp).height(500.dp)) {
                LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize(), state = state) {
                    items(count + 1) { Text("Row $it", Modifier.height(100.dp)) }
                }
                BrowseScrollbar(state, items, TrackSort.Title, Modifier.align(Alignment.CenterEnd))
            }
        } }
        compose.waitForIdle()
        return { scope.launch { state.scrollToItem(2) }; Unit }
    }

    @Test fun overflowingListsBelowFortyItemsHaveScrollbarWithGenerousTarget() {
        scrollbar(12)
        compose.onNodeWithTag("browse-scrollbar").assertExists().assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("browse-scrollbar-thumb").assertWidthIsEqualTo(4.dp)
    }

    @Test fun scrollbarFadesAfterIdleAndReappearsOnListMovement() {
        val move = scrollbar(60)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(2200)
        compose.waitForIdle()
        fun thumbPixels(): Float {
            val pixels = compose.onNodeWithTag("browse-scrollbar-thumb").captureToImage().toPixelMap()
            val pixel = pixels[pixels.width / 2, pixels.height / 2]
            return pixel.red + pixel.green + pixel.blue
        }
        val idle = thumbPixels()
        compose.runOnIdle(move)
        compose.mainClock.advanceTimeBy(250)
        compose.waitForIdle()
        val moving = thumbPixels()
        assertTrue("Movement must restore faded thumb: idle=$idle moving=$moving", kotlin.math.abs(idle - moving) > .05f)
        compose.mainClock.autoAdvance = true
    }
}
