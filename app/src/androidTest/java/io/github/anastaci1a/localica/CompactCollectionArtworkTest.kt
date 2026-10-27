package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.queue.QueueEntry
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Real queried song rows and MediaArtwork pixels, not a stand-in cover or label-only assertion. */
class CompactCollectionArtworkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactAlbumRowsShowArtworkInsteadOfAlbumNumbers() = verifyCollection(MediaKind.Album)
    @Test fun compactArtistRowsKeepArtwork() = verifyCollection(MediaKind.Artist)
    @Test fun compactMutablePlaylistRowsKeepOccurrenceArtwork() = verifyCollection(MediaKind.Playlist)
    @Test fun compactQueueRowsKeepOccurrenceArtwork() = verifyCollection(MediaKind.Queue)

    private fun verifyCollection(kind: MediaKind) {
        val tracks = listOf(
            Track("compact:first", "First song", artist = "Collection artist", albumArtist = "Collection artist",
                album = "Collection album", trackNumber = 12, fileName = "first.mp3", relativePath = "first.mp3"),
            Track("compact:second", "Second song", artist = "Collection artist", albumArtist = "Collection artist",
                album = "Collection album", trackNumber = 34, fileName = "second.mp3", relativePath = "second.mp3"),
        )
        val snapshot = LibrarySnapshot(tracks = tracks)
        // Mutable lists include a repeated file with a distinct physical occurrence identity.
        val occurrences = listOf(tracks[0], tracks[1], tracks[0])
        val playlist = ManualPlaylist(id = "compact-playlist", name = "Compact playlist",
            entries = occurrences.mapIndexed { index, track -> SongRow("playlist-$index", track) })
        val queue = ListeningQueue(id = "compact-queue", name = "Compact queue",
            entries = occurrences.mapIndexed { index, track -> QueueEntry("queue-$index", track) })
        val collection = when (kind) {
            MediaKind.Album -> LibraryViews.groups(snapshot, SongListView.Albums, LibraryQuery()).single().id
            MediaKind.Artist -> LibraryViews.groups(snapshot, SongListView.Artists, LibraryQuery()).single().id
            MediaKind.Playlist -> playlist.id
            MediaKind.Queue -> queue.id
            else -> error("Unsupported fixture collection")
        }
        val route = BrowseRoute(MainTab.Library, MediaRef(kind, collection))
        val items = BrowseQueries.evaluate(snapshot, listOf(playlist), route, queues = listOf(queue)).items
        val mutable = kind == MediaKind.Playlist || kind == MediaKind.Queue
        assertEquals(if (mutable) 3 else 2, items.size)
        assertEquals(items.size, items.map { it.key }.distinct().size)
        val physicalPositions = items.mapIndexed { index, item -> item.key to index + 1 }.toMap()
        val colors = mapOf(tracks[0].uri to android.graphics.Color.rgb(16, 224, 48),
            tracks[1].uri to android.graphics.Color.rgb(224, 32, 16))
        val retention = BrowseArtworkScope()
        items.forEach { item ->
            val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                eraseColor(colors.getValue(requireNotNull(item.row).track.uri))
            }
            retention.hold(item.key, item.coverUris, ArtworkCache.CachedArtwork(bitmap, 512))
        }
        var size by mutableStateOf(ListSize.Compact)
        var fontScale by mutableFloatStateOf(1f)
        var density = 1f
        compose.setContent { LocalicaTheme {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale),
                LocalBrowseArtwork provides retention, LocalReducedMotion provides true) {
                LookaheadScope {
                    Column(Modifier.width(328.dp).background(Color.White)) {
                        val style = BrowseStyle(listSize = size, extraInfo = ExtraInfo.Artist)
                        val geometry = BrowseCellGeometry(1)
                        items.forEachIndexed { index, item -> key(item.key) {
                            Box(Modifier.fillMaxWidth().testTag("compact-row-${item.key}")
                                .padding(horizontal = geometry.cardInset.dp, vertical = geometry.cardVerticalInset(style).dp)) {
                                // Match BrowserScreen's collection-sensitive inputs to the shared row.
                                BrowseItemLayout(item, style, geometry, this@LookaheadScope, true, menu = {},
                                    trackNumber = if (route.collection?.kind == MediaKind.Album)
                                        item.row?.track?.trackNumber?.toString() ?: (index + 1).toString() else null,
                                    itemIndex = index,
                                    mutablePosition = physicalPositions[item.key].takeIf { mutable })
                            }
                        } }
                    }
                }
            }
        } }

        fun bounds(item: BrowserItem, part: String): Rect =
            compose.onNodeWithTag("browse-$part-${item.key}", true).fetchSemanticsNode().boundsInRoot
        fun number(item: BrowserItem): String? = when {
            kind == MediaKind.Album -> item.row?.track?.trackNumber?.toString()
            mutable -> physicalPositions.getValue(item.key).toString()
            else -> null
        }
        fun assertArtwork(item: BrowserItem) {
            val pixels = compose.onNodeWithTag("browse-art-${item.key}", true).captureToImage().toPixelMap()
            val expected = colors.getValue(requireNotNull(item.row).track.uri)
            // Sample several interior points: a number/placeholder cannot stand in for the cover.
            for (fraction in listOf(.25f, .5f, .75f)) {
                val actual = pixels[(pixels.width * fraction).toInt(), (pixels.height * fraction).toInt()]
                assertEquals("$kind ${item.key} red", android.graphics.Color.red(expected) / 255f, actual.red, .03f)
                assertEquals("$kind ${item.key} green", android.graphics.Color.green(expected) / 255f, actual.green, .03f)
                assertEquals("$kind ${item.key} blue", android.graphics.Color.blue(expected) / 255f, actual.blue, .03f)
            }
        }
        fun assertCompact() {
            compose.waitForIdle()
            items.forEach { item ->
                val art = bounds(item, "art")
                val row = compose.onNodeWithTag("compact-row-${item.key}").fetchSemanticsNode().boundsInRoot
                assertTrue("Compact keeps its existing artwork sizing", art.width > 32 * density && art.width <= 56 * density + 1f)
                val pitch = (56 * density).roundToInt() + 2 * (4 * density).roundToInt()
                assertEquals("Compact row pitch must not grow", pitch.toFloat(), row.height, 1f)
                assertEquals("Compact information remains trailing", bounds(item, "menu").left, bounds(item, "info").right, 1f)
                assertEquals(row.center.y, bounds(item, "info").center.y, 1f)
                number(item)?.let { compose.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(0) }
                assertArtwork(item)
            }
        }

        for (scale in listOf(1f, 1.5f)) {
            compose.runOnIdle { fontScale = scale }
            assertCompact()
        }
        compose.runOnIdle { fontScale = 1f; size = ListSize.Small }
        compose.waitForIdle()
        items.forEach { item ->
            val label = number(item)
            if (label == null) assertEquals(0f, bounds(item, "art").width, 0f)
            else {
                compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                assertEquals(28 * density, bounds(item, "art").width, 1f)
            }
        }
        if (route.supportsTracklist()) {
            compose.runOnIdle { size = ListSize.Tracklist }
            compose.waitForIdle()
            items.forEach { item ->
                compose.onNodeWithText(requireNotNull(number(item)), useUnmergedTree = true).assertIsDisplayed()
                assertTrue("Tracklist retains subtitle placement", bounds(item, "info").top >= bounds(item, "text").bottom - 1f)
            }
        }
        compose.runOnIdle { size = ListSize.Normal }
        compose.waitForIdle()
        items.forEach { item ->
            assertArtwork(item)
            assertTrue("Normal information remains a subtitle", bounds(item, "info").top >= bounds(item, "text").bottom - 1f)
            number(item)?.let { compose.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(0) }
        }
        compose.runOnIdle { size = ListSize.Compact }
        assertCompact()
    }
}
