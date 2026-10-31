package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.anastaci1a.localica.library.FolderSelection
import io.github.anastaci1a.localica.library.LibrarySnapshot
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
    private fun assertAnchoredPopup(host: GlassDialogs, title: String, editBeforeCancel: () -> Unit = {}) {
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.waitUntil(5000) {
            host.entries.lastOrNull { it.mounted && it.placement != GlassPlacement.Page }?.anchor?.let {
                it.triggerMounted && it.hidden && it.bounds.width > 0f && it.bounds.height > 0f && it.face != null
            } == true
        }
        val origin = host.entries.last { it.mounted && it.placement != GlassPlacement.Page }.anchor
        editBeforeCancel()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        compose.waitUntil(5000) { !origin.hidden && !origin.modalPresent }
        assertTrue("Cancellation restores the same physical settings source",origin.triggerMounted)
    }

    @Test fun addMainTabUsesItsSettingsLinkAsTheRetainedPopupOrigin() {
        lateinit var host: GlassDialogs
        compose.setContent { LocalicaTheme {
            host = requireNotNull(LocalGlassDialogs.current)
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    InterfaceSettingsControls(InterfaceSettings(tabs=listOf(MainTab.Home)),onChange={ fail("Cancelled choice must not save") })
                }
            }
        } }
        repeat(2) { assertAnchoredPopup(host,"Add main tab") }
    }

    @Test fun addLibraryFilterUsesItsSettingsLinkAsTheRetainedPopupOrigin() {
        lateinit var host: GlassDialogs
        compose.setContent { LocalicaTheme {
            host = requireNotNull(LocalGlassDialogs.current)
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    LibraryFilterSettings(LibraryFilters(visible=emptyList())) { fail("Cancelled choice must not save") }
                }
            }
        } }
        repeat(2) { assertAnchoredPopup(host,"Add library filter") }
    }

    @Test fun scannerDurationDialogUsesItsRealSettingsOrigin() {
        val preferences = io.github.anastaci1a.localica.scanner.ScanPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val originalOptions = preferences.read("")
        val originalLaunchFlags = preferences.scanOnLaunch to preferences.fullScanOnLaunch
        lateinit var host: GlassDialogs
        compose.setContent { LocalicaTheme {
            host = requireNotNull(LocalGlassDialogs.current)
            CompositionLocalProvider(LocalReducedMotion provides true) {
                FolderSettings(LibraryUiState(loading=false),{},{},{},initialCategory="Scanner")
            }
        } }
        repeat(2) {
            assertAnchoredPopup(host, "Exclude short files") {
                compose.onNode(hasSetTextAction()).performTextReplacement("17")
            }
            compose.runOnIdle {
                assertEquals("Cancelling an edited duration preserves scanner rules", originalOptions, preferences.read(""))
                assertEquals(originalLaunchFlags, preferences.scanOnLaunch to preferences.fullScanOnLaunch)
            }
        }
    }

    @Test fun folderExclusionsAndRemovalConfirmationHaveIndependentRestorableOrigins() {
        val root = "content://synthetic-origin"
        val preferences = io.github.anastaci1a.localica.scanner.ScanPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val originalOptions = preferences.read(root)
        lateinit var host: GlassDialogs
        compose.setContent { LocalicaTheme {
            host = requireNotNull(LocalGlassDialogs.current)
            CompositionLocalProvider(LocalReducedMotion provides true) {
                FolderSettings(LibraryUiState(LibrarySnapshot(folders=listOf(FolderSelection("content://synthetic-origin","Synthetic origin"))),loading=false),
                    {},{}, { fail("Cancelled removal cannot remove the folder") }, initialCategory="Folders to scan")
            }
        } }
        compose.onNodeWithText("Synthetic origin").performScrollTo().performClick()
        repeat(2) {
            assertAnchoredPopup(host, "Excluded subfolders") {
                compose.onNode(hasSetTextAction()).performTextReplacement("Synthetic/Cancelled")
            }
            assertAnchoredPopup(host, "Remove folder")
            compose.runOnIdle { assertEquals("Cancellation preserves the folder scan configuration", originalOptions, preferences.read(root)) }
        }
    }

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
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.onNodeWithText("Scanner").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismissed) }
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Library and tags").assertIsDisplayed()
        compose.onNodeWithText("Scanner").performClick()
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
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
    @Test fun scannerPopupFollowsLiveCountWhileLibraryStateHoldsItsMilestone() {
        val state = LibraryUiState(snapshot = io.github.anastaci1a.localica.library.LibrarySnapshot(folders = listOf(
            io.github.anastaci1a.localica.library.FolderSelection("content://music", "Music"))), loading = false,
            scanning = true, scannedCount = 0,
            scanStatus = io.github.anastaci1a.localica.scanner.ScanState(running = true, totalFolders = 2))
        val progress = kotlinx.coroutines.flow.MutableStateFlow(io.github.anastaci1a.localica.scanner.ScanState(
            running = true, totalFolders = 2, count = 25))
        var popupCompositions = 0
        compose.setContent { LocalicaTheme {
            SideEffect { popupCompositions++ }
            ScannerPopup(state, {}, {}, {}, {}, progress) {}
        } }
        compose.onNodeWithText("25 files checked · 0/2 folders").assertExists()
        val before = compose.runOnIdle { popupCompositions }
        for (count in listOf(50, 75, 100)) {
            compose.runOnIdle { progress.value = progress.value.copy(count = count) }
            compose.onNodeWithText("$count files checked · 0/2 folders").assertExists()
        }
        compose.runOnIdle { assertEquals("Live counts must not recompose the popup owner", before, popupCompositions) }
        // An idle live source defers to the published library state.
        compose.runOnIdle { progress.value = progress.value.copy(running = false) }
        compose.onNodeWithText("0 files checked · 0/2 folders").assertExists()
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


