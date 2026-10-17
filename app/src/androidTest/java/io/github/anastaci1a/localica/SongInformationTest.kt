package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackLocation
import io.github.anastaci1a.localica.library.FolderSelection
import io.github.anastaci1a.localica.library.SongRootName
import io.github.anastaci1a.localica.library.resolveSongRootNames
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SongInformationTest {
    @get:Rule val compose = createComposeRule()
    private val track = Track("file:///private/missing.mp3", "Information song", artist = "Artist", album = "Album",
        albumArtist = "Album artist", fileName = "missing.mp3", relativePath = "Album/missing.mp3",
        locations = listOf(TrackLocation("root", "file:///private/missing.mp3", "Album/missing.mp3")),
        comment = "Long comment ".repeat(100), durationMs = 123000, addedAt = 1000, modifiedAt = 2000)

    @Test fun artworkViewerReturnsToTheInformationSheet() {
        compose.setContent { LocalicaTheme { SongInformation(track, {}, {}) {} } }
        compose.onNode(SemanticsMatcher("View artwork action") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "View artwork"
        }).performClick()
        compose.onNodeWithContentDescription("Close artwork").assertIsDisplayed().performClick()
        compose.onNodeWithText("Song information").assertIsDisplayed()
        compose.onNodeWithText(track.uri).assertDoesNotExist()
        compose.onNodeWithText("Explore").assertDoesNotExist()
    }

    @Test fun entireNavigableRowOwnsOneActivationAndOnePulse() {
        var calls = 0
        var pulses = 0
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalControlHaptics provides ControlHaptics { pulses++ }) {
                Box(Modifier.width(320.dp)) {
                    SongInformationRow("Album", "An album", R.drawable.ic_albums, PlayerAction.Album) {
                        assertEquals(PlayerAction.Album, it); calls++
                    }
                }
            }
        } }
        val row = compose.onNodeWithTag("song-information-Album")
        listOf(.08f to .5f, .35f to .3f, .35f to .7f, .97f to .5f).forEach { (x, y) ->
            row.performTouchInput { click(Offset(width * x, height * y)) }
        }
        compose.runOnIdle { assertEquals(4, calls); assertEquals(4, pulses) }
    }

    @Test fun rootSnapshotChangesRefreshReadableLocationWithoutShowingUris() {
        var roots by mutableStateOf(listOf(FolderSelection("root", "Music")))
        compose.setContent { LocalicaTheme { SongInformation(track, {}, {}, folders = roots) {} } }
        fun assertLocation(expected: String) {
            // Root metadata resolves on IO and the retained popup receives content in a side effect.
            // Wait for the exact required value rather than assuming external work is Compose-idle.
            try {
                compose.waitUntil(5000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().size == 1 }
            } catch (failure: ComposeTimeoutException) {
                val actual = compose.onAllNodesWithTag("song-information-Location", useUnmergedTree = true)
                    .printToString(maxDepth = Int.MAX_VALUE)
                throw AssertionError("Expected Location '$expected'; actual semantics:\n$actual", failure)
            }
            compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(track.uri).assertDoesNotExist()
        }
        assertLocation("Album/missing.mp3")
        compose.runOnIdle { roots = roots + FolderSelection("other-root", "Downloads") }
        assertLocation("Music/Album/missing.mp3")
        compose.runOnIdle { roots = roots.map { if (it.uri == "root") it.copy(name = "Renamed") else it } }
        assertLocation("Renamed/Album/missing.mp3")
        compose.runOnIdle { roots = roots.filter { it.uri == "root" } }
        assertLocation("Album/missing.mp3")
        compose.runOnIdle { roots = listOf(FolderSelection("other-root", "Downloads")) }
        assertLocation("Album/missing.mp3")
        compose.waitUntil(5000) {
            compose.onAllNodes(hasTestTag("song-information-Location") and hasClickAction()).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("song-information-Location").assertHasNoClickAction()
    }

    @Test fun unavailableProviderKeepsCachedDisplayNameAndNeverParsesOpaqueIds() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = "content://io.github.anastaci1a.localica.unavailable/tree/opaque%3A123%2Fsecret-parent"
        val resolved = resolveSongRootNames(context, listOf(FolderSelection(uri, "Cached music")))
        assertEquals(listOf("Cached music"), resolved.single().components)
        assertNull(resolved.single().provider)
    }

    @Test fun retiredRootResolutionCannotOverwriteNewRootSnapshot() {
        val oldResponse = CompletableDeferred<Unit>()
        val started = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        var roots by mutableStateOf(listOf(FolderSelection("root", "Old"), FolderSelection("other", "Other")))
        val resolver: suspend (android.content.Context, List<FolderSelection>) -> List<SongRootName> = { _, request ->
            if (request.first().name == "Old") {
                started.set(true)
                withContext(NonCancellable) { oldResponse.await() }
                finished.set(true)
            }
            request.map { SongRootName(it.uri, listOf("Verified " + it.name)) }
        }
        compose.setContent { LocalicaTheme {
            SongInformation(track, {}, {}, folders = roots, rootNameResolver = resolver) {}
        } }
        try {
            compose.waitUntil(5000) { started.get() }
            compose.runOnIdle { roots = listOf(FolderSelection("root", "New"), FolderSelection("other", "Other")) }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Verified New/Album/missing.mp3").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { oldResponse.complete(Unit) }
            compose.waitUntil(5000) { finished.get() }
            compose.onNodeWithText("Verified New/Album/missing.mp3").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Verified Old/Album/missing.mp3").assertDoesNotExist()
        } finally {
            oldResponse.complete(Unit)
        }
    }

    @Test fun metadataRowsKeepTheirExistingDestinations() {
        val actions = mutableListOf<PlayerAction>()
        compose.setContent { LocalicaTheme { SongInformation(track, {}, {}, folders = listOf(FolderSelection("root", "Music"))) { actions += it } } }
        listOf("Location" to PlayerAction.Folder, "Album" to PlayerAction.Album,
            "Artist" to PlayerAction.Artist, "Album Artist" to PlayerAction.AlbumArtist).forEach { (label, action) ->
            compose.onNodeWithTag("song-information-$label").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(action, actions.last()) }
        }
        compose.runOnIdle { assertEquals(4, actions.size) }
    }

    @Test fun footerRemainsVisibleAndFinalRowClearsIt() {
        var actions = 0
        compose.setContent { LocalicaTheme { SongInformation(track, {}, {}) { actions++ } } }
        val before = compose.onNodeWithText("Done").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("song-information-body").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, actions) }
        compose.onNodeWithTag("song-information-Qualified plays").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Edit tags").assertIsDisplayed()
        val after = compose.onNodeWithText("Done").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        assertTrue(compose.onNodeWithTag("song-information-Qualified plays").fetchSemanticsNode().boundsInRoot.bottom <= after.top)
    }

    @Test fun sparseTagsHaveNoFalseDestinationsAndLargeRtlTextWraps() {
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                SongInformation(track.copy(artist = null, album = null, albumArtist = null, comment = null), {}, {}) {}
            }
        } }
        compose.onNodeWithTag("song-information-Artist").assertDoesNotExist()
        compose.onNodeWithTag("song-information-Album").assertDoesNotExist()
        compose.onNodeWithTag("song-information-Title").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithTag("song-information-Qualified plays").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
    }
}
