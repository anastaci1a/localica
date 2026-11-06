package io.github.anastaci1a.localica

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsPathRestorationTest {
    @get:Rule val compose = createComposeRule()

    private fun restoreLegacyPath(category: String, aliases: Map<String, String> = emptyMap(),
        legacyPath: (List<String>) -> List<String> = { it }) {
        val restoration = StateRestorationTester(compose)
        var restoredPaths = 0
        restoration.setContent { LocalicaTheme {
            val registry = requireNotNull(LocalSaveableStateRegistry.current)
            val legacy = remember(registry) {
                object : SaveableStateRegistry by registry {
                    override fun consumeRestored(key: String): Any? {
                        val saved = registry.consumeRestored(key)
                        val path = (saved as? MutableState<*>)?.value as? List<*>
                        if (path != null && path.all { it is String }) {
                            val titles = path.filterIsInstance<String>()
                            val migrated = legacyPath(titles.map { aliases[it] ?: it })
                            if (migrated == titles) return saved
                            restoredPaths++
                            // Keep the real default MutableState/List saved representation,
                            // Recreate the titles and ancestors an older app persisted.
                            return mutableStateOf(migrated)
                        }
                        return saved
                    }
                }
            }
            CompositionLocalProvider(LocalSaveableStateRegistry provides legacy) {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = category,
                    interfaceContent = { Text("Interface destination: $it") })
            }
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, restoredPaths) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals("The saved path actually contained a legacy title", 1, restoredPaths) }
    }

    @Test fun returningToRootRemainsAtRootAfterRestoreWithAnInitialCategory() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LocalicaTheme {
            FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = "Scanner")
        } }
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Settings", substring = false).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Settings", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Search settings").assertExists()
    }

    @Test fun legacyScannerPageRestoresItsControlsAndCanonicalParent() {
        restoreLegacyPath("Scanner", mapOf("Scanner" to "Scanner configuration"))
        compose.onNodeWithText("Scanner", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Include subfolders").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Exclude short files").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Library and tags", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Scanner", substring = false).assertHasClickAction()
    }

    @Test fun legacyScannerAncestorRestoresMetadataPageAndBackDestination() {
        restoreLegacyPath("Metadata read failures", mapOf("Scanner" to "Scanner configuration"))
        compose.onNodeWithText("Metadata read failures", substring = false).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Scanner", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Include subfolders").performScrollTo().assertIsDisplayed()
    }

    @Test fun legacyInterfaceAncestorRestoresNestedPageAndBackDestination() {
        restoreLegacyPath("Navigation", mapOf("Interface" to "Interface and gestures"))
        compose.onNodeWithText("Interface destination: Navigation").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Interface", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Navigation", substring = false).assertHasClickAction()
    }

    @Test fun legacyRootIconPacksRestoresAndBackReturnsToInterface() {
        restoreLegacyPath("Icon packs", legacyPath = { it.drop(1) })
        compose.onNodeWithText("Icon packs", substring = false).assertIsDisplayed()
        compose.onAllNodesWithText("Built-in icons", substring = false)[0].assertIsDisplayed()
        androidx.test.uiautomator.UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Interface", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Icon packs", substring = false).assertHasClickAction()
    }

    @Test fun legacyRootPackViewerRestoresAndBackVisitsBothParents() {
        restoreLegacyPath("Pack icons", legacyPath = { it.drop(1) })
        compose.onNodeWithText("Pack icons", substring = false).assertIsDisplayed()
        compose.onNodeWithTag("pack-icon-tab.home").assertExists()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Icon packs", substring = false).assertIsDisplayed()
        compose.onAllNodesWithText("Built-in icons", substring = false)[0].assertIsDisplayed()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Interface", substring = false).assertIsDisplayed()
    }

    @Test fun iconPacksOpensUnderInterfaceAndSearchKeepsItsParentAfterRestore() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LocalicaTheme {
            FolderSettings(LibraryUiState(loading = false), {}, {}, {})
        } }
        compose.onNodeWithText("Icon packs", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Interface", substring = false).performClick()
        compose.onNodeWithText("Icon packs", substring = false).performScrollTo().performClick()
        compose.onAllNodesWithText("Built-in icons", substring = false)[0].assertIsDisplayed()
        compose.onAllNodesWithText("View icons", substring = false)[0].performScrollTo().performClick()
        compose.onNodeWithText("Pack icons", substring = false).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("pack-icon-tab.home").assertExists()
        repeat(3) { compose.onNodeWithContentDescription("Back from settings").performClick() }
        compose.onNodeWithText("Search settings").performTextInput("Icon packs")
        compose.onNodeWithText("Icon packs", substring = false).performClick()
        compose.onNodeWithContentDescription("Back from settings").performClick()
        compose.onNodeWithText("Interface", substring = false).assertIsDisplayed()
    }
}
