package io.github.anastaci1a.localica

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.lastfm.LastFmMatchRow
import io.github.anastaci1a.localica.lastfm.LastFmTrack
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LastFmChoiceDialogTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun nullStaleRemovedReorderedAndEmptyChoicesNeverConfirmAnUnavailableDraft() {
        var choices by mutableStateOf(listOf("One", "Two"))
        var selected by mutableStateOf<String?>(null)
        var confirmed: String? = null
        var dismissed = 0
        compose.setContent { LocalicaTheme {
            ChoiceDialog(ChoiceDialogActions("Use selection", "Skip"), "Choose", choices, selected, { it },
                { dismissed++ }, { confirmed = it })
        } }
        compose.onNodeWithText("Use selection").assertIsNotEnabled()
        compose.onNodeWithText("One").assertIsNotSelected()
        compose.onNodeWithText("Two").performClick().assertIsSelected()
        compose.onNodeWithText("Use selection").performClick()
        assertEquals("Two", confirmed); assertEquals(0, dismissed)
        compose.runOnIdle { selected = "Two"; choices = listOf("Two", "One"); confirmed = null }
        compose.onNodeWithText("Two").assertIsSelected()
        compose.onNodeWithText("Use selection").performClick()
        assertEquals("Two", confirmed)
        compose.runOnIdle { choices = listOf("One"); confirmed = null }
        compose.onNodeWithText("Use selection").assertIsNotEnabled()
        compose.onNodeWithText("Use selection").performClick()
        assertNull(confirmed)
        compose.runOnIdle { choices = emptyList() }
        compose.onNodeWithText("Use selection").assertIsNotEnabled()
        compose.onNodeWithText("Skip").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun originalTrailingCallbackOverloadStillSelectsThenDismissesExactlyOnce() {
        val events = mutableListOf<String>()
        compose.setContent { LocalicaTheme {
            ChoiceDialog("Original", listOf("One", "Two"), "One", { it }, { events += "dismiss" }) { events += "select:$it" }
        } }
        compose.onNodeWithText("Select").performClick()
        assertEquals(listOf("select:One", "dismiss"), events)
    }

    @Test fun failedSaveKeepsPopupForRetryDoubleSubmitIsBlockedAndSuccessClosesOnce() {
        var open by mutableStateOf(true)
        var result = CompletableDeferred<Boolean>()
        var calls = 0; var closes = 0
        val track = Track("one", "Song", "Artist", "Album", 1000, "song.flac", "song.flac")
        val row = LastFmMatchRow(LastFmTrack("Artist", "Song", 98), listOf(track), "Check recording")
        compose.setContent { LocalicaTheme {
            if (open) LastFmDecision(row, listOf(track), { open = false; closes++ }, onChoose = {
                calls++
                result.await().also { if (it) { open = false; closes++ } }
            }, onSkip = { open = false; closes++ }) else Text("Closed")
        } }
        compose.onNodeWithText("Song · Artist · Album").performClick()
        compose.onNodeWithText("Use selection").performClick()
        compose.onNodeWithText("Use selection").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, calls); result.complete(false) }
        compose.onNodeWithText("Could not save the match. Try again.").assertExists()
        assertEquals(0, closes)
        compose.runOnIdle { result = CompletableDeferred() }
        compose.onNodeWithText("Use selection").performClick()
        compose.runOnIdle { result.complete(true) }
        compose.onNodeWithText("Closed").assertExists()
        assertEquals(2, calls); assertEquals(1, closes)
    }

    @Test fun noneOpensAnyLibrarySongSearchAndSkipNeverSaves() {
        val candidate = Track("one", "Song", "Artist", "Album", 1000, "song.flac", "song.flac")
        val unrelated = candidate.copy(id = "any", title = "Unrelated", artist = "Other", album = "Different")
        var chosen: String? = null; var skips = 0
        compose.setContent { LocalicaTheme {
            LastFmDecision(LastFmMatchRow(LastFmTrack("Artist", "Song", 12), listOf(candidate), "Check"),
                listOf(candidate, unrelated), {}, { chosen = it; true }, { skips++ })
        } }
        compose.onNodeWithText("None of these").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lastfm-library-search")), useUnmergedTree = true)
            .performTextInput("Unrelated")
        compose.onNodeWithText("Use selection").assertIsNotEnabled()
        compose.onNodeWithText("Unrelated · Other · Different").performClick()
        compose.onNodeWithText("Use selection").performClick()
        compose.runOnIdle { assertEquals("any", chosen) }
        compose.onNodeWithText("Skip").performClick()
        assertEquals(1, skips)
    }

    @Test fun cancellingPendingSaveClosesOnceAndCannotSubmitAgain() {
        val result = CompletableDeferred<Boolean>()
        var open by mutableStateOf(true)
        var closes = 0; var calls = 0
        val song = Track("one", "Song", "Artist", "Album", 1000, "one.flac", "one.flac")
        compose.setContent { LocalicaTheme {
            if (open) LastFmDecision(LastFmMatchRow(LastFmTrack("Artist", "Song", 1), listOf(song), "Check"), listOf(song),
                { open = false; closes++ }, { calls++; result.await().also { if (it) { open = false; closes++ } } },
                { open = false; closes++ }) else Text("Closed")
        } }
        compose.onNodeWithText("Song · Artist · Album").performClick()
        compose.onNodeWithText("Use selection").performClick()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("Closed").assertExists()
        compose.runOnIdle { result.complete(true) }
        compose.runOnIdle { assertEquals(1, calls); assertEquals(1, closes) }
    }
}
