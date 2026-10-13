package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsOptionsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun settingsSearchIsRootOnlyAndScannerLinksReachSharedFolderPage() {
        compose.setContent { LocalicaTheme { FolderSettings(LibraryUiState(loading = false), {}, {}, {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("scanner")
        compose.onNodeWithText("Scanner configuration").performClick()
        compose.onNodeWithText("Search settings").assertDoesNotExist()
        compose.onNodeWithText("Scan now").assertDoesNotExist()
        compose.onNodeWithText("Folders to scan").performClick()
        compose.onNodeWithText("Choose folder").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Scanner configuration").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Library and tags").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("scanner")
    }
    @Test fun albumViewChoicesPersistAndOverrideOnlyTheirCollection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = BrowsePreferences(context)
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        val route = BrowseRoute(MainTab.Albums, MediaRef(MediaKind.Album, "album-a"), "Album A")
        var saved: BrowseStyle? = null
        try {
            compose.setContent { LocalicaTheme { BrowseSortDialog(route, prefs.read(route), false, {}, { style, override ->
                prefs.write(route, style, override); saved = style
            }, {}, presentationOnly = true) } }
            compose.onNodeWithText("Extra information").performClick()
            // This is the tenth option: scroll it clear of the dialog footer on
            // shorter viewports before injecting an actual touch.
            compose.onNodeWithText("Album Artist").performScrollTo().performClick()
            // Dialog windows finish removal asynchronously on Android 16. Wait for the old
            // radio group to close before opening another with overlapping option labels.
            compose.waitUntil(5000) { compose.onAllNodes(hasText("Album Artist") and
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                    androidx.compose.ui.semantics.Role.RadioButton)).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Group album songs").performClick()
            compose.onNodeWithText("Artist").performClick()
            compose.onNodeWithText("Quick-search box").assertDoesNotExist()
            compose.onNodeWithText("Only for this collection").performScrollTo().performClick()
            compose.onNodeWithText("Save").performClick()
            compose.runOnIdle {
                assertEquals(saved, BrowsePreferences(context).read(route))
                assertEquals(SongGrouping.Artist, saved?.songGrouping)
                assertEquals(ExtraInfo.AlbumArtist, saved?.extraInfo)
                assertTrue(prefs.overridden(route))
                assertEquals(SongGrouping.None, prefs.read(route.copy(collection = MediaRef(MediaKind.Album, "album-b"))).songGrouping)
                prefs.reset(route)
                assertFalse(prefs.overridden(route))
            }
        } finally { context.getSharedPreferences("browse-style", 0).edit().clear().commit() }
    }
}
