package io.github.anastaci1a.localica

import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LastFmDashboardTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun dashboardAndPopupFramesSearchAllGroupsAndUndoJudgments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val remote = LastFmTrack("Dream Theater", "Learning to Live", 86)
        val absent = LastFmTrack("Kendrick Lamar", "Mirror", 117)
        val song = Track("studio", "Learning to Live", "Dream Theater", "Images and Words", 1000, "learning.flac", "learning.flac")
        var persisted = SavedLastFm("fixture-user", snapshot = LastFmSnapshot("fixture-user", 1794157200000,
            listOf(remote, absent), localBaseline = emptyMap()), mappings = mapOf(remote.key to LastFmMatcher.REVIEW))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = LastFmController(object : LastFmStore {
            override suspend fun read() = persisted
            override suspend fun write(value: SavedLastFm) { persisted = value }
        }, LastFmSync(LastFmApi { _, _ -> error("No network in fixture") }),
            flowOf(LibrarySnapshot(tracks = listOf(song, song.copy(uri = "other", id = "other", title = "Unrelated song", album = "Other album")))),
            true, object : NetworkConsent {
                override val allowed = MutableStateFlow(true)
                override suspend fun setAllowed(value: Boolean) { allowed.value = value }
            }, scope)
        val directory = nativeEvidenceDirectory("lastfm-dashboard", context.getExternalFilesDir(null))
        fun capture(name: String) {
            compose.waitForIdle()
            assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory, "$name-api${Build.VERSION.SDK_INT}.png")))
        }
        try {
            compose.setContent { LocalicaTheme {
                FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = "Last.fm", lastFmContent = { LastFmSettings(controller) })
            } }
            compose.waitUntil(5000) { controller.matches.value.review.size == 1 }
            capture("01-dashboard")
            compose.onNodeWithText("Needs review").performScrollTo().performClick()
            compose.onNodeWithText("Dream Theater — Learning to Live").performScrollTo().performClick()
            compose.onNodeWithText("Use selection").assertIsNotEnabled()
            capture("02-popup")
            compose.onNodeWithText("Learning to Live · Dream Theater · Images and Words", useUnmergedTree = true).performScrollTo().performClick()
            capture("03-selected")
            compose.onNodeWithText("Learning to Live · Dream Theater · Images and Words", useUnmergedTree = true).performTouchInput { down(center) }
            capture("04-pressed")
            compose.onNodeWithText("Learning to Live · Dream Theater · Images and Words", useUnmergedTree = true).performTouchInput { up() }
            compose.onNodeWithText("None of these", useUnmergedTree = true).performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lastfm-library-search")), useUnmergedTree = true).performTextInput("unrelated")
            capture("05-any-library-search")
            compose.onNodeWithText("Unrelated song · Dream Theater · Other album", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Use selection").performClick()
            compose.waitUntil(5000) { persisted.mappings[remote.key] == "other" }
            compose.onNodeWithText("Matches", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        } finally { scope.cancel() }
    }
}
