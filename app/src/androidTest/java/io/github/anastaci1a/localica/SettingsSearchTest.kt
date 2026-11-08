package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.anastaci1a.localica.browse.LibraryFilters
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.queue.*
import io.github.anastaci1a.localica.library.SongListView
import androidx.compose.ui.test.junit4.StateRestorationTester
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsSearchTest {
    @get:Rule val compose = localicaComposeRule()

    private fun show(initial: String? = null, glass: GlassAppearance = GlassAppearance(),
        interfaceSettings: InterfaceSettings = InterfaceSettings(glass = glass)) {
        compose.setContent { LocalicaTheme {
            var settings by remember { mutableStateOf(interfaceSettings) }
            CompositionLocalProvider(LocalGlassAppearance provides settings.glass) {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = initial,
                    interfaceContent = { section -> InterfaceSettingsControls(settings, section) { settings = it } },
                    playbackContent = { section -> QueueSettingsControls(QueueSettings(), section) {} })
            }
        } }
    }

    @Test fun deepSearchFindsOptionsAndValuesFromEveryPage() {
        show("Player")
        for ((query, result) in listOf("Lift shadow" to "Lift shadow strength", "Carousel" to "Song-change transition",
            "Preload" to "Preload next track", "Shape damping" to "Shape damping", "grey" to "Interface gray",
            "minimum duration" to "Exclude short files", "sync" to "Lyrics timing correction")) {
            compose.onNodeWithContentDescription("Search settings input").performTextReplacement(query)
            compose.onNodeWithTag("search-result:$result").assertExists()
        }
    }

    @Test fun resultNavigatesScrollsAndHighlightsTheActualRow() {
        show()
        compose.onNodeWithContentDescription("Search settings input").performTextInput("Control expansion")
        compose.onNodeWithTag("search-result:Control expansion").assertExists()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("search-result:Control expansion").performClick()
        compose.mainClock.advanceTimeUntil(timeoutMillis = 5000) {
            compose.onAllNodesWithTag("setting:Control expansion").fetchSemanticsNodes().any {
                (it.config.getOrNull(SettingsHighlightAlpha) ?: 0f) > .01f
            }
        }
        compose.onNodeWithTag("setting:Control expansion").assertIsDisplayed()
        val scrollers = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes()
        assertTrue("Search must scroll beyond the top", scrollers.any {
            it.config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f
        })
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("setting:Control expansion").assertIsDisplayed()
        compose.onNodeWithTag("setting-highlight:Control expansion", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Search settings input").assertExists()
    }

    @Test fun hiddenOptionJumpsToItsRevealingChoice() {
        show()
        compose.onNodeWithContentDescription("Search settings input").performTextInput("Gray pill opacity")
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

    @Test fun disabledSliderIgnoresTapsAndDragsAndExposesDisabledSemantics() {
        var changes = 0
        compose.setContent { LocalicaTheme {
            CompositionLocalProvider(LocalSettingsEnabled provides false) {
                Column { SliderPreference("Disabled slider", .4f, { changes++ },
                    Modifier.semantics { contentDescription = "Disabled slider" }) }
            }
        } }
        val slider = compose.onNodeWithContentDescription("Disabled slider")
        slider.assertIsNotEnabled()
        slider.performTouchInput { click(centerRight); swipeLeft() }
        compose.runOnIdle { assertEquals(0, changes) }
        slider.assertIsNotEnabled()
    }

    @Test fun everyCatalogSettingHasARenderedAnchorOrItsRevealingControl() {
        var category by mutableStateOf("Appearance")
        compose.setContent { LocalicaTheme {
            key(category) {
                val context = androidx.compose.ui.platform.LocalContext.current
                FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = category,
                    interfaceContent = { section ->
                        if (section == "Player") SettingsLink("Customize player actions") {}
                        InterfaceSettingsControls(InterfaceSettings(), section) {}
                    }, playbackContent = { section -> QueueSettingsControls(QueueSettings(), section) {} },
                    aboutContent = { DiagnosticsExportAction(); LibraryLicenses() },
                    libraryFiltersContent = { LibraryFilterSettings(LibraryFilters()) {} },
                    extraContent = { (context.applicationContext as? LocalicaApplication)?.let { TagRecoverySettings(it.tagEditing) } })
            }
        } }
        for ((page, entries) in SettingsCatalog.entries.groupBy { it.page }) {
            compose.runOnIdle { category = page }
            val tags = compose.onAllNodes(SemanticsMatcher("setting anchor") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("setting:") == true
            }, useUnmergedTree = true).fetchSemanticsNodes().map {
                it.config[SemanticsProperties.TestTag].removePrefix("setting:")
            }.toSet()
            for (spec in entries) {
                assertTrue("Unreachable "+spec.id+" on "+page+": "+tags,
                    spec.id in tags || spec.revealedBy in tags)
                assertTrue(SettingsCatalog.search(spec.title).any { it.setting == spec })
            }
        }
    }

    @Test fun existingPreferenceKeysAndValuesSurviveOpeningTheReworkedPages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = context.getSharedPreferences("interface", 0)
        val before = store.all.toMap()
        try {
            assertTrue(store.edit().clear().putFloat("glass-blur", .65f).putFloat("glass-depth", .9f)
                .putString("glass-renderer", "None").putInt("lyric-delay-ms", -750)
                .putBoolean("expand-on-play", false).commit())
            val seeded = store.all.toMap()
            val restored = InterfacePreferences(context).read()
            assertEquals(.65f, restored.glass.blur)
            assertEquals(-750, restored.lyricDelayMs)
            assertFalse(restored.expandOnPlay)
            show("Player", interfaceSettings = restored)
            compose.onNodeWithText("Expand player when a song starts").assertIsOff()
            compose.onNodeWithText("Correction: -750 ms").performScrollTo().assertIsDisplayed()
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
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            assertTrue(editor.commit())
        }
    }

    @Test fun queueOverridesAndScannerValuesSurviveSearchAndRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queueStore = context.getSharedPreferences("queue-settings", 0)
        val scanStore = context.getSharedPreferences("scanner", 0)
        val originals = listOf(queueStore, scanStore).associateWith { it.all.toMap() }
        val preferences = QueuePreferences(context)
        val queues = QueueSettings(songTap = SongTap.Append, switching = QueueSwitch.Resume, maximum = 7,
            preloadNext = false, previousBehavior = PreviousBehavior.PreviousSong,
            tapOverrides = mapOf(SongListView.Songs to SongTap.PlayNext))
        try {
            preferences.write(queues)
            assertTrue(scanStore.edit().clear().putBoolean("subfolders", false).putInt("minimum-seconds", 17)
                .putBoolean("on-launch", true).putBoolean("full-on-launch", false)
                .putStringSet("excluded:seed", setOf("Podcasts", "Demos")).commit())
            val seededQueues = queueStore.all.toMap()
            val seededScan = scanStore.all.toMap()
            val restoration = StateRestorationTester(compose)
            restoration.setContent { LocalicaTheme {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = "Scanner",
                    playbackContent = { QueueSettingsControls(preferences.read(), it) {} })
            } }
            compose.onNode(hasText("Quick Scan") and isToggleable()).performScrollTo().assertIsOn()
            compose.onNode(hasText("Full scan") and isToggleable()).performScrollTo().assertIsOff()
            compose.onNodeWithText("Include subfolders").performScrollTo().assertIsOff()
            compose.onNodeWithContentDescription("Search settings input").performTextInput("Songs")
            compose.onNodeWithTag("search-result:Songs").performClick()
            compose.onNode(hasText("Songs") and hasText("Add next")).assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNode(hasText("Songs") and hasText("Add next")).assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(queues, preferences.read())
                assertEquals(seededQueues, queueStore.all)
                assertEquals(seededScan, scanStore.all)
            }
        } finally {
            originals.forEach { (store, values) ->
                val editor = store.edit().clear()
                values.forEach { (key, value) -> when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                assertTrue(editor.commit())
            }
        }
    }

}
