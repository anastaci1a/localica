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
        compose.onNodeWithText("Scanner").performClick()
        compose.onNodeWithText("Search settings").assertDoesNotExist()
        compose.onNodeWithText("Folders to scan").assertDoesNotExist()
        compose.onNodeWithText("Metadata read failures").performScrollTo().performClick()
        compose.onNodeWithText("No metadata read failures.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Scanner").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Library and tags").assertIsDisplayed()
        compose.onNodeWithText("Folders to scan").performClick()
        compose.onNodeWithText("Choose folder").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("scanner")
    }
    @Test fun quickScanIsSearchableAndKeepsTheExistingOptInPreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.getSharedPreferences("scanner", 0)
        val keys = listOf("on-launch", "full-on-launch")
        val previous = keys.associateWith { if (store.contains(it)) store.getBoolean(it, false) else null }
        val preferences = io.github.anastaci1a.localica.scanner.ScanPreferences(context)
        val originalOptions = preferences.read("")
        try {
            store.edit().remove("on-launch").remove("full-on-launch").commit()
            assertFalse(preferences.scanOnLaunch)
            assertFalse(preferences.fullScanOnLaunch)
            compose.setContent { LocalicaTheme { FolderSettings(LibraryUiState(loading = false), {}, {}, {}) } }
            compose.onNode(hasSetTextAction()).performTextInput("Quick scan")
            compose.onNodeWithText("Scanner").performClick()
            val quick = hasText("Quick scan") and isToggleable()
            val full = hasText("Full scan") and isToggleable()
            compose.onNode(quick).performScrollTo().performClick()
            compose.onNode(full).performScrollTo().performClick()
            compose.runOnIdle {
                assertTrue(preferences.scanOnLaunch)
                assertTrue(preferences.fullScanOnLaunch)
                assertEquals(originalOptions, preferences.read(""))
            }
            compose.onNodeWithText("Enable both", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNode(quick).performScrollTo().performClick()
            compose.runOnIdle { assertFalse(preferences.scanOnLaunch); assertTrue(preferences.fullScanOnLaunch) }
            compose.onNodeWithContentDescription("Back from settings").performClick()
            compose.onNodeWithText("Scanner").performClick()
            compose.onNode(quick).performScrollTo().assertIsOff()
            compose.onNode(full).performScrollTo().assertIsOn()
            compose.runOnIdle { assertEquals(originalOptions, preferences.read("")) }
        } finally {
            val edit = store.edit()
            previous.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putBoolean(key, value) }
            edit.commit()
        }
    }
    @Test fun scannerShortcutSystemBackReturnsToCallerButHeaderBackVisitsParent() {
        var dismissed = 0
        compose.setContent { LocalicaTheme {
            FolderSettings(LibraryUiState(loading = false), { dismissed++ }, {}, {}, initialCategory = "Scanner")
        } }
        compose.onNodeWithText("Metadata read failures").performScrollTo().performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Scanner").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismissed) }
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Library and tags").assertIsDisplayed()
        compose.onNodeWithText("Scanner").performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }
    @Test fun scannerPopupKeepsItsSizeAndSeparatesRepeatableQuickAndFullActions() {
        val state = androidx.compose.runtime.mutableStateOf(LibraryUiState(
            snapshot = io.github.anastaci1a.localica.library.LibrarySnapshot(folders = listOf(
                io.github.anastaci1a.localica.library.FolderSelection("content://music", "Music"))), loading = false))
        var quick = 0
        var full = 0
        compose.setContent { LocalicaTheme { ScannerPopup(state.value, {}, { full++ }, {}, { quick++ }) {} } }
        compose.onNodeWithText("Quick scan").performScrollTo().performClick()
        compose.onNodeWithText("Full scan").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, quick); assertEquals(1, full) }
        val idleBounds = compose.onNodeWithTag("scanner-body").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(scanning = true, scannedCount = 123,
            scanStatus = io.github.anastaci1a.localica.scanner.ScanState(running = true, totalFolders = 2)) }
        compose.onNodeWithText("Full scan").assertIsNotEnabled()
        compose.onNodeWithText("Quick scan").assertIsNotEnabled()
        assertEquals(idleBounds, compose.onNodeWithTag("scanner-body").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state.value = state.value.copy(scanning = false,
            scanStatus = io.github.anastaci1a.localica.scanner.ScanState(message = "Scan complete.")) }
        compose.onNodeWithText("Quick scan").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, quick); assertEquals(1, full) }
        assertEquals(idleBounds, compose.onNodeWithTag("scanner-body").fetchSemanticsNode().boundsInRoot)
    }
    @Test fun playlistViewChoicesPersistAndOverrideOnlyTheirCollection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = BrowsePreferences(context)
        context.getSharedPreferences("browse-style", 0).edit().clear().commit()
        val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, "playlist-a"), "Playlist A")
        var saved: BrowseStyle? = null
        try {
            compose.setContent { LocalicaTheme { BrowseSortDialog(route, prefs.read(route), false, {}, { style, override ->
                prefs.write(route, style, override); saved = style
            }, {}, presentationOnly = true) } }
            compose.onNodeWithText("Extra information").performClick()
            // This is the tenth option: scroll it clear of the dialog footer on
            // shorter viewports before injecting an actual touch.
            compose.onNodeWithText("Album Artist").performScrollTo().performClick()
            compose.onNodeWithText("Select", substring = false).performClick()
            // Dialog windows finish removal asynchronously on Android 16. Wait for the old
            // radio group to close before opening another with overlapping option labels.
            compose.waitUntil(5000) { compose.onAllNodes(hasText("Album Artist") and
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                    androidx.compose.ui.semantics.Role.RadioButton)).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Quick-search box").performScrollTo().assertExists()
            compose.onNodeWithText("Save only for this list").performClick()
            compose.onNodeWithText("Save").performClick()
            compose.runOnIdle {
                assertEquals(saved, BrowsePreferences(context).read(route))
                assertEquals(ExtraInfo.AlbumArtist, saved?.extraInfo)
                assertTrue(prefs.overridden(route))
                assertEquals(ExtraInfo.Automatic, prefs.read(route.copy(collection = MediaRef(MediaKind.Playlist, "playlist-b"))).extraInfo)
                prefs.reset(route)
                assertFalse(prefs.overridden(route))
            }
        } finally { context.getSharedPreferences("browse-style", 0).edit().clear().commit() }
    }
}

