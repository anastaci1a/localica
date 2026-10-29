package io.github.anastaci1a.localica

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GalleryNavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun scrolledGalleryOpensVisibleDetailsAndClickableMenusAndSearch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("interface", 0).edit().clear().commit()
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        InterfacePreferences(context).write(InterfaceSettings(tabs = listOf(MainTab.Library)))
        BrowsePreferences(context).write(BrowseRoute(MainTab.Library), BrowseStyle(presentation = Presentation.Standard), false)
        val tracks = (0..80).map { n ->
            val name = n.toString().padStart(3, '0')
            Track("song:$n", "Visible song $name", album = "Album $name", fileName = "$n.mp3", relativePath = "$n.mp3")
        }
        try {
            compose.fixtureCostPhase("Gallery.mount") { compose.setContent { LocalicaTheme {
                LocalicaScreen(LibraryUiState(LibrarySnapshot(tracks = tracks), loading = false),
                    PlaybackState(connected = true), {}, {}, {}, {}, {}, {},
                    playlist = PlaylistState(loading = false))
            } } }
            for (n in listOf("000", "005", "030", "061")) {
                compose.fixtureCostPhase("Gallery.$n.await-list") { compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() } }
                compose.fixtureCostPhase("Gallery.$n.scroll") { compose.scrollToUncoveredItem(hasText("Album $n")) }
                compose.onNodeWithContentDescription("Actions for Album $n").assertDoesNotExist()
                compose.fixtureCostPhase("Gallery.$n.long-click") { compose.onNodeWithText("Album $n").performTouchInput { longClick() } }
                compose.fixtureCostPhase("Gallery.$n.await-menu") { compose.waitUntil(3000) { runCatching { compose.onNodeWithText("Select all songs").assertIsDisplayed() }.isSuccess } }
                try { compose.onNodeWithText("Select all songs").assertIsDisplayed() } catch (error: Throwable) {
                    android.util.Log.e("GalleryProbe", "Album $n: " + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 20))
                    androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                        .takeScreenshot(java.io.File(context.getExternalFilesDir(null), "gallery-failure.png"))
                    throw error
                }
                compose.fixtureCostPhase("Gallery.$n.dismiss-menu") { pressSystemBack() }
                compose.fixtureCostPhase("Gallery.$n.await-dismiss") { compose.waitUntil(5000) { compose.onAllNodesWithText("Select all songs").fetchSemanticsNodes().isEmpty() } }
                compose.fixtureCostPhase("Gallery.$n.open") { compose.onNodeWithText("Album $n").performTouchInput { click() } }
                compose.fixtureCostPhase("Gallery.$n.await-detail") { compose.waitUntil(5000) { compose.onAllNodesWithText("Visible song $n").fetchSemanticsNodes().isNotEmpty() } }
                val song = compose.onNodeWithText("Visible song $n")
                song.assertIsDisplayed()
                // Hit targets alone previously passed while the actual pixels remained stale.
                val image = compose.fixtureCostPhase("Gallery.$n.capture") { song.captureToImage().toPixelMap() }
                val colors = buildSet { for (y in 0 until image.height) for (x in 0 until image.width) add(image[x, y]) }
                assertTrue("Song text must actually render for $n", colors.size > 8)
                compose.fixtureCostPhase("Gallery.$n.back") { compose.onNodeWithContentDescription("Back").performTouchInput { click() } }
                compose.fixtureCostPhase("Gallery.$n.await-return") { compose.waitUntil(5000) { runCatching {
                    compose.onNode(hasText("Album $n") and hasClickAction()).assertIsDisplayed()
                }.isSuccess } }
                compose.onNodeWithText("Album $n").assertIsDisplayed()
                compose.fixtureCostPhase("Gallery.$n.search-open") { compose.onNodeWithContentDescription("Search library").performTouchInput { click() } }
                compose.fixtureCostPhase("Gallery.$n.search-type") { compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput(n) }
                compose.fixtureCostPhase("Gallery.$n.ime-action") { compose.onNode(hasSetTextAction()).assertTextContains(n).performImeAction() }
                compose.fixtureCostPhase("Gallery.$n.search-scroll") { compose.onNodeWithTag("browser-list").performScrollToNode(hasText("Visible song $n")) }
                compose.onNodeWithText("Visible song $n").assertIsDisplayed()
                compose.fixtureCostPhase("Gallery.$n.reset") { compose.onNodeWithContentDescription("Library").performTouchInput { click() } }
            }
        } finally {
            context.getSharedPreferences("interface", 0).edit().clear().commit()
            context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        }
    }
}
