package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Revoking a photo resets its blend without replacing the offscreen header coordinate owner. */
class ArtistPhotoHeaderTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun resettingPhotoBlendPreservesOffscreenHeroAndPinchCoordinates() {
        lateinit var grid: LazyGridState
        lateinit var pinch: CollectionPinchHeader
        lateinit var backdrop: FrostedBackdrop
        var reset by mutableStateOf(true)
        val blue = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }.asImageBitmap()
        val red = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }.asImageBitmap()
        compose.setContent { LocalicaTheme {
            grid = rememberLazyGridState()
            pinch = remember(grid) { CollectionPinchHeader(BrowsePinchMotion(), grid) }
            val visual = PlayerVisual(if (reset) red else blue, contentKey = if (reset) "photo" else "album")
            val source = rememberFrostedBackdrop(visual)
            backdrop = source
            Box(Modifier.fillMaxSize()) {
                CollectionBackground(visual, grid, source, false, Modifier.matchParentSize(), pinch, "artist" to reset)
                LazyVerticalGrid(GridCells.Fixed(1), state = grid, modifier = Modifier.fillMaxSize().testTag("grid")) {
                    item(key = "browser-notice") { Spacer(Modifier.height(440.dp)) }
                    items(80, key = { "song-$it" }) { Text("Song $it", Modifier.height(72.dp)) }
                }
            }
        } }
        compose.waitForIdle()
        // Let the header become known, then deliberately recycle it out of the viewport.
        compose.onNodeWithTag("grid").performScrollToIndex(20)
        compose.waitForIdle()
        var before = 0f
        var index = 0
        var offset = 0
        var recordings = 0
        var originalBackdrop: FrostedBackdrop? = null
        compose.runOnIdle {
            before = requireNotNull(pinch.ordinaryTop)
            assertTrue("The header must be offscreen", before < -440f)
            index = grid.firstVisibleItemIndex; offset = grid.firstVisibleItemScrollOffset
            recordings = backdrop.recordings; originalBackdrop = backdrop
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { reset = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Discarding photo pixels must retain the CollectionScroll owner", before, requireNotNull(pinch.ordinaryTop), .01f)
            assertEquals(index, grid.firstVisibleItemIndex)
            assertEquals(offset, grid.firstVisibleItemScrollOffset)
            assertSame("Photo changes retain the backdrop producer", originalBackdrop, backdrop)
            assertTrue("Photo changes invalidate the recorded backdrop", backdrop.recordings > recordings)
            assertTrue("The backdrop is still placed after its image subtree changes", pinch.background?.isAttached == true)
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("grid").performScrollToIndex(21)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Coordinate tracking continues after revocation", requireNotNull(pinch.ordinaryTop) < before) }
    }
}
