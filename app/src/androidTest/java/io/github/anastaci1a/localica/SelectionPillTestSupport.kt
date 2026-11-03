package io.github.anastaci1a.localica

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** #534: secondary selection actions live in the pill's More menu. */
internal fun ComposeContentTestRule.awaitNoGlassMenu() =
    waitUntil(5000) { onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes().isEmpty() }

internal fun ComposeContentTestRule.selectionMenuAction(label: String) {
    awaitNoGlassMenu()
    onNodeWithContentDescription("More selection actions").performClick()
    onNodeWithText(label, substring = false).performClick()
    awaitNoGlassMenu()
}

/** Opens More, checks [label] is offered, and dismisses the menu without acting. */
internal fun ComposeContentTestRule.assertSelectionMenuAction(label: String) {
    awaitNoGlassMenu()
    onNodeWithContentDescription("More selection actions").performClick()
    onNodeWithText(label, substring = false).assertExists()
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    awaitNoGlassMenu()
}
