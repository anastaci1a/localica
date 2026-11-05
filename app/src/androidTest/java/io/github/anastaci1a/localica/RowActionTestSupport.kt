package io.github.anastaci1a.localica

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** A row can exist before its artwork resolves. Invisible row actions deliberately
 * have no semantics or input (#588); await that boundary before clicking or measuring. */
internal fun ComposeContentTestRule.awaitRowActions(title: String): SemanticsNodeInteraction {
    val action = onNodeWithContentDescription("Actions for $title")
    try {
        waitUntil(5000) {
            mainClock.advanceTimeByFrame()
            runCatching { action.assertIsDisplayed().assertIsEnabled().assertHasClickAction() }.isSuccess
        }
        return action.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
    } catch (failure: Throwable) {
        runCatching {
            val roots = onAllNodes(isRoot(), useUnmergedTree = true)
            roots.fetchSemanticsNodes().indices.forEach { roots[it].printToLog("RowActionReveal", maxDepth = 12) }
        }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}
