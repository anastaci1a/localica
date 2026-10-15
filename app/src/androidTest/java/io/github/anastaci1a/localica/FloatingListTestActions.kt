package io.github.anastaci1a.localica

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Scroll into the interactive viewport, not just the area drawn behind floating controls. */
internal fun ComposeContentTestRule.scrollToUncoveredItem(target: SemanticsMatcher) {
    // Query work runs outside Compose's idle clock. Wait for its first layout.
    waitUntil(5000) { onAllNodesWithTag("browser-list").fetchSemanticsNodes().isNotEmpty() }
    val list = onNodeWithTag("browser-list")
    val scopedTarget = target and hasAnyAncestor(hasTestTag("browser-list"))
    fun scrollBy(amount: Float) {
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, amount) }
        // StandardTestDispatcher queues the scroll launch before it has frame
        // awaiters. Start that work explicitly; Compose idle alone cannot see it.
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }
    if (onAllNodes(scopedTarget).fetchSemanticsNodes().isEmpty()) {
        list.performScrollToIndex(0)
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }
    waitUntil(5000) {
        if (onAllNodes(scopedTarget).fetchSemanticsNodes().isNotEmpty()) true
        else {
            val before = list.fetchSemanticsNode()
            val axis = before.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            val position = axis.value()
            val viewport = before.boundsInRoot
            scrollBy(viewport.height)
            val next = list.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            check(next > position) { "No scroll progress while locating ${target.description}: $position → $next" }
            false
        }
    }
    val viewport = list.fetchSemanticsNode().boundsInRoot
    val targetBounds = onNode(scopedTarget).fetchSemanticsNode().boundsInRoot
    val viewportCorrection = when {
        targetBounds.top < viewport.top -> targetBounds.top - viewport.top
        targetBounds.bottom > viewport.bottom -> targetBounds.bottom - viewport.bottom
        else -> 0f
    }
    if (viewportCorrection != 0f) scrollBy(viewportCorrection)
    val coveredTop = onAllNodes(hasTestTag("tab-pill") or hasTestTag("player-surface"))
        .fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }.minOfOrNull { it.top }
        ?: list.fetchSemanticsNode().boundsInRoot.bottom
    val bottom = onNode(scopedTarget).fetchSemanticsNode().boundsInRoot.bottom
    val amount = bottom - coveredTop + 16f
    if (amount > 0f) scrollBy(amount)
    val topOverlay = onAllNodes(hasTestTag("library-filters") or hasTestTag("list-search-pill"))
        .fetchSemanticsNodes().maxOfOrNull { it.boundsInRoot.bottom }
    if (topOverlay != null) {
        val correction = onNode(scopedTarget).fetchSemanticsNode().boundsInRoot.top - topOverlay - 16f
        if (correction < 0f) scrollBy(correction)
    }
    waitForIdle()
}
