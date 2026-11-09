package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.ui.LibraryUiState
import io.github.anastaci1a.localica.ui.FolderSettings
import io.github.anastaci1a.localica.ui.LocalicaTheme
import org.junit.Rule
import org.junit.Test

/** Online services share one explicit privacy parent. */
class LastFmEntryContractTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun settingsOfferTheExplicitReadOnlyLastFmEntry() {
        compose.setContent { LocalicaTheme { FolderSettings(LibraryUiState(loading = false), {}, {}, {}) } }
        compose.onNodeWithText("Last.fm").assertDoesNotExist()
        compose.onNodeWithText("Online features").performScrollTo().performClick()
        compose.onNodeWithText("Last.fm").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Artist photos").assertExists()
    }
}
