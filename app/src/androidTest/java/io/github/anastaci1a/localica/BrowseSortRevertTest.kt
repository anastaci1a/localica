package io.github.anastaci1a.localica

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BrowseSortRevertTest {
    @get:Rule val compose = createComposeRule()
    private val route = BrowseRoute(MainTab.Playlists, MediaRef(MediaKind.Playlist, "mix"))

    @Test fun repeatedRandomizeAndFailedRevertRetainOpeningCheckpointUntilSuccessfulRestore() {
        val opening = OrderCheckpoint(listOf("c", "b", "a"), SavedOrder(listOf("a", "c", "b"), TrackSort.Title, false))
        var ids by mutableStateOf(opening.physical)
        var order by mutableStateOf(opening.saved)
        var failRandomize = true
        var failRevert = true
        val checkpoints = mutableListOf<OrderCheckpoint>()
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
            BrowseSortDialog(route, BrowseStyle(), false, {}, { _, _ -> }, {},
                onRandomize = { done ->
                    if (!failRandomize) { ids = ids.reversed(); order = SavedOrder(ids) }
                    done(!failRandomize)
                }, mutableOrder = order, mutableOrderIds = ids,
                onRevertOrder = { checkpoint, done ->
                    checkpoints += checkpoint
                    if (!failRevert) { ids = checkpoint.physical; order = checkpoint.saved }
                    done(!failRevert)
                })
        } } }
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.onNodeWithText("Randomize").performScrollTo().performClick()
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.runOnIdle { failRandomize = false }
        repeat(2) { compose.onNodeWithText("Randomize").performScrollTo().performClick() }
        compose.onNodeWithText("Revert").assertIsDisplayed().performClick()
        compose.onNodeWithText("Couldn’t save this order. Try again.").assertExists()
        compose.onNodeWithText("Revert").assertIsDisplayed()
        compose.runOnIdle { failRevert = false }
        compose.onNodeWithText("Revert").performClick()
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.onNodeWithText("Title", substring = false).assertExists()
        compose.onNodeWithText("Descending").assertIsSelected()
        compose.runOnIdle { assertEquals(opening.physical, ids); assertEquals(opening.saved, order) }
        compose.onNodeWithText("Randomize").performScrollTo().performClick()
        compose.onNodeWithText("Revert").performClick()
        compose.runOnIdle { assertEquals(listOf(opening, opening, opening), checkpoints) }
    }

    @Test fun interruptedCloseAndReopenCapturesNewBaselineAndIgnoresOldCompletion() {
        var open by mutableStateOf(true)
        var ids by mutableStateOf(listOf("b", "a", "c"))
        var order by mutableStateOf(SavedOrder(listOf("a", "b", "c"), TrackSort.Title, false))
        val pending = mutableListOf<(Boolean) -> Unit>()
        var reverted: OrderCheckpoint? = null
        compose.setContent { LocalicaTheme {
            if (open) BrowseSortDialog(route, BrowseStyle(), false, { open = false }, { _, _ -> }, {},
                onRandomize = { pending += it }, mutableOrder = order, mutableOrderIds = ids,
                onRevertOrder = { checkpoint, done -> reverted = checkpoint; done(true) })
        } }
        compose.onNodeWithText("Randomize").performScrollTo().performClick()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { open = false; ids = listOf("c", "a", "b"); order = SavedOrder(ids) }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { open = true }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle { pending.first()(true) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.onNodeWithText("Randomize").performScrollTo().performClick()
        compose.runOnIdle { pending.last()(true) }
        compose.onNodeWithText("Revert").performClick()
        compose.runOnIdle { assertEquals(OrderCheckpoint.capture(ids, order), reverted) }
    }
}
