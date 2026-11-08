package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.artistphoto.*
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ArtistPhotoDisplayTest {
    @get:Rule val compose = localicaComposeRule()
    private fun bitmap(color: Int) = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun center(tag: String): Color = compose.onNodeWithTag(tag).captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
    private fun item(id: String = "fixture", name: String = "Fixture Artist", uri: String = "album:fixture") = BrowserItem(
        MediaRef(MediaKind.Artist, id), name, null,
        listOf(SongRow(uri, Track(uri, "Song", name, "Fixture Album", fileName = "fixture.mp3", relativePath = "fixture.mp3"))))
    private class Consent : NetworkConsent {
        override val allowed = MutableStateFlow(false)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }

    @Test fun nullReplacementKeepsTheExistingArtworkPathAndPhotoCanArriveBeforeFallback() {
        val pending = CompletableDeferred<Bitmap?>()
        val started = CompletableDeferred<Unit>()
        val blue = bitmap(android.graphics.Color.BLUE)
        val red = bitmap(android.graphics.Color.RED).asImageBitmap()
        var replacement by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        val provider = CoverProvider { _, _, _ -> started.complete(Unit); pending.await() }
        compose.setContent { LocalicaTheme { Column {
            MediaArtwork(listOf("pending"), Modifier.size(100.dp).testTag("replacement"), provider, replacementArtwork = replacement)
            MediaArtwork(listOf("parity"), Modifier.size(100.dp).testTag("default"), CoverProvider { _, _, _ -> blue })
            MediaArtwork(listOf("parity"), Modifier.size(100.dp).testTag("explicit-null"), CoverProvider { _, _, _ -> blue }, replacementArtwork = null)
        } } }
        compose.waitUntil(5000) { started.isCompleted }
        compose.waitForIdle()
        assertTrue(center("default").blue > .9f)
        assertEquals(center("default"), center("explicit-null"))
        compose.runOnIdle { replacement = red }
        compose.waitForIdle()
        assertTrue("Photo is visible even though album-art retrieval is unfinished", center("replacement").red > .9f)
        assertFalse(pending.isCompleted)
        pending.complete(blue)
        compose.waitForIdle()
        assertTrue("Finishing fallback must not overwrite the accepted photo", center("replacement").red > .9f)
    }

    @Test fun delayedPhotoCrossfadesWithoutBlockingAndConsentRevocationDiscardsIt() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "photo-display-${java.util.UUID.randomUUID()}")
        val consent = Consent()
        val pending = CompletableDeferred<ByteArray>()
        val requests = AtomicInteger()
        val transport = PhotoTransport { url, _ ->
            assertNotEquals("Photo requests must run off Main", Looper.getMainLooper(), Looper.myLooper())
            requests.incrementAndGet()
            when {
                url.contains("/search/artist?") -> """{"data":[{"id":8,"name":"Fixture Artist","picture_big":"https://cdn-images.dzcdn.net/fixture.jpg"}]}""".toByteArray()
                url.contains("/top?") -> """{"data":[{"album":{"title":"Fixture Album"}}]}""".toByteArray()
                url.contains("cdn-images") -> pending.await()
                else -> """{"artists":[]}""".toByteArray()
            }
        }
        val photos = ArtistPhotos(consent, ArtistPhotoDisk(File(root, "photos"), File(root, "choices"), 8L * 1024 * 1024), transport)
        val environment = ArtistPhotoEnvironment(photos, consent)
        val retention = BrowseArtworkScope().apply {
            hold("first", listOf("album:fixture"), ArtworkCache.CachedArtwork(bitmap(android.graphics.Color.BLUE), 768))
            hold("other", listOf("album:other"), ArtworkCache.CachedArtwork(bitmap(android.graphics.Color.GREEN), 768))
        }
        var current by mutableStateOf(item())
        var taps by mutableIntStateOf(0)
        try {
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalArtistPhotoEnvironment provides environment, LocalBrowseArtwork provides retention) {
                    Column {
                        ArtistMediaArtwork(current, Modifier.size(100.dp).testTag("artist"))
                        ActionButton({ taps++ }) { Text("Still responsive") }
                    }
                }
            } }
            compose.waitForIdle()
            assertEquals("Consent off starts no requests", 0, requests.get())
            assertTrue(center("artist").blue > .9f)
            runBlocking { consent.setAllowed(true) }
            compose.waitUntil(5000) { requests.get() >= 3 }
            compose.onNodeWithText("Still responsive").performClick()
            compose.runOnIdle { assertEquals(1, taps) }
            assertTrue("A blocked fetch retains album art", center("artist").blue > .9f)
            val bytes = ByteArrayOutputStream().also { bitmap(android.graphics.Color.RED).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            compose.mainClock.autoAdvance = false
            pending.complete(bytes)
            var mixed = false
            compose.waitUntil(10000) {
                compose.mainClock.advanceTimeByFrame()
                val color = center("artist")
                if (color.red > .05f && color.blue > .05f) mixed = true
                color.red > .98f
            }
            assertTrue("The existing resolution blend must present intermediate album/photo pixels", mixed)
            compose.runOnIdle { consent.allowed.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertTrue("Revocation drops the outgoing photo on the next frame, without waiting for a fade", center("artist").blue > .9f)
            val count = requests.get()
            runBlocking { consent.setAllowed(true) }
            compose.waitUntil(10000) { compose.mainClock.advanceTimeByFrame(); center("artist").red > .98f }
            assertEquals("Cached photo reuse does not refetch", count, requests.get())
            compose.runOnIdle { current = item("other", "Other Artist", "album:other") }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertTrue("A reused row immediately shows its new artist's fallback", center("artist").green > .9f)
            compose.mainClock.autoAdvance = true
        } finally {
            compose.mainClock.autoAdvance = true
            runBlocking { consent.setAllowed(false) }
            pending.cancel()
            root.deleteRecursively()
        }
    }
    @Test fun pinnedTilesRespectConsentAndIdentityForBothArtistScopesAndKeepAlbumFallback() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "photo-home-${java.util.UUID.randomUUID()}")
        val consent = Consent()
        val requests = AtomicInteger()
        val disk = ArtistPhotoDisk(File(root, "photos"), File(root, "choices"), 8L * 1024 * 1024)
        val photos = ArtistPhotos(consent, disk, PhotoTransport { _, _ -> requests.incrementAndGet(); error("Fresh cached pins need no network") })
        val first = Track("home:first", "First", "Fixture Artist", "Fixture Album", fileName = "first.mp3", relativePath = "first.mp3", albumArtist = "Fixture Artist")
        val other = first.copy(uri = "home:other", id = "home:other", title = "Other", artist = "Other Artist", albumArtist = "Other Artist", album = "Other Album")
        val snapshot = LibrarySnapshot(tracks = listOf(first, other))
        fun ref(kind: MediaKind, view: SongListView, title: String) = MediaRef(kind,
            LibraryViews.groups(snapshot, view, LibraryViews.defaults(view)).first { it.title == title }.id)
        val artist = ref(MediaKind.Artist, SongListView.Artists, "Fixture Artist")
        val otherArtist = ref(MediaKind.Artist, SongListView.Artists, "Other Artist")
        val albumArtist = ref(MediaKind.AlbumArtist, SongListView.AlbumArtists, "Fixture Artist")
        val otherAlbumArtist = ref(MediaKind.AlbumArtist, SongListView.AlbumArtists, "Other Artist")
        val album = ref(MediaKind.Album, SongListView.Albums, "Other Album")
        var pin by mutableStateOf(artist)
        fun tag() = "pin-${pin.kind}:${pin.id}"
        val retention = BrowseArtworkScope().apply {
            hold("first", listOf(first.uri), ArtworkCache.CachedArtwork(bitmap(android.graphics.Color.BLUE), 768))
            hold("other", listOf(other.uri), ArtworkCache.CachedArtwork(bitmap(android.graphics.Color.GREEN), 768))
        }
        try {
            val bytes = ByteArrayOutputStream().also { bitmap(android.graphics.Color.RED).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            runBlocking(Dispatchers.IO) {
                for (selected in listOf(artist, albumArtist)) disk.savePhoto("${selected.kind}:${selected.id}",
                    RemoteArtist(ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "8"), "Fixture Artist", listOf("Fixture Album")), bytes, System.currentTimeMillis())
                for (cleared in listOf(otherArtist, otherAlbumArtist)) disk.saveChoice("${cleared.kind}:${cleared.id}", SavedPhotoChoice("cleared"))
            }
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalArtistPhotoEnvironment provides ArtistPhotoEnvironment(photos, consent), LocalBrowseArtwork provides retention) {
                    PinnedHome(LibraryUiState(snapshot, loading = false), io.github.anastaci1a.localica.playlist.PlaylistState(loading = false), listOf(pin),
                        {}, {}, { _, _ -> }, {}, { _, _ -> }, queryDispatcher = Dispatchers.Unconfined)
                }
            } }
            fun tileReady() { compose.waitUntil(5000) { compose.onAllNodesWithTag(tag()).fetchSemanticsNodes().isNotEmpty() } }
            fun photoOn() {
                compose.runOnIdle { consent.allowed.value = true }
                compose.waitUntil(10000) { center(tag()).red > .98f }
            }
            fun photoOff() {
                compose.mainClock.autoAdvance = false
                compose.runOnIdle { consent.allowed.value = false }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertTrue("Pinned artist revocation must discard the photo on its next frame", center(tag()).blue > .9f)
                compose.mainClock.autoAdvance = true
            }
            fun switchTo(value: MediaRef) { compose.runOnIdle { pin = value }; tileReady(); compose.waitForIdle() }
            tileReady()
            assertTrue("Pinned artist starts with album art while consent is off", center(tag()).blue > .9f)
            photoOn(); photoOff(); photoOn()
            switchTo(otherArtist)
            assertTrue("A new pinned artist cannot inherit the previous photo", center(tag()).green > .9f)
            switchTo(albumArtist)
            photoOn(); photoOff(); photoOn()
            switchTo(otherAlbumArtist)
            assertTrue("A new pinned album artist cannot inherit the previous photo", center(tag()).green > .9f)
            switchTo(album)
            assertTrue("Ordinary album pins keep their original artwork", center(tag()).green > .9f)
            compose.runOnIdle { consent.allowed.value = false }
            compose.waitForIdle()
            assertTrue("Ordinary album artwork is independent of online consent", center(tag()).green > .9f)
            assertEquals(0, requests.get())
        } finally { compose.mainClock.autoAdvance = true; consent.allowed.value = false; root.deleteRecursively() }
    }

    @Test fun artistHeaderKeepsAlbumArtUntilLibraryRowsCanSupplyMatchEvidence() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "photo-header-readiness-${java.util.UUID.randomUUID()}")
        val consent = Consent().apply { allowed.value = true }
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val early = java.util.concurrent.atomic.AtomicBoolean(false)
        val calls = AtomicInteger()
        val bytes = ByteArrayOutputStream().also { bitmap(android.graphics.Color.RED).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val photos = ArtistPhotos(consent, ArtistPhotoDisk(File(root, "photos"), File(root, "choices"), 8L * 1024 * 1024), PhotoTransport { url, _ ->
            if (!ready.get()) early.set(true)
            calls.incrementAndGet()
            when {
                url.contains("/search/artist?") -> """{"data":[{"id":8,"name":"Fixture Artist","picture_big":"https://cdn-images.dzcdn.net/fixture.jpg"}]}""".toByteArray()
                url.contains("/top?") -> """{"data":[{"album":{"title":"Fixture Album"}}]}""".toByteArray()
                url.contains("cdn-images") -> bytes
                else -> """{"artists":[]}""".toByteArray()
            }
        })
        val artist = item()
        var result by mutableStateOf(io.github.anastaci1a.localica.browse.BrowseResult())
        val known = io.github.anastaci1a.localica.browse.BrowseResult(artist.songs.map { row ->
            BrowserItem(MediaRef(MediaKind.Song, row.track.id), row.track.title, row.track.artist, listOf(row), row = row)
        })
        val album = PlayerVisual(bitmap(android.graphics.Color.BLUE).asImageBitmap(), contentKey = "loading-album")
        try {
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalArtistPhotoEnvironment provides ArtistPhotoEnvironment(photos, consent)) {
                    Column {
                        val hero = rememberArtistCollectionVisual(artist.ref, artist.title, result, "album:fixture", album)
                        PlayerArtwork(hero, Modifier.size(100.dp).testTag("header-readiness"), androidx.compose.ui.graphics.RectangleShape,
                            androidx.compose.ui.layout.ContentScale.Crop, 240)
                        ActionButton({ ready.set(true); result = known }) { Text("Library rows ready") }
                    }
                }
            } }
            compose.waitForIdle()
            assertTrue("Loading library rows keep the album fallback", center("header-readiness").blue > .9f)
            assertEquals("An empty artist result cannot spend the retry window", 0, calls.get())
            compose.onNodeWithText("Library rows ready").performClick()
            compose.waitUntil(10000) { center("header-readiness").red > .98f }
            assertFalse("Provider matching must wait for local release evidence", early.get())
            assertEquals(3, calls.get())
        } finally { consent.allowed.value = false; root.deleteRecursively() }
    }

}
