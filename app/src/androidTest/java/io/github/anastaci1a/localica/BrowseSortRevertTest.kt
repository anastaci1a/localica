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
                    if (!failRandomize) { ids = ids.drop(1) + ids.first(); order = SavedOrder(ids) }
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
        for (label in listOf("Randomize", "Revert")) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            // Inspect the live action's paragraph. The unmerged inspection tree
            // also contains inert sizing faces beneath cleared semantics.
            compose.onNode(hasText(label) and hasAnyAncestor(hasClickAction()), useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Action labels must stay whole", 1, layouts.single().lineCount)
            val layout = layouts.single()
            assertTrue("$label must receive a bounded paragraph width", layout.layoutInput.constraints.hasBoundedWidth)
            assertFalse("$label overflow: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                "constraints=${layout.layoutInput.constraints}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}",
                layout.hasVisualOverflow)
        }
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
        val reopened = OrderCheckpoint.capture(ids, order)
        compose.onNodeWithText("Randomize").performScrollTo().performClick()
        compose.runOnIdle { ids = ids.reversed(); order = SavedOrder(ids); pending.last()(true) }
        compose.onNodeWithText("Revert").performClick()
        compose.runOnIdle { assertEquals(reopened, reverted) }
    }

    @Test fun reverseTogglesRevertFromOpeningOrderAndRetainsRandomizeBaselineAndRowAlignment() {
        val opening = OrderCheckpoint(listOf("a", "b", "c", "d"),
            SavedOrder(listOf("d", "c", "b", "a"), TrackSort.Title, false))
        var ids by mutableStateOf(opening.physical)
        var order by mutableStateOf(opening.saved)
        var failReverse = false
        val restored = mutableListOf<OrderCheckpoint>()
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
            BrowseSortDialog(route, BrowseStyle(), false, {}, { _, _ -> }, {},
                onRandomize = { done -> ids = ids.drop(1) + ids.first(); order = SavedOrder(ids); done(true) },
                onReverseOrder = { done ->
                    if (!failReverse) { ids = ids.reversed(); order = SavedOrder(ids) }
                    done(order.takeUnless { failReverse })
                }, mutableOrder = order, mutableOrderIds = ids,
                onRevertOrder = { checkpoint, done ->
                    restored += checkpoint
                    val current = checkpoint.reconcile(ids)
                    ids = current.physical; order = current.saved; done(true)
                })
        } } }
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.onNodeWithText("Reverse").performScrollTo().performClick()
        compose.onNodeWithText("Revert").assertExists()
        compose.onNodeWithText("Randomize").performScrollTo()
        val randomize = compose.onNodeWithText("Randomize").fetchSemanticsNode().boundsInRoot
        val revert = compose.onNodeWithText("Revert").fetchSemanticsNode().boundsInRoot
        val reverse = compose.onNodeWithText("Reverse").fetchSemanticsNode().boundsInRoot
        assertEquals(randomize.center.y, revert.center.y, 1f)
        assertTrue("Revert remains to the right of Randomize", revert.left >= randomize.right)
        assertTrue("Reverse follows below Randomize", reverse.top >= randomize.bottom)
        assertEquals("Reverse aligns with Randomize", randomize.left, reverse.left, 1f)
        compose.onNodeWithText("Reverse").performScrollTo().performClick()
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.runOnIdle { assertEquals(opening.physical, ids) }
        compose.onNodeWithText("Randomize").performScrollTo().performClick()
        val randomized = ids
        repeat(2) {
            compose.onNodeWithText("Reverse").performScrollTo().performClick()
            compose.onNodeWithText("Revert").assertExists()
        }
        compose.runOnIdle { assertEquals(randomized, ids); failReverse = true }
        compose.onNodeWithText("Reverse").performScrollTo().performClick()
        compose.onNodeWithText("Couldn’t save this order. Try again.").assertExists()
        compose.runOnIdle { assertEquals(randomized, ids); failReverse = false }
        compose.onNodeWithText("Revert").performScrollTo().performClick()
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(opening), restored)
            assertEquals(opening.physical, ids)
            assertEquals(opening.saved, order)
        }
    }

    @Test fun unchangedAndMembershipOnlyCompletionsDoNotExposeRevert() {
        var ids by mutableStateOf(listOf("a"))
        var order by mutableStateOf(SavedOrder(ids, TrackSort.Title, false))
        var reverseCalls = 0
        compose.setContent { LocalicaTheme { CompositionLocalProvider(LocalReducedMotion provides true) {
            BrowseSortDialog(route, BrowseStyle(), false, {}, { _, _ -> }, {},
                onRandomize = { it(true) },
                onReverseOrder = { reverseCalls++; it(order) },
                mutableOrder = order, mutableOrderIds = ids, onRevertOrder = { _, done -> done(true) })
        } } }
        compose.onNodeWithText("Reverse").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Randomize").performScrollTo().performClick()
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.runOnIdle { ids = listOf("a", "new"); order = order.reconcile(ids) }
        compose.onNodeWithText("Reverse").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.runOnIdle { ids = emptyList(); order = order.reconcile(ids) }
        compose.onNodeWithText("Reverse").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Revert").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, reverseCalls) }
    }
}
