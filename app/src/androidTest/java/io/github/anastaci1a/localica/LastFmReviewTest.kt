package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LastFmReviewTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun reviewFixtureCapturesTheNativeDecisionSurface() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val remote = listOf(LastFmTrack("Flying Lotus", "More (feat. Anderson .Paak)", 108),
            LastFmTrack("Dream Theater", "Learning to Live", 86), LastFmTrack("Kendrick Lamar", "Mirror", 117))
        val saved = SavedLastFm("fixture-user", snapshot = LastFmSnapshot("fixture-user", 1794157200000, remote))
        val store = object : LastFmStore {
            override suspend fun read() = saved
            override suspend fun write(value: SavedLastFm) = Unit
        }
        val consent = object : NetworkConsent {
            override val allowed = MutableStateFlow(true)
            override suspend fun setAllowed(value: Boolean) { allowed.value = value }
        }
        fun song(id: String, title: String, artist: String, album: String) = Track(id, title, artist, album, 1000, "04 $title.m4a", "$id.m4a")
        val tracks = listOf(song("more", "More (feat. Anderson .Paak)", "Flying Lotus", "Flamagra"),
            song("deluxe", "More (feat. Anderson .Paak)", "Flying Lotus", "Flamagra (Deluxe Edition)"),
            song("studio", "Learning to Live", "Dream Theater", "Images and Words"),
            song("live", "Learning to Live", "Dream Theater", "Live at the Marquee"))
        val controller = LastFmController(store, LastFmSync(LastFmApi { _, _ -> error("No network") }),
            flowOf(LibrarySnapshot(tracks = tracks)), true, consent, scope)
        try {
            compose.setContent { LocalicaTheme {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = "Last.fm",
                    lastFmContent = { LastFmSettings(controller) })
            } }
            compose.waitUntil(5000) { !controller.state.value.loading && controller.matches.value.review.isNotEmpty() }
            compose.onNodeWithText("Review matches").performScrollTo().performClick()
            compose.onNodeWithTag("lastfm-review-page-summary").performScrollTo()
            compose.waitForIdle()
            val directory = nativeEvidenceDirectory("lastfm-review", context.getExternalFilesDir(null))
            org.junit.Assert.assertTrue(androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .takeScreenshot(File(directory, "review-list.png")))
            compose.onNodeWithText("Dream Theater — Learning to Live").performScrollTo().performClick()
            compose.onNodeWithText("Use selection").assertIsNotEnabled()
            compose.onNodeWithText("Images and Words · Learning to Live").performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
            compose.onNodeWithText("None of these").assertIsNotSelected()
            compose.waitForIdle()
            org.junit.Assert.assertTrue(androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .takeScreenshot(File(directory, "review-decision-selected.png")))
            compose.onNodeWithText("Images and Words · Learning to Live").performTouchInput { down(center) }
            compose.waitForIdle()
            org.junit.Assert.assertTrue(androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .takeScreenshot(File(directory, "review-decision-pressed.png")))
            compose.onNodeWithText("Images and Words · Learning to Live").performTouchInput { up() }
            compose.onNodeWithText("Skip").performClick()
            compose.onNodeWithText("86 plays · Skipped for now").assertExists()
            compose.onNodeWithText("Dream Theater — Learning to Live").performClick()
            compose.onNodeWithText("None of these").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("Use selection").performClick()
            compose.waitUntil(5000) { controller.matches.value.review.isEmpty() }
            compose.onNodeWithText("All available matches are resolved.").assertExists()
        } finally { scope.cancel() }
    }
}
