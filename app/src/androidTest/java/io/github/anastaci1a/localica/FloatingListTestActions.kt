package io.github.anastaci1a.localica

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Scroll into the interactive viewport, not just the area drawn behind floating controls. */
internal fun ComposeContentTestRule.scrollToUncoveredItem(target: SemanticsMatcher) {
    // Query work runs outside Compose's idle clock. Wait for its first layout.
    waitUntil(5000) { onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
    val list = onNodeWithTag("browser-list")
    list.performScrollToNode(target)
    val coveredTop = onAllNodes(hasTestTag("tab-pill") or hasTestTag("player-surface"))
        .fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }.minOfOrNull { it.top }
        ?: return
    val bottom = onNode(target).fetchSemanticsNode().boundsInRoot.bottom
    val amount = bottom - coveredTop + 16f
    if (amount > 0f) list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, amount) }
    waitForIdle()
}
