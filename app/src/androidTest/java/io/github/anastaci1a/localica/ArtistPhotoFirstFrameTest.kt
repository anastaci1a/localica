package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.artistphoto.*
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Native window pixels at the first mounted frame, with the animation clock frozen. */
class ArtistPhotoFirstFrameTest {
    @get:Rule val compose = localicaComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun bitmap(color: Int) = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun list() = checkContext("list")
    @Test fun gallery() = checkContext("gallery")
    @Test fun carousel() = checkContext("carousel")
    @Test fun subview() = checkContext("subview")
    @Test fun searchResults() = checkContext("search")
    @Test fun collectionHeader() = checkContext("header")

    private fun checkContext(surface: String) {
        val root = File(context.cacheDir, "artist-first-frame-${UUID.randomUUID()}")
        val consent = object : NetworkConsent {
            override val allowed = MutableStateFlow(true)
            override suspend fun setAllowed(value: Boolean) { allowed.value = value }
        }
        val disk = ArtistPhotoDisk(File(root, "photos"), File(root, "choices"), 8L * 1024 * 1024)
        val photos = ArtistPhotos(consent, disk, PhotoTransport { _, _ -> error("No network in a warm display test") })
        val environment = ArtistPhotoEnvironment(photos, consent)
        val tracks = listOf("Cached Artist", "Missing Artist").mapIndexed { index, name ->
            Track("first-frame:$index", "Song $index", name, "Fixture Album", albumArtist = name,
                fileName = "$index.mp3", relativePath = "$index.mp3")
        }
        val snapshot = LibrarySnapshot(tracks = tracks)
        var kind by mutableStateOf<MediaKind?>(null)
        var show by mutableStateOf(false)
        val retention = BrowseArtworkScope()
        tracks.forEach { retention.hold(it.id, listOf(it.uri), ArtworkCache.CachedArtwork(bitmap(android.graphics.Color.BLUE), 768)) }
        fun refs(kind: MediaKind): List<BrowserItem> {
            val view = if (kind == MediaKind.Artist) SongListView.Artists else SongListView.AlbumArtists
            return LibraryViews.groups(snapshot, view, LibraryViews.defaults(view)).map { group ->
                BrowserItem(MediaRef(kind, group.id), group.title, null, group.tracks.map { SongRow(it.uri, it) })
            }
        }
        var currentItems = emptyList<BrowserItem>()
        var route = BrowseRoute(MainTab.Artists)
        val preferences = BrowsePreferences(context)
        val savedPreferences = mutableListOf<Pair<BrowseRoute, BrowseStyle>>()
        val output = File(context.getExternalFilesDir(null), "artist-first-frame-742").apply { mkdirs() }
        try {
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalArtistPhotoEnvironment provides environment, LocalBrowseArtwork provides retention) {
                    if (show) key(kind) {
                        if (surface == "header") Row {
                            currentItems.forEach { item ->
                                val result = BrowseResult(item.songs.map { row -> BrowserItem(MediaRef(MediaKind.Song, row.track.id), row.track.title, null, listOf(row), row = row) })
                                val album = PlayerVisual(bitmap(android.graphics.Color.BLUE).asImageBitmap(), contentKey = "album")
                                val visual = rememberArtistCollectionVisual(item.ref, item.title, result, item.coverUris.first(), album)
                                PlayerArtwork(visual, Modifier.size(120.dp).testTag("header-${item.key}"), RectangleShape, ContentScale.Crop, 240)
                            }
                        } else if (surface == "carousel") {
                            PinnedHome(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), currentItems.map { it.ref },
                                {}, {}, { _, _ -> }, {}, { _, _ -> }, queryDispatcher = Dispatchers.Unconfined)
                        } else BrowserScreen(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), route,
                            {}, {}, {}, { _, _, _ -> }, null, null, true, false, queryDispatcher = Dispatchers.Unconfined)
                    }
                }
            } }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            for (type in listOf(MediaKind.Artist, MediaKind.AlbumArtist)) {
                val items = refs(type).sortedBy { it.title }
                val cached = items.first { it.title == "Cached Artist" }
                val bytes = ByteArrayOutputStream().also { bitmap(android.graphics.Color.RED).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                runBlocking(Dispatchers.IO) {
                    disk.savePhoto("${type}:${cached.ref.id}", RemoteArtist(ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "8"), cached.title, listOf("Fixture Album")), bytes, System.currentTimeMillis())
                    val request = requireNotNull(artistPhotoRequest(cached.ref, cached.title, cached.songs.map { it.track }, cached.coverUris.first()))
                    val photo = requireNotNull(photos.ready(request).photo)
                    // Warm through the same disk/pixel APIs as a previous visit, never a UI override.
                    ArtistPhotoPixels.read(photo, thumbnail = true)
                    ArtistPhotoPixels.read(photo)
                }
                val tab = if (type == MediaKind.Artist) MainTab.Artists else MainTab.AlbumArtists
                val nextRoute = when (surface) {
                    "subview" -> BrowseRoute(MainTab.Library, filter = type)
                    "search" -> BrowseRoute(MainTab.Library, search = true, globalSearch = true, text = "Artist", filter = type, queryCommitted = true)
                    else -> BrowseRoute(tab)
                }
                savedPreferences += nextRoute to preferences.read(nextRoute)
                preferences.write(nextRoute, BrowseStyle(presentation = if (surface == "gallery") Presentation.Compact else Presentation.List,
                    extraInfo = ExtraInfo.None, itemsPerRow = 2), override = true)
                compose.runOnUiThread { currentItems = items; route = nextRoute; kind = type; show = true }
                fun tag(item: BrowserItem) = when (surface) {
                    "header" -> "header-${item.key}"
                    "carousel" -> "pin-${item.key}"
                    else -> "browse-art-${item.key}"
                }
                // Query publication may need frames; stop on the first frame that mounts the item.
                var mounted = false
                repeat(8) {
                    if (!mounted) {
                        compose.mainClock.advanceTimeByFrame()
                        mounted = compose.onAllNodesWithTag(tag(cached), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    }
                }
                assertTrue("$surface/$type must mount", mounted)
                val firstFrame = compose.mainClock.currentTime
                items.forEach { item ->
                    val image = compose.onNodeWithTag(tag(item), useUnmergedTree = true).captureNativeFrame()
                    File(output, "$surface-$type-${item.title.substringBefore(' ')}-first.png").outputStream().use {
                        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val map = image.toPixelMap()
                    val color = map[map.width / 2, if (surface == "carousel") map.width / 2 else map.height / 2]
                    assertEquals("Capture must not advance the Compose frame", firstFrame, compose.mainClock.currentTime)
                    if (item == cached) assertTrue("$surface/$type cached photo must be red on FIRST frame: $color", color.red > .95f && color.blue < .05f)
                    else assertTrue("$surface/$type no photo must retain blue album art: $color", color.blue > .95f && color.red < .05f)
                }
                compose.runOnUiThread { show = false }
                compose.mainClock.advanceTimeByFrame()
            }
        } finally {
            compose.mainClock.autoAdvance = true
            consent.allowed.value = false
            savedPreferences.forEach { (route, style) -> preferences.write(route, style, override = true) }
            root.deleteRecursively()
        }
    }
}
