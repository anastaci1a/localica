package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.ui.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LastFmGroupListTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun everySearchableGroupCanMatchAnUnrelatedLibrarySongAndJudgmentsOfferUndoAtLargeFontInRtl() {
        val song = Track("candidate", "Candidate", "Artist", "Album", 1000, "one.flac", "one.flac")
        val any = song.copy(id = "any", title = "Outside", album = "Other")
        val library = PendingLibrary(listOf(song, any))
        val remote = LastFmTrack("Remote Artist", "Remote Song", 123)
        val matches = LastFmMatches(emptyMap(), listOf(LastFmReview(remote, listOf(song), "Review evidence")),
            editions = listOf(LastFmReview(remote, listOf(song), "Edition evidence")),
            resolutions = listOf(LastFmResolution(remote, listOf(song), LastFmMatchTier.PROBABLE, "Weak evidence", 75),
                LastFmResolution(remote, listOf(song), LastFmMatchTier.CLEAR, "Exact recording", 100)), unmatched = listOf(remote))
        var group by mutableStateOf(LastFmGroup.MATCHED)
        val choices = mutableListOf<Pair<String, String>>()
        var undone: String? = null
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                val scroll = rememberScrollState()
                Column(Modifier.controlVerticalScroll(scroll)) {
                    LastFmReviewList(matches, group, library, { key, id -> choices += key to id; true }, { undone = it })
                }
            }
        } }
        LastFmGroup.entries.forEach { item ->
            compose.runOnIdle { group = item }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("lastfm-list-search").fetchSemanticsNodes().size == 1 }
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lastfm-list-search")), useUnmergedTree = true).performTextReplacement("Remote")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Remote Artist — Remote Song").fetchSemanticsNodes().size == 1 }
            if (item == LastFmGroup.JUDGMENTS) {
                compose.onNodeWithText("Undo match for Remote Song").performScrollTo().performClick()
                assertEquals(remote.key, undone)
            }
            compose.onNodeWithText("Remote Artist — Remote Song").performScrollTo().performClick()
            openReadyPicker()
            compose.onNodeWithText("Choose another song…").performScrollTo().performClick()
            compose.onNodeWithText("Select").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodes(hasTestTag("lastfm-match-field") and isEnabled()).fetchSemanticsNodes().size == 1
            }
            // Keep preparation pending across the old click to expose the worker/Main race deterministically.
            val pending = library.pauseNextSearch()
            try {
                compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lastfm-library-search")), useUnmergedTree = true).performTextReplacement("Outside")
                compose.waitUntil(5000) { pending.entered.count == 0L }
                compose.onNodeWithTag("lastfm-match-field").assertIsNotEnabled().performScrollTo().performClick()
                compose.onNodeWithText("Select match").assertDoesNotExist()
                compose.onNodeWithText("Outside · Artist · Other").assertDoesNotExist()
            } finally {
                library.resumeSearch(pending)
            }
            openReadyPicker()
            compose.onNodeWithText("Outside · Artist · Other").performScrollTo().performClick()
            compose.onNodeWithText("Select").performClick()
            compose.onNodeWithContentDescription("Use selection").performClick()
            compose.waitUntil(5000) { choices.size == item.ordinal + 1 }
            compose.onNodeWithText("Choose a match").assertDoesNotExist()
        }
        assertEquals(List(5) { remote.key to "any" }, choices)
    }
    private fun openReadyPicker() {
        // Compose idling does not own Dispatchers.Default catalog/search preparation.
        compose.waitUntil(5000) {
            compose.onAllNodes(hasTestTag("lastfm-match-field") and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("lastfm-match-field").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Select match").assertIsDisplayed()
    }

    private class PendingSearch {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
    }

    /** Holds real background preparation without replacing its dispatcher or changing product code. */
    private class PendingLibrary(private val tracks: List<Track>) : AbstractList<Track>() {
        private val pending = AtomicReference<PendingSearch?>()
        override val size get() = tracks.size
        override fun get(index: Int): Track {
            pending.get()?.let {
                check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                it.entered.countDown()
                it.released.await()
            }
            return tracks[index]
        }
        fun pauseNextSearch() = PendingSearch().also { check(pending.compareAndSet(null, it)) }
        fun resumeSearch(search: PendingSearch) {
            pending.compareAndSet(search, null)
            search.released.countDown()
        }
    }
}
