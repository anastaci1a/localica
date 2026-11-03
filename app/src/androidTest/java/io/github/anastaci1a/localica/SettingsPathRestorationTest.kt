package io.github.anastaci1a.localica

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsPathRestorationTest {
    @get:Rule val compose = createComposeRule()

    private fun restoreLegacyPath(category: String, aliases: Map<String, String>) {
        val restoration = StateRestorationTester(compose)
        var restoredPaths = 0
        restoration.setContent { LocalicaTheme {
            val registry = requireNotNull(LocalSaveableStateRegistry.current)
            val legacy = remember(registry) {
                object : SaveableStateRegistry by registry {
                    override fun consumeRestored(key: String): Any? {
                        val saved = registry.consumeRestored(key)
                        val path = (saved as? MutableState<*>)?.value as? List<*>
                        if (path != null && path.all { it is String } && path.any { it in aliases }) {
                            restoredPaths++
                            // Keep the real default MutableState/List saved representation,
                            // replacing only the title spellings an older app persisted.
                            return mutableStateOf(path.map { aliases[it] ?: it })
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
}
