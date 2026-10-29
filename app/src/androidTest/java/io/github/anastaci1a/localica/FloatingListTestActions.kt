package io.github.anastaci1a.localica

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Scroll into the interactive viewport, not just the area drawn behind floating controls. */
internal fun ComposeContentTestRule.scrollToUncoveredItem(target: SemanticsMatcher) {
    // Query work runs outside Compose's idle clock. Wait for its first layout.
    fixtureCostPhase("Scroll.wait-list") { waitUntil(5000) { onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() } }
    val list = onNodeWithTag("browser-list")
    fixtureCostPhase("Scroll.to-node") { list.performScrollToNode(target) }
    val coveredTop = onAllNodes(hasTestTag("tab-pill") or hasTestTag("player-surface"))
        .fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }.minOfOrNull { it.top }
        ?: list.fetchSemanticsNode().boundsInRoot.bottom
    val bottom = onNode(target).fetchSemanticsNode().boundsInRoot.bottom
    val amount = bottom - coveredTop + 16f
    if (amount > 0f) fixtureCostPhase("Scroll.bottom-clearance") { list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, amount) } }
    val topOverlay = onAllNodes(hasTestTag("library-filters") or hasTestTag("list-search-pill"))
        .fetchSemanticsNodes().maxOfOrNull { it.boundsInRoot.bottom }
    if (topOverlay != null) {
        val correction = onNode(target).fetchSemanticsNode().boundsInRoot.top - topOverlay - 16f
        if (correction < 0f) fixtureCostPhase("Scroll.top-clearance") { list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, correction) } }
    }
    fixtureCostPhase("Scroll.final-idle") { waitForIdle() }
}
