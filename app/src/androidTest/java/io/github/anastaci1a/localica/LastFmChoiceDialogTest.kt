package io.github.anastaci1a.localica

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import io.github.anastaci1a.localica.lastfm.*
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.ui.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LastFmChoiceDialogTest {
    @get:Rule val compose = localicaComposeRule()
    private val song = Track("one", "Song", "Artist", "Album", 1000, "song.flac", "song.flac")
    private val row get() = LastFmMatchRow(LastFmTrack("Artist", "Song", 98), listOf(song), "Check recording")
    private fun pick(label: String) {
        compose.onNodeWithTag("lastfm-match-field").performScrollTo().performClick()
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.onNodeWithText("Select").performClick()
    }

    @Test fun nullCancelStaleRemovedReorderedAndEmptyFieldsNeverConfirmUnavailableChoices() {
        var choices by mutableStateOf(listOf("One", "Two"))
        var selected by mutableStateOf<String?>(null)
        var confirmed: String? = null; var dismissed = 0
        compose.setContent { LocalicaTheme {
            ChoiceDialog(ChoiceDialogActions("Use selection", "Skip"), "Choose", choices, selected, { it }, { dismissed++ }, { confirmed = it })
        } }
        compose.onNodeWithContentDescription("Use selection").assertIsNotEnabled()
        compose.onNodeWithText("One").assertDoesNotExist()
        compose.onNodeWithTag("lastfm-match-field").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("lastfm-match-field").assertTextContains("Choose…")
        compose.onNodeWithContentDescription("Use selection").assertIsNotEnabled()
        assertEquals(0, dismissed)
        pick("Two")
        compose.onNodeWithTag("lastfm-match-field").assertTextContains("Two")
        compose.onNodeWithContentDescription("Use selection").performClick()
        assertEquals("Two", confirmed); assertEquals(0, dismissed)
        compose.runOnIdle { selected = "Two"; choices = listOf("Two", "One"); confirmed = null }
        compose.onNodeWithTag("lastfm-match-field").assertTextContains("Two")
        compose.onNodeWithContentDescription("Use selection").performClick()
        assertEquals("Two", confirmed)
        compose.runOnIdle { choices = listOf("One"); confirmed = null }
        compose.onNodeWithTag("lastfm-match-field").assertTextContains("Choose…")
        compose.onNodeWithContentDescription("Use selection").assertIsNotEnabled().performClick()
        assertNull(confirmed)
        compose.runOnIdle { choices = emptyList() }
        compose.onNodeWithTag("lastfm-match-field").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Use selection").assertIsNotEnabled()
        compose.onNodeWithText("Skip").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun removingTheInnerDraftBeforeSelectCannotPopulateTheOuterField() {
        var choices by mutableStateOf(listOf("One", "Two"))
        var confirmed: String? = null
        compose.setContent { LocalicaTheme {
            ChoiceDialog(ChoiceDialogActions("Use selection", "Skip"), "Choose", choices, null, { it }, {}, { confirmed = it })
        } }
        compose.onNodeWithTag("lastfm-match-field").performClick()
        compose.onNodeWithText("Two").performClick()
        compose.runOnIdle { choices = listOf("One") }
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithTag("lastfm-match-field").assertTextContains("Choose…")
        compose.onNodeWithContentDescription("Use selection").assertIsNotEnabled().performClick()
        assertNull(confirmed)
    }

    @Test fun originalTrailingCallbackOverloadStillSelectsThenDismissesExactlyOnce() {
        val events = mutableListOf<String>()
        compose.setContent { LocalicaTheme {
            ChoiceDialog("Original", listOf("One", "Two"), "One", { it }, { events += "dismiss" }) { events += "select:$it" }
        } }
        compose.onNodeWithText("Select").performClick()
        assertEquals(listOf("select:One", "dismiss"), events)
    }

    @Test fun failedSaveKeepsFieldForRetryDoubleSubmitIsBlockedAndSuccessClosesOnce() {
        var open by mutableStateOf(true); var result = CompletableDeferred<Boolean>()
        var calls = 0; var closes = 0
        compose.setContent { LocalicaTheme {
            if (open) LastFmDecision(row, listOf(song), { open = false; closes++ }, onChoose = {
                calls++; result.await().also { if (it) { open = false; closes++ } }
            }, onSkip = { open = false; closes++ }) else Text("Closed")
        } }
        pick("Song · Artist · Album")
        compose.onNodeWithContentDescription("Use selection").performClick()
        compose.onNodeWithContentDescription("Use selection").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, calls); result.complete(false) }
        compose.onNodeWithText("Could not save the match. Try again.").assertExists()
        assertEquals(0, closes)
        compose.runOnIdle { result = CompletableDeferred() }
        compose.onNodeWithContentDescription("Use selection").performClick()
        compose.runOnIdle { result.complete(true) }
        compose.onNodeWithText("Closed").assertExists()
        assertEquals(2, calls); assertEquals(1, closes)
    }

    @Test fun anotherSongOpensWholeLibrarySearchAndItsReturnedSongPopulatesTheField() {
        val any = song.copy(id = "any", title = "Unrelated", artist = "Other", album = "Different")
        var chosen: String? = null; var skips = 0
        compose.setContent { LocalicaTheme { LastFmDecision(row, listOf(song, any), {}, { chosen = it; true }, { skips++ }) } }
        pick("Choose another song…")
        compose.onNodeWithContentDescription("Use selection").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("lastfm-library-search")), useUnmergedTree = true).performTextInput("Unrelated")
        pick("Unrelated · Other · Different")
        compose.onNodeWithTag("lastfm-match-field").assertTextContains("Unrelated · Other · Different")
        compose.onNodeWithContentDescription("Use selection").performClick()
        compose.runOnIdle { assertEquals("any", chosen) }
        compose.onNodeWithText("Skip").performClick()
        assertEquals(1, skips)
    }

    @Test fun notInMyLibraryStagesAnExplicitAbsentChoiceAndSavesOnlyOnTheCheck() {
        var chosen: String? = null; var open by mutableStateOf(true); var closes = 0
        compose.setContent { LocalicaTheme {
            if (open) LastFmDecision(row, listOf(song), { open = false; closes++ }, {
                chosen = it; open = false; closes++; true
            }, { open = false; closes++ }) else Text("Closed")
        } }
        pick("Not in my library")
        assertNull(chosen)
        compose.onNodeWithTag("lastfm-match-field").assertTextContains("Not in my library")
        compose.onNodeWithContentDescription("Use selection").performClick()
        compose.onNodeWithText("Closed").assertExists()
        assertEquals(LastFmMatcher.NONE, chosen); assertEquals(1, closes)
    }

    @Test fun searchNavigationAndReturnNeverBecomeASavedFieldValue() {
        var chosen: String? = null
        compose.setContent { LocalicaTheme { LastFmDecision(row, listOf(song), {}, { chosen = it; true }, {}) } }
        pick("Choose another song…")
        compose.onNodeWithContentDescription("Use selection").assertIsNotEnabled()
        compose.onNodeWithText("Suggested matches").performScrollTo().performClick()
        compose.onNodeWithTag("lastfm-match-field").assertTextContains("Choose…")
        compose.onNodeWithText("Choose another song…").assertDoesNotExist()
        compose.onNodeWithContentDescription("Use selection").assertIsNotEnabled().performClick()
        assertNull(chosen)
    }

    @Test fun cancellingPendingSaveClosesOnceAndCannotSubmitAgain() {
        val result = CompletableDeferred<Boolean>(); var open by mutableStateOf(true)
        var closes = 0; var calls = 0
        compose.setContent { LocalicaTheme {
            if (open) LastFmDecision(row, listOf(song), { open = false; closes++ }, {
                calls++; result.await().also { if (it) { open = false; closes++ } }
            }, { open = false; closes++ }) else Text("Closed")
        } }
        pick("Song · Artist · Album")
        compose.onNodeWithContentDescription("Use selection").performClick()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("Closed").assertExists()
        compose.runOnIdle { result.complete(true) }
        compose.runOnIdle { assertEquals(1, calls); assertEquals(1, closes) }
    }
}
