package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playback.PlaybackState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NavigationSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun addingOverflowTabsReorderingAndRemovingTheActiveTabKeepsNavigationUsable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = InterfacePreferences(context)
        val previous = preferences.read()
        try {
            preferences.write(InterfaceSettings(reducedMotion = true))
            compose.setContent { LocalicaTheme {
                LocalicaScreen(LibraryUiState(LibrarySnapshot(), loading = false),
                    PlaybackState(connected = true), {}, {}, {}, {}, {}, {})
            } }
            fun awaitDestination(label: String, matcher: SemanticsMatcher) {
                // Dialog mounting and native-window handoff can outlive Compose idleness.
                // Wait for the destination, never repeat the tap that requested it.
                try {
                    compose.waitUntil(5_000) {
                        compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 &&
                            runCatching { compose.onNode(matcher).assertIsDisplayed() }.isSuccess
                    }
                } catch (failure: Throwable) {
                    runCatching {
                        compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
                            compose.onAllNodes(isRoot())[index].printToLog("NavigationSettings:$label")
                        }
                    }
                    throw failure
                }
            }
            fun closeSettings() {
                compose.onNodeWithContentDescription("Close settings").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithContentDescription("Close settings").fetchSemanticsNodes().isEmpty()
                }
            }
            fun navigationSettings() {
                compose.onNodeWithContentDescription("More").performClick()
                awaitDestination("More menu", hasText("Settings", substring = false) and hasClickAction())
                compose.onNodeWithText("Settings", substring = false).performClick()
                awaitDestination("Settings root", hasText("Interface", substring = false) and hasClickAction())
                compose.onNodeWithText("Interface", substring = false).performClick()
                awaitDestination("Interface", hasText("Navigation", substring = false) and hasClickAction())
                compose.onNodeWithText("Navigation", substring = false).performClick()
                awaitDestination("Navigation", hasText("Back button", substring = false))
            }
            fun selected(tab: MainTab) = compose.onNode(isSelected() and hasText(tab.label) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertIsDisplayed()
            compose.onNodeWithContentDescription("Library").performClick()
            navigationSettings()
            val added = listOf(MainTab.Albums, MainTab.Songs, MainTab.Queues)
            added.forEach { tab ->
                compose.onNodeWithText("Add main tab").performScrollTo().performClick()
                compose.onNode(hasText(tab.label, substring = false) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performScrollTo().performClick()
                compose.onNodeWithText("Select", substring = false).performClick()
                compose.waitForIdle()
                assertTrue(tab in preferences.read().tabs)
            }
            compose.onAllNodesWithText("Move up")[2].performScrollTo().performClick()
            assertEquals(listOf(MainTab.Home, MainTab.Albums, MainTab.Library, MainTab.Songs, MainTab.Queues), preferences.read().tabs)
            closeSettings()
            selected(MainTab.Library)
            navigationSettings()
            compose.onAllNodesWithText("Remove")[2].performScrollTo().performClick()
            assertEquals(listOf(MainTab.Home, MainTab.Albums, MainTab.Songs, MainTab.Queues), preferences.read().tabs)
            closeSettings()
            selected(MainTab.Home)
            navigationSettings()
            compose.onNodeWithText("Reset main tabs").performScrollTo().performClick()
            assertEquals(InterfaceSettings().tabs, preferences.read().tabs)
            closeSettings()
            compose.onNodeWithContentDescription("Home").performClick()
            compose.onNodeWithText("Your music").assertIsDisplayed()
        } finally { preferences.write(previous) }
    }
}
