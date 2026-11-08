package io.github.anastaci1a.localica

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.artistphoto.*
import io.github.anastaci1a.localica.browse.BrowserItem
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.network.OnlineMetadataConsent
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ArtistPhotoActionsTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun menuDismissalStillCommitsClearAndPickerCommitsTheChosenMatch() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "photo-actions-${java.util.UUID.randomUUID()}")
        val consent = OnlineMetadataConsent(File(root, "consent"))
        val disk = ArtistPhotoDisk(File(root, "photos"), File(root, "choices"), 8L * 1024 * 1024)
        val transport = PhotoTransport { url, _ -> when {
            url.contains("/search/artist?") -> """{"data":[{"id":8,"name":"Fixture Artist","picture_big":"https://cdn-images.dzcdn.net/fixture.jpg"}]}""".toByteArray()
            url.contains("/top?") -> """{"data":[{"album":{"title":"Fixture Album"}}]}""".toByteArray()
            else -> """{"artists":[]}""".toByteArray()
        } }
        val photos = ArtistPhotos(consent, disk, transport)
        val song = Track("fixture", "Song", "Fixture Artist", "Fixture Album", fileName = "fixture.mp3", relativePath = "fixture.mp3")
        val item = BrowserItem(MediaRef(MediaKind.Artist, "fixture"), "Fixture Artist", null, listOf(SongRow("fixture", song)))
        var open by mutableStateOf(false)
        try {
            runBlocking {
                consent.load(); consent.setAllowed(true)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bytes = ByteArrayOutputStream().also { output ->
                        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
                            .compress(Bitmap.CompressFormat.PNG, 100, output)
                    }.toByteArray()
                    disk.savePhoto("Artist:fixture", RemoteArtist(ArtistPhotoChoice(ArtistPhotoProvider.WIKIMEDIA, "Q8"),
                        "Fixture Artist", listOf("Fixture Album"), attribution = PhotoAttribution("Fixture Photographer", "Fixture credit",
                            "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/", "https://commons.wikimedia.org/wiki/File:Fixture.jpg")),
                        bytes, System.currentTimeMillis())
                }
            }
            compose.setContent { LocalicaTheme {
                CompositionLocalProvider(LocalArtistPhotoEnvironment provides ArtistPhotoEnvironment(photos, consent)) {
                    val anchor = rememberGlassAnchor()
                    Box(Modifier.size(200.dp)) {
                        ActionIcon(AppIcon.ActionEllipsis, "Artist actions", anchor = anchor) { open = true }
                        CollectionActions(open, anchor, item, PlaylistState(loading = false), null, null, emptyList(), {}, { open = false })
                    }
                }
            } }
            compose.onNodeWithContentDescription("Artist actions").performClick()
            compose.onNodeWithText("Artist photo information").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Fixture Photographer", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("CC BY 4.0", substring = true).assertExists()
            compose.onNodeWithText("https://commons.wikimedia.org/wiki/File:Fixture.jpg", substring = true).assertExists()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithContentDescription("Artist actions").performClick()
            compose.onNodeWithText("Clear artist photo").performScrollTo().performClick()
            compose.waitUntil(5000) { runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { disk.choice("Artist:fixture").mode == "cleared" } } }
            compose.onNodeWithContentDescription("Artist actions").performClick()
            compose.onNodeWithText("Choose artist photo").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Fixture Artist · deezer", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Fixture Artist · deezer", substring = true).performScrollTo().performClick()
            compose.onNodeWithText("Select").performClick()
            compose.waitUntil(5000) { runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { disk.choice("Artist:fixture").mode == "manual" } } }
            assertEquals(ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, "8"), disk.choice("Artist:fixture").choice)
        } finally { root.deleteRecursively() }
    }
}
