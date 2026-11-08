package io.github.anastaci1a.localica

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.statistics.ListeningStatistics
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File

class LastFmSettingsTest {
    private fun consent(initial: Boolean = true) = object : io.github.anastaci1a.localica.network.NetworkConsent {
        override val allowed = MutableStateFlow(initial)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }

    private val compose = localicaComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(FreshSystemIme()).around(compose)
    private val song = Track("song", "Song", "Artist", "Album", 1000, "song.mp3", "song.mp3")

    @Test fun settingsSyncReviewProviderSwitchAndDisconnectKeepLocalCountsUntouched() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "lastfm-settings-${java.util.UUID.randomUUID()}.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val snapshot = LibrarySnapshot(tracks = listOf(song, song.copy(id = "a", title = "Ambiguous", album = "A", fileName = "a.mp3"),
            song.copy(id = "b", title = "Ambiguous", album = "B", fileName = "b.mp3")))
        val store = FileLastFmStore(file)
        val controller = LastFmController(store, LastFmSync(LastFmApi { user, _ ->
            assertEquals("fixture-user", user)
            LastFmPage(1, 1, listOf(LastFmTrack("Artist", "Song", 42), LastFmTrack("Artist", "Ambiguous", 9), LastFmTrack("Absent", "Unmatched", 2)))
        }, {}), flowOf(snapshot), true, consent(), scope, { 100_000 })
        val local = ListeningStatistics(mapOf("song" to 7L), mapOf("playlist" to 3L))
        try {
            compose.setContent { LocalicaTheme {
                FolderSettings(LibraryUiState(snapshot, loading = false), {}, {}, {}, initialCategory = "Last.fm",
                    lastFmContent = { LastFmSettings(controller) })
            } }
            compose.waitUntil(5000) { !controller.state.value.loading }
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lastfm-username")), useUnmergedTree = true).performTextInput("fixture-user")
            compose.onNodeWithTag("lastfm-sync").performScrollTo().performClick()
            compose.waitUntil(5000) { controller.matches.value.counts["song"] == 42L }
            compose.onNodeWithTag("lastfm-review-summary").performScrollTo().assertTextEquals("1 matched · 1 to review")
            compose.onNodeWithText("Data", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNode(isToggleable() and hasText("Use Last.fm counts")).performScrollTo().performClick()
            compose.waitUntil(5000) { controller.state.value.saved.useCounts }
            assertEquals(42L, runBlocking { controller.applyTo(flowOf(local)).first().tracks["song"] })
            compose.onNodeWithText("Matches", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("Artist — Ambiguous").performScrollTo().performClick()
            compose.onNodeWithTag("lastfm-match-field").performClick()
            compose.onNodeWithText("Ambiguous · Artist · A").performScrollTo().performClick()
            compose.onNodeWithText("Select").performClick()
            compose.onNodeWithContentDescription("Use selection").performClick()
            compose.waitUntil(5000) { controller.matches.value.review.isEmpty() }
            assertEquals("a", runBlocking { store.read().mappings.values.single() })
            compose.onNodeWithText("Data").performScrollTo().performClick()
            compose.onNode(isToggleable() and hasText("Use Last.fm counts")).performScrollTo().performClick()
            compose.waitUntil(5000) { !controller.state.value.saved.useCounts }
            assertEquals(local, runBlocking { controller.applyTo(flowOf(local)).first() })
            assertEquals(7L, local.tracks["song"])
            assertEquals(42L, runBlocking { store.read().snapshot!!.tracks.first().plays })
            compose.onNodeWithTag("lastfm-disconnect").performScrollTo().performClick()
            compose.waitUntil(5000) { controller.state.value.saved.username.isEmpty() && !controller.state.value.saved.active }
            assertNotNull(runBlocking { store.read().snapshot })
            assertTrue(runBlocking { store.read().mappings.isNotEmpty() })
            compose.onNodeWithTag("lastfm-delete").performScrollTo().performClick()
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Delete synced data").fetchSemanticsNodes().isEmpty() }
            assertNotNull(runBlocking { store.read().snapshot })
            compose.onNodeWithTag("lastfm-delete").performScrollTo().performClick()
            compose.onNodeWithText("Delete synced data").performClick()
            compose.waitUntil(5000) { controller.state.value.saved.snapshot == null }
            val persisted = runBlocking { FileLastFmStore(file).read() }
            assertNull(persisted.snapshot); assertTrue(persisted.mappings.isEmpty()); assertFalse(persisted.useCounts)
            assertEquals(local, runBlocking { controller.applyTo(flowOf(local)).first() })
        } finally { scope.cancel(); file.delete(); File(file.path + ".bak").delete() }
    }

    @Test fun missingBundledKeyDisablesSyncAndNeverCallsTheApi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "lastfm-disabled-${java.util.UUID.randomUUID()}.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var calls = 0
        val controller = LastFmController(FileLastFmStore(file), LastFmSync(LastFmApi { _, _ -> calls++; error("No network") }, {}),
            flowOf(LibrarySnapshot()), false, consent(), scope)
        try {
            compose.setContent { LocalicaTheme {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = "Last.fm", lastFmContent = { LastFmSettings(controller) })
            } }
            compose.onNodeWithText("Last.fm is disabled in this build: no bundled API key.").assertIsDisplayed()
            // A disabled text field publishes no SetText action, so select the editor by its editable text instead.
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and hasAnyAncestor(hasTestTag("lastfm-username")),
                useUnmergedTree = true).assertIsNotEnabled()
            compose.onNodeWithTag("lastfm-sync").assertIsNotEnabled()
            controller.sync("fixture-user")
            compose.waitUntil(5000) { controller.state.value.problem?.contains("disabled") == true }
            assertEquals(0, calls)
        } finally { scope.cancel(); file.delete() }
    }

    @Test fun onlineConsentOffDisablesSyncAndNeverCallsTheApi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "lastfm-consent-off-" + java.util.UUID.randomUUID() + ".json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val controller = LastFmController(FileLastFmStore(file), LastFmSync(LastFmApi { _, _ ->
            calls.incrementAndGet(); error("No network allowed")
        }, {}), flowOf(LibrarySnapshot()), true, consent(false), scope)
        try {
            compose.setContent { LocalicaTheme {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = "Last.fm",
                    lastFmContent = { LastFmSettings(controller) })
            } }
            compose.onNodeWithText("Online features are off. Enable them to sync.").assertIsDisplayed()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and hasAnyAncestor(hasTestTag("lastfm-username")),
                useUnmergedTree = true).assertIsNotEnabled()
            compose.onNodeWithTag("lastfm-sync").assertIsNotEnabled()
            controller.sync("fixture-user")
            compose.waitUntil(5000) { controller.state.value.problem?.contains("Online features are off") == true }
            assertEquals(0, calls.get())
            assertNull(controller.state.value.saved.snapshot)
        } finally { scope.cancel(); file.delete() }
    }
}
