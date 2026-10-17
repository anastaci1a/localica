package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.library.FolderSelection
import io.github.anastaci1a.localica.library.LibrarySnapshot
import io.github.anastaci1a.localica.ui.FolderSettings
import io.github.anastaci1a.localica.ui.LibraryUiState
import io.github.anastaci1a.localica.ui.LocalicaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FolderLabelsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun duplicateBasenamesRetainExactFolderIdentityThroughOptionsAndConfirmation() {
        var removed: String? = null
        // Synthetic state and callback only: no repository, provider, or user data is changed.
        val folders = listOf(FolderSelection("synthetic-b", "Media"), FolderSelection("synthetic-a", "Media"))
        compose.setContent { LocalicaTheme {
            FolderSettings(LibraryUiState(snapshot = LibrarySnapshot(folders), loading = false),
                {}, {}, { removed = it }, initialCategory = "Folders to scan")
        } }
        compose.onNodeWithText("Media (1/2)").assertIsDisplayed()
        compose.onNodeWithText("Media (2/2)").performClick()
        compose.onNodeWithText("Media (2/2)").assertIsDisplayed()
        compose.onNodeWithText("Remove folder").performScrollTo().performClick()
        compose.onNodeWithText("Remove Media (2/2)?").assertIsDisplayed()
        compose.runOnIdle { assertNull(removed) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(removed) }
        compose.onNodeWithText("Remove folder").performScrollTo().performClick()
        compose.onAllNodesWithText("Remove").onLast().performClick()
        compose.runOnIdle { assertEquals("synthetic-b", removed) }
    }

    @Test fun rootSetChangesRecomputeFractionsAndUniqueNamesStayPlain() {
        var folders by mutableStateOf(listOf(FolderSelection("synthetic-a", "Media"), FolderSelection("synthetic-b", "Media")))
        compose.setContent { LocalicaTheme {
            FolderSettings(LibraryUiState(snapshot = LibrarySnapshot(folders), loading = false),
                {}, {}, {}, initialCategory = "Folders to scan")
        } }
        compose.onNodeWithText("Media (1/2)").assertIsDisplayed()
        compose.runOnIdle { folders = folders.reversed() }
        compose.onNodeWithText("Media (2/2)").assertIsDisplayed()
        compose.runOnIdle { folders = folders.take(1) }
        compose.onNodeWithText("Media").assertIsDisplayed()
        compose.onNodeWithText("Media (1/2)").assertDoesNotExist()
    }
}
