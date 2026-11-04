package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
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

/** #535: subview pinches must not strand their transaction, header or drawing. */
class SubviewPinchRecoveryTest {
    @get:Rule val compose = createComposeRule()

    private fun album(size: ListSize, test: (SemanticsNodeInteraction, () -> String?, BrowsePreferences, BrowseRoute) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tracks = (0..99).map { Track("recovery:$it", "Song ${it.toString().padStart(3, '0')}",
            artist = "Recovery Artist", album = "Recovery Album", fileName = "$it.mp3", relativePath = "$it.mp3") }
        val snapshot = LibrarySnapshot(folders = listOf(FolderSelection("recovery-root", "Music")), tracks = tracks)
        val group = LibraryViews.groups(snapshot, SongListView.Albums, LibraryQuery()).single()
        val route = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, group.id), "Recovery Album")
        val prefs = BrowsePreferences(context)
        val original = prefs.read(route)
        val overridden = prefs.overridden(route)
        prefs.write(route, BrowseStyle(listSize = size), true)
        try {
            compose.setContent { LocalicaTheme { Box(Modifier.fillMaxSize().testTag("window")) {
                BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(), route, {}, {}, {},
                    { _, _, _ -> }, null, null, true, false, queryDispatcher = Dispatchers.Unconfined)
            } } }
            val list = compose.onNodeWithTag("browser-list")
            val view = { list.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) }
            compose.waitUntil(5000) { view() == "List, ${size.name}" }
            test(list, view, prefs, route)
        } finally { compose.mainClock.autoAdvance = true; prefs.write(route, original, overridden) }
    }

    /** (a) A contact during the settle refused the anchor's scroll, so the header handoff never completed. */
    @Test fun touchDuringTheSettleLeavesTheAlbumPinchable() = album(ListSize.Normal) { list, view, prefs, route ->
        compose.mainClock.autoAdvance = false
        // A quick outward pinch, released while its items still ease into the new view.
        list.performTouchInput {
            down(0, Offset(centerX - 60f, centerY)); down(1, Offset(centerX + 60f, centerY))
            moveTo(0, Offset(centerX - 80f, centerY)); moveTo(1, Offset(centerX + 80f, centerY))
        }
        compose.waitUntil(5000) { compose.mainClock.advanceTimeByFrame(); view() == "List, Large" }
        list.performTouchInput {
            moveTo(0, Offset(centerX - 140f, centerY)); moveTo(1, Offset(centerX + 140f, centerY))
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        list.performTouchInput { up(0); up(1) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        // The next two-finger contact lands during the settle ease and is held past it,
        // while the release hands the header back.
        list.performTouchInput { down(0, Offset(centerX - 60f, centerY)); down(1, Offset(centerX + 60f, centerY)) }
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        list.performTouchInput { up(0); up(1) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        assertEquals(BrowseStyle(listSize = ListSize.Large), prefs.read(route))
        // The transaction retired: another pinch is recognized and changes the view.
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
        assertTrue("A pinch after a touch during the settle must still change the view (now ${view()})", recognized)
    }
}
