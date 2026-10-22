package io.github.anastaci1a.localica

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A hidden trailing-menu slot must not own a minimum-sized touch strip. */
class GalleryMenuInputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fourColumnGalleryScrollsThroughItsEmptyMenuSlot() = scrollThroughMenuSlot(4)
    @Test fun fiveColumnGalleryScrollsThroughItsEmptyMenuSlot() = scrollThroughMenuSlot(5)

    @Test fun outgoingQuietActionRetainsItsPixelsWithoutRetainingInput() {
        var inputEnabled by mutableStateOf(true)
        var stage by mutableIntStateOf(0)
        var clicks = 0
        var holds = 0
        lateinit var view: View
        compose.setContent { LocalicaTheme {
            view = LocalView.current
            CompositionLocalProvider(LocalContentColor provides Color(0xFF0044FF)) {
                Box(Modifier.size(64.dp).background(Color.White).testTag("retained-action-scene"),
                    contentAlignment = Alignment.Center) {
                    ActionIcon(R.drawable.ic_ellipsis, "Retained action", quiet = true,
                        quietInputEnabled = inputEnabled, onLongClickLabel = "Hold retained action",
                        onLongClick = { holds++ }) { clicks++ }
                    PresentationMarker(Modifier.align(Alignment.TopStart)) { stage }
                }
            }
        } }
        val scene = compose.onNodeWithTag("retained-action-scene")
        compose.onNodeWithContentDescription("Retained action").assertHasClickAction()
        scene.performTouchInput { click(center); longClick(center) }
        compose.runOnIdle { assertEquals(1, clicks); assertEquals(1, holds) }
        val before = capturePresentedPopupPixels(compose, scene, 0) { view }

        compose.runOnIdle { inputEnabled = false; stage = 1 }
        compose.mainClock.advanceTimeBy(500)
        val after = capturePresentedPopupPixels(compose, scene, 1) { view }
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        var inkPixels = 0
        // The central half contains all of the 24dp icon, excluding only the
        // unrelated 8dp presentation marker in this 64dp scene.
        for (y in before.height / 4 until before.height * 3 / 4) {
            for (x in before.width / 4 until before.width * 3 / 4) {
                if (before[x, y].blue - before[x, y].red > .3f) inkPixels++
                assertEquals("Display-only input must not dim or move the retained icon at $x,$y", before[x, y], after[x, y])
            }
        }
        assertTrue("The preserved drawing contains actual icon ink", inkPixels > 10)
        compose.onAllNodesWithContentDescription("Retained action").assertCountEquals(0)
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        scene.performTouchInput { click(center); longClick(center) }
        compose.runOnIdle { assertEquals(1, clicks); assertEquals(1, holds) }

        compose.runOnIdle { inputEnabled = true }
        compose.onNodeWithContentDescription("Retained action").assertHasClickAction()
        scene.performTouchInput { click(center); longClick(center) }
        compose.runOnIdle { assertEquals(2, clicks); assertEquals(2, holds) }
    }

    private fun scrollThroughMenuSlot(columns: Int) {
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val route = BrowseRoute(MainTab.Songs)
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        val tracks = (0..119).map { index -> Track("menu-slot:$index", "Song ${index.toString().padStart(3, '0')}",
            fileName = "$index.mp3", relativePath = "$index.mp3") }
        val library = LibraryUiState(LibrarySnapshot(
            folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false)
        val playlists = PlaylistState()
        var density = 1f
        prefs.write(route, BrowseStyle(presentation = Presentation.Standard, itemsPerRow = columns), false)
        try {
            compose.setContent { LocalicaTheme {
                density = LocalDensity.current.density
                Box(Modifier.size(360.dp, 640.dp)) {
                    BrowserScreen(library, playlists, route, {}, {}, {}, { _, _, _ -> }, null, null,
                        true, false, reducedMotion = true, queryDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
                }
            } }
            val list = compose.onNodeWithTag("browser-list")
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("browse-menu-menu-slot:0", true).fetchSemanticsNodes().isNotEmpty()
            }
            // Use the second row's first card, well inside the viewport, so both
            // the source and the destination of the swipe have scrolling room.
            val key = "menu-slot:$columns"
            val menu = compose.onNodeWithTag("browse-menu-$key", true).fetchSemanticsNode()
            val slotPosition = menu.positionInRoot
            val slotSize = menu.size
            val card = compose.onNodeWithTag("media-$key").fetchSemanticsNode().boundsInRoot
            val viewport = list.fetchSemanticsNode().boundsInRoot
            assertEquals("Gallery keeps the menu layout slot empty", 0, slotSize.width)
            assertTrue("The empty slot still has the outgoing menu's height", slotSize.height > 0)
            // Read placement rather than clipped bounds: a zero-width rectangle
            // can collapse to Rect.Zero. This lies just inside the card's right
            // edge, in the hidden slot's minimum touch target on the broken code.
            val rootStart = slotPosition + Offset(-density, 4 * density)
            val start = rootStart - viewport.topLeft
            val end = start - Offset(0f, 96 * density)
            assertTrue("The hit is inside the actual gallery card: $rootStart in $card", card.contains(rootStart))
            assertTrue("The whole probe is inside the list", viewport.contains(end + viewport.topLeft))
            compose.mainClock.autoAdvance = false
            list.performTouchInput { swipe(start, end, durationMillis = 200) }
            compose.mainClock.advanceTimeBy(500)
            val remaining = compose.onAllNodesWithTag("media-$key").fetchSemanticsNodes()
            val after = if (remaining.isEmpty()) null else remaining.single().boundsInRoot.top
            assertTrue("The empty menu slot must allow native scrolling: before=${card.top} after=$after slot=$slotPosition/$slotSize",
                after == null || after < card.top - 8f)
            compose.onAllNodes(hasContentDescription("Actions for", substring = true)).assertCountEquals(0)
        } finally {
            compose.mainClock.autoAdvance = true
            prefs.write(route, original, overridden)
        }
    }
}
