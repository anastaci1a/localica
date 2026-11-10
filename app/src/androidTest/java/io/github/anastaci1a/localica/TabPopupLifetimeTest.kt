package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * #767: a popup opened from a tab or subtab leaves with that tab. After the switch
 * recomposes (a grace of [GRACE] frames), no selector window or entry may remain over
 * the incoming tab and no outgoing selector content may stay on screen, whether the
 * switch lands mid-open or mid-minimize.
 */
class TabPopupLifetimeTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var host: GlassDialogs
    private var baseline = 0

    private fun frames(count: Int) = repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }

    private fun openSelector(open: () -> Unit, midMinimize: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { baseline = host.entries.size }
        open()
        if (midMinimize) {
            frames(90)
            compose.runOnIdle {
                assertEquals("selector must be fully open", baseline + 1, host.entries.size)
                host.entries.last().dismiss()
            }
            frames(3)
            compose.runOnIdle { assertEquals("selector must be minimizing", baseline + 1, host.entries.size) }
        } else {
            frames(3)
            compose.runOnIdle { assertEquals("selector must be opening", baseline + 1, host.entries.size) }
        }
    }

    /** Switch, then require the popup to be gone after the grace and for every later frame. */
    private fun switchAndAssertGone(switch: () -> Unit, outgoingContent: () -> Unit) {
        switch()
        val stale = mutableListOf<Int>()
        for (frame in 1..45) {
            frames(1)
            if (frame > GRACE) compose.runOnIdle { if (host.entries.size > baseline) stale += frame }
        }
        assertTrue("popup entries remained over the incoming tab on frames $stale", stale.isEmpty())
        outgoingContent()
    }

    private fun consent() = object : io.github.anastaci1a.localica.network.NetworkConsent {
        override val allowed = MutableStateFlow(true)
        override suspend fun setAllowed(value: Boolean) { allowed.value = value }
    }

    private fun lastFm(midMinimize: Boolean) {
        val snapshot = LibrarySnapshot(tracks = emptyList())
        val store = object : LastFmStore {
            override suspend fun read() = SavedLastFm("fixture-user", snapshot = LastFmSnapshot("fixture-user", 100_000, emptyList(), localBaseline = emptyMap()))
            override suspend fun write(value: SavedLastFm) {}
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = LastFmController(store, LastFmSync(LastFmApi { _, _ -> error("no sync") }, {}), flowOf(snapshot), true, consent(), scope)
        try {
            compose.setContent { LocalicaTheme {
                host = checkNotNull(LocalGlassDialogs.current)
                FolderSettings(LibraryUiState(snapshot, loading = false), {}, {}, {}, initialCategory = "Last.fm",
                    lastFmContent = { LastFmSettings(controller) })
            } }
            compose.waitUntil(5000) { !controller.state.value.loading }
            fun tab(label: String) = compose.onNodeWithText(label, useUnmergedTree = true)
            tab("Matches").performScrollTo().performClick()
            openSelector({ compose.onNodeWithText("Match group").performScrollTo().performClick() }, midMinimize)
            switchAndAssertGone({ tab("Overview").performClick() }) {
                compose.onAllNodesWithText("Match group").assertCountEquals(0)
                compose.onAllNodesWithText("Select").assertCountEquals(0)
            }
        } finally { compose.mainClock.autoAdvance = true; scope.cancel() }
    }

    @Test fun lastFmTabSwitchMidOpen() = lastFm(false)
    @Test fun lastFmTabSwitchMidMinimize() = lastFm(true)

    private fun sortView(midMinimize: Boolean) {
        compose.setContent { LocalicaTheme {
            host = checkNotNull(LocalGlassDialogs.current)
            BrowseSortDialog(BrowseRoute(MainTab.Albums), BrowseStyle(), false, {}, { _, _ -> }, {})
        } }
        fun tab(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("browse-options-tabs")), useUnmergedTree = true)
        try {
            openSelector({ compose.onNodeWithText("Sort by").performClick() }, midMinimize)
            switchAndAssertGone({ tab("View").performClick() }) {
                compose.onAllNodesWithText("Select").assertCountEquals(0)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun sortViewSubtabSwitchMidOpen() = sortView(false)
    @Test fun sortViewSubtabSwitchMidMinimize() = sortView(true)

    /** The library pager keeps neighbouring pages composed; the owner follows the target page. */
    private fun libraryTab(midMinimize: Boolean) {
        lateinit var scope: CoroutineScope
        lateinit var go: () -> Unit
        compose.setContent { LocalicaTheme {
            host = checkNotNull(LocalGlassDialogs.current)
            scope = rememberCoroutineScope()
            val pager = rememberPagerState { 2 }
            go = { scope.launch { pager.scrollToPage(1) } }
            val tabs = remember { listOf(MainTab.Songs, MainTab.Albums) }
            TabPager(pager, { tabs }, Modifier.fillMaxSize()) { page ->
                run {
                    Column(Modifier.fillMaxSize()) {
                        if (page == 0) ChoicePreference("Library order", "A", listOf("A", "B", "C"), { it }, popup = true) {}
                    }
                }
            }
        } }
        try {
            openSelector({ compose.onNodeWithText("Library order").performClick() }, midMinimize)
            switchAndAssertGone({ compose.runOnIdle { go() } }) {
                compose.onAllNodesWithText("Select").assertCountEquals(0)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun libraryTabSwitchMidOpen() = libraryTab(false)
    @Test fun libraryTabSwitchMidMinimize() = libraryTab(true)

    private companion object { const val GRACE = 2 }
}
