package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.artistphoto.*
import io.github.anastaci1a.localica.browse.BrowseResult
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.network.OnlineMetadataConsent
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ArtistPhotoChooserTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun previewPickUpdatesRowsGalleryHeaderAndHomePinsThroughTheirExistingFades() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "photo-chooser-${java.util.UUID.randomUUID()}")
        val evidence = nativeEvidenceDirectory("artist-photo-712", context.getExternalFilesDir(null))
        fun bitmap(color: Int) = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        fun bytes(color: Int) = ByteArrayOutputStream().also { bitmap(color).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val green = bytes(android.graphics.Color.GREEN)
        val red = bytes(android.graphics.Color.RED)
        val consent = OnlineMetadataConsent(File(root, "consent"))
        val disk = ArtistPhotoDisk(File(root, "photos"), File(root, "choices"), 8L * 1024 * 1024)
        val photos = ArtistPhotos(consent, disk, PhotoTransport { url, _ -> when {
            url.contains("/search/artist?") -> """{"data":[{"id":8,"name":"Fixture Artist","picture_big":"https://cdn-images.dzcdn.net/green.jpg"},{"id":9,"name":"Fixture Artist","picture_big":"https://cdn-images.dzcdn.net/red.jpg"}]}""".toByteArray()
            url.contains("/top?") -> """{"data":[{"album":{"title":"Fixture Album"}}]}""".toByteArray()
            url.contains("cdn-images") -> if (url.contains("red")) red else green
            else -> """{"artists":[]}""".toByteArray()
        } })
        val track = Track("fixture:chooser", "Song", "Fixture Artist", "Fixture Album", fileName = "fixture.mp3", relativePath = "fixture.mp3")
        val snapshot = LibrarySnapshot(tracks = listOf(track))
        val group = LibraryViews.groups(snapshot, SongListView.Artists, LibraryViews.defaults(SongListView.Artists)).single()
        val item = BrowserItem(MediaRef(MediaKind.Artist, group.id), group.title, null, listOf(SongRow(track.uri, track)))
        val request = requireNotNull(artistPhotoRequest(item.ref, item.title, listOf(track), track.uri))
        val pinTag = "pin-${item.ref.kind}:${item.ref.id}"
        val tags = listOf("photo-row", "photo-gallery", "photo-header", pinTag)
        val retention = BrowseArtworkScope().apply { hold("album", listOf(track.uri), ArtworkCache.CachedArtwork(bitmap(android.graphics.Color.BLUE), 768)) }
        fun pixel(tag: String) = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().let { it.getPixel(it.width / 2, it.height / 2) }
        fun save(stage: String) {
            for ((index, tag) in tags.withIndex()) File(evidence, "$stage-$index.png").outputStream().use {
                compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        try {
            runBlocking(Dispatchers.IO) {
                consent.load(); consent.setAllowed(true)
                disk.saveChoice(request.artistKey, SavedPhotoChoice("manual", ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "8")))
                disk.savePhoto(request.artistKey, RemoteArtist(ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "8"), request.name,
                    request.localReleases.toList()), green, System.currentTimeMillis())
            }
            compose.setContent { LocalicaTheme {
                val environment = remember { ArtistPhotoEnvironment(photos, consent) }
                val scope = rememberCoroutineScope()
                val actions = remember { ArtistPhotoActions(environment, request, scope) }
                CompositionLocalProvider(LocalArtistPhotoEnvironment provides environment, LocalBrowseArtwork provides retention) {
                    Column {
                        Row {
                            ArtistMediaArtwork(item, Modifier.size(72.dp).testTag("photo-row"))
                            ArtistMediaArtwork(item, Modifier.size(96.dp).testTag("photo-gallery"))
                            val rows = BrowseResult(listOf(BrowserItem(MediaRef(MediaKind.Song, track.id), track.title, track.artist,
                                item.songs, row = item.songs.single())))
                            val album = PlayerVisual(bitmap(android.graphics.Color.BLUE).asImageBitmap(), contentKey = "fixture-album")
                            val hero = rememberArtistCollectionVisual(item.ref, item.title, rows, track.uri, album)
                            PlayerArtwork(hero, Modifier.size(96.dp).testTag("photo-header"), androidx.compose.ui.graphics.RectangleShape,
                                androidx.compose.ui.layout.ContentScale.Crop, 240)
                        }
                        Box(Modifier.height(230.dp)) {
                            PinnedHome(LibraryUiState(snapshot, loading = false), PlaylistState(loading = false), listOf(item.ref),
                                {}, {}, { _, _ -> }, {}, { _, _ -> }, queryDispatcher = Dispatchers.Unconfined)
                        }
                        ActionButton({ actions.open = true }) { Text("Open photo chooser") }
                    }
                    ArtistPhotoDialog(actions)
                }
            } }
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag(pinTag).fetchSemanticsNodes().isNotEmpty() && tags.all { android.graphics.Color.green(pixel(it)) > 240 }
            }
            save("before")
            compose.onNodeWithText("Open photo chooser").performClick()
            val choice = ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "9")
            val preview = "artist-photo-preview:$choice"
            compose.waitUntil(10000) { compose.onAllNodesWithTag(preview).fetchSemanticsNodes().isNotEmpty() &&
                android.graphics.Color.red(pixel(preview)) > 240 }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { image ->
                File(evidence, "chooser-previews.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onAllNodesWithText("Fixture Artist · deezer")[1].performScrollTo().performClick()
            val blocked = runBlocking(Dispatchers.IO) {
                val selection = File(root, "choices").listFiles().orEmpty().single { it.extension == "json" }
                File(selection.parentFile, selection.name + ".pending").also {
                    check(it.mkdir()); File(it, "block").writeText("Simulated selection write failure")
                }
            }
            compose.onNodeWithText("Select").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Could not apply this photo. Previous photo kept.").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(tags.all { android.graphics.Color.green(pixel(it)) > 240 })
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { image ->
                File(evidence, "chooser-inline-retry.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            runBlocking(Dispatchers.IO) { check(File(blocked, "block").delete()); check(blocked.delete()) }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Retry").performScrollTo().performClick()
            var mixed = false
            var frame = 0
            compose.waitUntil(10000) {
                compose.mainClock.advanceTimeByFrame()
                val colors = tags.map(::pixel)
                if (colors.any { android.graphics.Color.red(it) > 10 && android.graphics.Color.green(it) > 10 }) mixed = true
                assertTrue("Photo revisions must not drop matching ready pixels to blue album art", colors.none { android.graphics.Color.blue(it) > 240 })
                if (frame++ in listOf(0, 3, 8, 16)) save("frame-$frame")
                colors.all { android.graphics.Color.red(it) > 240 }
            }
            assertTrue("Existing artwork fades must present intermediate old/new pixels", mixed)
            save("after")
            assertEquals(choice, runBlocking { photos.selection(request.artistKey) }.current)
            compose.mainClock.autoAdvance = true
            compose.onNodeWithText("Close").performClick()
        } finally { compose.mainClock.autoAdvance = true; root.deleteRecursively() }
    }
}
