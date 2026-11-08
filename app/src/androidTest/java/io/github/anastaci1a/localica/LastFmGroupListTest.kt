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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LastFmGroupListTest {
    @get:Rule val compose = localicaComposeRule()
    @Test fun everySearchableGroupCanMatchAnUnrelatedLibrarySongAndJudgmentsOfferUndoAtLargeFontInRtl() {
        val song = Track("candidate", "Candidate", "Artist", "Album", 1000, "one.flac", "one.flac")
        val any = song.copy(id = "any", title = "Outside", album = "Other")
        val remote = LastFmTrack("Remote Artist", "Remote Song", 123)
        val matches = LastFmMatches(emptyMap(), listOf(LastFmReview(remote, listOf(song), "Review evidence")),
            editions = listOf(LastFmReview(remote, listOf(song), "Edition evidence")),
            resolutions = listOf(LastFmResolution(remote, listOf(song), LastFmMatchTier.PROBABLE, "Weak evidence", 75)), unmatched = listOf(remote))
        var group by mutableStateOf(LastFmGroup.MATCHED)
        val choices = mutableListOf<Pair<String, String>>()
        var undone: String? = null
        compose.setContent { LocalicaTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                val scroll = rememberScrollState()
                Column(Modifier.controlVerticalScroll(scroll)) {
                    LastFmReviewList(matches, group, listOf(song, any), { key, id -> choices += key to id; true }, { undone = it })
                }
            }
        } }
        LastFmGroup.entries.forEach { item ->
            compose.runOnIdle { group = item }
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lastfm-list-search")), useUnmergedTree = true).performTextReplacement("Remote")
            if (item == LastFmGroup.JUDGMENTS) {
                compose.onNodeWithText("Undo match for Remote Song").performScrollTo().performClick()
                assertEquals(remote.key, undone)
            }
            compose.onNodeWithText("Remote Artist — Remote Song").performScrollTo().performClick()
            if (item != LastFmGroup.ABSENT) compose.onNodeWithText("None of these").performScrollTo().performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lastfm-library-search")), useUnmergedTree = true).performTextReplacement("Outside")
            compose.onNodeWithText("Outside · Artist · Other").performScrollTo().performClick()
            compose.onNodeWithText("Use selection").performClick()
            compose.waitUntil(5000) { choices.size == item.ordinal + 1 }
            compose.onNodeWithText("Choose a match").assertDoesNotExist()
        }
        assertEquals(List(5) { remote.key to "any" }, choices)
    }
}
