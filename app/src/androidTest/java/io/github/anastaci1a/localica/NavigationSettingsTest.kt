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
            fun navigationSettings() {
                compose.onNodeWithContentDescription("More").performClick()
                compose.onNodeWithText("Settings", substring = false).performClick()
                compose.onNodeWithText("Interface", substring = false).performClick()
                compose.onNodeWithText("Navigation", substring = false).performClick()
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
            compose.onNodeWithContentDescription("Close settings").performClick()
            selected(MainTab.Library)
            navigationSettings()
            compose.onAllNodesWithText("Remove")[2].performScrollTo().performClick()
            assertEquals(listOf(MainTab.Home, MainTab.Albums, MainTab.Songs, MainTab.Queues), preferences.read().tabs)
            compose.onNodeWithContentDescription("Close settings").performClick()
            selected(MainTab.Home)
            navigationSettings()
            compose.onNodeWithText("Reset main tabs").performScrollTo().performClick()
            assertEquals(InterfaceSettings().tabs, preferences.read().tabs)
            compose.onNodeWithContentDescription("Close settings").performClick()
            compose.onNodeWithContentDescription("Home").performClick()
            compose.onNodeWithText("Your music").assertIsDisplayed()
        } finally { preferences.write(previous) }
    }
}
