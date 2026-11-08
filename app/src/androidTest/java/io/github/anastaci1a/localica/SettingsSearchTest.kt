package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.queue.QueueSettings
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsSearchTest {
    @get:Rule val compose = localicaComposeRule()

    private fun show(initial: String? = null, glass: GlassAppearance = GlassAppearance()) {
        compose.setContent { LocalicaTheme {
            var settings by remember { mutableStateOf(InterfaceSettings(glass = glass)) }
            CompositionLocalProvider(LocalGlassAppearance provides settings.glass) {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = initial,
                    interfaceContent = { section -> InterfaceSettingsControls(settings, section) { settings = it } },
                    playbackContent = { section -> QueueSettingsControls(QueueSettings(), section) {} })
            }
        } }
    }

    @Test fun deepSearchFindsOptionsAndValuesFromEveryPage() {
        show("Player")
        for ((query, result) in listOf("Lift shadow" to "Lift shadow strength", "Carousel" to "Player artwork",
            "Preload" to "Preload next track", "Shape damping" to "Shape damping", "grey" to "Interface gray",
            "minimum duration" to "Exclude short files", "sync" to "Lyrics timing correction")) {
            compose.onNodeWithText("Search settings").performTextReplacement(query)
            compose.onNodeWithTag("search-result:$result").assertExists()
        }
    }

    @Test fun resultNavigatesScrollsAndHighlightsTheActualRow() {
        show()
        compose.onNodeWithText("Search settings").performTextInput("Control expansion")
        compose.onNodeWithTag("search-result:Control expansion").performClick()
        compose.onNodeWithTag("setting:Control expansion").assertIsDisplayed()
        compose.onNodeWithTag("setting-highlight:Control expansion", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Search settings").assertExists()
    }

    @Test fun hiddenOptionJumpsToItsRevealingChoice() {
        show()
        compose.onNodeWithText("Search settings").performTextInput("Gray pill opacity")
        compose.onNodeWithTag("search-result:Gray pill opacity").performClick()
        compose.onNodeWithTag("setting:Selector style").assertIsDisplayed()
        compose.onNodeWithTag("setting-highlight:Selector style", useUnmergedTree = true).assertExists()
    }

    @Test fun noneKeepsGlassSubmenusVisibleAndDisabled() {
        show("Glass", GlassAppearance(renderer = GlassRendererMode.None))
        compose.onNodeWithText("Glass appearance", substring = false).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Glass advanced", substring = false).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Navigation selector", substring = false).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Glass renderer").assertIsEnabled()
    }

    @Test fun existingPreferenceKeysAndValuesSurviveOpeningTheReworkedPages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.getSharedPreferences("interface", 0)
        val before = store.all.toMap()
        try {
            assertTrue(store.edit().putFloat("glass-blur", .65f).putFloat("glass-depth", .9f)
                .putString("glass-renderer", "None").putInt("lyric-delay-ms", -750)
                .putBoolean("expand-on-play", false).commit())
            val seeded = store.all.toMap()
            val restored = InterfacePreferences(context).read()
            assertEquals(.65f, restored.glass.blur)
            assertEquals(-750, restored.lyricDelayMs)
            assertFalse(restored.expandOnPlay)
            show("Glass", restored.glass)
            compose.waitForIdle()
            assertEquals(seeded, store.all)
        } finally {
            val editor = store.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
            } }
            assertTrue(editor.commit())
        }
    }
}
