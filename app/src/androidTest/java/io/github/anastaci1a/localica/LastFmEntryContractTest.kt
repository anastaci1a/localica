package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.library.LibraryUiState
import io.github.anastaci1a.localica.ui.FolderSettings
import io.github.anastaci1a.localica.ui.LocalicaTheme
import org.junit.Rule
import org.junit.Test

/** Uses only the pre-#20 settings API, so the same witness can run against the parent. */
class LastFmEntryContractTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun settingsOfferTheExplicitReadOnlyLastFmEntry() {
        compose.setContent { LocalicaTheme { FolderSettings(LibraryUiState(loading = false), {}, {}, {}) } }
        compose.onNodeWithText("Last.fm").performScrollTo().assertIsDisplayed()
    }
}
