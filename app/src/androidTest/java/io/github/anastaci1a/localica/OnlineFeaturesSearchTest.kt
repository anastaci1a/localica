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
    @Test fun deepSearchNavigatesToTheArtistPhotoControlAndHighlightsIt() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "online-search-${java.util.UUID.randomUUID()}")
        val consent = OnlineMetadataConsent(File(root, "consent"))
        val photos = ArtistPhotos(consent, File(root, "photos"), File(root, "choices"))
        try {
            compose.setContent { LocalicaTheme {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {},
                    onlineFeaturesContent = { OnlineFeaturesSettings(consent) },
                    artistPhotosContent = { ArtistPhotosSettings(photos, consent, LibrarySnapshot()) })
            } }
            compose.onNodeWithText("Last.fm").assertDoesNotExist()
            compose.onNodeWithContentDescription("Search settings input").performTextReplacement("Clear photo cache")
            compose.onNodeWithTag("search-result:Clear photo cache").performClick()
            compose.onNodeWithTag("setting:Clear photo cache").assertIsDisplayed()
            compose.onNodeWithTag("setting-highlight:Clear photo cache", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("Refresh all photos").performScrollTo().assertIsNotEnabled()
        } finally { root.deleteRecursively() }
    }
}
