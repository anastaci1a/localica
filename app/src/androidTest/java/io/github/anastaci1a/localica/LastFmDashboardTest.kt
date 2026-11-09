package io.github.anastaci1a.localica

import android.os.Build
import android.view.Choreographer
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.network.NetworkConsent
import io.github.anastaci1a.localica.ui.*
import io.github.anastaci1a.localica.ui.spec.Space
import io.github.anastaci1a.localica.ui.spec.StockUiSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LastFmDashboardTest {
    @get:Rule val compose = localicaComposeRule()
    private class Store(@Volatile var saved: SavedLastFm) : LastFmStore {
        override suspend fun read() = saved
        override suspend fun write(value: SavedLastFm) { saved = value }
    }
    private data class Fixture(val store: Store, val controller: LastFmController, val scope: CoroutineScope, val review: LastFmTrack)
    private fun fixture(api: LastFmApi = LastFmApi { _, _ -> error("No network in fixture") }): Fixture {
        val review = LastFmTrack("Dream Theater", "Learning to Live", 86)
        val remotes = listOf(review, LastFmTrack("Kendrick Lamar", "Mirror", 117), LastFmTrack("Flying Lotus", "More", 108, "Flamagra"),
            LastFmTrack("Dark Boson", "Thad Jones & Mel Lewis Orchestra - Cherry Juice", 98, durationMs = 331_000))
        val studio = Track("studio", "Learning to Live", "Dream Theater", "Images and Words", 1000, "learning.flac", "learning.flac")
        val more = studio.copy(uri = "more", id = "more", title = "More", artist = "Flying Lotus", album = "Flamagra")
        val tracks = listOf(studio, studio.copy(uri = "other", id = "other", title = "Unrelated song", album = "Other album"), more,
            more.copy(uri = "edition", id = "edition", album = "Flamagra (Deluxe Edition)"),
            studio.copy(uri = "cherry", id = "cherry", title = "Cherry Juice", artist = "The Thad Jones/Mel Lewis Orchestra", album = "Consummation", durationMs = 330_000))
        val store = Store(SavedLastFm("fixture-user", snapshot = LastFmSnapshot("fixture-user", 1794157200000, remotes, localBaseline = emptyMap()),
            mappings = mapOf(review.key to LastFmMatcher.REVIEW)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = LastFmController(store, LastFmSync(api, {}), flowOf(LibrarySnapshot(tracks = tracks)), true, object : NetworkConsent {
            override val allowed = MutableStateFlow(true)
            override suspend fun setAllowed(value: Boolean) { allowed.value = value }
        }, scope, now = { 1794160000000 })
        return Fixture(store, controller, scope, review)
    }
    private fun show(f: Fixture) {
        compose.setContent { LocalicaTheme {
            FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = "Last.fm", lastFmContent = { LastFmSettings(f.controller) })
        } }
        compose.waitUntil(5000) { f.controller.matches.value.review.size == 1 && f.controller.matches.value.judgments.size == 1 }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Semantics can settle before the physical window has submitted its new frame.
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val choreographer = Choreographer.getInstance()
            var remaining = 3
            val fence = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (--remaining == 0) drawn.countDown() else choreographer.postFrameCallback(this)
                }
            }
            choreographer.postFrameCallback(fence)
        }
        assertTrue("Native frame fence", drawn.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val context = instrumentation.targetContext
        val dir = nativeEvidenceDirectory("lastfm-dashboard", context.getExternalFilesDir(null))
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(dir, "$name-api${Build.VERSION.SDK_INT}.png")))
    }
    private fun pick(label: String) {
        compose.onNodeWithTag("lastfm-match-field").performScrollTo().performClick()
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithText("Select").performClick()
    }
    private fun assertHeaderActionsInsideCard(vararg labels: String) {
        val card = compose.onNodeWithTag("lastfm-dashboard-card").getUnclippedBoundsInRoot()
        val inset = StockUiSpec.space.getValue(Space.Comfortable).value
        labels.forEach { label ->
            val button = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("$label leading card padding", button.left.value >= card.left.value + inset - .5f)
            assertTrue("$label bottom card padding", button.bottom.value <= card.bottom.value - inset + .5f)
        }
    }

    @Test fun idleDashboardTabsAndFieldDecisionAreCapturedWithoutInlineCandidateChoices() {
        val f = fixture()
        try {
            show(f)
            compose.onNodeWithTag("lastfm-sync-percent").assertTextEquals("100%")
            compose.onNodeWithTag("lastfm-dashboard-last-sync").assertExists()
            assertHeaderActionsInsideCard("Sync now", "Full rescan")
            capture("01-idle-dashboard")
            compose.onNodeWithTag("lastfm-legend-ABSENT").performScrollTo()
            capture("02-matching-grid")
            compose.onNodeWithText("Needs review").performScrollTo().performClick()
            compose.onNodeWithTag("lastfm-tabs").performScrollTo()
            capture("03-matches-tab")
            compose.onNodeWithText("Dream Theater — Learning to Live").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Use selection").assertIsNotEnabled()
            compose.onNodeWithText("Learning to Live · Dream Theater · Images and Words").assertDoesNotExist()
            capture("04-decision-placeholder")
            compose.onNodeWithTag("lastfm-match-field").performClick()
            capture("05-existing-single-select")
            compose.onNodeWithText("Learning to Live · Dream Theater · Images and Words").performScrollTo().performClick()
            compose.onNodeWithText("Select").performClick()
            compose.onNodeWithTag("lastfm-match-field").assertTextContains("Learning to Live · Dream Theater · Images and Words")
            compose.onNodeWithContentDescription("Use selection").assertIsEnabled()
            compose.onNodeWithText("Use selection").assertDoesNotExist()
            capture("06-selected-field-check")
            compose.onNodeWithContentDescription("Use selection").performTouchInput { down(center) }
            capture("07-check-pressed")
            compose.onNodeWithContentDescription("Use selection").performTouchInput { up() }
            compose.waitUntil(5000) { f.store.saved.mappings[f.review.key] == "studio" }
            compose.onNodeWithText("Data").performScrollTo().performClick()
            capture("08-data-tab")
            compose.onNodeWithText("Keep syncing in the background").performScrollTo()
            capture("09-data-controls")
        } finally { f.scope.cancel() }
    }

    @Test fun syncingHeaderShowsPercentAndLiveCountsBeforeAtomicIdleReplacement() {
        val second = CompletableDeferred<LastFmPage>()
        val f = fixture(LastFmApi { _, page -> when (page) {
            1 -> LastFmPage(1, 3, listOf(LastFmTrack("Live artist", "First", 10, "Live album")))
            2 -> second.await()
            else -> LastFmPage(3, 3, listOf(LastFmTrack("Final artist", "Third", 30, "Final album")))
        } })
        try {
            show(f)
            f.controller.sync("fixture-user", fullRescan = true)
            compose.waitUntil(5000) { f.controller.state.value.progress?.percent == 33 }
            compose.onNodeWithTag("lastfm-dashboard-card").performScrollTo()
            compose.onNodeWithTag("lastfm-sync-percent").assertTextEquals("33%")
            compose.onNodeWithTag("lastfm-progress").assertTextEquals("1 song · 1 artist · 1 album")
            assertEquals(4, f.store.saved.snapshot!!.tracks.size)
            assertHeaderActionsInsideCard("Stop sync")
            capture("10-syncing-header")
            second.complete(LastFmPage(2, 3, listOf(LastFmTrack("Next artist", "Second", 20, "Next album"))))
            compose.waitUntil(5000) { !f.controller.state.value.syncing && f.store.saved.snapshot!!.tracks.size == 3 }
            compose.onNodeWithTag("lastfm-sync-percent").assertTextEquals("100%")
            compose.onNodeWithTag("lastfm-dashboard-sync").assertIsEnabled()
            compose.onNodeWithTag("lastfm-dashboard-full-rescan").assertIsEnabled()
            assertHeaderActionsInsideCard("Sync now", "Full rescan")
            capture("11-idle-after-sync")
        } finally { f.scope.cancel() }
    }

    @Test fun markedAbsentChoiceCanBeUndoneFromTheAbsentListWithoutDeletingTheArchive() {
        val f = fixture()
        try {
            show(f)
            compose.onNodeWithText("Needs review").performScrollTo().performClick()
            compose.onNodeWithText("Dream Theater — Learning to Live").performScrollTo().performClick()
            pick("Not in my library")
            compose.onNodeWithContentDescription("Use selection").performClick()
            compose.waitUntil(5000) { f.store.saved.mappings[f.review.key] == LastFmMatcher.NONE }
            compose.onNodeWithText("Match group").performScrollTo().performClick()
            compose.onNodeWithText("Not in your library").performScrollTo().performClick()
            compose.onNodeWithText("Select").performClick()
            compose.onNodeWithText("Undo match for Learning to Live").performScrollTo().performClick()
            compose.waitUntil(5000) { f.review.key !in f.store.saved.mappings }
            assertEquals(4, f.store.saved.snapshot!!.tracks.size)
        } finally { f.scope.cancel() }
    }

    @Test fun stockOuterAndTextGridMatchesAtNormalLargestFontAndRtl() {
        val f = fixture()
        var scale by mutableFloatStateOf(1f)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        try {
            compose.setContent { LocalicaTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale), LocalLayoutDirection provides direction) {
                    FolderSettings(LibraryUiState(loading = false), {}, {}, {}, initialCategory = "Last.fm", lastFmContent = {
                        // A popup window installs its own density; exercise the content at the requested font scale.
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                            LastFmSettings(f.controller)
                        }
                    })
                }
            } }
            compose.waitUntil(5000) { f.controller.matches.value.review.size == 1 }
            var normalTextHeight = 0f
            val evidence = org.json.JSONArray()
            for (font in listOf(1f, 2f)) for (rtl in listOf(false, true)) {
                compose.runOnIdle { scale = font; direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr }
                compose.waitForIdle()
                val card = compose.onNodeWithTag("lastfm-stats-card").getUnclippedBoundsInRoot()
                val tags = listOf("lastfm-dashboard-card", "lastfm-tabs", "lastfm-matching-strip") +
                    listOf("MATCHED", "JUDGMENTS", "REVIEW", "ABSENT").map { "lastfm-legend-$it" }
                val edges = org.json.JSONArray().put(org.json.JSONObject().put("tag", "lastfm-stats-card")
                    .put("left", card.left.value).put("right", card.right.value))
                tags.forEach { tag ->
                    val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                    assertEquals("$tag left $font/$rtl", card.left.value, bounds.left.value, .5f)
                    assertEquals("$tag right $font/$rtl", card.right.value, bounds.right.value, .5f)
                    edges.put(org.json.JSONObject().put("tag", tag).put("left", bounds.left.value).put("right", bounds.right.value))
                }
                fun start(node: SemanticsNodeInteraction): Float {
                    val bounds = node.getUnclippedBoundsInRoot()
                    return if (rtl) bounds.right.value else bounds.left.value
                }
                val rowText = start(compose.onNodeWithText("Matched", useUnmergedTree = true))
                val textBounds = compose.onNodeWithText("Matched", useUnmergedTree = true).getUnclippedBoundsInRoot()
                val textHeight = textBounds.bottom.value - textBounds.top.value
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText("Matched", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals("Actual row font scale", font, layouts.single().layoutInput.density.fontScale, 0f)
                if (font == 1f && !rtl) normalTextHeight = textHeight
                if (font == 2f) assertTrue("Largest-font text is actually enlarged: $textHeight vs $normalTextHeight", textHeight > normalTextHeight)
                val heading = compose.onNode(SemanticsMatcher.expectValue(SettingsRowPartKey, SettingsRowPart.Heading) and hasText("Matching"), useUnmergedTree = true)
                assertEquals("heading grid $font/$rtl", rowText, start(heading), .5f)
                assertEquals("summary grid $font/$rtl", rowText, start(compose.onNodeWithTag("lastfm-review-summary")), .5f)
                assertEquals("stats grid $font/$rtl", rowText, start(compose.onNodeWithText("Scrobbles", useUnmergedTree = true)), .5f)
                evidence.put(org.json.JSONObject().put("fontScale", font).put("rtl", rtl).put("edges", edges)
                    .put("textStart", rowText).put("textHeight", textHeight).put("actualFontScale", layouts.single().layoutInput.density.fontScale))
                compose.onNodeWithTag("lastfm-tabs").performScrollTo()
                capture("12-grid-top-font${font.toInt()}-${if (rtl) "rtl" else "ltr"}")
                compose.onNodeWithTag("lastfm-legend-ABSENT").performScrollTo()
                capture("12-grid-font${font.toInt()}-${if (rtl) "rtl" else "ltr"}")
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(nativeEvidenceDirectory("lastfm-dashboard", context.getExternalFilesDir(null)), "grid-bounds-api${Build.VERSION.SDK_INT}.json")
                .writeText(evidence.toString(2))
        } finally { f.scope.cancel() }
    }
}
