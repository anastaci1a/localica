package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise real lazy-grid key anchoring independently of playback and pointer timing. */
class SongDragViewportTest {
    @get:Rule val compose = createComposeRule()

    private fun crossingViewportStart(selected: Set<Int>) {
        val songs = (0 until 60).map { BrowserItem(MediaRef(MediaKind.Song, "$it"), "Song $it", null, emptyList()) }
        lateinit var grid: LazyGridState
        lateinit var reorder: SongDragState
        lateinit var dragScope: CoroutineScope
        compose.setContent {
            grid = rememberLazyGridState(initialFirstVisibleItemIndex = 20)
            val scope = rememberCoroutineScope()
            dragScope = scope
            reorder = remember { SongDragState(grid, scope).also { it.items = songs } }
            LazyVerticalGrid(columns = GridCells.Fixed(1), state = grid, modifier = Modifier.height(480.dp)) {
                items(reorder.rendered(songs), key = { it?.key ?: DropGapKey }) {
                    Box(Modifier.fillMaxWidth().height(64.dp))
                }
            }
        }
        compose.runOnIdle {
            val held = songs[23]
            assertTrue(reorder.begin(held, selected.map { songs[it].key }.toSet(), reorder.bounds().getValue(held.key).center))
        }
        compose.waitForIdle()
        val firstIndex = grid.firstVisibleItemIndex
        val firstOffset = grid.firstVisibleItemScrollOffset
        compose.runOnIdle { reorder.move(Offset(20f, 1f)) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Gap should move before the first visible song", 20, reorder.drag!!.insertion)
            assertEquals("Moving the gap must not push the viewport down a row", firstIndex, grid.firstVisibleItemIndex)
            assertEquals(firstOffset, grid.firstVisibleItemScrollOffset)
        }
        // Keep the same held pointer at the upper edge as multiple rows scroll underneath it.
        val rowHeight = reorder.drag!!.cardSize.height
        var previousPosition = firstIndex * rowHeight + firstOffset
        val scrolling = compose.runOnIdle { dragScope.launch { reorder.autoScroll(64f) } }
        try {
            compose.waitUntil(5000) {
                compose.runOnIdle {
                    val position = grid.firstVisibleItemIndex * rowHeight + grid.firstVisibleItemScrollOffset
                    assertTrue("Upward edge scrolling must never jump downward: $previousPosition -> $position",
                        position <= previousPosition + 1f)
                    previousPosition = position
                    grid.firstVisibleItemIndex < 15
                }
            }
        } finally { compose.runOnIdle { scrolling.cancel() } }
        compose.runOnIdle { reorder.finish(cancel = true, reducedMotion = true) { _, _, _ -> fail("Cancellation must not save a reorder") } }
        compose.waitUntil(5000) { reorder.drag == null }
        compose.runOnIdle { assertEquals(songs, reorder.rendered(songs)) }
    }

    @Test fun singleSongCrossingTheViewportStartDoesNotJump() = crossingViewportStart(setOf(23))
    @Test fun disjointSelectionCrossingTheViewportStartDoesNotJump() = crossingViewportStart(setOf(23, 25))
}
