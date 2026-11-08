package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.artistphoto.ArtistPhotos
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.network.OnlineMetadataConsent
import io.github.anastaci1a.localica.ui.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OnlineFeaturesSearchTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun deepSearchNavigatesToPhotoAndLastFmControlsAndHighlightsThem() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "online-search-${java.util.UUID.randomUUID()}")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LocalicaApplication
        val consent = OnlineMetadataConsent(File(root, "consent"))
        val photos = ArtistPhotos(consent, File(root, "photos"), File(root, "choices"))
        try {
            compose.setContent { LocalicaTheme {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {},
                    onlineFeaturesContent = { OnlineFeaturesSettings(consent) },
                    lastFmContent = { LastFmSettings(app.lastfm) },
                    artistPhotosContent = { ArtistPhotosSettings(photos, consent, LibrarySnapshot()) })
            } }
            compose.onNodeWithText("Last.fm").assertDoesNotExist()
            compose.onNodeWithContentDescription("Search settings input").performTextReplacement("Clear photo cache")
            compose.onNodeWithTag("search-result:Clear photo cache").performClick()
            compose.onNodeWithTag("setting:Clear photo cache").assertIsDisplayed()
            compose.onNodeWithTag("setting-highlight:Clear photo cache", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("Refresh all photos").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithContentDescription("Search settings input").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back from settings").performClick()
            compose.onNodeWithContentDescription("Search settings input").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back from settings").performClick()
            compose.onNodeWithContentDescription("Search settings input").performTextReplacement("Last.fm username")
            compose.onNodeWithTag("search-result:Last.fm username").performClick()
            compose.onNodeWithTag("setting:Last.fm username").assertIsDisplayed()
            compose.onNodeWithTag("setting-highlight:Last.fm username", useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription("Search settings input").assertDoesNotExist()
        } finally { root.deleteRecursively() }
    }
}
