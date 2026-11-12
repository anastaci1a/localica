package io.github.anastaci1a.localica

import io.github.anastaci1a.localica.ui.components.TriggerRole

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.ArtworkCache
import io.github.anastaci1a.localica.library.ArtworkColors
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The shared row must finish loading after lazy reuse, including failed cover reads. */
class ArtworkScrollLoadingTest {
    @get:Rule val compose = localicaComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()

    @After fun cleanUp() {
        files.forEach { it.delete() }
        ArtworkCache.invalidate()
        ArtworkColors.publish(emptyMap())
    }

    @Test fun normalListLoadsAfterJumpsAndFlings() = checkScroll(ListSize.Normal)
    @Test fun compactListLoadsAfterJumpsAndFlings() = checkScroll(ListSize.Compact)
    @Test fun largeListLoadsAfterJumpsAndFlings() = checkScroll(ListSize.Large)
    @Test fun smallListActionsSurviveJumpsAndFlings() = checkScroll(ListSize.Small)
    @Test fun smallestListActionsSurviveJumpsAndFlings() = checkScroll(ListSize.Smallest)
    @Test fun tracklistActionsSurviveJumpsAndFlings() = checkScroll(ListSize.Tracklist)
    @Test fun oneColumnGalleryLoadsAfterJumpsAndFlings() = checkScroll(columns = 1)
    @Test fun twoColumnGalleryLoadsAfterJumpsAndFlings() = checkScroll(columns = 2)
    @Test fun threeColumnGalleryLoadsAfterJumpsAndFlings() = checkScroll(columns = 3)
    @Test fun fiveColumnGalleryLoadsAfterJumpsAndFlings() = checkScroll(columns = 5)

    private fun checkScroll(listSize: ListSize? = null, columns: Int = 1) {
        ArtworkCache.invalidate()
        ArtworkColors.publish(emptyMap())
        val directory = File(context.filesDir, "playlist-covers").also { it.mkdirs() }
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val items = (0..99).map { index ->
            val file = File.createTempFile("scroll-art-588-", ".png", directory).also { files += it }
            // Missing audio sources take the normal metadata-reader path, unlike
            // an undecodable custom cover in playlist-covers (which returns early).
            if (index % 2 == 0) file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val source = if (index % 2 == 0) file else File(context.cacheDir, "missing-audio-${file.name}.mp3")
            BrowserItem(MediaRef(MediaKind.Playlist, "scroll-art-$index"), "Artwork row %03d".format(index), null,
                emptyList(), coverUri = android.net.Uri.fromFile(source).toString())
        }
        bitmap.recycle()
        val style = BrowseStyle(presentation = if (listSize == null) Presentation.Standard else Presentation.List,
            listSize = listSize ?: ListSize.Normal)
        var scrolling: () -> Boolean = { false }
        var visibleKeys: () -> List<String> = { emptyList() }
        compose.setContent { LocalicaTheme {
            LookaheadScope {
                val state = rememberLazyGridState()
                SideEffect {
                    scrolling = { state.isScrollInProgress }
                    visibleKeys = { state.layoutInfo.visibleItemsInfo.map { it.key as String } }
                }
                LazyVerticalGrid(GridCells.Fixed(columns), Modifier.size(360.dp, 440.dp).testTag("artwork-scroll"), state) {
                    items(items.size, key = { items[it].key }) { index ->
                        val item = items[index]
                        BrowseItemLayout(item, style, BrowseCellGeometry(columns), this@LookaheadScope, false,
                            menu = { shown -> if (shown) ActionIcon(AppIcon.ActionEllipsis, "Actions for ${item.title}", role = TriggerRole.PlainIcon) {} },
                            itemIndex = index)
                    }
                }
            }
        } }
        val viewport = compose.onNodeWithTag("artwork-scroll")
        fun awaitVisibleArtwork() {
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeByFrame()
                !compose.runOnIdle { scrolling() } &&
                    compose.onAllNodesWithTag("artwork-loading", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            val visible = compose.runOnIdle { visibleKeys() }
            val hasArtwork = listSize == null || listSize in listOf(ListSize.Normal, ListSize.Compact, ListSize.Large)
            visible.forEach { key ->
                val item = items.single { it.key == key }
                if (hasArtwork) {
                    val loaded = ArtworkCache.peek(item.coverUris)
                    org.junit.Assert.assertNotNull("Visible recycled cover must resolve: $key", loaded)
                    val index = items.indexOf(item)
                    assertEquals("The resolved cover belongs to this row", index % 2 == 0, loaded!!.bitmap != null)
                }
                // Edge rows can be clipped, but a resolved list action must exist.
                if (listSize != null) compose.onNodeWithContentDescription("Actions for ${item.title}")
                    .assertExists().assertHasClickAction()
            }
        }
        awaitVisibleArtwork()
        viewport.performScrollToNode(hasText(items[70].title))
        awaitVisibleArtwork()
        for (index in listOf(70, 15, 90, 0)) {
            viewport.performScrollToIndex(index)
            awaitVisibleArtwork()
        }
        viewport.performTouchInput { swipeUp(durationMillis = 80) }
        awaitVisibleArtwork()
        viewport.performTouchInput { swipeDown(durationMillis = 80) }
        awaitVisibleArtwork()
    }
}
