package io.github.anastaci1a.localica

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #635: a long jump inside the browse LookaheadScope makes the lazy grid's lookahead retain the
 * previous approach rows (composed, listed in layoutInfo, never placed). The rest overlay must not
 * draw their artwork and text at the last placed position until the next remeasure.
 */
class ScrollTopRetainedRowsTest {
    @get:Rule val compose = createComposeRule()

    /** The fly-through's last two landings in the operator's video: the rows just past the top
     * screen (items 35 to 44) at the top, then one step to the top. The lookahead keeps the rows
     * after its new last visible item composed, and those were just placed on screen. */
    @Test fun snapToTopDoesNotDrawRetainedRowsAtTheirOldPositions() = land { list, _ ->
        list.performScrollToIndex(35)
        compose.waitForIdle()
        list.performScrollToIndex(0)
    }

    /** The operator's sequence: scroll to the bottom of the gallery, then re-tap the tab. */
    @Test fun tabReturnFromTheBottomLandsWithoutRetainedRows() = land { _, request ->
        request()
    }

    private fun land(trigger: (SemanticsNodeInteraction, () -> Unit) -> Unit) {
        val prefs = BrowsePreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val route = BrowseRoute(MainTab.Albums)
        val old = prefs.read(route)
        val tracks = (0 until 300).map {
            val title = if (it % 3 == 0) "Album $it with a title that wraps" else "Album $it"
            Track("retained:$it", "Song $it", artist = "Artist ${it / 2}", albumArtist = "Artist ${it / 2}", album = title,
                fileName = "$it.mp3", relativePath = "$it.mp3")
        }
        prefs.write(route, BrowseStyle(presentation = Presentation.Standard, itemsPerRow = 5), false)
        var topRequest by mutableIntStateOf(0)
        try {
            compose.setContent { LocalicaTheme {
                BrowserScreen(LibraryUiState(LibrarySnapshot(folders = listOf(FolderSelection("root", "Music")), tracks = tracks), loading = false),
                    PlaylistState(loading = false), route, {}, {}, {}, { _, _, _ -> }, null, null, true, false,
                    scrollToTopRequest = topRequest, reducedMotion = false)
            } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
            val list = compose.onNodeWithTag("browser-list")
            list.performScrollToIndex(299)
            compose.waitForIdle()
            trigger(list) { topRequest++ }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            val landed = pixels(list)
            // A no-op scroll forces the remeasure a touch would; it must not change the picture.
            list.performScrollToIndex(0)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(2000)
            compose.waitForIdle()
            val remeasured = pixels(list)
            val changed = landed.indices.count { landed[it] != remeasured[it] }
            assertTrue("Landing frame differs from its own remeasure in $changed of ${landed.size} pixels (stale retained rows drawn)",
                changed <= landed.size / 1000)
        } finally { prefs.write(route, old, false) }
    }

    private fun pixels(node: SemanticsNodeInteraction): IntArray {
        val bitmap = node.captureToImage().asAndroidBitmap()
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)?.let { dir ->
            java.io.File(dir, "probe-${System.nanoTime()}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    }
}
