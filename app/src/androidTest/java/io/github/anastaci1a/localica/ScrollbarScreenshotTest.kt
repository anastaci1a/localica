package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.TrackSort
import io.github.anastaci1a.localica.navigation.MediaKind
import io.github.anastaci1a.localica.navigation.MediaRef
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Evidence captures (#582): the thumb on a short, a medium and a very long list, at rest and held. */
class ScrollbarScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var state: LazyGridState
    private lateinit var scope: CoroutineScope

    private fun capture(name: String, count: Int, rowDp: Int) {
        val items = List(count) { BrowserItem(MediaRef(MediaKind.Album, "a$it"), "T$it", null, emptyList()) }
        compose.setContent { LocalicaTheme {
            state = rememberLazyGridState(); scope = rememberCoroutineScope()
            Box(Modifier.height(640.dp).width(360.dp).testTag("shot")) {
                LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize(), state = state) {
                    item { Text("Header", Modifier.height(120.dp)) }
                    items(count) { Text("Row $it", Modifier.height(if (it % 7 == 0) (rowDp * 3 / 2).dp else rowDp.dp)) }
                }
                BrowseScrollbar(state, items, TrackSort.Title, Modifier.align(androidx.compose.ui.Alignment.CenterEnd))
            }
        } }
        compose.waitForIdle()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val dir = nativeEvidenceDirectory("scrollbar-582", context.getExternalFilesDir(null))
        for (place in listOf("top", "middle", "bottom")) {
            compose.runOnIdle { scope.launch {
                when (place) {
                    "top" -> state.scrollToItem(0)
                    "middle" -> state.scrollToItem(count / 2)
                    else -> { state.scrollToItem(count); state.scrollBy(1e7f) }
                }
            } }
            // #704: an idle scrollbar is faded and untouchable; show it as a scroll does before the rest shot.
            compose.revealScrollbar(state)
            shot(dir, "$name-$place-rest")
            val thumb = compose.onNodeWithTag("browse-scrollbar-thumb").fetchSemanticsNode().boundsInRoot
            val bar = compose.onNodeWithTag("browse-scrollbar").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("browse-scrollbar").performTouchInput { down(Offset(centerX, thumb.center.y - bar.top)) }
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            shot(dir, "$name-$place-held")
            compose.onNodeWithTag("browse-scrollbar").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            compose.mainClock.autoAdvance = true
        }
    }

    private fun shot(dir: File, name: String) {
        val image = compose.onNodeWithTag("shot").captureToImage()
        File(dir, "$name-api${android.os.Build.VERSION.SDK_INT}.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(File(dir, "$name-api${android.os.Build.VERSION.SDK_INT}.png").length() > 0)
    }

    @Test fun shortList() = capture("short", 40, 20)
    @Test fun mediumList() = capture("medium", 100, 64)
    @Test fun longList() = capture("long", 2500, 64)
}
