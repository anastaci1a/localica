package io.github.anastaci1a.localica

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

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

/** Native window capture of the panel in its real navigation and collection context. */
internal fun ComposeContentTestRule.saveSelectionPreview(name: String) {
    waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
    val directory = File(root, "selection-pill").apply { mkdirs() }
    org.junit.Assert.assertTrue("Save native selection preview", UiDevice.getInstance(instrumentation)
        .takeScreenshot(File(directory, "app-$name-api${android.os.Build.VERSION.SDK_INT}.png")))
}
