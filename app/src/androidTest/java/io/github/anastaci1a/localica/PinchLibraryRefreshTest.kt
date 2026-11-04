package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A library refresh during a collection pinch must not strand its header handoff (#535, #542 review). */
class PinchLibraryRefreshTest {
    @get:Rule val compose = createComposeRule()

    private fun snapshot(prefix: String) = LibrarySnapshot(folders = listOf(FolderSelection("refresh-root", "Music")),
        tracks = (0..99).map { Track("$prefix:$it", "Song ${it.toString().padStart(3, '0')}",
            artist = "Refresh Artist", album = "Refresh Album", fileName = "$prefix-$it.mp3", relativePath = "$prefix-$it.mp3") })

    /** Every row is rescanned under a new key while the pinch is held, so its focus row leaves the result:
     * the release anchor can never be measured and must hand the header back instead of awaiting it. */
    @Test fun refreshRemovingTheFocusRowLeavesTheAlbumPinchable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = snapshot("refresh-a")
        val after = snapshot("refresh-b")
        val group = LibraryViews.groups(before, SongListView.Albums, LibraryQuery()).single()
        assertEquals(group.id, LibraryViews.groups(after, SongListView.Albums, LibraryQuery()).single().id)
        val route = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, group.id), "Refresh Album")
        val prefs = BrowsePreferences(context)
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        prefs.write(route, BrowseStyle(listSize = ListSize.Normal), true)
        var library by mutableStateOf(LibraryUiState(before, loading = false))
        try {
            compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize().testTag("window")) {
                BrowserScreen(library, PlaylistState(), route, {}, {}, {},
                    { _, _, _ -> }, null, null, true, false, queryDispatcher = Dispatchers.Unconfined)
            } } }
            val list = compose.onNodeWithTag("browser-list")
            val view = { list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) }
            compose.waitUntil(5000) { view() == "List, Normal" }
            compose.mainClock.autoAdvance = false
            list.performTouchInput {
                down(0, Offset(centerX - 60f, centerY)); down(1, Offset(centerX + 60f, centerY))
                moveTo(0, Offset(centerX - 80f, centerY)); moveTo(1, Offset(centerX + 80f, centerY))
            }
            compose.waitUntil(5000) { compose.mainClock.advanceTimeByFrame(); view() == "List, Large" }
            list.performTouchInput {
                moveTo(0, Offset(centerX - 140f, centerY)); moveTo(1, Offset(centerX + 140f, centerY))
            }
            repeat(4) { compose.mainClock.advanceTimeByFrame() }
            compose.runOnUiThread { library = LibraryUiState(after, loading = false) }
            repeat(4) { compose.mainClock.advanceTimeByFrame() }
            list.performTouchInput { up(0); up(1) }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(3000)
            compose.waitForIdle()
            assertEquals(BrowseStyle(listSize = ListSize.Large), prefs.read(route))
            // The settled transaction retired: another pinch is recognized and changes the view.
            compose.mainClock.autoAdvance = false
            list.performTouchInput {
                down(0, Offset(centerX - 60f, centerY)); down(1, Offset(centerX + 60f, centerY))
                moveTo(0, Offset(centerX - 70f, centerY)); moveTo(1, Offset(centerX + 70f, centerY))
                moveTo(0, Offset(centerX - 140f, centerY)); moveTo(1, Offset(centerX + 140f, centerY))
            }
            val recognized = runCatching {
                compose.waitUntil(3000) { compose.mainClock.advanceTimeByFrame(); view() != "List, Large" }
            }.isSuccess
            list.performTouchInput { up(0); up(1) }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertTrue("A pinch after a library refresh during the last one must change the view (now ${view()})", recognized)
        } finally { compose.mainClock.autoAdvance = true; prefs.write(route, original, overridden) }
    }
}
